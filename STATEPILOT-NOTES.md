# StatePilot — voortgang

## Omgeving (server)
- Geen GUI/emulator mogelijk; Android SDK staat in `~/android-sdk` (sdk.dir in local.properties).
- Gradle 8.7 distro: `~/tools/gradle-8.7`; wrapper aanwezig in repo.
- JDK 17 via apt. Kotlin 1.9.24, AGP 8.5.2, Compose BOM 2024.06, compose compiler 1.5.14.
- Projectroot: `/data/workspace/statepilot`.

## Gedaan
- Fase 0–5 afgerond + calendar-module (Fase 4 logica) en wear-protocol (Fase 8) als bonus.
- Fase 5: ScheduleEngine in domain/schedule (ScheduleStatus, TransitionStatus NONE/UPCOMING/DUE→OVERDUE, deviation, nextWarningTime), 11 FakeClock-tests.
- Fase 6: PhoneExecutionPresenter (app-phone) — UI-state mapping + command-forwarding, JVM-testbaar; MainActivity Compose-scherm toont presenter-state.
- Fase 7: NotificationScheduler (domain, pure) met reconcile-logica; AndroidNotificationGateway + NotificationActionReceiver in app-phone (acties → commands).
- Fase 8-10: FakeWearTransport + PhoneWearBridge in wear-protocol; watch-commando's → StateEngine, state-publicatie met revision. 8 Wear-flow-tests.
- Fase 15: DayAnalyzer (domain/analytics) — per-block planned-vs-actual (start delay, end deviation, interruptions, net focused, fragments, skip), unplanned-samenvatting. 9 tests.
- `./gradlew test assembleDebug` → BUILD SUCCESSFUL; 78 unit tests groen.
- Modules: domain (JVM), data (JVM, InMemory-repo's), calendar (JVM), wear-protocol (JVM, kotlinx.serialization), app-phone + app-wear (Android library modules met minimale Compose/Wear-UI).

## Kernbeslissingen
- In-memory repositories eerst; Room komt later als vervanging achter dezelfde interfaces.
- getOpenForState(activeStateId) zoekt op interruptionStateId == huidige actieve state.
- Delay verschuift alleen toekomstige planning; history onaanraakbaar.
