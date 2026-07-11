package be.mv3d.tablet

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("mv3d")

/** Persistente instellingen: koppelcode, server-URL en de gekozen doelmap (SAF tree-URI). */
class Prefs(private val ctx: Context) {
    private val CODE = stringPreferencesKey("connection_code")
    private val SERVER = stringPreferencesKey("server_url")
    private val TREE = stringPreferencesKey("tree_uri")

    val codeFlow = ctx.dataStore.data.map { it[CODE] ?: "" }
    val serverFlow = ctx.dataStore.data.map { it[SERVER] ?: "https://mv3d.be" }
    val treeFlow = ctx.dataStore.data.map { it[TREE] ?: "" }

    suspend fun code() = ctx.dataStore.data.first()[CODE] ?: ""
    suspend fun server() = ctx.dataStore.data.first()[SERVER] ?: "https://mv3d.be"
    suspend fun tree() = ctx.dataStore.data.first()[TREE] ?: ""

    suspend fun setCode(v: String) = ctx.dataStore.edit { it[CODE] = v.trim() }
    suspend fun setServer(v: String) = ctx.dataStore.edit { it[SERVER] = v.trim().trimEnd('/') }
    suspend fun setTree(v: String) = ctx.dataStore.edit { it[TREE] = v }
}
