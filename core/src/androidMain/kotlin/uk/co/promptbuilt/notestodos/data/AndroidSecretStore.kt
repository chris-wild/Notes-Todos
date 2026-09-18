package uk.co.promptbuilt.notestodos.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed store. Same "secure_prefs" file and schemes as the pre-KMP
 * SecureKeys, so an already-stored key keeps decrypting. The file is excluded
 * from Auto Backup and device transfer (see app res/xml backup rules).
 * The library is deprecated upstream but deliberately kept: replacing it would
 * orphan existing stored keys.
 */
class AndroidSecretStore(context: Context) : SecretStore {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun contains(key: String): Boolean = prefs.contains(key)
}
