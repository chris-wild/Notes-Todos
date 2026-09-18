package uk.co.promptbuilt.notestodos.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

enum class ViewMode(val key: String) {
    Grid("grid"),
    List("list");

    companion object {
        fun fromKey(key: String?): ViewMode = entries.firstOrNull { it.key == key } ?: Grid
    }
}

// Replaces the web app's localStorage keys 'noteSort' and 'viewMode'.
class SettingsRepository(private val context: Context) {

    private val noteSortKey = stringPreferencesKey("noteSort")
    private val viewModeKey = stringPreferencesKey("viewMode")

    val noteSort: Flow<NoteSort> =
        context.settingsDataStore.data.map { NoteSort.fromKey(it[noteSortKey]) }

    val viewMode: Flow<ViewMode> =
        context.settingsDataStore.data.map { ViewMode.fromKey(it[viewModeKey]) }

    suspend fun setNoteSort(sort: NoteSort) {
        context.settingsDataStore.edit { it[noteSortKey] = sort.key }
    }

    suspend fun setViewMode(mode: ViewMode) {
        context.settingsDataStore.edit { it[viewModeKey] = mode.key }
    }
}
