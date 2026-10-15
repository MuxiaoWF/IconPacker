package com.muxiao.iconpacker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.muxiao.iconpacker.ui.AppRoot
import com.muxiao.iconpacker.ui.theme.IconPackerTheme

/**
 * 应用唯一 Activity（单 Activity + Compose Navigation，docs/architecture.md）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IconPackerTheme {
                AppRoot()
            }
        }
    }
}
