package com.muxiao.iconpacker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muxiao.iconpacker.ServiceLocator
import com.muxiao.iconpacker.data.ThemeMode
import com.muxiao.iconpacker.ui.onboarding.OnboardingPrefs
import com.muxiao.iconpacker.ui.onboarding.OnboardingScreen
import com.muxiao.iconpacker.ui.theme.IconPackerTheme

/**
 * 应用根节点：主题外观选择 + 首次启动走引导（FR-01），完成后进主导航。
 *
 * 外观模式存全局设置（SharedPreferences，`data.AppSettings`）；
 * 收集 state 快照即时生效——手动切换时整棵树即时换肤，无需重启 Activity。
 *
 * 引导标记存 SharedPreferences；Activity 重建时 remember 重新初始化会再次读取，
 * 进程死亡恢复后也不会重复弹出。
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val settings by ServiceLocator.settings.state.collectAsStateWithLifecycle()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.FollowSystem -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    var onboarded by remember { mutableStateOf(OnboardingPrefs.isDone(context)) }

    IconPackerTheme(darkTheme = darkTheme) {
        if (onboarded) {
            IconPackerApp()
        } else {
            OnboardingScreen(
                onFinish = {
                    OnboardingPrefs.setDone(context)
                    onboarded = true
                },
            )
        }
    }
}
