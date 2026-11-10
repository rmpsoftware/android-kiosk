package pl.snowdog.kiosk

import VolumeResetWorker
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager

class MyApp: Application() {

    companion object {
        /**
         * Raise this number in a release to clear the stored PIN once on the first start after
         * the update. The settings page then stays open until a new PIN is entered.
         */
        const val PIN_RESET_GENERATION = 1
    }

    override fun onCreate() {
        super.onCreate()
        val sharedPref =
            getSharedPreferences(getString(R.string.storage_key), MODE_PRIVATE) ?: return

        resetPinIfRequested(sharedPref)
        val autostart = sharedPref.getBoolean(getString(R.string.autostart), false)

        if (autostart){
            sharedPref.edit(commit = true) {
                putBoolean(getString(R.string.edit_key), false)
            }
        }

        scheduleVolumeReset(this)
    }

    private fun resetPinIfRequested(sharedPref: SharedPreferences) {
        val key = getString(R.string.pin_reset_generation_key)
        if (sharedPref.getInt(key, 0) >= PIN_RESET_GENERATION) return
        sharedPref.edit(commit = true) {
            remove(getString(R.string.pin_key))
            putInt(key, PIN_RESET_GENERATION)
        }
    }

    fun scheduleVolumeReset(context: Context) {
        val request = PeriodicWorkRequestBuilder<VolumeResetWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES
        )
            // Optional: add constraints if you like (but not required here)
            //.setConstraints(Constraints.NONE)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "reset_volume",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

}