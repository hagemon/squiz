package com.hagemon.squiz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hagemon.squiz.data.SquizRepository
import com.hagemon.squiz.model.AppSettings
import com.hagemon.squiz.model.ThemeMode
import com.hagemon.squiz.ui.SquizApp
import com.hagemon.squiz.ui.theme.SquizTheme

class MainActivity : ComponentActivity() {
    private val repository by lazy { SquizRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by repository.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings(),
            )
            val isDark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !isDark
                    isAppearanceLightNavigationBars = !isDark
                }
            }

            SquizTheme(settings = settings) {
                SquizApp(repository = repository, settings = settings)
            }
        }
    }
}
