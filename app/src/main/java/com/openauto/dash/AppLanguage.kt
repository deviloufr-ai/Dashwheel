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

        /** Whether Dashwheel is written in [language] (an ISO code such as "fr"). */
        internal fun hasText(language: String): Boolean = entries.any { it.tag == language }

        /**
         * The language Dashwheel speaks and writes in: the one chosen ([chosenTag]),
         * else the device's ([systemLanguage]) where Dashwheel has text for it
         * (null: nothing to override), else English.
         */
        internal fun textLanguage(chosenTag: String?, systemLanguage: String): String? =
            chosenTag ?: ENGLISH.tag.takeIf { !hasText(systemLanguage) }

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
            val system = systemLocale()
            val chosen = current(base)
            // The device in a language Dashwheel has no text for (Hungarian): the
            // text comes out in English, so the voice, dates and numbers follow,
            // or a sentence was said half in each ("130" in Hungarian).
            val tag = textLanguage(chosen.tag, system.language)
            // Android 13 applies a chosen language itself; only the fallback is left to do here.
            if (Build.VERSION.SDK_INT >= 33 && (chosen != SYSTEM || tag == null)) return base
            keepDefaultOnConfigChanges(base)
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
