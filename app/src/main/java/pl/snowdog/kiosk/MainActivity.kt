package pl.snowdog.kiosk

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.UserManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.google.android.material.snackbar.Snackbar
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import org.json.JSONObject
import pl.snowdog.kiosk.databinding.ActivityMainBinding
import java.io.File
import java.util.Calendar
import kotlin.system.exitProcess


class MainActivity : AppCompatActivity() {

    private lateinit var mAdminComponentName: ComponentName
    private lateinit var mDevicePolicyManager: DevicePolicyManager
    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPref: SharedPreferences

    private var latestVersion: String? = null
    private var updateUrl = "https://kiosk.neoli.ms"
    private var updateVersionJsonUrl = "$updateUrl/version.json"
    private var downloadUrl = "$updateUrl/primus-kiosk-{x.y.z}.apk"

    private lateinit var audioManager: AudioManager

    companion object {
        const val LOCK_ACTIVITY_KEY = "pl.snowdog.kiosk.MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mAdminComponentName = MyDeviceAdminReceiver.getComponentName(this)
        mDevicePolicyManager =
            getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        sharedPref =
            getSharedPreferences(getString(R.string.storage_key), MODE_PRIVATE) ?: return

        if (!mDevicePolicyManager.isAdminActive(mAdminComponentName)){
            requestAdminActivation()
            return
        }

        init()
    }

    private fun init() {
//        mDevicePolicyManager.removeActiveAdmin(mAdminComponentName)

        checkForUpdate(binding.btnUpdate, updateVersionJsonUrl, BuildConfig.VERSION_NAME )
        { latest ->
            // Optional: change label or set click to start your update flow
            // findViewById<Button>(R.id.btnUpdate).text = "Update to $latest"
            binding.btnUpdate.text = "Update to $latest"
            latestVersion = latest
        }

        val url = sharedPref.getString(getString(R.string.url_key), "")
        binding.txtUrl.editText?.setText(url)
        val pin = sharedPref.getString(getString(R.string.pin_key), "")
        binding.txtPin.editText?.setText(pin)
        val allowedOrigin = sharedPref.getString(
            getString(R.string.allowed_origin_key),
            KioskSecurity.originOf(url) ?: "https://app.example.com"
        )
        binding.txtAllowedOrigin.editText?.setText(allowedOrigin)
        binding.txtKioskToken.editText?.setText(
            sharedPref.getString(getString(R.string.kiosk_token_key), "")
        )
        binding.txtKioskLocation.editText?.setText(
            sharedPref.getString(getString(R.string.kiosk_location_key), "")
        )
        binding.txtPrinterHost.editText?.setText(
            sharedPref.getString(getString(R.string.printer_host_key), "")
        )
        binding.txtPrinterPort.editText?.setText(
            sharedPref.getInt(getString(R.string.printer_port_key), 9100).toString()
        )
        binding.cbDiagnosticMode.isChecked = sharedPref.getBoolean(
            getString(R.string.diagnostic_mode_key), false
        )
        val autostart = sharedPref.getBoolean(getString(R.string.autostart), false)
        binding.cbKioskAutostart.isChecked = autostart

        val time = sharedPref.getString(getString(R.string.reload_time), "02:00")
        binding.ttReloadTime.setText(time)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        binding.sbAudioVolume.valueTo = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()

        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        val audio = sharedPref.getFloat(getString(R.string.audio_volume), current)
        binding.sbAudioVolume.value = audio
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            audio.toInt(),
            AudioManager.FLAG_SHOW_UI
        )

        val edit = sharedPref.getBoolean(getString(R.string.edit_key), false)

        if (!edit && !url.isNullOrEmpty() && !pin.isNullOrEmpty()) {
            val intent = Intent(applicationContext, WebviewActivity::class.java)
            startActivity(intent)
            return
        } else if (url == null || url == "") {
            showValidationError(R.string.url_wrong, R.id.txtUrl)
        } else if (pin == null || pin == "") {
            showValidationError(R.string.pin_wrong, R.id.txtPin)
        }

        val isAdmin = isAdmin()
        if (isAdmin) {
            Snackbar.make(binding.content, R.string.device_owner, Snackbar.LENGTH_SHORT).show()
        } else {
            Snackbar.make(binding.content, R.string.not_device_owner, Snackbar.LENGTH_SHORT).show()
        }

        initButtons(isAdmin)

        maybeRequestExactAlarmOnStartup()

