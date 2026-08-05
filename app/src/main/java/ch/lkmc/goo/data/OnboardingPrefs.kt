package ch.lkmc.goo.data

import android.content.Context

/**
 * First-run flags. Plain SharedPreferences: two booleans don't need
 * DataStore's coroutine ceremony, and reads happen once per screen.
 */
class OnboardingPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)

    /** The editor's "drag to goo" hint has served its purpose. */
    var smearHintSeen: Boolean
        get() = prefs.getBoolean(KEY_SMEAR_HINT, false)
        set(value) = prefs.edit().putBoolean(KEY_SMEAR_HINT, value).apply()

    private companion object {
        const val KEY_SMEAR_HINT = "smear_hint_seen"
    }
}
