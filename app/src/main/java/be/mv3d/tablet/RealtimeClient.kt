package be.mv3d.tablet

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live schermdeling via Supabase Realtime (Phoenix-protocol) over één websocket:
 *  - frames worden gepusht (broadcast 'frame') → veel sneller dan HTTP-polling,
 *  - input komt binnen (broadcast 'input') → meteen uitvoeren.
 * De tablet gebruikt Android-DNS (OkHttp), dus supabase.co resolvet gewoon (i.t.t.
 * de cloudflared Go-binary die dat niet kon).
 */
class RealtimeClient(
    private val supabaseUrl: String,
    private val anonKey: String,
    channel: String,                         // bv. "screen-L39YZ3K4"
    private val onInput: (JSONObject) -> Unit,
    private val onStatus: ((String) -> Unit)? = null,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private val ref = AtomicInteger(1)
    private val phxTopic = "realtime:$channel"
    private var joinRef = "1"
    @Volatile var joined = false
        private set

    fun connect() {
        val host = supabaseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
        val url = "wss://$host/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                joinRef = ref.getAndIncrement().toString()
                // exact zoals supabase-js: join_ref + access_token + postgres_changes meesturen.
                val join = JSONObject()
                    .put("topic", phxTopic).put("event", "phx_join")
                    .put("payload", JSONObject()
                        .put("config", JSONObject()
                            .put("broadcast", JSONObject().put("self", false).put("ack", false))
                            .put("presence", JSONObject().put("key", ""))
                            .put("postgres_changes", org.json.JSONArray())
                            .put("private", false))
                        .put("access_token", anonKey))
                    .put("ref", joinRef).put("join_ref", joinRef)
                webSocket.send(join.toString())
                onStatus?.invoke("ws-open, join gestuurd")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val o = JSONObject(text)
                    when (o.optString("event")) {
                        "phx_reply" -> {
                            val pl = o.optJSONObject("payload")
                            if (!joined) {
                                if (pl?.optString("status") == "ok") { joined = true; onStatus?.invoke("joined ✓") }
                                else onStatus?.invoke("join-fout: " + (pl?.toString()?.take(140) ?: text.take(140)))
                            }
                        }
                        "broadcast" -> {
                            val p = o.optJSONObject("payload") ?: return
                            if (p.optString("event") == "input") p.optJSONObject("payload")?.let(onInput)
                        }
                        "phx_error" -> onStatus?.invoke("phx_error: " + text.take(140))
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                joined = false; onStatus?.invoke("ws-fout: ${t.javaClass.simpleName}: ${t.message}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                joined = false; onStatus?.invoke("ws dicht: $code $reason")
            }
        })
    }

    /** één JPEG-frame pushen (base64). */
    fun sendFrame(b64: String): Boolean {
        val sock = ws ?: return false
        if (!joined) return false
        val msg = JSONObject()
            .put("topic", phxTopic).put("event", "broadcast")
            .put("payload", JSONObject().put("type", "broadcast").put("event", "frame")
                .put("payload", JSONObject().put("jpg", b64)))
            .put("ref", ref.getAndIncrement().toString()).put("join_ref", joinRef)
        return sock.send(msg.toString())
    }

    fun heartbeat() {
        ws?.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat")
            .put("payload", JSONObject()).put("ref", ref.getAndIncrement().toString()).toString())
    }

    fun close() {
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        joined = false
    }
}
