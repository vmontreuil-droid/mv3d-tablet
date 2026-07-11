# Fase 2 — Scherm overnemen (VNC + tunnel)

De backend is klaar: de tablet meldt een tunnel-URL via `POST /api/machines/tunnel`, en de
admin bekijkt/bedient via de bestaande noVNC-viewer (`remote-tablet-viewer.tsx`).

## Componenten op de tablet
1. **droidVNC-NG** (open source, F-Droid/GitHub) — VNC-server:
   - scherm via **MediaProjection** (operator geeft eenmalig toestemming),
   - input-injectie via **Accessibility-service** (zet aan in Instellingen → Toegankelijkheid).
   - Draait een VNC-server op bv. poort `5900`.
2. **cloudflared** (ARM64-binary) — maakt een publieke HTTPS-tunnel:
   - `cloudflared tunnel --url tcp://localhost:5900` (of via websockify → ws).
   - noVNC in de browser praat over websocket; gebruik **websockify** (poort 5901 → 5900)
     of cloudflared's `--url http://localhost:6080` als je noVNC lokaal draait.
3. Onze app leest de publieke URL en doet `Api.tunnel(url)`; bij afsluiten `Api.tunnel(null)`.

## Status: orchestratie zit al in de app (`RemoteService.kt`)
De knop **"Scherm delen"** start `RemoteService`, die:
1. droidVNC-NG start via zijn intent-API (`ACTION_START`, poort 5900),
2. de bijgeleverde `cloudflared`-binary (in `app/src/main/assets/cloudflared`) start met
   `--url http://localhost:5900`, de `*.trycloudflare.com`-URL uit de output plukt,
3. die URL post naar `POST /api/machines/tunnel`; bij stoppen → `tunnel(null)` + droidVNC-NG stop.

### noVNC-brug zit nu ingebouwd (`NoVncBridge.kt`)
De MV3D-viewer laadt `${tunnel}/vnc.html?...&path=websockify`, dus de tablet moet noVNC + een
websocket→TCP-brug serveren. In plaats van Python-websockify draait dit nu **ingebed in Kotlin**
(NanoWSD) op `WEB_PORT` (6080): het serveert `assets/novnc/*` en brugt `/websockify` naar
droidVNC-NG (5900). cloudflared tunnelt `http://localhost:6080` → publieke URL.

Twee bestanden moet je zelf toevoegen (kunnen hier niet opgehaald worden):
- `app/src/main/assets/cloudflared` — cloudflared arm64-binary.
- `app/src/main/assets/novnc/…` — inhoud van een noVNC-release (met `vnc.html`).

### Nog te verifiëren op toestel (kon hier niet gecompileerd/getest worden)
- **droidVNC-NG intent-namen** (`ACTION_START`/`EXTRA_PORT`/`EXTRA_PASSWORD`): controleer tegen de
  versie die je installeert; pas de constanten in `RemoteService` aan indien nodig.
- **Loopback-verbinding**: droidVNC-NG moet op `127.0.0.1:5900` bereikbaar zijn voor de brug.
- **Per-sessie VNC-wachtwoord**: nu leeg; laat MV3D er één meegeven en vul `EXTRA_PASSWORD`.
- **cloudflared op Android**: sommige toestellen beperken `ProcessBuilder` op assets-binaries;
  als dat blokkeert, plaats de binary via `jniLibs/arm64-v8a/libcloudflared.so` (wordt wél
  uitvoerbaar geïnstalleerd) en pas het pad aan.

- **Netter (later)**: droidVNC-NG-broncode als module integreren (GPL — licentie!) zodat de klant
  maar één app installeert.

## Aandachtspunten
- **Accessibility** vereist Play Store-motivering; zij-laden (APK) omzeilt dit.
- **Doze/achtergrond**: gebruik een foreground service + battery-optimalisatie uitsluiten.
- **Beveiliging**: tunnel-URL is een geheime, tijdelijke URL; alleen admin ziet ze. Overweeg
  een VNC-wachtwoord dat MV3D per sessie meegeeft.

## RustDesk-alternatief
Wil je één kant-en-klare oplossing i.p.v. droidVNC-NG + cloudflared: **RustDesk** (self-hostable)
doet scherm + controle op Android out-of-the-box. Je kan MV3D dan enkel het RustDesk-ID/paswoord
laten tonen i.p.v. een tunnel-iframe. Minder naadloos, maar veel minder eigen code.
