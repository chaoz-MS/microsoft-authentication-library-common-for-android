// Copyright (c) Microsoft Corporation.
// All rights reserved.
//
// This code is licensed under the MIT License.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files(the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and / or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions :
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.
package com.microsoft.identity.common.internal.providers.oauth2

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.UiThread
import androidx.credentials.PublicKeyCredential
import com.microsoft.identity.common.BuildConfig
import com.microsoft.identity.common.internal.ui.webview.AzureActiveDirectoryWebViewClient
import com.microsoft.identity.common.java.exception.ClientException
import com.microsoft.identity.common.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebView JavascriptInterface for handling WebAuthN/Passkey authentication flows.
 *
 * Intercepts calls from JavaScript to handle credential creation and retrieval
 * using the Android Credential Manager API.
 *
 * @property webView The WebView instance for executing JavaScript callbacks.
 * @property coroutineScope Scope for launching credential operations.
 * @property credentialManagerHandler Handles passkey creation and retrieval.
 */
class PasskeyWebListener(
    private val webView: WebView,
    private val coroutineScope: CoroutineScope,
    private val credentialManagerHandler: CredentialManagerHandler,
) {

    /** Tracks if a WebAuthN request is currently pending. Only one request is allowed at a time. */
    private val havePendingRequest = AtomicBoolean(false)
    
    /** Handler for executing JavaScript on the UI thread */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * JavaScript interface method for handling WebAuthN requests.
     * Called from JavaScript via window.__webauthn_interface__.handleRequest(messageJson)
     *
     * @param messageJson JSON string containing the WebAuthN request.
     */
    @JavascriptInterface
    fun handleRequest(messageJson: String) {
        parseMessage(messageJson)?.let { webAuthNMessage ->
            onRequest(
                webAuthNMessage = webAuthNMessage
            )
        }
    }

    /**
     * Processes an incoming WebAuthN request.
     *
     * @param webAuthNMessage Parsed WebAuthN message.
     */
    private fun onRequest(
        webAuthNMessage: WebAuthNMessage
    ) {
        val methodTag = "$TAG:onRequest"
        Logger.info(
            methodTag,
            "Received WebAuthN request of type: ${webAuthNMessage.type}"
        )
        val passkeyReplyChannel = PasskeyReplyChannel(webView, mainHandler, webAuthNMessage.type)

        // Only allow one request at a time.
        if (havePendingRequest.get()) {
            passkeyReplyChannel.postError(
                ClientException(
                    ClientException.REQUEST_IN_PROGRESS,
                    "A WebAuthN request is already in progress."
                )
            )
            return
        }
        havePendingRequest.set(true)

        when (webAuthNMessage.type) {
            CREATE_UNIQUE_KEY ->
                this.coroutineScope.launch {
                    handleCreateFlow(
                        credentialManagerHandler,
                        webAuthNMessage.request,
                        passkeyReplyChannel
                    )
                    havePendingRequest.set(false)
                }

            GET_UNIQUE_KEY -> this.coroutineScope.launch {
                handleGetFlow(
                    credentialManagerHandler,
                    webAuthNMessage.request,
                    passkeyReplyChannel
                )
                havePendingRequest.set(false)
            }

            else -> {
                passkeyReplyChannel.postError(
                    ClientException(
                        ClientException.UNSUPPORTED_OPERATION,
                        "Unsupported WebAuthN request type: ${webAuthNMessage.type}"
                    )
                )
                havePendingRequest.set(false)
            }
        }
    }

    /**
     * Handles the WebAuthN get flow to retrieve an existing passkey.
     *
     * @param credentialManagerHandler Handler for credential operations.
     * @param message JSON string with the get request parameters.
     * @param reply Channel for sending the response.
     */
    private suspend fun handleGetFlow(
        credentialManagerHandler: CredentialManagerHandler,
        message: String,
        reply: PasskeyReplyChannel
    ) {
        runCatching { credentialManagerHandler.getPasskey(message) }
            .onSuccess { credentialResponse ->
                val publicKeyCredential = credentialResponse.credential as? PublicKeyCredential
                if (publicKeyCredential != null) {
                    reply.postSuccess(publicKeyCredential.authenticationResponseJson)
                } else {
                    reply.postError(
                        ClientException(
                            ClientException.UNSUPPORTED_OPERATION,
                            "Retrieved credential is not a PublicKeyCredential."
                        )
                    )                }
           }
            .onFailure { throwable ->
                reply.postError(throwable)
            }
    }

    /**
     * Handles the WebAuthN create flow to register a new passkey.
     *
     * @param credentialManagerHandler Handler for credential operations.
     * @param message JSON string with the create request parameters.
     * @param reply Channel for sending the response.
     */
    private suspend fun handleCreateFlow(
        credentialManagerHandler: CredentialManagerHandler,
        message: String,
        reply: PasskeyReplyChannel
    ) {
        runCatching { credentialManagerHandler.createPasskey(message) }
            .onSuccess { createCredentialResponse ->
                reply.postSuccess(createCredentialResponse.registrationResponseJson)
            }
            .onFailure { throwable ->
                reply.postError(throwable)
            }
    }

    /**
     * Parses a JSON message into a [WebAuthNMessage].
     *
     * Expected format: `{"type": "create|get", "request": "<JSON payload>"}`
     *
     * @param messageData JSON string to parse.
     * @return Parsed [WebAuthNMessage] or null if invalid.
     */
    private fun parseMessage(
        messageData: String?
    ): WebAuthNMessage? {
        val passkeyReplyChannel = PasskeyReplyChannel(webView, mainHandler)
        return runCatching {
            if (messageData.isNullOrBlank()) {
                throw ClientException(ClientException.MISSING_PARAMETER, "Message data is null or blank")
            }
            val json = JSONObject(messageData)
            val type = json.optString(TYPE_KEY).takeIf { it.isNotBlank() }
            val request = json.optString(REQUEST_KEY).takeIf { it.isNotBlank() }

            if (type == null) {
                throw ClientException(ClientException.MISSING_PARAMETER, "Missing required key: type")
            } else if (request == null) {
                throw ClientException(ClientException.MISSING_PARAMETER, "Missing required key: request")
            } else {
                WebAuthNMessage(type, request)
            }
        }.onFailure { throwable ->
            passkeyReplyChannel.postError(throwable)
        }.getOrNull()
    }

    /** Internal representation of a WebAuthN message with type and request payload. */
    private data class WebAuthNMessage(val type: String, val request: String)

    companion object {
        const val TAG = "PasskeyWebListener"

        /** WebAuthN request type for creating a new credential. */
        const val CREATE_UNIQUE_KEY = "create"

        /** WebAuthN request type for retrieving an existing credential. */
        const val GET_UNIQUE_KEY = "get"

        /** JSON key for the request type field. */
        const val TYPE_KEY = "type"

        /** JSON key for the request payload field. */
        const val REQUEST_KEY = "request"

        /** Name of the JavaScript message port interface. */
        private const val INTERFACE_NAME = "__webauthn_interface__"

        /**
         * Minified JavaScript code that intercepts WebAuthN API calls.
         *
         * ⚠️ IMPORTANT: This is the MINIFIED version adapted for JavascriptInterface
         *
         * Key changes from WebMessageListener version:
         * - Uses __webauthn_interface__.handleRequest() instead of postMessage()
         * - Replies are handled via window.__webauthn_reply__ callback set by native code
         *
         * When updating:
         * 1. Update the source JavaScript with your changes
         * 2. Minify the updated JavaScript code
         * 3. Replace the string below with the new minified version
         * 4. Verify the minified code works correctly through testing
         */
        private const val WEB_AUTHN_INTERFACE_JS_MINIFIED = """
            (function(){var pendingResolve=null,pendingReject=null;window.__webauthn_reply__=function(response){var data=JSON.parse(response);if(data.status==="success"){var result=decodeCredential(data.data);if(pendingResolve){pendingResolve(result);pendingResolve=null;pendingReject=null}}else{var error=new DOMException(data.data.domExceptionMessage,data.data.domExceptionName);if(pendingReject){pendingReject(error);pendingResolve=null;pendingReject=null}}};function base64urlDecode(str){var padding=str.length%4;return Uint8Array.from(atob(str.replace(/-/g,"+").replace(/_/g,"/").padEnd(str.length+(padding===0?0:4-padding),"=")),function(c){return c.charCodeAt(0)}).buffer}function base64urlEncode(buffer){return btoa(Array.from(new Uint8Array(buffer),function(b){return String.fromCharCode(b)}).join("")).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+${'$'}/,"")}function decodeCredential(cred){cred.rawId=base64urlDecode(cred.rawId);cred.response.clientDataJSON=base64urlDecode(cred.response.clientDataJSON);if(cred.response.attestationObject)cred.response.attestationObject=base64urlDecode(cred.response.attestationObject);if(cred.response.authenticatorData)cred.response.authenticatorData=base64urlDecode(cred.response.authenticatorData);if(cred.response.signature)cred.response.signature=base64urlDecode(cred.response.signature);if(cred.response.userHandle)cred.response.userHandle=base64urlDecode(cred.response.userHandle);cred.getClientExtensionResults=function(){return{}};cred.response.getTransports=function(){return cred.response.transports||[]};return cred}var originalCreate=navigator.credentials.create;var originalGet=navigator.credentials.get;navigator.credentials.create=function(options){if(!("publicKey"in options))return originalCreate.call(this,options);return new Promise(function(resolve,reject){pendingResolve=resolve;pendingReject=reject;var opts=options.publicKey;if(opts.challenge)opts.challenge=base64urlEncode(opts.challenge);if(opts.user&&opts.user.id)opts.user.id=base64urlEncode(opts.user.id);if(opts.excludeCredentials)for(var i=0;i<opts.excludeCredentials.length;i++)if(opts.excludeCredentials[i].id)opts.excludeCredentials[i].id=base64urlEncode(opts.excludeCredentials[i].id);var msg=JSON.stringify({type:"create",request:opts});__webauthn_interface__.handleRequest(msg)})};navigator.credentials.get=function(options){if(!("publicKey"in options))return originalGet.call(this,options);return new Promise(function(resolve,reject){pendingResolve=resolve;pendingReject=reject;var opts=options.publicKey;if(opts.challenge)opts.challenge=base64urlEncode(opts.challenge);var msg=JSON.stringify({type:"get",request:opts});__webauthn_interface__.handleRequest(msg)})};window.PublicKeyCredential=function(){};window.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable=function(){return Promise.resolve(true)}})();
         """

        /** Allowed origins that can use the WebAuthN interface. */
        private val ALLOWED_ORIGIN_RULES_PRODUCTION = setOf(
            "https://login.microsoft.com",
            "https://account.live.com",
            "https://mysignins.microsoft.com",
            "https://mysignins.azure.us",
            "https://mysignins.microsoft.scloud",
            "https://mysignins.eaglex.ic.gov",
            "https://login.microsoftonline.us",
            "https://login.microsoftonline.microsoft.scloud",
            "https://login.microsoftonline.eaglex.ic.gov"
        )

        /** Allowed origins for pre-production/testing environments. */
        private val ALLOWED_ORIGIN_PRE_PRODUCTION = setOf(
            "https://account.live-int.com",
            "https://login.windows-ppe.net",
            "https://mysignins-ppe.microsoft.com"
        )

        /**
         * Gets the set of allowed origin rules based on build configuration.
         *
         * @return Set of allowed origin rules.
         */
        private fun getAllowedOriginRules(): Set<String> {
            val mutableSet = ALLOWED_ORIGIN_RULES_PRODUCTION.toMutableSet()
            if (BuildConfig.DEBUG) {
                mutableSet.addAll(ALLOWED_ORIGIN_PRE_PRODUCTION)
            }
            return mutableSet.toSet()
        }

        /**
         * Attaches the passkey listener to a WebView.
         *
         * Requires Android 9+ (API 28) for Passkey support.
         *
         * @param webView WebView to attach to.
         * @param activity Activity context for credential operations.
         * @param webClient WebViewClient to inject JavaScript into.
         * @return True if successfully hooked, false otherwise.
         */
        @JvmStatic
        fun hook(
            webView: WebView,
            activity: Activity,
            webClient: AzureActiveDirectoryWebViewClient
        ): Boolean {
            val methodTag = "$TAG:hook"

            // Passkey features are supported only on Android 9 (API 28) and higher.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                Logger.warn(
                    methodTag,
                    "Passkey functionality requires Android 9 (Pie) or higher. " +
                            "Current version: ${Build.VERSION.SDK_INT}"
                )
                return false
            }

            Logger.verbose(methodTag, "Setting up JavascriptInterface for passkey support.")

            // Create and attach the JavascriptInterface that handles WebAuthN/Passkey communication.
            val passkeyListener = PasskeyWebListener(
                webView = webView,
                coroutineScope = CoroutineScope(Dispatchers.Default),
                credentialManagerHandler = CredentialManagerHandler(activity)
            )
            webView.addJavascriptInterface(passkeyListener, INTERFACE_NAME)

            Logger.info(methodTag, "PasskeyWebListener successfully hooked into WebView.")

            // Injects the JavaScript interface early in the page load lifecycle.
            val scriptToInject = if (BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true)
                loadJsBridgeScript(activity)
            } else {
                WEB_AUTHN_INTERFACE_JS_MINIFIED
            }
            webClient.addOnPageStartedScript(
                TAG,
                scriptToInject,
                getAllowedOriginRules()
            )

            return true
        }

        /**
         * Loads the full js-bridge.js script from assets for debugging.
         */
        private fun loadJsBridgeScript(context: Context): String {
            return try {
                context.assets.open("js-bridge.js").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Logger.warn(TAG, "Failed to load js-bridge.js from assets, falling back to minified version: ${e.message}")
                WEB_AUTHN_INTERFACE_JS_MINIFIED
            }
        }

    }
}
