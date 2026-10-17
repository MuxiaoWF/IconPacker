package com.muxiao.iconpacker

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.muxiao.iconpacker.data.AppSettings
import com.muxiao.iconpacker.data.LocaleMode
import com.muxiao.iconpacker.ui.AppRoot

/**
 * 应用唯一 Activity（单 Activity + Compose Navigation，docs/architecture.md）。
 * 主题由 [AppRoot] 按用户外观选择应用，Activity 不持有主题逻辑。
 *
 * 语言：attachBaseContext 阶段按用户选择（[LocaleMode]）包裹基础上下文，
 * 资源（stringResource 等）在此后的一切解析都落在目标语言；设置页手动切换后
 * 重建 Activity 重新走本方法。"跟随系统"不包裹，系统语言（含 Android 13+
 * 应用级语言）自然生效。
 */
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            AppSettings.readLocaleMode(newBase).wrap(newBase),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
    }
}
