package com.openauto.dash

import android.app.Activity
import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The launcher's UI language: the system's by default, or one the driver picks
 * in the launcher itself. Head units rarely expose Android's language settings
 * (and per-app languages only arrive with Android 13), so the choice is kept
 * here and applied by wrapping each activity's and service's base context.
 *
 * [nativeName] is written in the language itself so it can be found whatever
 * the current one is.
 */
enum class AppLanguage(val tag: String?, val nativeName: String) {
    SYSTEM(null, ""),
    ENGLISH("en", "English"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    SPANISH("es", "Español"),
    ITALIAN("it", "Italiano"),
    PORTUGUESE("pt", "Português"),
    DUTCH("nl", "Nederlands"),
    POLISH("pl", "Polski"),
    RUSSIAN("ru", "Русский");

    companion object {
        private const val PREFS = "app_language"
        private const val KEY = "language"

        /** The device's own language, whatever the launcher overrides. */
        fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]

        fun current(context: Context): AppLanguage {
            if (Build.VERSION.SDK_INT >= 33) {
                val tag = context.getSystemService(LocaleManager::class.java)
                    ?.applicationLocales?.get(0)?.language
                return entries.firstOrNull { it.tag == tag } ?: SYSTEM
            }
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == saved } ?: SYSTEM
        }

        /** Stores [language] and restarts [activity] so every screen redraws in it. */
        fun select(activity: Activity, language: AppLanguage) {
            if (language == current(activity)) return
            if (Build.VERSION.SDK_INT >= 33) {
                // Android applies (and recreates for) its own per-app language.
                activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                    language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
                return
            }
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.name).apply()
            activity.recreate()
        }

        /** The locale [wrap] last applied; null while the system's is used. */
        @Volatile private var applied: Locale? = null
        private var watching = false

        /**
         * [base] with the chosen language applied, for `attachBaseContext`. Also
         * sets the JVM default locale so dates and numbers follow the language.
         */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= 33) return base
            keepDefaultOnConfigChanges(base)
            val tag = current(base).tag
            val system = systemLocale()
            if (tag == null || tag == system.language) {
                applied = null
                Locale.setDefault(system)
                return base
            }
            // Keep the country so e.g. French-in-Belgium date formats survive a
            // switch to Dutch; the language is what picks the strings.
            val locale = Locale(tag, system.country)
            applied = locale
            Locale.setDefault(locale)
            // A blank Configuration is a delta: only the locale is overridden,
            // so day/night and font scale keep following the system.
            val delta = Configuration().apply { setLocales(LocaleList(locale)) }
            return base.createConfigurationContext(delta)
        }

        /**
         * Android resets the default locale to the system's on every configuration
         * change (day/night, split screen…); put the chosen one back each time so
         * dates and numbers stay in the chosen language.
         */
        private fun keepDefaultOnConfigChanges(base: Context) {
            if (watching) return
            watching = true
            base.applicationContext.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    applied?.let { Locale.setDefault(it) }
                }

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = Unit
            })
        }
    }
}
