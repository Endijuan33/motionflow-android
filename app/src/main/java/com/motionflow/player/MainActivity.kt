package com.motionflow.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import com.motionflow.player.core.designsystem.theme.MotionFlowTheme
import com.motionflow.player.navigation.MotionFlowNavHost

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MotionFlowTheme {
                MotionFlowNavHost(modifier = Modifier.safeDrawingPadding())
            }
        }
    }
}
