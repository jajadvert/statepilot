# Implementatieplan voor een Calendar-gestuurde State Execution App: StatePilot

## 1. Doel van het project

Bouw een nieuwe Android + Wear OS-app die een kalenderplanning vertaalt naar een runtime execution-systeem.

De kern van het systeem is het onderscheid tussen:

* **Planned state**: wat volgens de kalender nu of straks zou moeten gebeuren.
* **Actual state**: wat de gebruiker werkelijk aan het doen is.
* **Transition**: de overgang van de ene actual state naar de volgende.
* **Deviation**: het verschil tussen planning en werkelijkheid.
* **Interruption**: een ongeplande onderbreking van een lopende state.
* **Resume**: terugkeer naar een onderbroken state.
* **Trigger**: een signaal dat een transition zou kunnen of moeten plaatsvinden.

De gebruiker moet via telefoon en Galaxy Watch snel kunnen aangeven:

* start geplande activiteit;
* wissel naar andere activiteit;
* onderbreek huidige activiteit;
* hervat onderbroken activiteit;
* stel geplande overgang uit;
* sla geplande activiteit over;
* beëindig huidige activiteit.

Later moeten locatie/geofencing en andere sensoren transitions kunnen suggereren of eventueel automatisch uitvoeren.

---

# 2. Kernprincipes

Houd deze principes tijdens de gehele implementatie aan.

## 2.1 Planning en uitvoering zijn aparte domeinen

Een calendar event is geen actual state.

Voorbeeld:

```text
Planned:
09:00–11:00 Deep Work

Actual:
09:08–09:47 Deep Work
09:47–09:59 Phone Call
09:59–11:03 Deep Work
```

De planning moet intact blijven, ook als de werkelijkheid anders verloopt.

---

## 2.2 Er is maximaal één actuele execution state

Op elk moment is er maximaal één actieve `ActualState`.

Een transition sluit de vorige state af en opent de volgende.

---

## 2.3 Calendar-data mag geen execution-history overschrijven

Calendar-wijzigingen mogen toekomstige `PlannedBlock`-records veranderen.

Ze mogen nooit reeds geregistreerde execution-history vernietigen.

---

## 2.4 De telefoon is de source of truth

De Wear OS-app is primair:

* input-interface;
* notification-interface;
* snelle state viewer.

De canonical execution-state leeft op de telefoon.

---

## 2.5 Businesslogica staat niet in UI-code

Gebruik duidelijke domeinservices:

```text
StateEngine
ScheduleEngine
CalendarImporter
TransitionEngine
NotificationScheduler
LocationTriggerEngine
```

Compose UI mag deze services niet rechtstreeks omzeilen.

---

## 2.6 Gebruik dependency inversion voor externe systemen

Definieer interfaces voor:

```text
Clock
CalendarSource
WearTransport
LocationProvider
NotificationGateway
```

Maak fake implementations voor tests.

---

# 3. Technische uitgangspunten

Gebruik bij voorkeur:

* Kotlin
* Android
* Jetpack Compose
* Room of SQLDelight voor lokale database
* Kotlin Coroutines
* Flow / StateFlow
* kotlinx.serialization
* Wear OS Compose
* Wear Data Layer
* Android Calendar Provider
* DAVx5 / CalDAV als externe sync-laag
* WorkManager waar achtergrondtaken nodig zijn

Gebruik geen eigen CalDAV-client in de eerste MVP.

De eerste architectuur is:

```text
CalDAV server
      ↕
DAVx5
      ↕
Android Calendar Provider
      ↓
CalendarImporter
      ↓
PlannedBlock
      ↓
ScheduleEngine
      ↓
StateEngine
      ↓
ActualState / Transition / Interruption
      ↓
Phone UI + Wear OS
```

---

# 4. Domeinmodel

Maak minimaal de volgende domeinobjecten.

## 4.1 ActivityType

Een herbruikbaar type activiteit.

Voorbeelden:

```text
deep_work
meeting
travel
breakfast
lunch
exercise
admin
reading
sleep
phone_call
break
other
```

Voorstel:

