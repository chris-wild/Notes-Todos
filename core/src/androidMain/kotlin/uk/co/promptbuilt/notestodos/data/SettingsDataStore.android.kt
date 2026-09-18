package uk.co.promptbuilt.notestodos.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * MUST resolve to files/datastore/settings.preferences_pb — exactly where the
 * pre-KMP `preferencesDataStore(name = "settings")` delegate wrote — so the
 * stored noteSort/viewMode survive the refactor.
 */
fun createSettingsDataStore(context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        produceFile = {
            File(context.filesDir, "datastore/settings.preferences_pb").toOkioPath()
        },
    )
