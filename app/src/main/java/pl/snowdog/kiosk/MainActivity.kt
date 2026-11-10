package pl.snowdog.kiosk

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar
import pl.snowdog.kiosk.databinding.ActivityMainBinding
import java.util.Calendar
import kotlin.system.exitProcess


class MainActivity : AppCompatActivity() {

    private lateinit var mAdminComponentName: ComponentName
    private lateinit var mDevicePolicyManager: DevicePolicyManager
    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPref: SharedPreferences

    private lateinit var audioManager: AudioManager

    companion object {
        const val LOCK_ACTIVITY_KEY = "pl.snowdog.kiosk.MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // targetSdk 35 enforces edge-to-edge: keep the form clear of system bars and the keyboard
        ViewCompat.setOnApplyWindowInsetsListener(binding.nestedScrollView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

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

        val url = sharedPref.getString(getString(R.string.url_key), "")
        binding.txtUrl.editText?.setText(url)
        val pin = sharedPref.getString(getString(R.string.pin_key), "")
        binding.txtPin.editText?.setText(pin)
        binding.txtPinConfirm.editText?.setText(pin)
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
        val pinConfirm = binding.txtPinConfirm.editText?.text.toString()
        var allowedOrigin = binding.txtAllowedOrigin.editText?.text.toString()
        val kioskToken = binding.txtKioskToken.editText?.text.toString().trim()
        val kioskLocation = binding.txtKioskLocation.editText?.text.toString().trim()
        val printerHost = binding.txtPrinterHost.editText?.text.toString().trim()
        val printerPort = binding.txtPrinterPort.editText?.text.toString().toIntOrNull()
        val diagnosticMode = binding.cbDiagnosticMode.isChecked

        // The PIN is saved below in both cases, so never store a mistyped one
        if (pin != pinConfirm) {
            showValidationError(R.string.pin_mismatch, R.id.txtPinConfirm)
            return
        }

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


}
