package os.proximity.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import os.proximity.shared.guardrail.PolicyCatalog

/**
 * Local, on-device preferences. Nothing here leaves the device.
 *
 * The display name is deliberately *not* defaulted to the phone's Bluetooth
 * name: that name is often the owner's real name and is broadcast widely.
 * The user picks what to be called during onboarding instead.
 */
class AppSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val displayNameState = MutableStateFlow(
        prefs.getString(KEY_DISPLAY_NAME, null) ?: DEFAULT_DISPLAY_NAME
    )
    val displayName: StateFlow<String> = displayNameState

    private val onboardedState = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val hasOnboarded: StateFlow<Boolean> = onboardedState

    fun setDisplayName(name: String) {
        val cleaned = name.trim().take(MAX_NAME_LENGTH).ifBlank { DEFAULT_DISPLAY_NAME }
        prefs.edit().putString(KEY_DISPLAY_NAME, cleaned).apply()
        displayNameState.value = cleaned
    }

    fun setOnboarded(value: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()
        onboardedState.value = value
    }

    private val enabledPoliciesState = MutableStateFlow(
        // A missing key means first run — fall back to the catalog's defaults
        // rather than to "nothing enabled", which would silently drop the
        // protections the user is entitled to expect.
        prefs.getStringSet(KEY_ENABLED_POLICIES, null)
            ?: PolicyCatalog.defaultEnabledIds
    )
    val enabledPolicyIds: StateFlow<Set<String>> = enabledPoliciesState

    private val backgroundState = MutableStateFlow(prefs.getBoolean(KEY_BACKGROUND, false))

    /**
     * Whether the mesh keeps running when the app is not in front.
     *
     * Off by default and deliberately so: holding the Bluetooth radio open
     * in the background is exactly the kind of thing this app tells users it
     * will not do without asking.
     */
    val runInBackground: StateFlow<Boolean> = backgroundState

    fun setRunInBackground(value: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND, value).apply()
        backgroundState.value = value
    }

    fun setPolicyEnabled(id: String, enabled: Boolean) {
        val updated = if (enabled) {
            enabledPoliciesState.value + id
        } else {
            enabledPoliciesState.value - id
        }
        prefs.edit().putStringSet(KEY_ENABLED_POLICIES, updated).apply()
        enabledPoliciesState.value = updated
    }

    companion object {
        private const val FILE_NAME = "proximity_os_settings"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ONBOARDED = "has_onboarded"
        private const val KEY_ENABLED_POLICIES = "enabled_policies"
        private const val KEY_BACKGROUND = "run_in_background"
        private const val DEFAULT_DISPLAY_NAME = "Someone nearby"
        const val MAX_NAME_LENGTH = 24
    }
}
