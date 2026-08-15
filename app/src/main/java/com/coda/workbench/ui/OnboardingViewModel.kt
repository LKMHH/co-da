package com.coda.workbench.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coda.workbench.platform.AppPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 首次使用引导：仅在首启显示一次（DataStore 标记）。 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val prefs: AppPreferencesStore,
) : ViewModel() {
    private val _show = MutableStateFlow(false)
    val show: StateFlow<Boolean> = _show.asStateFlow()

    init {
        viewModelScope.launch { _show.value = !prefs.onboardingShownNow() }
    }

    fun dismiss() {
        _show.value = false
        viewModelScope.launch { prefs.markOnboardingShown() }
    }

    /** 设置页重看入口：直接再显示一次（不改首启标记）。 */
    fun showAgain() {
        _show.value = true
    }
}
