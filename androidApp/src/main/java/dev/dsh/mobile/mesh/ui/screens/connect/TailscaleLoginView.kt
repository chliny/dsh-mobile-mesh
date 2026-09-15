package dev.dsh.mobile.mesh.ui.screens.connect

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun TailscaleLoginView(loginUrl: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize().height(640.dp),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                webViewClient = WebViewClient()
                loadUrl(loginUrl)
            }
        },
        update = { webView ->
            if (webView.url != loginUrl) webView.loadUrl(loginUrl)
        },
    )
}
