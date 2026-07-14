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
    private val UNI = stringPreferencesKey("uni_tree_uri")   // Unicontrol-doelmap (converter)
    private val SRC = stringPreferencesKey("src_tree_uri")   // bron-bladermap (converter)
    private val AUTH = stringPreferencesKey("auth_token")    // MV3D-account sessie
    private val AUTH_REFRESH = stringPreferencesKey("auth_refresh")  // Supabase refresh-token (WebView-sessie)
    private val AUTH_EMAIL = stringPreferencesKey("auth_email")
    private val LANG = stringPreferencesKey("lang")          // "" = automatisch (tablet-taal)

    val codeFlow = ctx.dataStore.data.map { it[CODE] ?: "" }
    val serverFlow = ctx.dataStore.data.map { it[SERVER] ?: "https://mv3d.be" }
    val treeFlow = ctx.dataStore.data.map { it[TREE] ?: "" }
    val uniFlow = ctx.dataStore.data.map { it[UNI] ?: "" }
    val srcFlow = ctx.dataStore.data.map { it[SRC] ?: "" }
    val authFlow = ctx.dataStore.data.map { it[AUTH] ?: "" }
    val authRefreshFlow = ctx.dataStore.data.map { it[AUTH_REFRESH] ?: "" }
    val authEmailFlow = ctx.dataStore.data.map { it[AUTH_EMAIL] ?: "" }
    val langFlow = ctx.dataStore.data.map { it[LANG] ?: "" }

    suspend fun code() = ctx.dataStore.data.first()[CODE] ?: ""
    suspend fun server() = ctx.dataStore.data.first()[SERVER] ?: "https://mv3d.be"
    suspend fun tree() = ctx.dataStore.data.first()[TREE] ?: ""
    suspend fun uni() = ctx.dataStore.data.first()[UNI] ?: ""

    suspend fun setCode(v: String) = ctx.dataStore.edit { it[CODE] = v.trim() }
    suspend fun setServer(v: String) = ctx.dataStore.edit { it[SERVER] = v.trim().trimEnd('/') }
    suspend fun setTree(v: String) = ctx.dataStore.edit { it[TREE] = v }
    suspend fun setUni(v: String) = ctx.dataStore.edit { it[UNI] = v }
    suspend fun setSrc(v: String) = ctx.dataStore.edit { it[SRC] = v }
    suspend fun setAuth(token: String, email: String, refresh: String = "") = ctx.dataStore.edit { it[AUTH] = token; it[AUTH_EMAIL] = email; it[AUTH_REFRESH] = refresh }
    suspend fun clearAuth() = ctx.dataStore.edit { it.remove(AUTH); it.remove(AUTH_EMAIL); it.remove(AUTH_REFRESH) }
    suspend fun setLang(v: String) = ctx.dataStore.edit { it[LANG] = v }
}
