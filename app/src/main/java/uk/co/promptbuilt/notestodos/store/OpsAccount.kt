package uk.co.promptbuilt.notestodos.store

import android.content.Context
import java.util.UUID

/**
 * The anonymous account that owns this install's conversion credits: a random UUID, minted once,
 * used as the Worker bearer token and as Play Billing's obfuscated account id, so a purchase can
 * only credit the account that made it. The Android counterpart of iOS's iCloud-Keychain OpsAccount.
 *
 * It lives in a plain SharedPreferences file, which Android's own backup and phone-to-phone
 * transfer include (res/xml/data_extraction_rules.xml excludes only secure_prefs and recipe
 * PDFs), so the balance survives a reinstall or a new phone. It must never move into
 * secure_prefs: that file is excluded from backup because its key is bound to this install.
 */
class OpsAccount(context: Context) {

    private val prefs = context.getSharedPreferences("ops_account", Context.MODE_PRIVATE)

    @Synchronized
    fun token(): String {
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        val minted = UUID.randomUUID().toString().lowercase()
        prefs.edit().putString(KEY_TOKEN, minted).commit()
        return minted
    }

    private companion object {
        const val KEY_TOKEN = "token"
    }
}
