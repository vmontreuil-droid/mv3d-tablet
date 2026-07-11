# MV3D Machine — Android tablet-app

Companion-app voor de machine-tablet. De klant installeert ze eenmalig, koppelt met een
**connection_code** (QR of handmatig), en daarna:

- **Fase 1 — Bestandssync** (werkt tegen de bestaande MV3D-backend):
  - pollt `POST https://mv3d.be/api/machines/sync` met de connection_code,
  - downloadt machinebesturingsbestanden naar de gekozen map (SAF) van de besturingssoftware,
  - voert commando's uit (delete/move/pull/push), stuurt heartbeat + GPS + mappenlijst,
  - bevestigt via `PATCH .../sync`.
- **Fase 2 — Scherm overnemen** (bediening op afstand):
  - start **droidVNC-NG** (scherm via MediaProjection, input via Accessibility),
  - opent een **cloudflared** tunnel naar de VNC/websockify-poort,
  - meldt de tunnel-URL via `POST .../api/machines/tunnel`,
  - admin bekijkt + bedient via de bestaande noVNC-viewer.

## Bouwen
1. Open de map in **Android Studio** (Giraffe+). Laat het Gradle-wrapper genereren
   (`gradle wrapper` of Android Studio doet dit) — de wrapper-jar zit niet in git.
2. `minSdk 26`, `targetSdk 34`. Test op een echte tablet (niet emulator voor GPS/SAF).
3. Signen + APK verdelen (zij-laden) of via Play Store.

## Play Store / Accessibility
Voor **input op afstand** gebruikt droidVNC-NG de Accessibility-service. Play Store vereist een
duidelijke motivering ("remote support voor GPS-machinebesturing, met toestemming van de
operator"). Zij-laden (APK) omzeilt dit; Play Store-distributie vereist het beleid-formulier.

## Architectuur
- `MainActivity` — Compose UI: pairing (code + server-URL), map kiezen (SAF), status, start/stop.
- `Prefs` — DataStore: connection_code, serverUrl, tree-URI.
- `Api` — OkHttp calls naar /sync (POST+PATCH) en /tunnel.
- `SyncService` — foreground service: poll-lus, downloads, commando's, heartbeat, GPS.
- (fase 2) `RemoteService` — droidVNC-NG + cloudflared beheren, tunnel-URL posten.

> Fase 1 is functioneel te bouwen en te testen tegen productie. Fase 2 (VNC/tunnel) heeft
> extra binaries (cloudflared ARM) en de droidVNC-NG-app nodig; zie `docs/PHASE2.md`.
