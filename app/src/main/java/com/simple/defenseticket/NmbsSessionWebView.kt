package com.simple.defenseticket

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView


@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NmbsSessionWebView(
    engine: NmbsBookingEngine,
    visible: Boolean
) {

    var webViewReference by remember {
        mutableStateOf<WebView?>(
            null
        )
    }


    /*
     * Verborgen = buiten beeld, maar NIET disabled.
     *
     * Zo blijft JavaScript/fetch/sessionStorage/cookies
     * volledig actief.
     */
    val containerModifier =
        if (
            visible
        ) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .size(
                    width = 400.dp,
                    height = 800.dp
                )
                .offset(
                    x = 2000.dp,
                    y = 0.dp
                )
                .alpha(
                    0.01f
                )
        }


    Column(
        modifier =
            containerModifier
                .background(
                    MaterialTheme.colorScheme.background
                )
    ) {

        if (
            visible
        ) {
            Surface(
                tonalElevation =
                    4.dp,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier =
                        Modifier.padding(
                            12.dp
                        )
                ) {
                    Text(
                        text =
                            "NMBS-browsercontrole",
                        style =
                            MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text =
                            "Rond de normale NMBS-controle hieronder af. " +
                                    "De boeking gaat daarna automatisch verder."
                    )
                }
            }
        }


        AndroidView(
            modifier =
                Modifier.fillMaxSize(),

            factory = { context ->

                val view =
                    WebView(
                        context
                    )


                webViewReference =
                    view


                view.settings.javaScriptEnabled =
                    true

                view.settings.domStorageEnabled =
                    true

                view.settings.loadsImagesAutomatically =
                    true

                view.settings.useWideViewPort =
                    true

                view.settings.loadWithOverviewMode =
                    false

                view.settings.javaScriptCanOpenWindowsAutomatically =
                    false

                view.settings.setSupportMultipleWindows(
                    false
                )


                /*
                 * Normale Android WebView user-agent behouden.
                 * Geen stealth / anti-bot bypass.
                 */


                val cookieManager =
                    CookieManager.getInstance()


                cookieManager.setAcceptCookie(
                    true
                )


                cookieManager.setAcceptThirdPartyCookies(
                    view,
                    true
                )


                view.webChromeClient =
                    WebChromeClient()


                view.isEnabled =
                    true

                view.isFocusable =
                    true

                view.isFocusableInTouchMode =
                    true


                engine.attach(
                    view
                )


                view
            },

            update = { view ->

                webViewReference =
                    view


                /*
                 * Altijd actief houden, ook wanneer buiten beeld.
                 */
                view.isEnabled =
                    true

                view.isFocusable =
                    true

                view.isFocusableInTouchMode =
                    true
            }
        )
    }


    DisposableEffect(
        Unit
    ) {

        onDispose {

            val view =
                webViewReference


            engine.detach(
                view
            )


            view?.stopLoading()

            view?.destroy()
        }
    }
}