```kotlin
data class ActivityType(
    val id: String,
    val name: String,
    val colorKey: String?,
    val iconKey: String?,
    val defaultTransitionPolicy: TransitionPolicy
)
```

---

## 4.2 PlannedBlock

Representeert een geplande kalenderperiode.

```kotlin
data class PlannedBlock(
    val id: String,
    val externalCalendarId: String?,
    val externalEventId: String?,
    val activityTypeId: String?,
    val title: String,
    val plannedStart: Instant,
    val plannedEnd: Instant,
    val locationText: String?,
    val placeId: String?,
    val status: PlannedBlockStatus,
    val revision: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
```

Status:

```kotlin
enum class PlannedBlockStatus {
    ACTIVE,
    CANCELLED
}
```

---

## 4.3 ActualState

Representeert wat werkelijk wordt uitgevoerd.

```kotlin
data class ActualState(
    val id: String,
    val activityTypeId: String,
    val plannedBlockId: String?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val source: StateSource,
    val trigger: TransitionTriggerType?,
    val resumedFromStateId: String?,
    val note: String?
)
```

Bron:

```kotlin
enum class StateSource {
    PHONE,
    WATCH,
    NOTIFICATION,
    SYSTEM,
    LOCATION,
    IMPORT
}
```

---

## 4.4 Transition

Bewaar elke daadwerkelijke statewissel expliciet.

```kotlin
data class Transition(
    val id: String,
    val fromStateId: String?,
    val toStateId: String,
    val occurredAt: Instant,
    val source: StateSource,
    val triggerType: TransitionTriggerType,
    val plannedBlockId: String?,
    val requestId: String?
)
```

---

## 4.5 Interruption

```kotlin
data class Interruption(
    val id: String,
    val interruptedStateId: String,
    val interruptionStateId: String,
    val category: InterruptionCategory,
    val startedAt: Instant,
    val endedAt: Instant?,
    val resumedStateId: String?
)
```

Categorieën:

```text
CALL
PERSON
ADMIN
BREAK
MESSAGE
URGENT_TASK
OTHER
```

---

## 4.6 Deviation

```kotlin
data class Deviation(
    val id: String,
    val plannedBlockId: String?,
    val actualStateId: String?,
    val type: DeviationType,
    val amountSeconds: Long?,
    val createdAt: Instant
)
```

Types:

```text
STARTED_LATE
STARTED_EARLY
ENDED_LATE
ENDED_EARLY
SKIPPED
INTERRUPTED
UNPLANNED
RESCHEDULED
OVERRUN
```

---

## 4.7 Place

Voor toekomstige location-aware transitions.

```kotlin
data class Place(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double
)
```

---

# 5. Commands voor de StateEngine

Alle statewijzigingen moeten via commands lopen.

Maak bijvoorbeeld:

```kotlin
sealed interface StateCommand
```

met:

```text
StartPlannedBlock
StartActivity
FinishCurrentState
InterruptCurrentState
ResumeInterruptedState
SwitchActivity
SkipPlannedBlock
DelayPlannedBlock
```

Voorbeeld:

```kotlin
data class StartPlannedBlock(
    val plannedBlockId: String,
    val source: StateSource,
    val requestId: String
) : StateCommand
```

---

# 6. Fase 0 — repository en projectfundament

## 6.1 Taken

Maak modules zoals:

```text
app-phone
app-wear
domain
data
calendar
wear-protocol
```

Of houd het eenvoudiger zolang grenzen logisch blijven.

Maak:

* CI build;
* formatting/linting;
* unit test setup;
* instrumented test setup.

## Definition of Done

Deze opdrachten slagen:

```bash
./gradlew test
./gradlew assembleDebug
```

Phone en Wear modules bouwen onafhankelijk.

## Tests

Nog alleen:

* build tests;
* dependency wiring tests;
* simpele smoke tests.

---

# 7. Fase 1 — database en repositories

Implementeer persistence voor:

```text
ActivityType
PlannedBlock
ActualState
Transition
Interruption
Deviation
Place
```

Maak repository interfaces.

Voorbeeld:

```kotlin
interface ActualStateRepository {
    suspend fun getCurrent(): ActualState?
    suspend fun insert(state: ActualState)
    suspend fun finish(id: String, endedAt: Instant)
}
```

