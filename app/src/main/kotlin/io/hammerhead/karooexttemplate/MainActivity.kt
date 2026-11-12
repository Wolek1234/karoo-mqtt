package io.hammerhead.karooexttemplate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.hammerhead.karooexttemplate.data.SettingsRepository
import io.hammerhead.karooexttemplate.screens.MainScreen
import io.hammerhead.karooexttemplate.theme.AppTheme

class MainActivity : ComponentActivity() {

    private val settingsRepo by lazy { SettingsRepository(applicationContext) }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AppTheme {
                MainScreen(settingsRepo)
            }
        }
    }
}
