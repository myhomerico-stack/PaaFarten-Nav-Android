package dk.fynogjylland.paafartennav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val baseUrl = "https://minside.fynogjylland.dk/navigator/"
    private var pendingBusReg = ""
    private var pendingBusName = ""

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            startGpsIfReady()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.geolocationEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(NavBridge(), "PaaFartenNative")

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                if (hasLocation()) callback?.invoke(origin, true, false)
                else {
                    requestLocation()
                    callback?.invoke(origin, true, false)
                }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectBusBridge()
            }
        }
        requestLocation()
        web.loadUrl(baseUrl)
    }

    private fun injectBusBridge() {
        web.evaluateJavascript("""
            (function(){
              if(window.__PF_NATIVE_BUS_BRIDGE)return;
              window.__PF_NATIVE_BUS_BRIDGE=true;
              function pushBus(){
                try{
                  if(typeof selectedBus!=='undefined' && selectedBus){
                    PaaFartenNative.setBus(JSON.stringify(selectedBus));
                  }
                }catch(e){}
              }
              setInterval(pushBus,2000); pushBus();
              var s=document.getElementById('busSelect');
              if(s)s.addEventListener('change',function(){setTimeout(pushBus,50);});
            })();
        """.trimIndent(), null)
    }

    inner class NavBridge {
        @JavascriptInterface fun setBus(json: String) {
            try {
                val o = JSONObject(json)
                pendingBusReg = o.optString("bus_reg")
                pendingBusName = o.optString("bus_name")
                runOnUiThread { startGpsIfReady() }
            } catch (_: Exception) { }
        }
    }

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        if (!hasLocation()) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun startGpsIfReady() {
        if (!hasLocation() || pendingBusReg.isBlank()) return
        val cookie = CookieManager.getInstance().getCookie(baseUrl) ?: ""
        val i = Intent(this, GpsService::class.java).apply {
            action = GpsService.ACTION_UPDATE
            putExtra("bus_reg", pendingBusReg)
            putExtra("bus_name", pendingBusName)
            putExtra("cookie", cookie)
        }
        ContextCompat.startForegroundService(this, i)
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) injectBusBridge()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
