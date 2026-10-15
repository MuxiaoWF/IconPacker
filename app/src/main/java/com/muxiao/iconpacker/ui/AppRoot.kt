package com.muxiao.iconpacker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.muxiao.iconpacker.ui.onboarding.OnboardingPrefs
import com.muxiao.iconpacker.ui.onboarding.OnboardingScreen

/**
 * 应用根节点：首次启动走引导（FR-01），完成后进主导航。
 *
 * 引导标记存 SharedPreferences；Activity 重建时 remember 重新初始化会再次读取，
 * 进程死亡恢复后也不会重复弹出。
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    var onboarded by remember { mutableStateOf(OnboardingPrefs.isDone(context)) }

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
