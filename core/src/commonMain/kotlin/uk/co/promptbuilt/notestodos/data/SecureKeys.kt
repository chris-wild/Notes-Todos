package uk.co.promptbuilt.notestodos.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-secure string storage. Android: EncryptedSharedPreferences
 * (core androidMain). iOS: Keychain, implemented in Swift and injected.
 */
interface SecretStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun delete(key: String)
    fun contains(key: String): Boolean
}

/**
 * The Anthropic API key, held in the platform's secure store. The key never
 * enters the database or backups; hasAnthropicKey drives the
 * ingredient-automation feature gate (replaces the old GET /api/features).
 */
class SecureKeys(private val store: SecretStore) {

    private val _hasAnthropicKey = MutableStateFlow(store.contains(ANTHROPIC_KEY))
    val hasAnthropicKey: StateFlow<Boolean> = _hasAnthropicKey

    fun getAnthropicKey(): String? = store.get(ANTHROPIC_KEY)

    fun setAnthropicKey(key: String) {
        store.set(ANTHROPIC_KEY, key)
        _hasAnthropicKey.value = true
    }

    fun deleteAnthropicKey() {
        store.delete(ANTHROPIC_KEY)
        _hasAnthropicKey.value = false
    }

    private companion object {
        const val ANTHROPIC_KEY = "anthropic_api_key"
    }
}
