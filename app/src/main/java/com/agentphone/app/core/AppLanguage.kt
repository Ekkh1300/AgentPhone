package com.agentphone.app.core

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Lightweight app-language handling that works on API 26+ without AppCompat.
 * The choice is stored in a non-sensitive SharedPreferences file so it can be read in
 * attachBaseContext before DI/DataStore are available.
 */
object AppLanguage {
    const val PREFS = "app_language"
    const val KEY = "language"
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val PERSIAN = "fa"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, SYSTEM) ?: SYSTEM

    fun set(context: Context, language: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, language).apply()
    }

    fun isPersian(context: Context): Boolean {
        val tag = get(context)
        return tag == PERSIAN || (tag == SYSTEM && currentLocale(context).language == "fa")
    }

    fun wrap(context: Context): Context {
        val tag = get(context)
        if (tag == SYSTEM) return context
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    private fun currentLocale(context: Context): Locale =
        context.resources.configuration.locales[0] ?: Locale.getDefault()
}