## Definition of Done

De database kan een complete dag reconstrueren.

Beantwoordbaar vanuit database:

```text
Wat stond gepland?
Wat gebeurde werkelijk?
Wat is nu actief?
Welke interruptions waren er?
Welke planned blocks zijn overgeslagen?
Welke deviations zijn geregistreerd?
```

## Tests

Schrijf tests voor:

```text
only one active ActualState
closed states stay immutable
PlannedBlock may exist without ActualState
ActualState may exist without PlannedBlock
cancelled future block remains auditable
transitions reference valid states
```

---

# 8. Fase 2 — pure StateEngine

Bouw:

```text
StateEngine
```

Zonder UI, Calendar, Wear of GPS.

Verantwoordelijkheden:

* nieuwe state starten;
* bestaande state afsluiten;
* state wisselen;
* interruption starten;
* interruption beëindigen;
* vorige state hervatten;
* block skippen;
* manual unplanned state starten;
* request idempotency.

## 8.1 Idempotency

Elk extern command krijgt een:

```text
requestId
```

Een command met dezelfde `requestId` mag niet tweemaal worden uitgevoerd.

## Definition of Done

Alle belangrijke stateflows werken volledig via unit tests.

## Tests

Minimaal:

```text
start first state
start second state closes first
switch creates transition
starting same state twice follows defined behavior
duplicate requestId executes once
interrupt closes current state
interrupt remembers interrupted context
resume restores activity context
interrupt then choose different state
finish leaves no current state
unplanned state allowed
```

---

# 9. Fase 3 — Clock abstraction en tijdlogica

Maak:

```kotlin
interface Clock {
    fun now(): Instant
}
```

Gebruik nergens in domain direct:

```text
System.currentTimeMillis()
Instant.now()
```

Behalve in de production clock implementation.

## Definition of Done

Alle tijdafhankelijke domainlogica is deterministisch testbaar.

## Tests

Test:

```text
elapsed duration
future timestamp
zero duration
cross-midnight
timezone rendering
DST transition
```

---

# 10. Fase 4 — Calendar import

Gebruik in MVP:

```text
Android Calendar Provider
```

Niet direct CalDAV.

CalDAV synchronisatie wordt extern gedaan via bijvoorbeeld DAVx5.

## 10.1 CalendarSource interface

```kotlin
interface CalendarSource {
    suspend fun getEvents(
        from: Instant,
        to: Instant
    ): List<CalendarEventDto>
}
```

## 10.2 Event mapping

Map calendar event naar `PlannedBlock`.

Gebruik minimaal:

```text
UID/event id
title
start
end
location
calendar id
lastModified/revision
```

## 10.3 Activity mapping

Begin simpel met mapping op titel of configuratie.

Bijvoorbeeld:

```text
"Deep Work" → deep_work
"Travel" → travel
"Lunch" → lunch
```

Later uitbreidbaar met categorie/metadata.

## 10.4 Incremental sync

Import moet idempotent zijn.

Calendar-event wijzigingen mogen:

* toekomstige block aanpassen;
* cancelled status zetten;
* metadata bijwerken.

Ze mogen nooit bestaande execution-history verwijderen.

## Definition of Done

Calendar events worden correct naar lokale `PlannedBlock`-records gesynchroniseerd.

## Tests

Schrijf:

```text
new event creates block
same event twice creates one block
changed time updates block
changed title updates block
deleted future event marks cancelled
executed historical block remains auditable
all-day event handled explicitly
overlapping events supported
```

---

# 11. Fase 5 — ScheduleEngine

Maak:

```text
ScheduleEngine
```

Deze engine leest:

```text
PlannedBlocks
ActualState
Clock
```

en levert:

```text
currentPlannedBlock
nextPlannedBlock
currentActualState
transitionDue
transitionOverdue
delaySeconds
nextWarningTime
```

Voorbeeld output:

```kotlin
data class ScheduleStatus(
    val currentActualState: ActualState?,
    val currentPlannedBlock: PlannedBlock?,
    val nextPlannedBlock: PlannedBlock?,
    val transitionStatus: TransitionStatus,
    val deviationSeconds: Long
)
```

