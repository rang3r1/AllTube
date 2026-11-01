# AllTube

Werbefreier YouTube-Client für Android-Handys und -Tablets, für den privaten Gebrauch.

AllTube basiert auf [SmarterTube](https://github.com/CodeSculptor/SmarterTube) von CodeSculptor (Handy-Oberfläche), das wiederum auf [SmartTube](https://github.com/yuliskov/SmartTube) von yuliskov aufbaut. SmartTube liefert den YouTube-Teil: Wiedergabe ohne Werbung, SponsorBlock, Return YouTube Dislike, DeArrow, Hintergrundwiedergabe.

## Installieren und aktualisieren

1. Unter [Releases](https://github.com/rang3r1/AllTube/releases) die neueste APK laden. Für fast alle Handys ist das `AllTube-…-arm64-v8a.apk`, im Zweifel `…-universal.apk`.
2. Die APK öffnen und die Installation erlauben.
3. Updates meldet die App selbst (Info → „Check for updates“), oder du nutzt [Obtainium](https://github.com/ImranR98/Obtainium) mit diesem Repo.

AllTube hat einen eigenen Paketnamen (`de.rang3r1.alltube`) und läuft neben SmartTube oder SmarterTube.

## Was AllTube zusätzlich kann

- **Auf dem Fernseher abspielen** wie in der YouTube-App: Cast-Knopf im Player, danach steuert der Player den Fernseher (Pause, Zeitleiste, Doppeltippen, Lautstärketasten). „Stop casting“ spielt am Handy an derselben Stelle weiter.
  - Google TV und Chromecast im WLAN werden gefunden. Läuft dort YouTube nicht, startet AllTube es selbst.
  - SmartTube auf dem Fernseher: dort Einstellungen → Fernsteuerung → Gerät hinzufügen, den Code am Handy unter „Link with TV code…“ eingeben.
- Keine Statistik und keine Absturzberichte an Dritte.

## So bleibt AllTube aktuell

Alles läuft über GitHub Actions in diesem Repo:

| Workflow | Wann | Was |
|---|---|---|
| `upstream-sync` | alle 6 Stunden | Prüft, ob SmartTube eine neue **Stable**-Version veröffentlicht hat (Tags wie `32.68s`). Wenn ja: Merge, Prüfung, dann `master` und ein neues Release. Bei Konflikt oder Fehler entsteht ein Issue, `master` bleibt unverändert. |
| `stmobile-validate` | bei jedem Push und PR | Prüft die Stellen, an denen AllTube in SmartTube-Code eingreift, baut die APK und führt die Unit-Tests aus. |
| `release` | nach einem erfolgreichen Sync oder von Hand (Actions → Release → Run workflow) | Baut und signiert die APKs und veröffentlicht sie als Release `v1.<n>.0+st<SmartTube-Version>`. |

Ein Issue „SmartTube … : merge conflict“ oder „… validation failed“ heißt: Hier muss ein Mensch ran. Nach dem Beheben das Issue schließen, dann versucht es der nächste Lauf erneut.

Neues von CodeSculptor kommt **nicht** automatisch. Bei Bedarf von Hand: `git fetch codesculptor && git merge codesculptor/master`.

## Signierschlüssel

Die Releases werden mit einem eigenen Schlüssel signiert. Er liegt als Secret `ALLTUBE_*` in den Repo-Einstellungen und als Backup außerhalb des Repos. **Geht der Schlüssel verloren, lassen sich Updates nicht mehr über installierte Versionen installieren**, dann hilft nur Deinstallieren und neu Installieren.

## Selbst bauen

```sh
./gradlew :smarttubetv:assembleStmobileDebug
```

Die APKs liegen danach unter `smarttubetv/build/outputs/apk/stmobile/debug/`. Der Handy-Code liegt in `smarttubetv/src/stmobile/`, die übrigen Module sind SmartTube.

## Lizenz

MIT, wie SmartTube und SmarterTube, siehe [LICENSE](LICENSE). Copyright (c) 2020-present yuliskov; Handy-Oberfläche von CodeSculptor (SmarterTube); Anpassungen für AllTube von rang3r1.
