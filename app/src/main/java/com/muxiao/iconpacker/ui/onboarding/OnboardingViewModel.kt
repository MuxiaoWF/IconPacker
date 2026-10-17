package com.muxiao.iconpacker.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.muxiao.iconpacker.data.LauncherCompat
import com.muxiao.iconpacker.data.LauncherSupport
import com.muxiao.iconpacker.ui.components.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 引导页状态（FR-01） */
data class OnboardingUiState(
    val checking: Boolean = true,
    val currentLauncherLabel: String? = null,
    val currentSupportLevel: LauncherSupport.SupportLevel = LauncherSupport.SupportLevel.UNKNOWN,
    val currentGuidance: UiText? = null,
    val availableLaunchers: List<LauncherCompat.LauncherInfo> = emptyList(),
)

class OnboardingViewModel(
    private val launcherCompat: LauncherCompat,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = OnboardingUiState(checking = true)
            val launchers = launcherCompat.detectIconPackLaunchers()
            val current = launcherCompat.currentLauncherPackage()
            val currentInfo = launchers.firstOrNull { it.isCurrent }
            _uiState.value = OnboardingUiState(
                checking = false,
                currentLauncherLabel = currentInfo?.label ?: current,
                currentSupportLevel = launcherCompat.currentSupportLevel(),
                currentGuidance = launcherCompat.currentGuidanceRes()?.let { UiText(it) },
                availableLaunchers = launchers,
            )
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { OnboardingViewModel(LauncherCompat(context)) }
        }
    }
}