Transition status:

```text
NONE
UPCOMING
DUE
OVERDUE
```

## Definition of Done

De app kan op elk willekeurig tijdstip bepalen:

```text
wat doe ik?
wat zou ik moeten doen?
wat komt hierna?
hoeveel loop ik voor/achter?
moet er een waarschuwing komen?
```

## Tests

Gebruik FakeClock.

Test:

```text
before first block
1 minute before transition
exact block start
5 minutes overdue
between blocks
after last block
overlapping blocks
empty calendar
cancelled block
current state matches plan
current state differs from plan
```

---

# 12. Fase 6 — minimale Android UI

Bouw alleen de execution interface.

Geen volledige kalendereditor.

Hoofdscherm:

```text
CURRENT
Deep Work
01:12:43

PLANNED NOW
Deep Work
09:00–11:00

NEXT
Travel
11:00

STATUS
On schedule
```

Bij afwijking:

```text
CURRENT
Breakfast

PLANNED NOW
Deep Work

17 min behind schedule
```

Acties:

```text
Start planned
Switch activity
Interrupt
Resume
Finish
Delay
Skip
```

## Definition of Done

Een gebruiker kan een volledige dag uitvoeren zonder database of kalender handmatig te wijzigen.

## Tests

Compose tests:

```text
current state visible
planned state visible
next block visible
delay visible
start button sends command
interrupt works
resume appears when appropriate
skip confirmation works
empty plan state renders
```

---

# 13. Fase 7 — notifications

Gebruik notifications voor geplande transitions.

Voorbeeld:

```text
09:00

Deep Work should start now

[START]
[+10 MIN]
[SKIP]
```

Voor travel:

```text
10:45

Leave in 15 minutes
Travel to Dentist

[LEAVING NOW]
[+5 MIN]
```

## 13.1 Notification types

Minimaal:

```text
UPCOMING
DUE
OVERDUE
TRAVEL_WARNING
```

## 13.2 Notification actions

Actions sturen commands naar StateEngine.

## Definition of Done

Notifications verschijnen rond de juiste tijden en zijn direct uitvoerbaar.

## Tests

Unit tests:

```text
correct warning timestamp
correct due timestamp
delay reschedules
changed calendar event cancels old notification
duplicate notification not created
```

Instrumented tests:

```text
notification action starts state
notification delay updates scheduling
notification skip marks planned block skipped
```

---

# 14. Fase 8 — Wear protocol

Maak een aparte protocolmodule zonder Android UI dependencies.

## 14.1 DTOs

Voorbeeld:

```kotlin
data class WearStateDto(
    val revision: Long,
    val currentActivity: WearActivityDto?,
    val currentStateStartedAt: Instant?,
    val currentPlannedBlock: WearPlannedBlockDto?,
    val nextPlannedBlock: WearPlannedBlockDto?,
    val transitionStatus: String,
    val deviationSeconds: Long
)
```

Commands:

```text
START_PLANNED
START_ACTIVITY
INTERRUPT
RESUME
DELAY
SKIP
FINISH
```

## 14.2 Revision semantics

Watch accepteert alleen state met:

```text
revision >= huidige revision
```

Oudere state wordt genegeerd.

## Definition of Done

Protocol roundtript betrouwbaar tussen JSON/binary representations.

## Tests

```text
serialization roundtrip
unknown fields ignored
old revision ignored
new revision accepted
duplicate revision idempotent
unicode labels
empty planned state
```

---

# 15. Fase 9 — WearTransport abstraction

Definieer:

```kotlin
interface WearTransport {
    val states: Flow<WearStateDto>

    suspend fun sendCommand(
        command: WearCommandDto
    ): Result<Unit>
}
```

Implementaties:

```text
FakeWearTransport
DataLayerWearTransport
```

## Definition of Done

De volledige Wear UI kan draaien zonder echte telefoon of watch.

---

# 16. Fase 10 — Wear OS UI MVP

Watch hoofdscherm:

```text
CURRENT
Deep Work
01:18:23

NEXT
Travel · 11:00

[INTERRUPT]
[SWITCH]
```

