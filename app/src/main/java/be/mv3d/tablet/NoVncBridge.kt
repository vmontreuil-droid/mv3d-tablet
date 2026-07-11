package be.mv3d.tablet

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Ingebedde noVNC-server + websocket→TCP-brug (vervangt Python-websockify op Android).
 *  - Serveert de noVNC-webbestanden uit `assets/novnc/` (bv. /vnc.html, /app, /core …).
 *  - `/websockify` (elke websocket-upgrade) brugt binaire frames naar droidVNC-NG (127.0.0.1:vncPort).
 * De MV3D-viewer laadt `${tunnel}/vnc.html?...&path=websockify`, dus deze poort komt via cloudflared
 * naar buiten (WEB_PORT).
 */
class NoVncBridge(private val ctx: Context, port: Int, private val vncPort: Int) : NanoWSD(port) {

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = VncSocket(handshake)

    /** Websocket-sessie die één noVNC-client koppelt aan één TCP-verbinding met de VNC-server. */
    private inner class VncSocket(hs: IHTTPSession) : WebSocket(hs) {
        private var sock: Socket? = null

        override fun onOpen() {
            try {
                val s = Socket("127.0.0.1", vncPort); sock = s
                // TCP → websocket (binair)
                thread(name = "vnc-rx") {
                    try {
                        val ins = s.getInputStream(); val buf = ByteArray(32 * 1024)
                        while (true) { val n = ins.read(buf); if (n < 0) break; send(buf.copyOf(n)) }
                    } catch (_: Exception) {
                    } finally { try { close(WebSocketFrame.CloseCode.NormalClosure, "vnc dicht", false) } catch (_: Exception) {} }
                }
            } catch (e: Exception) {
                try { close(WebSocketFrame.CloseCode.InternalServerError, e.message ?: "vnc-fout", false) } catch (_: Exception) {}
            }
        }

        // websocket → TCP
        override fun onMessage(message: WebSocketFrame) {
            try { sock?.getOutputStream()?.apply { write(message.binaryPayload); flush() } } catch (_: Exception) {}
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            try { sock?.close() } catch (_: Exception) {}
        }

        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: IOException?) { try { sock?.close() } catch (_: Exception) {} }
    }

    /** Statische noVNC-bestanden uit assets/novnc serveren (alle niet-websocket requests). */
    override fun serveHttp(session: IHTTPSession): Response {
        var path = session.uri.trimStart('/')
        if (path.isEmpty()) path = "vnc.html"
        return try {
            newChunkedResponse(Response.Status.OK, mimeOf(path), ctx.assets.open("novnc/$path"))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "niet gevonden: $path")
        }
    }

    private fun mimeOf(p: String) = when {
        p.endsWith(".html") -> "text/html"
        p.endsWith(".js") || p.endsWith(".mjs") -> "application/javascript"
        p.endsWith(".css") -> "text/css"
        p.endsWith(".json") -> "application/json"
        p.endsWith(".png") -> "image/png"
        p.endsWith(".svg") -> "image/svg+xml"
        p.endsWith(".woff2") -> "font/woff2"
        else -> "application/octet-stream"
    }

    fun startBridge() = start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
}
