package com.example.scretui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

val Context.dataStore by preferencesDataStore(name = "todo_prefs")

class ToDoRepository(private val context: Context) {

    private val TODO_KEY = stringPreferencesKey("todo_list")

    val toDoListFlow: Flow<MutableList<String>> = context.dataStore.data.map { prefs ->
        val json = prefs[TODO_KEY] ?: return@map mutableListOf()
        Json.decodeFromString(json)
    }

    suspend fun zapisz(lista: List<String>) {
        val json = Json.encodeToString(lista)
        context.dataStore.edit { prefs ->
            prefs[TODO_KEY] = json
        }
    }
}