Als transition due:

```text
DEEP WORK
should start now

[START]
[+10]
[SKIP]
```

Tijdens interruption:

```text
PHONE CALL
08:42

[RESUME DEEP WORK]
[CHOOSE OTHER]
```

## Definition of Done

Vanaf Wear OS kan gebruiker:

```text
start planned
switch state
interrupt
resume
delay
skip
finish
```

## Tests

Compose Wear UI:

```text
current state shown
next block shown
overdue shown
start action sends correct command
interrupt category screen works
resume target correct
offline error state
```

---

# 17. Fase 11 — echte Wear Data Layer

Gebruik:

```text
MessageClient
```

voor commands.

Gebruik:

```text
DataClient
```

voor state.

Concept:

```text
Watch
  ↓ MessageClient
Phone command handler
  ↓
StateEngine
  ↓
database
  ↓
ScheduleEngine
  ↓ DataClient
Watch
```

## Offline-beleid MVP

Geen optimistische transitions.

Als telefoon niet bereikbaar is:

```text
Phone unavailable
State was not changed
```

Later kan command queueing worden toegevoegd.

## Definition of Done

End-to-end werkt op gekoppelde Android + Wear emulator.

## Tests

```text
watch start command changes phone state
phone publishes new state
watch receives state
duplicate command processed once
old state packet ignored
disconnect shows failure
reconnect gets newest state
phone foreground not required
```

---

# 18. Fase 12 — state caching op Wear

Bewaar laatst bekende Wear-state lokaal.

Gebruik bijvoorbeeld DataStore.

Flow:

```text
watch opens
↓
cached state immediately visible
↓
Data Layer sync
↓
newer state replaces cache
```

## Definition of Done

Watch UI start niet leeg als telefoon tijdelijk niet bereikbaar is.

## Tests

```text
cached state restored
newer remote state replaces cache
older remote state ignored
corrupt cache falls back safely
```

---

# 19. Fase 13 — Tile

Maak een Wear Tile.

Voorbeeld:

```text
DEEP WORK
1h18

NEXT
Travel · 11:00

[Interrupt]
```

Bij transition:

```text
TRAVEL DUE

[START]
[+5]
```

## Definition of Done

De belangrijkste statewisseling kan vanaf Tile worden uitgevoerd.

## Tests

Gebruik Wear Tiles testing APIs.

Test:

```text
current activity visible
next activity visible
transition button visible when due
correct action ids
empty-state rendering
```

---

# 20. Fase 14 — interruption UX

Maak interruptions first-class.

Flow:

```text
Deep Work
↓
Interrupt
↓
Choose:
Call
Person
Admin
Break
Message
Other
↓
Interruption state starts
↓
Resume Deep Work
```

## Definition of Done

Elke interruption behoudt context naar de onderbroken state.

## Tests

```text
interrupt records parent state
resume creates new state
resume references original context
interrupt then switch does not auto-resume
multiple sequential interruptions handled
```

---

# 21. Fase 15 — planned vs actual analytics

Bereken per `PlannedBlock`:

```text
planned duration
actual duration
start delay
end deviation
interruption duration
net focused duration
skip status
fragment count
```

Voorbeeld:

```text
Deep Work

planned:             120 min
elapsed execution:   126 min
interruptions:        23 min
net activity:        103 min
started:              +8 min
ended:                +3 min
```

## Definition of Done

Een dagrapport kan planning en werkelijkheid vergelijken.

## Tests

```text
exact execution
late start
early finish
late finish
multiple execution fragments
interruptions
skipped block
unplanned state
```

---

# 22. Fase 16 — Places en geofencing

Pas na stabiele handmatige workflow.

Maak `LocationProvider` interface.

Events:

```text
ENTER_PLACE
EXIT_PLACE
```

Voorbeeld:

```text
current actual = Breakfast
next planned = Travel
time = departure ±10m
exit Home detected
```

Output:

```text
Suggest: Start Travel
```

Nog niet automatisch uitvoeren.

## Definition of Done

Location evidence kan transition suggestions genereren.

## Tests

FakeLocationProvider:

