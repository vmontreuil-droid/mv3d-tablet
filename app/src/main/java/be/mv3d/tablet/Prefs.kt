package be.mv3d.tablet

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("mv3d")

/**
 * Wat de app onthoudt. Drie dingen, en geen vierde.
 *
 * Er stonden er negen: een sessietoken, een verversingstoken, een mailadres, een taal, een
 * bronmap voor de omzetter. Die hoorden bij het portaal-in-de-app en bij de omzetter, en die
 * zijn er allebei uit. Wat een app onthoudt over jou, hoort te passen bij wat ze doet.
 */
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

    /** Ontkoppelen: de code en de map vergeten. Het adres van de server blijft staan. */
    suspend fun wis() = ctx.dataStore.edit { it.remove(CODE); it.remove(TREE) }
}
