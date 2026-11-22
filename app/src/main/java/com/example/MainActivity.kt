package com.example

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.arif.goku.overlay.GokuOverlayService
import com.arif.goku.ui.DashboardScreen
import com.example.ui.theme.GokuAgentTheme

class MainActivity : ComponentActivity() {

    private val autoListenCounter = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        handleIncomingIntent(intent)
        setContent {
            GokuAgentTheme {
                DashboardScreen(autoListenTrigger = autoListenCounter.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(GokuOverlayService.EXTRA_TRIGGER_VOICE_LISTEN, false) == true) {
            intent.removeExtra(GokuOverlayService.EXTRA_TRIGGER_VOICE_LISTEN)
            autoListenCounter.intValue += 1
        }
    }
}