```text
exit Home near commute
exit Home hours too early
enter Office during commute
geofence bounce
duplicate enter/exit
unknown place
```

---

# 23. Fase 17 — TransitionEngine met confidence

Introduceer:

```text
TransitionSuggestion
```

met:

```kotlin
data class TransitionSuggestion(
    val targetActivityTypeId: String,
    val confidence: Double,
    val evidence: List<TransitionEvidence>,
    val recommendedAction: TransitionPolicy
)
```

Evidence kan zijn:

```text
calendar due
geofence exit
geofence enter
manual input
motion state
current Wi-Fi
Bluetooth connection
```

Policies:

```text
MANUAL
SUGGEST
AUTO
```

## Voorbeeld

```text
planned commute due:     +0.40
exited Home:             +0.35
movement detected:       +0.20

confidence = 0.95
```

## Definition of Done

Elke automatische/suggested transition is uitlegbaar.

Voorbeeld:

```text
Travel suggested because:
- scheduled departure was 08:30
- Home geofence exited at 08:32
- movement detected
```

---

# 24. Fase 18 — optional automatic transitions

Voeg pas auto-transitions toe als suggesties aantoonbaar betrouwbaar zijn.

Per activity/config:

```text
TransitionPolicy.MANUAL
TransitionPolicy.SUGGEST
TransitionPolicy.AUTO
```

Default:

```text
SUGGEST
```

## Definition of Done

Een gebruiker kan automation per trigger/type uitzetten.

Geen automatic transition zonder audit trail.

---

# 25. Fase 19 — feedback naar weekplanner

Maak een export/API waarin execution-statistieken beschikbaar komen voor de planner.

Voorbeeld:

```json
{
  "activityType": "deep_work",
  "samples": 18,
  "plannedMedianMinutes": 120,
  "actualMedianMinutes": 103,
  "medianStartDelayMinutes": 11,
  "medianInterruptionMinutes": 19
}
```

Voor travel:

```json
{
  "activityType": "commute_office",
  "plannedMedianMinutes": 25,
  "actualMedianMinutes": 34
}
```

Planner kan daarmee toekomstige weekplanning verbeteren.

## Definition of Done

De weekplanner hoeft niet rechtstreeks de execution DB te kennen.

Hij krijgt een stabiel data-contract.

---

# 26. MVP scope

De echte MVP stopt na:

```text
Calendar import
StateEngine
ScheduleEngine
Phone UI
Notifications
Wear manual transitions
Interrupt/Resume
Basic planned-vs-actual history
```

Niet in MVP:

```text
geofencing
auto transitions
AI
full analytics dashboard
custom CalDAV client
calendar editing
routing
maps
complex task manager
Pomodoro
cloud account system
```

---

# 27. MVP acceptance scenario

MVP is pas done wanneer dit volledige scenario automatisch testbaar of reproduceerbaar werkt.

## Scenario

Calendar bevat:

```text
08:00–09:00 Morning
09:00–11:00 Deep Work
11:00–11:30 Travel
11:30–12:30 Appointment
```

### 08:50

Systeem toont:

```text
Deep Work starts in 10 minutes
```

### 09:00

Watch toont:

```text
Deep Work should start

[START]
[+10]
[SKIP]
```

### 09:03

Gebruiker kiest:

```text
START
```

Opslag:

```text
planned start = 09:00
actual start = 09:03
deviation = +3m
```

### 09:41

Gebruiker kiest:

```text
INTERRUPT
→ Phone Call
```

### 09:52

Watch toont:

```text
Resume Deep Work?
```

Gebruiker kiest:

```text
RESUME
```

### 10:50

Travel-warning verschijnt:

```text
Leave in 10 minutes
```

### 11:04

Gebruiker start:

```text
Travel
```

Opslag:

```text
planned start = 11:00
actual start = 11:04
```

### Einde dag

App kan rapporteren:

```text
Deep Work planned:
120m

Started:
+3m late

Interruptions:
11m

Net actual:
...

Travel planned:
30m

Travel started:
+4m late
```

---

# 28. Teststrategie

Gebruik een testpiramide.

## Niveau 1 — pure unit tests

Geen Android nodig.

