# StatePilot

Execution-aware planning & tracking for **Android phone + Wear OS**.
Plan je dag, voer uit, en zie waar de planning van de werkelijkheid afwijkt —
op je pols, met suggesties en een volledige audit-trail.

> Nederlands implementatieplan: `StatePilot-app-prompt.md` (fasen 0–19).
> Voortgangsnotities: `STATEPILOT-NOTES.md`.

## Status: MVP-logica compleet ✅

| Fase | Onderdeel | Status |
|---|---|---|
| 0–2 | Projectstructuur, repositories, StateEngine | ✅ |
| 3–5 | Clock, calendar-import, ScheduleEngine | ✅ |
| 6 | Phone execution UI (presenter, Compose-scherm) | ✅ (E2E bewezen op emulator) |
| 7 | Notifications + acties → commands | ✅ |
| 8–10 | Wear-protocol, transport, watch UI | ✅ (3 instrumented tests groen) |
| 11 | Echte Data Layer (MessageClient) | ⏸ vereist gekoppelde phone+watch |
| 12 | Wear state-caching | ✅ (DataStore-adapter compileert) |
| 13 | Wear Tile | ✅ (mapper JVM-getest, service compileert; instrumented later) |
| 14 | Interruption UX (context-behoud) | ✅ |
| 15 | Planned vs actual analytics | ✅ |
| 16 | Places & geofencing | ✅ (logica + FakeLocationProvider) |
| 17 | TransitionEngine met confidence | ✅ |
| 18 | Automatic transitions + audit trail | ✅ |
| 19 | Planner-feedback-contract | ✅ (versioned JSON) |

**Tests:** 129 JVM-unit-tests (incl. Room/Robolectric) + 3 instrumented wear-tests, 0 failures.

## Modules

| Module | Type | Inhoud |
|---|---|---|
| `domain` | pure JVM | StateEngine, ScheduleEngine, DayAnalyzer, NotificationScheduler, GeofenceEngine, TransitionEngine, contracts |
| `data` | pure JVM | In-memory repositories (invarianten-afdwingend) |
| `calendar` | pure JVM | Idempotente kalenderimport/merge |
| `wear-protocol` | pure JVM | DTO's, revision-merger, transport, cache, tile-mapper |
| `persistence` | Android (Room) | SQLite-implementaties van alle repository-interfaces (Robolectric-getest) |
| `app-phone` | Android | Compose-execution-UI, presenter, notifications, tile/action-receiver |
| `app-wear` | Android | WearMainActivity, TileService, DataStore-cache-adapter |

## Bouwen & testen

```bash
export ANDROID_HOME=$HOME/android-sdk
./gradlew test assembleDebug      # 120 unit tests + APK's/AAR's
./gradlew :app-wear:assembleDebug # watch-app met Tile
```

Instrumented wear-tests (emulator nodig):

```bash
adb install -r -g app-wear/build/outputs/apk/debug/app-wear-debug.apk
adb install -r -g app-wear/build/outputs/apk/androidTest/debug/app-wear-debug-androidTest.apk
adb shell am instrument -w com.example.execution.wear.test/androidx.test.runner.AndroidJUnitRunner
```

> Let op de emulator-ervaring: software-emulatie (geen KVM op deze VM) heeft
> ~2–4 GB RAM per AVD + ~1–1,5 GB host-overhead. Draai niet tegelijk met een
> Gradle-build. Gebruik `-gpu off -cores 2` voor stabiliteit.

## Architectuurprincipes

- **Domain is pure Kotlin** — geen Android, geen wall-clock, geen GPS. Alles via interfaces (`Clock`, `LocationProvider`, `NotificationGateway`, `WearTransport`).
- **Idempotentie** — elk extern command via `requestId`; replay geeft `IdempotentReplay` zonder neveneffect.
- **Invarianten** — maximaal 1 actieve ActualState; gesloten states immutabel; kalendersync vernietigt nooit execution-history.
- **Uitlegbaarheid** — elke automatische/suggested transition heeft een evidence-trail (§17) en elke automatische executie een audit-record (§18).

## Artifacts

- APK's: `artifacts/apk/` (phone, wear, wear-test)
- Releases: https://github.com/jajadvert/statepilot/releases
- Screenshots E2E-demo: `docs/screenshots/`
- MVP acceptance-checklist (§27-scenario): `docs/MVP-ACCEPTANCE.md`
- Device-test-playbook: `PLAYBOOK.md`
- Testresultaten: `artifacts/test-results/`
