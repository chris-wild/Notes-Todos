@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package uk.co.promptbuilt.notestodos.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

fun createSettingsDataStore(): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        produceFile = {
            val base = NSSearchPathForDirectoriesInDomains(
                NSApplicationSupportDirectory, NSUserDomainMask, true,
            ).first() as String
            NSFileManager.defaultManager.createDirectoryAtPath(
                "$base/datastore", withIntermediateDirectories = true, attributes = null, error = null,
            )
            "$base/datastore/settings.preferences_pb".toPath()
        },
    )
