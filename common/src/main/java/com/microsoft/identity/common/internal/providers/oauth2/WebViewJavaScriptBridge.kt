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

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.UiThread
import com.microsoft.identity.common.logging.Logger

/**
 * Bridge for WebView-JavaScript bidirectional communication.
 * 
 * Replaces androidx.webkit WebMessageListener functionality using native WebView APIs.
 * Provides a JavascriptInterface for JavaScript to call into native code, and uses
 * evaluateJavascript() for native to call JavaScript.
 *
 * @property webView The WebView instance to bridge with.
 * @property interfaceName Name of the JavaScript interface object.
 * @property allowedOrigins Set of allowed origin URLs for security validation.
 * @property listener Callback interface for handling received messages.
 */
class WebViewJavaScriptBridge(
    private val webView: WebView,
    private val interfaceName: String,
    private val allowedOrigins: Set<String>,
    private val listener: MessageListener
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val TAG = "WebViewJsBridge"
    }

    /**
     * Listener interface for receiving messages from JavaScript.
     */
    interface MessageListener {
        /**
         * Called when a message is received from JavaScript.
         *
         * @param webView The WebView that sent the message.
         * @param messageData The message data as a String.
         * @param sourceOrigin The origin URL of the page.
         */
        @UiThread
        fun onMessageReceived(webView: WebView, messageData: String, sourceOrigin: Uri)
    }

    /**
     * Registers this bridge with the WebView.
     * Must be called on the main thread.
     */
    @UiThread
    fun attach() {
        mainHandler.post {
            webView.addJavascriptInterface(JsBridgeInterface(), interfaceName)
            Logger.verbose(TAG, "JavaScript interface '$interfaceName' attached to WebView.")
        }
    }

    /**
     * Unregisters this bridge from the WebView.
     */
    @UiThread
    fun detach() {
        mainHandler.post {
            webView.removeJavascriptInterface(interfaceName)
            Logger.verbose(TAG, "JavaScript interface '$interfaceName' removed from WebView.")
        }
    }

    /**
     * JavaScript interface that receives messages from the web page.
     * This class is exposed to JavaScript as the interface object.
     */
    private inner class JsBridgeInterface {
        /**
         * Receives a message from JavaScript via postMessage().
         * Called from the JavaScript context.
         *
         * @param message The message string from JavaScript.
         */
        @JavascriptInterface
        fun postMessage(message: String) {
            mainHandler.post {
                handleMessage(message)
            }
        }
    }

    /**
     * Handles an incoming message from JavaScript.
     * Validates the origin and forwards to the listener.
     *
     * @param messageData The message received from JavaScript.
     */
    private fun handleMessage(messageData: String) {
        val methodTag = "$TAG:handleMessage"
        
        // Get the current page URL to validate origin
        val currentUrl = webView.url
        if (currentUrl.isNullOrBlank()) {
            Logger.warn(methodTag, "Received message but WebView URL is null or blank. Ignoring.")
            return
        }

        val sourceOrigin = Uri.parse(currentUrl)
        val originAuthority = "${sourceOrigin.scheme}://${sourceOrigin.host}"

        // Validate origin against allowed list
        if (!isOriginAllowed(originAuthority)) {
            Logger.warn(
                methodTag,
                "Received message from disallowed origin: $originAuthority. Ignoring."
            )
            return
        }

        Logger.verbose(methodTag, "Received message from allowed origin: $originAuthority")
        
        // Forward to listener on main thread
        listener.onMessageReceived(webView, messageData, sourceOrigin)
    }

    /**
     * Checks if an origin is in the allowed list.
     *
     * @param origin The origin to check (e.g., "https://login.microsoft.com").
     * @return True if allowed, false otherwise.
     */
    private fun isOriginAllowed(origin: String): Boolean {
        return allowedOrigins.any { allowedOrigin ->
            origin.equals(allowedOrigin, ignoreCase = true)
        }
    }
}
