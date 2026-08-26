# StatePilot — voortgang

## Omgeving (server)
- Android SDK in `~/android-sdk` (sdk.dir in local.properties); Gradle 8.7 distro `~/tools/gradle-8.7`; wrapper in repo.
- JDK 17 via apt. Kotlin 1.9.24, AGP 8.5.2.
- Emulators (software, geen KVM): watch = Wear OS 4/API 33 AVD "watch", phone = Android 15/API 35 AVD "phone".
  - **Geheugenregel:** 2–4 GB per AVD + ~1–1,5 GB host-overhead; niet tegelijk met Gradle-build; `-gpu off -cores 2` voor stabiliteit (swiftshader gaf ColorBuffer-crashes).
  - ddmlib API-level=1-bug → APK's handmatig via adb installeren + `am instrument` (AndroidJUnitRunner expliciet).
- Screenshots: `/data/workspace/screenshots/` + repo `docs/screenshots/`.

## Gedaan
- Fase 0–10 + 12–19 afgerond (alles behalve Fase 11 = echte Data Layer, vereist gekoppelde phone+watch).
- Fase 13: Tile-mapper JVM-getest (7), TileService compileert op Tiles 1.4.1 (geen Button-element; tekst+clickable).
- Fase 14: 5 interruption-flow-tests op StateEngine (context-behoud, chain-invariant via resumedStateId).
- Fase 16: GeofenceEngine (Haversine, ENTER/EXIT, place→place, duplicate-onderdrukking), 7 tests.
- Fase 17: TransitionEngine (calendar 0.40 / geofence 0.35 / motion 0.20, suggestie ≥0.3, AUTO ≥0.9), 6 tests.
- Fase 18: AutomaticTransitionExecutor met verplichte audit-trail, 6 tests.
- E2E-demo op phone-emulator bewezen: Start/Interrupt/Resume/Finish via adb-taps op echte StateEngine.
- `./gradlew test assembleDebug` → BUILD SUCCESSFUL; **120 unit tests** + 3 instrumented wear-tests groen.
- GitHub: `jajadvert/statepilot` (private), release v0.1 met APK's, README met MVP-statusoverzicht.

## Kernbeslissingen
- In-memory repositories eerst; Room komt later als vervanging achter dezelfde interfaces.
- getOpenForState(activeStateId) zoekt op interruptionStateId == huidige actieve state.
- Delay verschuift alleen toekomstige planning; history onaanraakbaar.
- Auto-transitions alleen bij AUTO-policy + ≥0.9 confidence + evidence; audit-record verplicht.
- Tile/TileService: alle layout-logica in pure mapper (JVM-testbaar), service is thin adapter.

## Nog open
- Fase 11: WearableListenerService/MessageClient (pairen + echte devices).
- Instrumented Tile-tests (Wear Tiles testing APIs) op emulator of echte watch.
- CI (GitHub Actions) voor JVM-tests; Room-persistentie als vervanging van in-memory.
