/*
 * Copyright 2022-2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openmrs.android.fhir

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.google.android.fhir.sync.HttpAuthenticationMethod
import com.google.android.fhir.sync.HttpAuthenticator
import org.openmrs.android.fhir.auth.AuthConfigUtil
import org.openmrs.android.fhir.auth.AuthConfiguration
import org.openmrs.android.fhir.auth.AuthStateManager
import org.openmrs.android.fhir.auth.ConnectionBuilderForTesting
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientAuthentication
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import net.openid.appauth.browser.AnyBrowserMatcher
import net.openid.appauth.browser.BrowserMatcher
import timber.log.Timber

class LoginRepository
private constructor(
  private val authStateManager: AuthStateManager,
  private val authConfig: AuthConfiguration,
  private var authService: AuthorizationService
) : HttpAuthenticator {
  private val clientId = AtomicReference<String?>()
  private val authRequest = AtomicReference<AuthorizationRequest?>()
  private val _needLogin = MutableStateFlow(false)
  val needLogin: StateFlow<Boolean> = _needLogin.asStateFlow()

  suspend fun updateAuthIfConfigurationChanged() {
    if (hasConfigurationChanged()) {
      Timber.i("Configuration change detected, discarding old state")
      authStateManager.replace(AuthState())
      authConfig.save()
    }
  }

  private suspend fun hasConfigurationChanged() =
    authConfig.isNotStored() || authConfig.stored != authConfig.authConfigData

  suspend fun isAuthEstablished() =
    !hasConfigurationChanged() && authStateManager.current.authorizationServiceConfiguration != null

  fun getLastConfigurationError(): AuthorizationException?= authConfig.lastException


  fun getAuthIntent(): Intent? {
    if (authStateManager.current.authorizationServiceConfiguration == null) {
      Timber.i("can't get authorizationServiceConfiguration")
      return null
    }
    val authRequestBuilder =
      AuthorizationRequest.Builder(
        authStateManager.current.authorizationServiceConfiguration!!,
        clientId.get()!!,
        ResponseTypeValues.CODE,
        authConfig.redirectUri!!
      )
        .setScope(authConfig.scope)
    authRequest.set(authRequestBuilder.build())

    return authService.getAuthorizationRequestIntent(authRequest.get()!!)
  }

  suspend fun initializeAppAuth() {
    Timber.i("Initializing AppAuth")
    if (authStateManager.current.authorizationServiceConfiguration != null) {

      // configuration is already created, skip to client initialization
      Timber.i("auth authConfig already established")
      clientId.set(authConfig.clientId)
      return
    }
    // if we are not using discovery, build the authorization service configuration directly
    // from the static configuration values.
    if (authConfig.discoveryUri == null) {
      Timber.i("Creating auth authConfig from res/raw/auth_config.json")
      val authConfig =
        AuthorizationServiceConfiguration(
          authConfig.authEndpointUri!!,
          authConfig.tokenEndpointUri!!,
          authConfig.registrationEndpointUri,
          authConfig.endSessionEndpoint
        )
      authStateManager.replace(AuthState(authConfig))
      clientId.set(this.authConfig.clientId)
      return
    }
    retrieveOpenID()
    Timber.i("Retrieving OpenID discovery doc")
  }

  private suspend fun retrieveOpenID() {
    return suspendCoroutine { cont ->
      AuthorizationServiceConfiguration.fetchFromUrl(
        authConfig.discoveryUri!!,
        { config: AuthorizationServiceConfiguration?, ex: AuthorizationException? ->
          handleConfigurationRetrievalResult(config, ex)
          cont.resume(Unit)
        },
        authConfig.connectionBuilder
      )
    }
  }

  private fun handleConfigurationRetrievalResult(
    authServiceConfig: AuthorizationServiceConfiguration?,
    ex: AuthorizationException?,
  ) {
    runBlocking {
      authConfig.lastException=ex
      if (authServiceConfig == null) {
        Timber.i("Failed to retrieve discovery document", ex)
        return@runBlocking
      }
      Timber.i("Discovery document retrieved")

      if (authConfig.connectionBuilder is ConnectionBuilderForTesting) {
        val updatedConfig = AuthConfigUtil.replaceLocalhost(
          authServiceConfig.toJsonString(),
          ConnectionBuilderForTesting.replace_localhost_by_10_0_2_2
        )
        authStateManager.replace(
          AuthState(AuthorizationServiceConfiguration.fromJson(updatedConfig))
        )
      } else {
        authStateManager.replace(AuthState(authServiceConfig))
      }
      clientId.set(this@LoginRepository.authConfig.clientId)
    }
  }

  suspend fun updateAfterAuthorization(
    response: AuthorizationResponse?,
    ex: AuthorizationException?
  ) {
    authStateManager.updateAfterAuthorization(response, ex)
  }

  suspend fun exchangeCodeForToken(response: AuthorizationResponse?, ex: AuthorizationException?) {
    authStateManager.updateAfterAuthorization(response, ex)
    if (response != null) {
      exchangeAuthorizationCode(response)
    }
  }

  private suspend fun exchangeAuthorizationCode(authorizationResponse: AuthorizationResponse) {
    return suspendCoroutine { cont ->
      Timber.i("Exchanging authorization code")
      performTokenRequest(authorizationResponse.createTokenExchangeRequest()) {
          tokenResponse: TokenResponse?,
          authException: AuthorizationException?,
        ->
        handleCodeExchangeResponse(tokenResponse, authException)
        cont.resume(Unit)
      }
    }
  }

  private fun performTokenRequest(
    request: TokenRequest,
    callback: AuthorizationService.TokenResponseCallback,
  ) {
    try {
      val clientAuthentication: ClientAuthentication = authStateManager.current.clientAuthentication
      authService.performTokenRequest(request, clientAuthentication, callback)
    } catch (ex: ClientAuthentication.UnsupportedAuthenticationMethod) {
      Timber.d(
        "Token request cannot be made, client authentication for the token " +
          "endpoint could not be constructed (%s)",
        ex
      )
      Timber.e("Client authentication method is unsupported")
    }
  }

  private fun handleCodeExchangeResponse(
    tokenResponse: TokenResponse?,
    authException: AuthorizationException?,
  ) {

    runBlocking {
      authStateManager.updateAfterTokenResponse(tokenResponse, authException)
      if (!authStateManager.current.isAuthorized) {
        val message =
          ("Authorization Code exchange failed" +
            if (authException != null) authException.error else "")
        Timber.e(message)
      } else {
        Timber.i("code worked")
      }
    }
  }

  override fun getAuthenticationMethod(): HttpAuthenticationMethod {
    return HttpAuthenticationMethod.Bearer(getAccessToken())
  }


  fun getAccessToken(): String {
    return runBlocking {
      if (authStateManager.current.needsTokenRefresh and authStateManager.current.isAuthorized && (authStateManager.current.refreshToken !=null)) {
        Timber.i("Refreshing access token")
        refreshAccessToken()
      }
      if (authStateManager.current.needsTokenRefresh) {
        _needLogin.emit(true)
        Timber.i("Refresh token expired")
      }
      authStateManager.current.accessToken ?: ""
    }
  }

  private suspend fun refreshAccessToken() {
    return suspendCoroutine { cont ->
      performTokenRequest(authStateManager.current.createTokenRefreshRequest()) {
          tokenResponse: TokenResponse?,
          authException: AuthorizationException?,
        ->
        handleCodeExchangeResponse(tokenResponse, authException)
        cont.resume(Unit)
      }
    }
  }

  companion object {
    @SuppressLint("StaticFieldLeak")
    private var INSTANCE: LoginRepository? = null

    @Synchronized
    fun getInstance(
      context: Context,
      authStateManager: AuthStateManager = AuthStateManager.getInstance(context.applicationContext),
      authConfig: AuthConfiguration = AuthConfiguration.getInstance(context.applicationContext)
    ): LoginRepository {
      if (INSTANCE == null) {
        Timber.i("Creating authorization service")
        val browserMatcher: BrowserMatcher = AnyBrowserMatcher.INSTANCE
        val builder = AppAuthConfiguration.Builder()
        builder.setBrowserMatcher(browserMatcher)
        builder.setSkipIssuerHttpsCheck(!authConfig.authConfigData.https_required)
        builder.setConnectionBuilder(authConfig.connectionBuilder)
        val authService = AuthorizationService(context.applicationContext, builder.build())

        INSTANCE = LoginRepository(authStateManager, authConfig, authService)
      }
      return INSTANCE as LoginRepository
    }

    @Synchronized
    fun unsetInstance() {
      INSTANCE = null
    }
  }
}
