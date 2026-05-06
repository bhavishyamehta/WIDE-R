package com.david.adbtest.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.david.adbtest.data.prefs.PreferencesManager
import com.david.adbtest.data.repository.AdbRepository
import com.david.adbtest.domain.model.AdbState
import com.david.adbtest.domain.model.PairingInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: AdbRepository,
    private val prefsManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<AdbState>(AdbState.Idle)
    val uiState: StateFlow<AdbState> = _uiState.asStateFlow()

    private val _showPairingDialog = MutableStateFlow(false)
    val showPairingDialog: StateFlow<Boolean> = _showPairingDialog.asStateFlow()

    fun startAutoConnect() {
        viewModelScope.launch {
            repository.checkAndConnect().collect { state ->
                _uiState.value = state
                if (state is AdbState.NeedsPairing) {
                    _showPairingDialog.value = true
                }
            }
        }
    }

    fun pairAndConnect(pairingInfo: PairingInfo) {
        viewModelScope.launch {
            _showPairingDialog.value = false
            repository.pairAndConnect(pairingInfo).collect { state ->
                _uiState.value = state
            }
        }
    }

    fun resetAndRepair() {
        prefsManager.clearPairing()
        _showPairingDialog.value = true
        _uiState.value = AdbState.NeedsPairing()
    }

    fun dismissPairingDialog() {
        _showPairingDialog.value = false
    }
}