/*
 * Copyright 2018-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import im.vector.app.core.di.DefaultPreferences
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Object to manage the Locale choice of the user.
 */
@Singleton
class VectorLocale @Inject constructor(
        @DefaultPreferences
        private val preferences: SharedPreferences,
) {
    companion object {
        const val APPLICATION_LOCALE_COUNTRY_KEY = "APPLICATION_LOCALE_COUNTRY_KEY"
        const val APPLICATION_LOCALE_VARIANT_KEY = "APPLICATION_LOCALE_VARIANT_KEY"
        const val APPLICATION_LOCALE_LANGUAGE_KEY = "APPLICATION_LOCALE_LANGUAGE_KEY"
        private const val APPLICATION_LOCALE_SCRIPT_KEY = "APPLICATION_LOCALE_SCRIPT_KEY"
        private const val ISO_15924_LATN = "Latn"
    }

    private val defaultLocale = Locale("fa", "IR")

    /**
     * The cache of supported application languages.
     */
    private val supportedLocales = listOf(defaultLocale)

    /**
     * Provides the current application locale.
     */
    var applicationLocale = defaultLocale
        private set

    /**
     * Init this singleton.
     */
    fun init() {
        // Force the application locale to Persian.
        saveApplicationLocale(defaultLocale)
    }

    /**
     * Save the new application locale.
     */
    fun saveApplicationLocale(locale: Locale) {
        val resolvedLocale = locale.takeIf {
            it.language == defaultLocale.language && it.country == defaultLocale.country
        } ?: defaultLocale
        applicationLocale = resolvedLocale

        preferences.edit {
            val language = resolvedLocale.language
            if (language.isEmpty()) {
                remove(APPLICATION_LOCALE_LANGUAGE_KEY)
            } else {
                putString(APPLICATION_LOCALE_LANGUAGE_KEY, language)
            }

            val country = resolvedLocale.country
            if (country.isEmpty()) {
                remove(APPLICATION_LOCALE_COUNTRY_KEY)
            } else {
                putString(APPLICATION_LOCALE_COUNTRY_KEY, country)
            }

            val variant = resolvedLocale.variant
            if (variant.isEmpty()) {
                remove(APPLICATION_LOCALE_VARIANT_KEY)
            } else {
                putString(APPLICATION_LOCALE_VARIANT_KEY, variant)
            }

            val script = resolvedLocale.script
            if (script.isEmpty()) {
                remove(APPLICATION_LOCALE_SCRIPT_KEY)
            } else {
                putString(APPLICATION_LOCALE_SCRIPT_KEY, script)
            }
        }
    }

    /**
     * Convert a locale to a string.
     *
     * @param locale the locale to convert
     * @return the string
     */
    fun localeToLocalisedString(locale: Locale): String {
        return buildString {
            append(locale.getDisplayLanguage(locale))

            if (locale.script != ISO_15924_LATN && locale.getDisplayScript(locale).isNotEmpty()) {
                append(" - ")
                append(locale.getDisplayScript(locale))
            }

            if (locale.getDisplayCountry(locale).isNotEmpty()) {
                append(" (")
                append(locale.getDisplayCountry(locale))
                append(")")
            }
        }
    }

    /**
     * Information about the locale in the current locale.
     *
     * @param locale the locale to get info from
     * @return the string
     */
    fun localeToLocalisedStringInfo(locale: Locale): String {
        return buildString {
            append("[")
            append(locale.displayLanguage)
            if (locale.script != ISO_15924_LATN) {
                append(" - ")
                append(locale.displayScript)
            }
            if (locale.displayCountry.isNotEmpty()) {
                append(" (")
                append(locale.displayCountry)
                append(")")
            }
            append("]")
        }
    }

    suspend fun getSupportedLocales(): List<Locale> {
        return supportedLocales
    }
}
