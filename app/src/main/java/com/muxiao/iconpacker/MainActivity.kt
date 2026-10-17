package com.muxiao.iconpacker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.muxiao.iconpacker.ui.AppRoot

/**
 * 应用唯一 Activity（单 Activity + Compose Navigation，docs/architecture.md）。
 * 主题由 [AppRoot] 按用户外观选择应用，Activity 不持有主题逻辑。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
    }
}
