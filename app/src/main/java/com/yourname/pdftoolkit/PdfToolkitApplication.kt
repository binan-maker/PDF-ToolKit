package com.yourname.pdftoolkit

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.yourname.pdftoolkit.review.ReviewIntegration
import com.yourname.pdftoolkit.util.CacheManager
import com.yourname.pdftoolkit.util.ThemeManager
import com.yourname.pdftoolkit.util.LanguageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application class for Paperly.
 * Initializes PdfBox-Android on startup and manages cache cleanup.
 */
class PdfToolkitApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Initialize PdfBox-Android without delaying the first Activity frame.
        applicationScope.launch {
            PDFBoxResourceLoader.init(applicationContext)
        }

        // Initialize In-App Review system for session tracking
        ReviewIntegration.initialize(this)

        // Load preferences off the main thread. Blocking Application.onCreate() here
        // made the first frame wait on two independent DataStore reads.
        applicationScope.launch {
            val (themeMode, savedLanguage) = coroutineScope {
                val theme = async {
                    ThemeManager.getThemeMode(applicationContext).first()
                }
                val language = async {
                    LanguageManager.getLanguageFlow(applicationContext).first()
                }
                theme.await() to language.await()
            }

            withContext(Dispatchers.Main.immediate) {
                if (AppCompatDelegate.getDefaultNightMode() != themeMode.value) {
                    ThemeManager.applyTheme(themeMode)
                }
                if (LanguageManager.getCurrentLanguage() != savedLanguage) {
                    LanguageManager.setLanguage(applicationContext, savedLanguage)
                }
                Log.d("PdfToolkit", "Startup preferences applied")
            }
        }

        // Auto-clean cache on startup (runs in background)
        applicationScope.launch {
            try {
                // Clean old cache files (older than 24 hours)
                val cleanedBytes = CacheManager.clearOldCache(applicationContext)
                if (cleanedBytes > 0) {
                    Log.d("PdfToolkit", "Auto-cleaned ${cleanedBytes / 1024} KB from cache")
                }

                // Also clean PDF operation temp files
                CacheManager.clearPdfOperationsCache(applicationContext)
            } catch (e: Exception) {
                Log.e("PdfToolkit", "Cache cleanup failed", e)
            }
        }
    }
}