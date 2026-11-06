package com.veluna.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.music.innertube.YouTube
import com.veluna.LocalPlayerAwareWindowInsets
import com.veluna.R
import com.veluna.constants.AccountChannelHandleKey
import com.veluna.constants.AccountEmailKey
import com.veluna.constants.AccountNameKey
import com.veluna.constants.DataSyncIdKey
import com.veluna.constants.InnerTubeCookieKey
import com.veluna.constants.SavedAccountsKey
import com.veluna.constants.VisitorDataKey
import com.veluna.models.AccountData
import com.veluna.ui.component.IconButton
import com.veluna.ui.utils.backToMain
import com.veluna.utils.rememberPreference
import com.veluna.utils.reportException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, DelicateCoroutinesApi::class)
@Composable
fun LoginScreen(navController: NavController) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var visitorData by rememberPreference(VisitorDataKey, "")
  var dataSyncId by rememberPreference(DataSyncIdKey, "")
  var innerTubeCookie by rememberPreference(InnerTubeCookieKey, "")
  var accountName by rememberPreference(AccountNameKey, "")
  var accountEmail by rememberPreference(AccountEmailKey, "")
  var accountChannelHandle by rememberPreference(AccountChannelHandleKey, "")
  var savedAccountsJson by rememberPreference(SavedAccountsKey, "[]")
  var hasCompletedLogin by remember { mutableStateOf(false) }
  var isValidatingAccount by remember { mutableStateOf(false) }

  var webView: WebView? = null

  Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(
      modifier = Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current).fillMaxSize(),
      factory = { webViewContext ->
        WebView(webViewContext).apply {
          val cookieManager = CookieManager.getInstance()
          cookieManager.setAcceptCookie(true)
          cookieManager.setAcceptThirdPartyCookies(this, true)

          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            // Strip "; wv" and "Version/4.0 " to prevent Google from blocking embedded WebView
            val defaultUa = userAgentString
            userAgentString =
              defaultUa
                .replace("; wv", "")
                .replace(Regex("Version/[0-9.]+\\s*"), "")
          }

          webChromeClient = WebChromeClient()

          webViewClient =
            object : WebViewClient() {
              override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
              ): Boolean {
                val url = request.url ?: return false
                val host = url.host?.lowercase().orEmpty()

                // Allow all Google, YouTube, and authentication-related domains
                val isAuthHost =
                  host.endsWith("google.com") ||
                    host.contains(".google.") ||
                    host.endsWith("googleapis.com") ||
                    host.endsWith("gstatic.com") ||
                    host.endsWith("googleusercontent.com") ||
                    host.endsWith("youtube.com") ||
                    host.endsWith("g.co") ||
                    host.endsWith("android.com")

                if (isAuthHost) {
                  return false
                }

                // If it's an external link, open in system browser
                return try {
                  val intent = Intent(Intent.ACTION_VIEW, url)
                  context.startActivity(intent)
                  true
                } catch (e: Exception) {
                  false
                }
              }

              override fun onPageFinished(view: WebView, url: String?) {
                if (url?.contains("youtube.com") == true) {
                  loadUrl(
                    "javascript:Android.onRetrieveVisitorData(window.yt && window.yt.config_ ? window.yt.config_.VISITOR_DATA : '')"
                  )
                  loadUrl(
                    "javascript:Android.onRetrieveDataSyncId(window.yt && window.yt.config_ ? window.yt.config_.DATASYNC_ID : '')"
                  )
                }

                if (url?.startsWith("https://music.youtube.com") == true && !hasCompletedLogin) {
                  cookieManager.flush()
                  val cookies = cookieManager.getCookie(url).orEmpty()

                  // Ensure critical authentication cookies have actually been committed
                  val hasAuthCookies =
                    cookies.contains("SAPISID") ||
                      cookies.contains("__Secure-3PAPISID") ||
                      cookies.contains("LOGIN_INFO") ||
                      cookies.contains("SID")

                  if (hasAuthCookies) {
                    hasCompletedLogin = true
                    isValidatingAccount = true
                    innerTubeCookie = cookies

                    coroutineScope.launch {
                      delay(800)
                      cookieManager.flush()
                      val latestCookies = cookieManager.getCookie("https://music.youtube.com").orEmpty()
                      val finalCookies =
                        if (latestCookies.contains("SAPISID")) latestCookies else cookies

                      innerTubeCookie = finalCookies
                      YouTube.cookie = finalCookies
                      YouTube.dataSyncId = dataSyncId
                      YouTube.visitorData = visitorData

                      Timber.d("Login: Validating YouTube account...")

                      // Retry accountInfo if it takes a moment for the new session to activate
                      var accountResult = YouTube.accountInfo()
                      if (accountResult.isFailure) {
                        delay(1200)
                        accountResult = YouTube.accountInfo()
                      }
                      if (accountResult.isFailure) {
                        delay(1500)
                        accountResult = YouTube.accountInfo()
                      }

                      accountResult
                        .onSuccess {
                          accountName = it.name
                          accountEmail = it.email.orEmpty()
                          accountChannelHandle = it.channelHandle.orEmpty()

                          val newAccount =
                            AccountData(
                              name = it.name,
                              email = it.email.orEmpty(),
                              channelHandle = it.channelHandle.orEmpty(),
                              cookie = finalCookies,
                              visitorData = visitorData,
                              dataSyncId = dataSyncId,
                              avatarUrl = it.thumbnailUrl.orEmpty(),
                            )
                          val accounts =
                            try {
                              Json.decodeFromString<List<AccountData>>(savedAccountsJson)
                            } catch (e: Exception) {
                              emptyList()
                            }.toMutableList()
                          accounts.removeAll { acc -> acc.name == newAccount.name }
                          accounts.add(newAccount)
                          savedAccountsJson = Json.encodeToString(accounts)

                          Timber.d("Login: Successfully logged in as ${it.name}, restarting app...")

                          webView?.apply {
                            stopLoading()
                            clearHistory()
                            clearCache(true)
                            clearFormData()
                          }

                          val intent =
                            context.packageManager.getLaunchIntentForPackage(context.packageName)
                          intent?.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                          )
                          context.startActivity(intent)
                          delay(500)
                          Runtime.getRuntime().exit(0)
                        }
                        .onFailure {
                          Timber.e(it, "Login: Authentication validation failed")
                          hasCompletedLogin = false
                          isValidatingAccount = false
                          reportException(it)
                        }
                    }
                  }
                }
              }
            }

          addJavascriptInterface(
            object {
              @JavascriptInterface
              fun onRetrieveVisitorData(newVisitorData: String?) {
                if (!newVisitorData.isNullOrBlank()) {
                  visitorData = newVisitorData
                }
              }

              @JavascriptInterface
              fun onRetrieveDataSyncId(newDataSyncId: String?) {
                if (!newDataSyncId.isNullOrBlank()) {
                  dataSyncId = newDataSyncId.substringBefore("||")
                }
              }
            },
            "Android",
          )
          webView = this

          cookieManager.removeAllCookies {
            cookieManager.flush()
            loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
          }
        }
      },
    )

    if (isValidatingAccount) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator()
      }
    }
  }

  TopAppBar(
    title = { Text(stringResource(R.string.login)) },
    navigationIcon = {
      IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
      }
    },
  )

  BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }
}
