# MVP Acceptance Checklist — §27 scenario

Het acceptance-scenario uit `PLAN.md` (§27) is de formele DoD van de MVP.
Deze checklist is afvinkbaar: elke stap geeft aan **wat** er getoond/opgeslagen
moet worden, **waar** het al geautomatiseerd gedekt is, en **hoe** je het op
jouw machine/hardware verifieert.

## Setup

Calendar bevat (via import of demo-seed):

```
08:00–09:00 Morning
09:00–11:00 Deep Work
11:00–11:30 Travel
11:30–12:30 Appointment
```

---

## De stappen

### 08:50 — waarschuwing

**Verwacht:** systeem toont `Deep Work starts in 10 minutes`

| Dekking | Status |
|---|---|
| `NotificationSchedulerTest > correct warning timestamp` | ✅ geautomatiseerd |
| `ScheduleEngineTest > 1 minute before transition` (UPCOMING) | ✅ geautomatiseerd |
| Handmatig: app open, notificatie zichtbaar om 08:50 | ☐ device |

### 09:00 — due op de watch

**Verwacht:** watch toont `Deep Work should start` met `[START] [+10] [SKIP]`

| Dekking | Status |
|---|---|
| `NotificationSchedulerTest > correct due timestamp` (DUE + acties) | ✅ geautomatiseerd |
| `TileStateMapperTest > transition button visible when due` + `correct action ids` | ✅ geautomatiseerd |
| `StatePilotTileServiceTest > transitionButtonVisibleWhenDue` | 🟡 instrumented (device) |
| Handmatig: watch-tile toont due-state | ☐ device |

### 09:03 — START

**Verwacht:** gebruiker tikt START. Opslag:

```
planned start = 09:00
actual start   = 09:03
deviation      = +3m
```

| Dekking | Status |
|---|---|
| `ScheduleEngineTest > current state matches plan` (180s deviation) | ✅ geautomatiseerd |
| `DayAnalyzerTest > late start` (+3 min) | ✅ geautomatiseerd |
| `StateEngineTest` (StartPlannedBlock) | ✅ geautomatiseerd |
| Handmatig: start via phone-UI of watch, deviation in statusregel | ☐ device |

### 09:41 — INTERRUPT

**Verwacht:** gebruiker kiest INTERRUPT → Phone Call

| Dekking | Status |
|---|---|
| `InterruptionFlowTest > interrupt records parent state` | ✅ geautomatiseerd |
| `PhoneExecutionPresenterTest > interrupt picker category sends command` | ✅ geautomatiseerd |
| Handmatig: picker toont categorieën, "call" wordt current | ☐ device |

### 09:52 — RESUME

**Verwacht:** watch toont `Resume Deep Work?`, gebruiker kiest RESUME

| Dekking | Status |
|---|---|
| `InterruptionFlowTest > resume creates new state` + `resume references original context` | ✅ geautomatiseerd |
| `InterruptionFlowTest > multiple sequential interruptions handled` | ✅ geautomatiseerd |
| Handmatig: resume herstelt "Deep Work" met context | ☐ device |

### 10:50 — travel-warning

**Verwacht:** `Leave in 10 minutes`

| Dekking | Status |
|---|---|
| `NotificationSchedulerTest > travel warning appears 15 minutes before departure` (leadtime config) | ✅ geautomatiseerd |
| Handmatig: warning verschijnt vóór een travel-block | ☐ device |

### 11:04 — start Travel

**Verwacht:** gebruiker start Travel. Opslag:

```
planned start = 11:00
actual start   = 11:04
```

| Dekking | Status |
|---|---|
| `DayAnalyzerTest > late start` (+4 min patroon) | ✅ geautomatiseerd |
| Handmatig: travel-state zichtbaar op phone + watch | ☐ device |

### Einde dag — dagrapport

**Verwacht:** app rapporteert per block:

```
Deep Work planned: 120m
Started: +3m late
Interruptions: 11m
Net actual: ...
Travel planned: 30m
Travel started: +4m late
```

| Dekking | Status |
|---|---|
| `DayAnalyzerTest` (planned/actual/start-delay/end-deviation/interruptions/net/fragments/skip) | ✅ geautomatiseerd |
| `PlannerFeedbackContractTest` (medianen + JSON-contract) | ✅ geautomatiseerd |
| Handmatig: Export-knop deelt `statepilot-feedback.json` | ☐ device |

---

## Device-suite (na setup volgens PLAYBOOK.md)

```bash
# watch-emulator of echte watch
./gradlew :app-wear:connectedDebugAndroidTest
#  → WearMainActivityTest (3) + StatePilotTileServiceTest (5)
#  → WearDataLayerInstrumentedTest (3) skipt zonder pairing

# met gepaarde phone + watch (Android Studio pairing assistant)
ANDROID_SERIAL=emulator-5554 ./gradlew :app-wear:connectedDebugAndroidTest
```

## Totaaloverzicht

| Groep | Aantal | Waar |
|---|---|---|
| Unit-tests (JVM, incl. Room/Robolectric) | 128 | `./gradlew test` |
| Watch instrumented (activity + tile) | 8 | `:app-wear:connectedDebugAndroidTest` |
| Data Layer instrumented (round-trip) | 3 | idem, met pairing |
| Handmatig op echte hardware | 7 stappen | bovenstaande ☐-rijtjes |

**MVP is 1.0-klaar wanneer alle ✅ én alle ☐ afgevinkt zijn.**
