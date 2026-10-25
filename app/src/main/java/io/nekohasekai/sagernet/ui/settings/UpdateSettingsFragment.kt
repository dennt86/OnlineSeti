package app.qthrone.mobile.ui.settings

import android.content.Intent
import androidx.preference.Preference
import app.qthrone.mobile.Key
import app.qthrone.mobile.R
import app.qthrone.mobile.update.UpdateActivity
import app.qthrone.mobile.update.UpdateScheduler
import app.qthrone.mobile.update.UpdateManager
import app.qthrone.mobile.BuildConfig
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class UpdateSettingsFragment : SettingsScreenFragment(R.xml.settings_updates) {
    override fun bind() {
        val currentVersion=pref<Preference>("updateCurrentVersion")
        currentVersion.summary=getString(R.string.update_current_version,BuildConfig.VERSION_NAME)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateManager.state.collect { state ->
                    currentVersion.isVisible=state is UpdateManager.State.Idle || state is UpdateManager.State.Checking ||
                        state is UpdateManager.State.UpToDate || state is UpdateManager.State.Failed && state.offer==null
                }
            }
        }
        pref<Preference>(Key.UPDATE_CHECK_AUTO).setOnPreferenceChangeListener { _, value ->
            UpdateScheduler.schedule(enabled=value as Boolean)
            true
        }
        pref<Preference>("qmobileUpdate").setOnPreferenceClickListener {
            startActivity(Intent(requireContext(),UpdateActivity::class.java))
            true
        }
    }
}
