package pl.snowdog.kiosk

import android.app.AlertDialog
import android.app.ComponentCaller
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.http.SslError
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.webkit.SslErrorHandler
import android.webkit.PermissionRequest
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import pl.snowdog.kiosk.databinding.ActivityMainBinding


class WebviewActivity : AppCompatActivity() {
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var reloadOnConnected: ReloadOnConnected
    private lateinit var adminComponentName: ComponentName
    private lateinit var policyManager: DevicePolicyManager
    private lateinit var mDevicePolicyManager: DevicePolicyManager
    private lateinit var sharedPref: SharedPreferences
    private lateinit var binding: ActivityMainBinding
    private var pin: String? = null
    private var diagnosticMode: Boolean = false
    private var allowedOrigin: String = ""
    private var printBridge: GuestRegistrationBridge? = null
    @Volatile private var bridgePageAllowed: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)
        binding = ActivityMainBinding.inflate(layoutInflater)

        val defaultUrl = "https://app.example.com/kiosk"
        sharedPref =
            getSharedPreferences(getString(R.string.storage_key), Context.MODE_PRIVATE) ?: return
        val url = sharedPref.getString(getString(R.string.url_key), defaultUrl)
        pin = sharedPref.getString(getString(R.string.pin_key), "1234")
        diagnosticMode = sharedPref.getBoolean(getString(R.string.diagnostic_mode_key), false)
        allowedOrigin = KioskSecurity.normalizeHttpsOrigin(
            sharedPref.getString(getString(R.string.allowed_origin_key), null)
        ) ?: ""

        initVars()
        setKioskPolicies(isAdmin())
        showInFullScreen(findViewById(R.id.root))
        setupWebView(url ?: defaultUrl)
        requestCameraPermission()
        listenToConnectionChange()
        initSwipe()

        RefreshScheduler.cancel(this.applicationContext)
        setScheduler(this.applicationContext)



        val fab: View = findViewById(R.id.fab)
        fab.setOnClickListener {
            showDialog()
        }

        if (intent?.action == RefreshScheduler.ACTION_REFRESH) {
            reloadSite()
        }

    }

    override fun onNewIntent(intent: Intent, caller: ComponentCaller) {
        super.onNewIntent(intent, caller)

        if (intent.action == RefreshScheduler.ACTION_REFRESH){
            reloadSite()
        }
    }

    private fun reloadSite() {
        if (reloadOnConnected.isNetworkAvailable()){
            webView.stopLoading()
            webView.reload()
            webView.evaluateJavascript("location.reload(true);", null)
            endRefreshWithTimeout()
        }else{
            swipeRefreshLayout.isRefreshing = false;
            Snackbar.make(binding.content, "No internet Connection", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun initSwipe() {
        swipeRefreshLayout.setOnRefreshListener {
            reloadSite()
        }
    }

    private fun endRefreshWithTimeout() {
        swipeRefreshLayout.postDelayed({
            if (swipeRefreshLayout.isRefreshing) swipeRefreshLayout.isRefreshing = false
        }, 8_000) // 8s safety net
    }

    private fun showDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.dialog_title)
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.width = 0
        builder.setView(input)

        builder.setPositiveButton(android.R.string.yes) { _, _ ->
            val inputPin = input.text.toString().toInt()
            if (inputPin == pin?.toInt()) {
                RefreshScheduler.cancel(this.applicationContext)
                goToHome()
            } else {
                Toast.makeText(
                    applicationContext,
                    R.string.dialog_wrong, Toast.LENGTH_SHORT
                ).show()
            }
        }

        val dialog = builder.show()
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        button.isEnabled = false

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable) {
                button.isEnabled = !TextUtils.isEmpty(s)
            }
        })
    }

    private fun goToHome() {
        sharedPref.edit(commit = true) {
            putBoolean(getString(R.string.edit_key), true)
        }
        super.onBackPressed()
    }

    private fun setKeyGuardEnabled() {
        mDevicePolicyManager.setKeyguardDisabled(adminComponentName, false)
    }

    private fun setAsHomeApp() {
        val intentFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        mDevicePolicyManager.addPersistentPreferredActivity(
            adminComponentName,
            intentFilter,
            ComponentName(packageName, MainActivity::class.java.name)
        )
    }

    private fun setUserRestriction(restriction: String) =
        mDevicePolicyManager.addUserRestriction(adminComponentName, restriction)

    private fun enableStayOnWhilePluggedIn() =
        mDevicePolicyManager.setGlobalSetting(
            adminComponentName,
            Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
            (BatteryManager.BATTERY_PLUGGED_AC
                    or BatteryManager.BATTERY_PLUGGED_USB
                    or BatteryManager.BATTERY_PLUGGED_WIRELESS).toString()
        )

    private fun setUpdatePolicy() {
        mDevicePolicyManager.setSystemUpdatePolicy(
            adminComponentName,
            SystemUpdatePolicy.createWindowedInstallPolicy(60, 120)
        )
    }

    private fun setRestrictions() {
        setUserRestriction(UserManager.DISALLOW_SAFE_BOOT)
        setUserRestriction(UserManager.DISALLOW_FACTORY_RESET)
        setUserRestriction(UserManager.DISALLOW_ADD_USER)
        setUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
        setUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME)
        mDevicePolicyManager.setStatusBarDisabled(adminComponentName, true)
    }

    private fun setKioskPolicies(isAdmin: Boolean) {
        if (isAdmin) {
            setRestrictions()
            enableStayOnWhilePluggedIn()
            setUpdatePolicy()
            setAsHomeApp()
            setKeyGuardEnabled()
        }

        setLockTask(isAdmin)
        setImmersiveMode()
    }

    private fun setImmersiveMode() {
        val flags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        window.decorView.systemUiVisibility = flags
    }

    private fun setLockTask(isAdmin: Boolean) {
        if (isAdmin) {
            mDevicePolicyManager.setLockTaskPackages(
                adminComponentName, arrayOf(packageName)
            )
        }
        startLockTask()
    }

    private fun isAdmin() = mDevicePolicyManager.isDeviceOwnerApp(packageName)

    override fun onResume() {
        super.onResume()
        setupKiosk()
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack())
            webView.goBack()
    }

    override fun onDestroy() {
        printBridge?.shutdown()
        webView.removeJavascriptInterface(GuestRegistrationBridge.INTERFACE_NAME)
        if (::reloadOnConnected.isInitialized)
            reloadOnConnected.onActivityDestroy()
        super.onDestroy()
    }

    private fun setupKiosk() {
        if (policyManager.isDeviceOwnerApp(packageName)) {
            setupAutoStart()
            stayAwake()
            policyManager.setLockTaskPackages(adminComponentName, arrayOf(packageName))
        }
        startLockTask()
    }

    private fun setupAutoStart() {
        val intentFilter = IntentFilter(Intent.ACTION_MAIN)
        intentFilter.addCategory(Intent.CATEGORY_HOME)
        intentFilter.addCategory(Intent.CATEGORY_DEFAULT)
        policyManager.addPersistentPreferredActivity(
            adminComponentName,
            intentFilter, ComponentName(packageName, WebviewActivity::class.java.name)
        )
        policyManager.setKeyguardDisabled(adminComponentName, true)
    }

    private fun stayAwake() {
        policyManager.setGlobalSetting(
            adminComponentName,
            Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
            (BatteryManager.BATTERY_PLUGGED_AC
                    or BatteryManager.BATTERY_PLUGGED_USB
                    or BatteryManager.BATTERY_PLUGGED_WIRELESS).toString()
        )
    }

    private fun listenToConnectionChange() = reloadOnConnected.onActivityCreate(this)

    private fun setupWebView(url: String) {
        val bridgeConfig = loadBridgeConfig()
        webView.removeJavascriptInterface(GuestRegistrationBridge.INTERFACE_NAME)
        if (!diagnosticMode && bridgeConfig != null) {
            printBridge = GuestRegistrationBridge(webView, bridgeConfig) { bridgePageAllowed }
            webView.addJavascriptInterface(printBridge!!, GuestRegistrationBridge.INTERFACE_NAME)
        }
        webView.webViewClient = object : WebViewClient() {

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (diagnosticMode) return false
                return !KioskSecurity.isAllowedUrl(url, allowedOrigin)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (diagnosticMode) return false
                return !KioskSecurity.isAllowedUrl(request?.url?.toString(), allowedOrigin)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                bridgePageAllowed = !diagnosticMode && KioskSecurity.isAllowedUrl(url, allowedOrigin)
                if (!diagnosticMode && !bridgePageAllowed) {
                    view?.stopLoading()
                    return
                }
                if (!swipeRefreshLayout.isRefreshing) swipeRefreshLayout.isRefreshing = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefreshLayout.isRefreshing = false
            }

            // Network/SSL/HTTP errors should also end the refresh animation
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                swipeRefreshLayout.isRefreshing = false
            }

            override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                swipeRefreshLayout.isRefreshing = false
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                if (diagnosticMode) handler?.proceed() else handler?.cancel()
                swipeRefreshLayout.isRefreshing = false
            }
        }

        webView.webChromeClient = object : WebChromeClient(){
            override fun onPermissionRequest(request: PermissionRequest?) {
                val cameraAllowed = request != null &&
                    KioskSecurity.isAllowedUrl(request.origin?.toString(), allowedOrigin) &&
                    ContextCompat.checkSelfPermission(
                        this@WebviewActivity,
                        android.Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                val videoRequested = request?.resources?.contains(
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE
                ) == true
                if (cameraAllowed && videoRequested) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    request?.deny()
                }
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress == 100)
                    swipeRefreshLayout.isRefreshing = false
            }
        }



        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            userAgentString = "${userAgentString} OPR-Guest-Kiosk/${BuildConfig.VERSION_NAME}"
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        webView.scrollBarStyle = WebView.SCROLLBARS_OUTSIDE_OVERLAY
        webView.isScrollbarFadingEnabled = false
        webView.loadUrl(url)
    }

    private fun loadBridgeConfig(): GuestRegistrationKioskConfig? {
        if (allowedOrigin.isBlank()) return null
        val token = sharedPref.getString(getString(R.string.kiosk_token_key), "")?.trim().orEmpty()
        val locationId = sharedPref.getString(
            getString(R.string.kiosk_location_key), ""
        )?.trim().orEmpty()
        val printerHost = sharedPref.getString(
            getString(R.string.printer_host_key), ""
        )?.trim().orEmpty()
        val printerPort = sharedPref.getInt(getString(R.string.printer_port_key), 9100)
        if (
            token.length < 20 ||
            locationId.isBlank() ||
            !KioskSecurity.isValidPrinterHost(printerHost) ||
            !KioskSecurity.isValidPrinterPort(printerPort)
        ) return null
        return GuestRegistrationKioskConfig(
            allowedOrigin = allowedOrigin,
            kioskToken = token,
            locationId = locationId,
            deviceName = Build.MODEL,
            printerHost = printerHost,
            printerPort = printerPort,
        )
    }

    private fun requestCameraPermission() {
        if (
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.CAMERA),
                4101,
            )
        }
    }

    private fun initVars() {
        webView = findViewById(R.id.webView)
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        reloadOnConnected = ReloadOnConnected(webView)
        adminComponentName = MyDeviceAdminReceiver.getComponentName(this)
        mDevicePolicyManager =
            getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        policyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private fun showInFullScreen(rootView: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, rootView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }


}
