package com.veilkeepers.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.veilkeepers.app.data.ApiClient
import com.veilkeepers.app.data.ApiError
import com.veilkeepers.app.data.AuthApi
import com.veilkeepers.app.data.DeviceApi
import com.veilkeepers.app.data.DeviceEntry
import com.veilkeepers.app.data.HttpAuthApi
import com.veilkeepers.app.data.HttpDeviceApi
import com.veilkeepers.app.data.SessionStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ChangePasswordState {
    object Idle : ChangePasswordState()
    object Submitting : ChangePasswordState()
    object Success : ChangePasswordState()
    data class Error(val message: String) : ChangePasswordState()
}

sealed class DevicesState {
    object Loading : DevicesState()
    data class Loaded(val devices: List<DeviceEntry>) : DevicesState()
    data class Error(val message: String) : DevicesState()
}

class AccountViewModel(
    private val storage: SessionStorage,
    private val repository: AuthRepository,
    private val vaultKey: ByteArray,
    private val deviceApi: DeviceApi,
) : ViewModel() {

    private val _changePasswordState = MutableStateFlow<ChangePasswordState>(ChangePasswordState.Idle)
    val changePasswordState: StateFlow<ChangePasswordState> = _changePasswordState.asStateFlow()

    private val _devicesState = MutableStateFlow<DevicesState>(DevicesState.Loading)
    val devicesState: StateFlow<DevicesState> = _devicesState.asStateFlow()

    private val _revokingDeviceId = MutableStateFlow<Long?>(null)
    val revokingDeviceId: StateFlow<Long?> = _revokingDeviceId.asStateFlow()

    fun changePassword(currentPassword: CharArray, newPassword: CharArray) {
        if (_changePasswordState.value is ChangePasswordState.Submitting) return
        if (currentPassword.isEmpty() || newPassword.isEmpty()) {
            _changePasswordState.value = ChangePasswordState.Error("Both passwords are required.")
            return
        }
        _changePasswordState.value = ChangePasswordState.Submitting
        viewModelScope.launch {
            try {
                repository.changePassword(currentPassword, newPassword, vaultKey)
                _changePasswordState.value = ChangePasswordState.Success
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _changePasswordState.value = ChangePasswordState.Error(errorUiMessage(error))
            }
        }
    }

    fun dismissChangePasswordResult() {
        _changePasswordState.value = ChangePasswordState.Idle
    }

    fun loadDevices() {
        _devicesState.value = DevicesState.Loading
        viewModelScope.launch {
            try {
                val token = storage.sessionToken
                val devices = deviceApi.listDevices(token)
                _devicesState.value = DevicesState.Loaded(devices)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _devicesState.value = DevicesState.Error(errorUiMessage(error))
            }
        }
    }

    fun revokeDevice(deviceId: Long) {
        if (_revokingDeviceId.value != null) return
        _revokingDeviceId.value = deviceId
        viewModelScope.launch {
            try {
                val token = storage.sessionToken
                deviceApi.revokeDevice(deviceId, token)
                val current = _devicesState.value
                if (current is DevicesState.Loaded) {
                    _devicesState.value = DevicesState.Loaded(
                        current.devices.filter { it.id != deviceId }
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                _devicesState.value = DevicesState.Error(errorUiMessage(error))
            } finally {
                _revokingDeviceId.value = null
            }
        }
    }

    companion object {
        fun factory(
            storage: SessionStorage,
            vaultKey: ByteArray,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val baseUrl = storage.serverUrl
                val client = ApiClient(baseUrl)
                val repository = AuthRepository(storage)
                val deviceApi = HttpDeviceApi(client)
                return AccountViewModel(storage, repository, vaultKey, deviceApi) as T
            }
        }
    }
}