        sharedPref.edit(commit = true) { putString(getString(R.string.reload_time), "13:46") }

    }


    private fun maybeRequestExactAlarmOnStartup() {

        val alreadyAsked = sharedPref.getBoolean("asked_exact_alarm", false)
        if (!ExactAlarmPermission.hasPermission(this)) {
            // Optional: show a short rationale dialog so users know why
            // If you skip the dialog, you can navigate directly:
            ExactAlarmPermission.openSettingsToGrant(this)
            // remember we asked to avoid nagging every launch
            sharedPref.edit { putBoolean("asked_exact_alarm", true) }
        } else if (!alreadyAsked) {
            // first run and already allowed — schedule now
            RefreshScheduler.scheduleDailyRefresh(applicationContext, 2, 0)
            sharedPref.edit { putBoolean("asked_exact_alarm", true) }
        }
    }



    private fun requestAdminActivation() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, mAdminComponentName)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enable admin for kiosk features like screen lock.")
        }

        val resultLauncher = this.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                init()
            }else {
                AlertDialog.Builder(this)
                    .setTitle("Error")
                    .setMessage("Could not get the needed Rights")
                    .setPositiveButton("Ok"){_,_ ->
                        moveTaskToBack(true)
                        exitProcess(-1)
                    }.setCancelable(false)
                    .create()
                    .show()
            }
        }

        resultLauncher.launch(intent)
    }

    private fun initButtons(isAdmin: Boolean) {
        binding.btStartLockTask.setOnClickListener {
            setKioskPolicies(true, isAdmin)
        }

        binding.btStopLockTask.setOnClickListener {
            setKioskPolicies(false, isAdmin)
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            intent.putExtra(LOCK_ACTIVITY_KEY, false)
            startActivity(intent)
        }

        binding.cbKioskAutostart.setOnCheckedChangeListener { _, value ->
            sharedPref.edit(commit = true) {
                putBoolean(getString(R.string.autostart), value)
            }
        }

        binding.ttReloadTime.setOnClickListener {
            val cal = Calendar.getInstance()
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val minute = cal.get(Calendar.MINUTE)

            val picker = TimePickerDialog(
                this,
                { _, h, m -> binding.ttReloadTime.setText(String.format("%02d:%02d", h, m)) },
                hour, minute, true
            )
            picker.show()
        }

        binding.ttReloadTime.addTextChangedListener(object : TextWatcher{
            override fun afterTextChanged(s: Editable?) {
                val time = s.toString().split(":")
                if (time.count() == 2){

                    if (time[0].isEmpty() || time[0].isBlank() || time[1].isEmpty() || time[1].isBlank())
                        return

                    if (time[0].toInt() < 0 || time[0].toInt() > 24)
                        return

                    if (time[1].toInt() < 0 || time[1].toInt() > 59)
                        return

                    sharedPref.edit(commit = true) {
                        putString(getString(R.string.reload_time), s.toString())
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnUpdate.setOnClickListener {
            if (!latestVersion.isNullOrEmpty()){
                startUpdateFlow(downloadUrl.replace("{x.y.z}", latestVersion!!))
            }
        }

        binding.sbAudioVolume.addOnChangeListener { _, value, _ ->
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                value.toInt(),
                AudioManager.FLAG_SHOW_UI
            )
            sharedPref.edit(commit = true) {
                putFloat(getString(R.string.audio_volume), value)
            }
        }

    }

    override fun onResume() {
        super.onResume()
        initButtons(isAdmin())

        if (ExactAlarmPermission.hasPermission(this)) {
            // safe to schedule your 02:00 alarm
            setScheduler(this.applicationContext)
            sharedPref.edit { putBoolean("asked_exact_alarm", true) }
        }

    }

    private fun isAdmin() = mDevicePolicyManager.isDeviceOwnerApp(packageName)

    private fun showValidationError(message: Int, anchorId: Int) {
        val snack = Snackbar.make(binding.content, message, Snackbar.LENGTH_SHORT)
        val layoutParams = snack.view.layoutParams as CoordinatorLayout.LayoutParams
        layoutParams.anchorId = anchorId //Id for your bottomNavBar or TabLayout
        layoutParams.anchorGravity = Gravity.BOTTOM
        layoutParams.gravity = Gravity.BOTTOM
        layoutParams.topMargin = 10
        snack.view.layoutParams = layoutParams
        snack.show()
    }

    private fun setKioskPolicies(enable: Boolean, isAdmin: Boolean) {
        var url = binding.txtUrl.editText?.text.toString()
        val pin = binding.txtPin.editText?.text.toString()
        var allowedOrigin = binding.txtAllowedOrigin.editText?.text.toString()
        val kioskToken = binding.txtKioskToken.editText?.text.toString().trim()
        val kioskLocation = binding.txtKioskLocation.editText?.text.toString().trim()
        val printerHost = binding.txtPrinterHost.editText?.text.toString().trim()
        val printerPort = binding.txtPrinterPort.editText?.text.toString().toIntOrNull()
        val diagnosticMode = binding.cbDiagnosticMode.isChecked

        if (enable) {
            if (url.trim() == "") {
                showValidationError(R.string.url_wrong, R.id.txtUrl)
                return
            } else if (pin.trim() == "") {
                showValidationError(R.string.pin_wrong, R.id.txtPin)
                return
            }

            if (!url.startsWith("http")) {
                url = "https://$url"
            } else if (!url.startsWith("https")) {
                url = url.replace("http", "https")
            }

            val normalizedOrigin = KioskSecurity.normalizeHttpsOrigin(allowedOrigin)
            if (normalizedOrigin == null || (!diagnosticMode && !KioskSecurity.isAllowedUrl(url, normalizedOrigin))) {
                showValidationError(R.string.url_wrong, R.id.txtAllowedOrigin)
                return
            }
            allowedOrigin = normalizedOrigin
            if (!diagnosticMode && (
                    kioskToken.length < 20 ||
                        kioskLocation.isBlank() ||
                        !KioskSecurity.isValidPrinterHost(printerHost) ||
                        printerPort == null ||
                        !KioskSecurity.isValidPrinterPort(printerPort)
                    )
            ) {
                Toast.makeText(
                    this,
                    "Kiosk-Token, Standort und Drucker-Konfiguration prüfen.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        if (isAdmin) {
            setRestrictions(enable)
            enableStayOnWhilePluggedIn(enable)
            setUpdatePolicy(enable)
            setAsHomeApp(enable)
            setKeyGuardEnabled(enable)
        }
        setLockTask(enable, isAdmin)
        setImmersiveMode(enable)


        // save URL on storage
        sharedPref.edit(commit = true) {
            putString(getString(R.string.url_key), url)
            putString(getString(R.string.pin_key), pin)
            putString(getString(R.string.allowed_origin_key), allowedOrigin)
            putString(getString(R.string.kiosk_token_key), kioskToken)
            putString(getString(R.string.kiosk_location_key), kioskLocation)
            putString(getString(R.string.printer_host_key), printerHost)
            putInt(getString(R.string.printer_port_key), printerPort ?: 9100)
            putBoolean(getString(R.string.diagnostic_mode_key), diagnosticMode)
        }

        if (enable) {
            sharedPref.edit(commit = true) {
                putBoolean(getString(R.string.edit_key), false)
            }
            val intent = Intent(applicationContext, WebviewActivity::class.java)
            startActivity(intent)
        }
    }

    // region restrictions
    private fun setRestrictions(disallow: Boolean) {
        setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, disallow)
        setUserRestriction(UserManager.DISALLOW_FACTORY_RESET, disallow)
        setUserRestriction(UserManager.DISALLOW_ADD_USER, disallow)
        setUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, disallow)
        setUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME, disallow)
        mDevicePolicyManager.setStatusBarDisabled(mAdminComponentName, disallow)
    }

    private fun setUserRestriction(restriction: String, disallow: Boolean) = if (disallow) {
        mDevicePolicyManager.addUserRestriction(mAdminComponentName, restriction)
    } else {
        mDevicePolicyManager.clearUserRestriction(mAdminComponentName, restriction)
    }
    // endregion

    private fun enableStayOnWhilePluggedIn(active: Boolean) = if (active) {
        mDevicePolicyManager.setGlobalSetting(
            mAdminComponentName,
            Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
            (BatteryManager.BATTERY_PLUGGED_AC
                    or BatteryManager.BATTERY_PLUGGED_USB
                    or BatteryManager.BATTERY_PLUGGED_WIRELESS).toString()
        )
    } else {
        mDevicePolicyManager.setGlobalSetting(
            mAdminComponentName,
            Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
            "0"
        )
    }

    private fun setLockTask(start: Boolean, isAdmin: Boolean) {
        if (isAdmin) {
            mDevicePolicyManager.setLockTaskPackages(
                mAdminComponentName, if (start) arrayOf(packageName) else arrayOf()
            )
        }
        if (start) {
            startLockTask()
        } else {
            stopLockTask()
        }
    }

    private fun setUpdatePolicy(enable: Boolean) {
        if (enable) {
            mDevicePolicyManager.setSystemUpdatePolicy(
                mAdminComponentName,
                SystemUpdatePolicy.createWindowedInstallPolicy(60, 120)
            )
        } else {
            mDevicePolicyManager.setSystemUpdatePolicy(mAdminComponentName, null)
        }
    }

    private fun setAsHomeApp(enable: Boolean) {
        if (enable) {
            val intentFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            mDevicePolicyManager.addPersistentPreferredActivity(
                mAdminComponentName,
                intentFilter,
                ComponentName(packageName, MainActivity::class.java.name)
            )
        } else {
            mDevicePolicyManager.clearPackagePersistentPreferredActivities(
                mAdminComponentName, packageName
            )
        }
    }

    private fun setKeyGuardEnabled(enable: Boolean) {
        mDevicePolicyManager.setKeyguardDisabled(mAdminComponentName, !enable)
    }

    private fun setImmersiveMode(enable: Boolean) {
        if (enable) {
            val flags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            window.decorView.systemUiVisibility = flags
        } else {
            val flags = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            window.decorView.systemUiVisibility = flags
        }
    }

    /**
     * Fetches a JSON with { "version": "x.y.z" } and shows the button if a newer version exists.
     *
     * @param updateButton The button to show/hide.
     * @param versionUrl   Your backend endpoint (e.g., https://example.com/app/latest.json).
     * @param currentVersion Your app's current version (e.g., BuildConfig.VERSION_NAME).
     * @param onNewVersion Optional callback with the latest version when available.
     */
    fun checkForUpdate(
        updateButton: Button,
        versionUrl: String,
        currentVersion: String,
        onNewVersion: ((String) -> Unit)? = null
    ) {
        updateButton.visibility = View.GONE

        val client = OkHttpClient()
        val req = Request.Builder().url(versionUrl).get().build()

        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                // You might log this or ignore silently.
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) return

                    val body = it.body?.string() ?: return
                    val latest = try {
                        JSONObject(body).getString("version")
                    } catch (_: Exception) { return }

                    val hasUpdate = compareVersions(latest, currentVersion) > 0

                    updateButton.post {
                        updateButton.visibility = if (hasUpdate) View.VISIBLE else View.GONE
                        if (hasUpdate) onNewVersion?.invoke(latest)
                    }
                }
            }
        })
    }

    /**
     * Compares semantic-ish versions like "1.2.10" vs "1.3".
     * Returns >0 if a>b, 0 if equal, <0 if a<b.
     */
    fun compareVersions(a: String, b: String): Int {
        val asParts = a.split('.', '-', '_')
        val bsParts = b.split('.', '-', '_')
        val max = maxOf(asParts.size, bsParts.size)

        for (i in 0 until max) {
            val ai = asParts.getOrNull(i)?.toIntOrNull() ?: 0
            val bi = bsParts.getOrNull(i)?.toIntOrNull() ?: 0
            if (ai != bi) return ai - bi
        }
        return 0
    }

    fun startUpdateFlow(apkUrl: String) {
        pendingAfterUnknownSources = { downloadAndInstallApk(apkUrl) }
        ensureCanInstallUnknownSources { downloadAndInstallApk(apkUrl) }
    }

    fun downloadAndInstallApk(url: String) {
        val last = Uri.parse(url).lastPathSegment ?: "update.apk"
        val fileName = if (last.endsWith(".apk", ignoreCase = true)) last else "update.apk"

        // nuke any stale file
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
        File(dir, fileName).apply { if (exists()) delete() }

        downloadWithOkHttpAndInstall(url)
    }

    private fun downloadWithOkHttpAndInstall(url: String) {
        val client = OkHttpClient()
        val req = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity") // avoid gzip for APKs
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful || resp.body == null) return@use
                    val target = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }

                    val apkUri = FileProvider.getUriForFile(
                        this@MainActivity, "$packageName.fileprovider", target
                    )
                    runOnUiThread { installApk(this@MainActivity, apkUri) }
                }
            }
        })
    }

    private fun installApk(context: Context, apkUri: Uri) {
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Verify we can handle the intent
        if (installIntent.resolveActivity(context.packageManager) != null) {
            try {
                context.startActivity(installIntent)
            } catch (e: Exception) {
                Toast.makeText(context, "Unable to open installer: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "No installer available.", Toast.LENGTH_LONG).show()
        }
    }

    // Somewhere in an Activity or a class with an Activity reference
    private fun ensureCanInstallUnknownSources(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onReady()
            return
        }

        val canInstall = packageManager.canRequestPackageInstalls()
        if (canInstall) {
            onReady()
        } else {
            // Ask the user to allow it for this app
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            // After user comes back, try again
            unknownSourcesResult.launch(intent)
        }
    }

    private val unknownSourcesResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // User returned from Settings. Try again or handle denial.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                packageManager.canRequestPackageInstalls()
            ) {
                // Continue the flow you wanted (e.g., start download)
                pendingAfterUnknownSources?.invoke()
                pendingAfterUnknownSources = null
            } else {
                Toast.makeText(this, "Can't install without permission.", Toast.LENGTH_LONG).show()
            }
        }

    private var pendingAfterUnknownSources: (() -> Unit)? = null


}
