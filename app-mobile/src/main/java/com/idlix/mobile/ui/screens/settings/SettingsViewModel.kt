package com.idlix.mobile.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.idlix.data.api.ApiClient
import com.idlix.data.repository.SubdlRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val activeBackendUrl: String = "",
    val activeSubdlKey: String = "",
    val backendUrl: String = "",
    val subdlKey: String = "",
    val isTestingBackend: Boolean = false,
    val backendTestResult: String? = null,
    val isTestingSubdl: Boolean = false,
    val subdlTestResult: String? = null,
    val cacheCleared: Boolean = false
)

class SettingsViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            activeBackendUrl = ApiClient.getBaseUrl(),
            activeSubdlKey = SubdlRepository.getApiKey(),
            backendUrl = ApiClient.getBaseUrl(),
            subdlKey = SubdlRepository.getApiKey()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun updateBackendUrl(url: String) {
        _uiState.value = _uiState.value.copy(backendUrl = url, backendTestResult = null)
    }

    fun updateSubdlKey(key: String) {
        _uiState.value = _uiState.value.copy(subdlKey = key, subdlTestResult = null)
    }

    fun resetBackendUrl() {
        val defaultUrl = ApiClient.getDefaultUrl()
        _uiState.value = _uiState.value.copy(backendUrl = defaultUrl, backendTestResult = null)
    }

    fun resetSubdlKey() {
        val defaultKey = SubdlRepository.DEFAULT_API_KEY
        _uiState.value = _uiState.value.copy(subdlKey = defaultKey, subdlTestResult = null)
    }

    fun saveBackendUrl() {
        val targetUrl = _uiState.value.backendUrl.trim()
        if (targetUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(backendTestResult = "Failed: Server URL cannot be empty")
            return
        }
        ApiClient.setBaseUrl(targetUrl)
        val newActive = ApiClient.getBaseUrl()
        _uiState.value = _uiState.value.copy(
            activeBackendUrl = newActive,
            backendUrl = newActive,
            backendTestResult = "Server URL saved successfully!"
        )
    }

    fun saveSubdlKey() {
        val targetKey = _uiState.value.subdlKey.trim()
        SubdlRepository.setApiKey(targetKey)
        val newActive = SubdlRepository.getApiKey()
        _uiState.value = _uiState.value.copy(
            activeSubdlKey = newActive,
            subdlKey = newActive,
            subdlTestResult = "SubDL API key saved successfully!"
        )
    }

    fun testBackend() {
        val targetUrl = _uiState.value.backendUrl.trim()
        if (targetUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(
                isTestingBackend = false,
                backendTestResult = "Failed: Server URL cannot be empty"
            )
            return
        }
        if (!targetUrl.startsWith("http://", ignoreCase = true) && !targetUrl.startsWith("https://", ignoreCase = true)) {
            _uiState.value = _uiState.value.copy(
                isTestingBackend = false,
                backendTestResult = "Failed: URL must start with http:// or https://"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTestingBackend = true, backendTestResult = null)
            val result = ApiClient.testBackendConnection(targetUrl)
            _uiState.value = _uiState.value.copy(
                isTestingBackend = false,
                backendTestResult = result.fold(
                    onSuccess = { it },
                    onFailure = { "Failed: ${it.localizedMessage ?: "Unknown network error"}" }
                )
            )
        }
    }

    fun testSubdl() {
        val targetKey = _uiState.value.subdlKey.trim()
        if (targetKey.isBlank()) {
            _uiState.value = _uiState.value.copy(
                isTestingSubdl = false,
                subdlTestResult = "Failed: SubDL API key cannot be empty"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTestingSubdl = true, subdlTestResult = null)
            val result = SubdlRepository.testApiKey(targetKey)
            _uiState.value = _uiState.value.copy(
                isTestingSubdl = false,
                subdlTestResult = result.fold(
                    onSuccess = { it },
                    onFailure = { "Failed: ${it.localizedMessage ?: "Unknown network error"}" }
                )
            )
        }
    }

    fun clearImageCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            try {
                val cacheDir = context.cacheDir.resolve("image_cache")
                if (cacheDir.exists()) {
                    cacheDir.deleteRecursively()
                }
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(cacheCleared = true)
                }
            } catch (_: Exception) {}
        }
    }
}
