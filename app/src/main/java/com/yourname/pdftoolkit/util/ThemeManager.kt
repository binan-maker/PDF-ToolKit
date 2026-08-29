package com.anonymous.imgpdf.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Theme modes available in the app.
 */
enum class ThemeMode(val value: Int, val displayName: String) {
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO, "Light"),
    DARK(AppCompatDelegate.MODE_NIGHT_YES, "Dark"),
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, "System Default");

    companion object {
        fun fromValue(value: Int): ThemeMode {
            return entries.find { it.value == value } ?: SYSTEM
        }
    }
}

/**
 * ThemeManager handles theme persistence and application using DataStore.
 * Provides a modern, type-safe way to manage app theme preferences.
 */
object ThemeManager {
    private const val STARTUP_PREFS = "theme_startup"
    private const val STARTUP_THEME_KEY = "theme_mode"

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_preferences")
    private val THEME_MODE_KEY = intPreferencesKey("theme_mode")

    /**
     * Returns the last theme synchronously so the Activity window can start in
     * the correct mode before DataStore finishes its first asynchronous read.
     */
    fun getStartupThemeMode(context: Context): ThemeMode? {
        val value = context.getSharedPreferences(STARTUP_PREFS, Context.MODE_PRIVATE)
            .getInt(STARTUP_THEME_KEY, Int.MIN_VALUE)
        return if (value == Int.MIN_VALUE) null else ThemeMode.fromValue(value)
    }

    private fun cacheStartupThemeMode(context: Context, themeMode: ThemeMode) {
        context.getSharedPreferences(STARTUP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(STARTUP_THEME_KEY, themeMode.value)
            .apply()
    }

    /**
     * Get the current theme mode as a Flow.
     * Defaults to SYSTEM if no preference is set.
     */
    fun getThemeMode(context: Context): Flow<ThemeMode> {
        return context.dataStore.data.map { preferences ->
            val modeValue = preferences[THEME_MODE_KEY] ?: ThemeMode.SYSTEM.value
            ThemeMode.fromValue(modeValue)
        }
    }

    /**
     * Save the theme mode preference.
     *
     * @param context Application context
     * @param themeMode The theme mode to save
     */
    suspend fun setThemeMode(context: Context, themeMode: ThemeMode) {
        // Cache before the asynchronous write so the next cold start is stable.
        cacheStartupThemeMode(context, themeMode)
        context.dataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = themeMode.value
        }
        applyTheme(themeMode)
    }

    /**
     * Backfills the synchronous startup cache for users who already had a
     * DataStore preference before the cache was introduced.
     */
    fun cacheLoadedThemeMode(context: Context, themeMode: ThemeMode) {
        cacheStartupThemeMode(context, themeMode)
    }

    /**
     * Apply the theme immediately using AppCompatDelegate.
     * 
     * @param themeMode The theme mode to apply
     */
    fun applyTheme(themeMode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(themeMode.value)
    }

    /**
     * Initialize theme on app startup.
     * Should be called from Application.onCreate() or MainActivity.onCreate().
     * 
     * @param context Application context
     * @param onThemeLoaded Callback invoked when theme is loaded and applied
     */
    suspend fun initializeTheme(context: Context, onThemeLoaded: ((ThemeMode) -> Unit)? = null) {
        getThemeMode(context).collect { themeMode ->
            applyTheme(themeMode)
            onThemeLoaded?.invoke(themeMode)
        }
    }
}
