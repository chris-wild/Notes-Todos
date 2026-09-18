package uk.co.promptbuilt.notestodos.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keystore-backed storage for the Anthropic API key. The prefs file name is
 * excluded from Auto Backup and device transfer (see res/xml backup rules), so
 * the key never leaves this device.
 */
class SecureKeys(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _hasAnthropicKey = MutableStateFlow(prefs.contains(ANTHROPIC_KEY))

    /** Drives the ingredient-automation feature gate (replaces GET /api/features). */
    val hasAnthropicKey: StateFlow<Boolean> = _hasAnthropicKey

    fun getAnthropicKey(): String? = prefs.getString(ANTHROPIC_KEY, null)

    fun setAnthropicKey(key: String) {
        prefs.edit().putString(ANTHROPIC_KEY, key).apply()
        _hasAnthropicKey.value = true
    }

    fun deleteAnthropicKey() {
        prefs.edit().remove(ANTHROPIC_KEY).apply()
        _hasAnthropicKey.value = false
    }

    private companion object {
        const val ANTHROPIC_KEY = "anthropic_api_key"
    }
}