Test:

```text
StateEngine
ScheduleEngine
TransitionEngine
deviation calculations
timer calculations
serialization
revision handling
idempotency
```

Deze tests moeten het grootste deel van de domain coverage vormen.

---

## Niveau 2 — database tests

Test:

```text
repositories
transactions
one-current-state invariant
history reconstruction
migration behavior
```

---

## Niveau 3 — calendar importer tests

Gebruik FakeCalendarSource.

Test:

```text
new event
update
cancel
duplicate
overlap
timezone
DST
```

---

## Niveau 4 — Phone Compose tests

Test UI semantics en user actions.

---

## Niveau 5 — Wear Compose tests

Gebruik FakeWearTransport.

Geen echte watch nodig.

---

## Niveau 6 — phone emulator + Wear emulator

Test echte Wear Data Layer end-to-end.

Gebruik dit voor:

```text
command delivery
state sync
offline
reconnect
background service behavior
```

---

## Niveau 7 — fysieke Galaxy Watch

Alleen nodig voor:

```text
Samsung-specific behavior
real Bluetooth conditions
battery behavior
real watch readability
gestures
haptics
background restrictions
```

Niet gebruiken als primaire development-loop.

---

# 29. Testability-eisen

Volg deze eisen strikt.

## Gebruik FakeClock

Geen domain-test mag afhankelijk zijn van echte wall-clock tijd.

## Gebruik FakeWearTransport

Wear UI moet volledig testbaar zijn zonder Google Play Services.

## Gebruik FakeCalendarSource

Calendar importer moet zonder echte calendar-account kunnen worden getest.

## Gebruik FakeLocationProvider

Geofence logic moet zonder GPS testbaar zijn.

## Gebruik request IDs

Alle externe commands moeten veilig opnieuw uitgevoerd kunnen worden.

---

# 30. Belangrijke invarianten

Schrijf hier expliciete tests voor.

```text
MAX 1 active ActualState
```

```text
A state transition closes previous active state
```

```text
Historical ActualState records are never rewritten by calendar sync
```

```text
Calendar cancellation never deletes execution history
```

```text
Duplicate request IDs have no duplicate effect
```

```text
Older Wear revisions never overwrite newer state
```

```text
An interruption always retains interrupted context
```

```text
Resume never mutates the original historical state
```

```text
Automatic transitions always record source + evidence
```

---

# 31. Suggested package architecture

Voorbeeld:

```text
com.example.execution

domain/
    activity/
    state/
    schedule/
    transition/
    interruption/
    deviation/
    place/

data/
    db/
    repository/

calendar/
    CalendarSource
    AndroidCalendarSource
    CalendarImporter

notifications/
    NotificationScheduler

wear/
    protocol/
    transport/

location/
    LocationProvider
    GeofenceLocationProvider

app/
    phone/
    wear/
```

---

# 32. Logging en observability

Maak structured logs voor belangrijke gebeurtenissen.

Voorbeelden:

```text
CALENDAR_SYNC_STARTED
CALENDAR_EVENT_IMPORTED
STATE_STARTED
STATE_FINISHED
STATE_INTERRUPTED
STATE_RESUMED
WEAR_COMMAND_RECEIVED
WEAR_COMMAND_DUPLICATE
TRANSITION_SUGGESTED
TRANSITION_AUTO_EXECUTED
GEOFENCE_ENTER
GEOFENCE_EXIT
```

Log geen gevoelige locatiegegevens onnodig in production logs.

---

# 33. Audit trail

Elke transition moet later verklaarbaar zijn.

Bewaar minstens:

```text
timestamp
source
trigger
requestId
plannedBlockId
previous state
new state
automatic/manual
```

Voor automatische transitions ook:

```text
confidence
evidence
rule version
```

---

# 34. Privacy-uitgangspunten

Locatie- en execution-data zijn persoonlijk.

Daarom:

```text
local-first
```

als default.

Geen cloud-upload zonder expliciete noodzaak.

Geen externe analytics SDK in MVP.

Geen GPS-history bewaren als alleen een geofence enter/exit-event nodig is.

Bewaar liever:

```text
entered Home at 08:31
```

