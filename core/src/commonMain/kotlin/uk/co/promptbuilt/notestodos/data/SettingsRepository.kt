package uk.co.promptbuilt.notestodos.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ViewMode(val key: String) {
    Grid("grid"),
    List("list");

    companion object {
        fun fromKey(key: String?): ViewMode = entries.firstOrNull { it.key == key } ?: Grid
    }
}

/**
 * App settings on Preferences DataStore. The store is built per platform
 * (see createSettingsDataStore) — on Android at the exact path the pre-KMP
 * app used, so existing settings survive.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private val noteSortKey = stringPreferencesKey("noteSort")
    private val viewModeKey = stringPreferencesKey("viewMode")

    val noteSort: Flow<NoteSort> =
        dataStore.data.map { NoteSort.fromKey(it[noteSortKey]) }

    val viewMode: Flow<ViewMode> =
        dataStore.data.map { ViewMode.fromKey(it[viewModeKey]) }

    suspend fun setNoteSort(sort: NoteSort) {
        dataStore.edit { it[noteSortKey] = sort.key }
    }

    suspend fun setViewMode(mode: ViewMode) {
        dataStore.edit { it[viewModeKey] = mode.key }
    }
}
