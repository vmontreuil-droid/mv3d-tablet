package be.mv3d.tablet

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("mv3d")

/**
 * Wat de app onthoudt.
 *
 * De koppeling — code, server, map — en daarnaast wat er over het bijwerken te onthouden valt:
 * wanneer we voor het laatst gekeken hebben, en welke versie er klaarstaat. Dat laatste hoort
 * hier en niet in het geheugen van de dienst: een tablet die 's nachts herstart, is anders
 * vergeten dat de nieuwe versie al binnen is en haalt hem de volgende dag opnieuw over 4G.
 *
 * Er stonden er ooit negen: een sessietoken, een verversingstoken, een mailadres, een taal, een
 * bronmap voor de omzetter. Die hoorden bij het portaal-in-de-app en bij de omzetter, en die
 * zijn er allebei uit. Wat een app onthoudt over jou, hoort te passen bij wat ze doet.
 */
class Prefs(private val ctx: Context) {
    private val CODE = stringPreferencesKey("connection_code")
    private val SERVER = stringPreferencesKey("server_url")
    private val TREE = stringPreferencesKey("tree_uri")
    private val GEKEKEN = longPreferencesKey("update_gekeken")
    private val KLAAR = intPreferencesKey("update_klaar")
    private val KLAARNAAM = stringPreferencesKey("update_klaar_naam")

    val codeFlow = ctx.dataStore.data.map { it[CODE] ?: "" }
    val serverFlow = ctx.dataStore.data.map { it[SERVER] ?: "https://mv3d.be" }
    val treeFlow = ctx.dataStore.data.map { it[TREE] ?: "" }

    suspend fun code() = ctx.dataStore.data.first()[CODE] ?: ""
    suspend fun server() = ctx.dataStore.data.first()[SERVER] ?: "https://mv3d.be"
    suspend fun tree() = ctx.dataStore.data.first()[TREE] ?: ""

    suspend fun setCode(v: String) = ctx.dataStore.edit { it[CODE] = v.trim() }
    suspend fun setServer(v: String) = ctx.dataStore.edit { it[SERVER] = v.trim().trimEnd('/') }
    suspend fun setTree(v: String) = ctx.dataStore.edit { it[TREE] = v }

    // ── het bijwerken ──
    suspend fun gekeken() = ctx.dataStore.data.first()[GEKEKEN] ?: 0L
    suspend fun setGekeken(v: Long) = ctx.dataStore.edit { it[GEKEKEN] = v }
    suspend fun klaar() = ctx.dataStore.data.first()[KLAAR] ?: 0
    suspend fun klaarNaam() = ctx.dataStore.data.first()[KLAARNAAM] ?: ""
    suspend fun setKlaar(code: Int, naam: String) = ctx.dataStore.edit { it[KLAAR] = code; it[KLAARNAAM] = naam }
    suspend fun wisKlaar() = ctx.dataStore.edit { it.remove(KLAAR); it.remove(KLAARNAAM) }

    /** Ontkoppelen: de code en de map vergeten. Het adres van de server blijft staan. */
    suspend fun wis() = ctx.dataStore.edit { it.remove(CODE); it.remove(TREE) }
}