dan:

```text
continu GPS coordinates every 10 seconds
```

---

# 35. Error handling

Gebruik expliciete states zoals:

```text
CalendarPermissionMissing
CalendarUnavailable
WearDisconnected
NotificationPermissionMissing
LocationPermissionMissing
NoCurrentState
NoPlannedBlock
CommandRejected
```

UI mag fouten niet stil negeren.

---

# 36. UX-regel voor automatisering

Default moet zijn:

```text
suggest first
automate later
```

Voorbeeld:

```text
Calendar says Travel
+
Home geofence exited

→ Suggest "Start Travel?"
```

Niet direct automatisch starten.

Pas na expliciete gebruikersconfiguratie:

```text
AUTO
```

---

# 37. Releasevolgorde

Gebruik deze milestones.

## 0.1

```text
domain model
database
StateEngine
unit tests
```

## 0.2

```text
CalendarSource
CalendarImporter
PlannedBlock sync
```

## 0.3

```text
ScheduleEngine
phone execution UI
```

## 0.4

```text
notifications
delay
skip
```

## 0.5

```text
Wear protocol
FakeWearTransport
Wear UI
```

## 0.6

```text
real Data Layer
phone ↔ Wear emulator
```

## 0.7

```text
interruptions
resume flow
```

## 0.8

```text
planned-vs-actual daily analysis
```

Dit is de eerste volwaardige MVP.

## 0.9

```text
Places
geofence suggestions
```

## 0.10

```text
confidence engine
```

## 1.0

```text
stable automation
planner feedback contract
hardware validation
```

---

# 38. Werkwijze voor Hermes Agent

Werk fase voor fase.

Voor iedere subfase:

1. Inspecteer bestaande code.
2. Benoem expliciet welke files je gaat toevoegen of wijzigen.
3. Implementeer de minimale verticale slice.
4. Voeg tests toe vóór of tegelijk met businesslogica.
5. Draai relevante tests.
6. Los failures op.
7. Controleer regressies.
8. Rapporteer:

   * gewijzigde files;
   * architectuurkeuzes;
   * tests;
   * resterende risico's.
9. Ga pas naar de volgende fase als de Definition of Done gehaald is.

Maak geen grote architecture rewrite als een kleinere uitbreiding voldoende is.

---

# 39. Coding constraints voor Hermes Agent

Houd deze regels aan:

```text
No business logic inside Compose UI
```

```text
No direct wall-clock calls inside domain logic
```

```text
No direct Google Wear APIs inside ViewModels
```

```text
No direct Calendar Provider access inside domain
```

```text
No GPS dependency inside StateEngine
```

```text
Every external command must be idempotent
```

```text
Every state change must be auditable
```

```text
Calendar sync must never destroy actual history
```

```text
Tests must not require physical hardware unless explicitly hardware-specific
```

---

# 40. Prioriteiten

Prioriteit 1:

```text
correctness of state history
```

Prioriteit 2:

```text
reliable manual transitions
```

Prioriteit 3:

```text
calendar-triggered suggestions
```

Prioriteit 4:

```text
Wear usability
```

Prioriteit 5:

```text
analytics
```

Prioriteit 6:

```text
location automation
```

Niet andersom.

---

# 41. Einddoel

Het eindproduct moet op ieder moment drie vragen kunnen beantwoorden:

```text
1. Wat stond gepland?
2. Wat doe ik werkelijk?
3. Wat zou nu de meest logische volgende transition zijn?
```

En historisch:

```text
1. Hoe week mijn uitvoering af van mijn planning?
2. Welke interruptions traden op?
3. Welke activiteiten werden structureel te kort gepland?
4. Waar liep mijn dag structureel vertraging op?
5. Welke informatie kan de volgende weekplanning verbeteren?
```

Het systeem is daarmee geen gewone time tracker en geen gewone calendar-app.

Het is een:

```text
planning-aware execution state tracker
```

met:

```text
Calendar → Planned State
Reality → Actual State
Watch/Sensors → Transitions
History → Feedback
```

Bouw eerst de betrouwbare handmatige loop.

Voeg pas daarna slimme automatisering toe.

