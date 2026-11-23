# ski-logger

Android-App, die beim Skifahren Beschleunigung, Gyroskop, Kompass und GPS aufzeichnet,
damit Stürze, g-Kräfte und Geschwindigkeit später genau ausgewertet werden können.
Gedacht für die Kombination mit einer Garmin-Uhr: Die Uhr liefert GPS und Barometer-Höhe,
das Handy die schnellen Bewegungsdaten.

## Installation

1. Unter **Releases** die neueste `ski-logger.apk` auf dem Handy herunterladen.
2. Öffnen und die Installation aus unbekannter Quelle erlauben.
3. Beim ersten Start Standort (genau) und Benachrichtigungen erlauben.
4. **Akku / Autostart** öffnen und alles freigeben (wichtig bei Xiaomi/HyperOS).

Jeder Push auf `main` baut automatisch eine neue Version. Neue Versionen lassen sich über die
alte installieren, die Aufnahmen bleiben erhalten.

## Benutzung

- **Aufnahme starten**, Handy in die Innentasche (eng am Körper). Der Bildschirm darf aus sein.
- **Markierung** (in der App oder in der Benachrichtigung) setzt einen Zeitstempel, z. B. nach einem Sturz.
- Parallel auf der Uhr die normale Ski-Aktivität starten.
- Danach **Teilen** → ZIP-Datei mit allen Daten.

## Datenformat

Pro Aufnahme ein Ordner `JJJJ-MM-TT_hh-mm-ss/`:

| Datei | Inhalt |
|---|---|
| `imu_000.csv.gz`, `imu_001.csv.gz` … | `sensor,t_ns,x,y,z` – `A` Beschleunigung (m/s², inkl. Erdbeschleunigung), `G` Gyroskop (rad/s), `M` Kompass (µT). 200 Hz bzw. 50 Hz, alle 5 Minuten eine neue Datei |
| `gps_000.csv.gz` … | `t_ns,utc_ms,lat,lon,alt_m,speed_ms,bearing_deg,acc_m,vacc_m,speed_acc_ms`, 1 Hz |
| `events.csv` | Start, Stopp, Markierungen, GPS an/aus |
| `meta.json` | Gerät, Sensor-Messbereiche, Startzeit, Statistik |

`t_ns` ist `SystemClock.elapsedRealtimeNanos`. Umrechnung in UTC:
`utc_ms = start_utc_ms + (t_ns - start_elapsed_ns) / 1e6` (Werte aus `meta.json`).
Damit lassen sich die Daten mit der FIT-Datei der Uhr zusammenführen.

Datenmenge: etwa 20–30 MB pro Stunde.
