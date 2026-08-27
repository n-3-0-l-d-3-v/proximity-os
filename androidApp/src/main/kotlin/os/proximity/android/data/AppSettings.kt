package os.proximity.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    companion object {
        private const val FILE_NAME = "proximity_os_settings"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ONBOARDED = "has_onboarded"
        private const val DEFAULT_DISPLAY_NAME = "Someone nearby"
        const val MAX_NAME_LENGTH = 24
    }
}
