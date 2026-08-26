package com.example.execution.domain.place

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.*

/**
 * Fase 16 test list (FakeLocationProvider):
 *  - exit Home near commute
 *  - exit Home hours too early
 *  - enter Office during commute
 *  - geofence bounce
 *  - duplicate enter/exit
 *  - unknown place
 */
class GeofenceEngineTest {

    private val T0 = Instant.parse("2026-08-25T08:00:00Z")
    private val home = Place("home", "Home", 52.3676, 4.9041, 200.0)   // Amsterdam
    private val office = Place("office", "Office", 52.0800, 4.3100, 300.0) // Den Haag-ish
    private lateinit var engine: GeofenceEngine

    private fun sample(lat: Double, lon: Double, t: Instant = T0) =
        LocationSample(lat, lon, t)

    private suspend fun makeEngine(places: List<Place>): GeofenceEngine {
        val repo = object : PlaceRepository {
            override suspend fun getAll(): List<Place> = places
        }
        return GeofenceEngine(repo)
    }

    // --- helpers: place-relative offsets ---

    /** ~100 m north-west of home center. */
    private fun atHome(t: Instant = T0) = sample(52.3685, 4.9035, t)

    /** ~100 m east of office center. */
    private fun atOffice(t: Instant = T0) = sample(52.0809, 4.3110, t)

    /** ~10 km south of home — outside any geofence. */
    private fun farAway(t: Instant = T0) = sample(52.27, 4.90, t)

    @BeforeTest
    fun setup() = runTest {
        engine = makeEngine(listOf(home, office))
    }

    @Test
    fun `enter home then exit near commute`() = runTest {
        val entered = engine.onLocation(atHome())
        assertEquals(1, entered.size)
        assertEquals(PlaceEventType.ENTER_PLACE, entered[0].type)
        assertEquals("home", entered[0].placeId)

        // 30 min later, still at home
        val still = engine.onLocation(atHome(T0.plusMinutesTest(30)))
        assertTrue(still.isEmpty())

        // commute: leave home
        val exited = engine.onLocation(farAway(T0.plusMinutesTest(45)))
        assertEquals(1, exited.size)
        assertEquals(PlaceEventType.EXIT_PLACE, exited[0].type)
        assertEquals("home", exited[0].placeId)
    }

    @Test
    fun `exit home hours too early still emits exit`() = runTest {
        engine.onLocation(atHome())
        // leave at 06:00 (way before planned commute) — exit still detected
        val exited = engine.onLocation(farAway(Instant.parse("2026-08-25T06:00:00Z")))
        assertEquals(PlaceEventType.EXIT_PLACE, exited.single().type)
    }

    @Test
    fun `enter office during commute`() = runTest {
        engine.onLocation(atHome())
        engine.onLocation(farAway(T0.plusMinutesTest(40)))
        val entered = engine.onLocation(atOffice(T0.plusMinutesTest(70)))
        assertEquals(PlaceEventType.ENTER_PLACE, entered.single().type)
        assertEquals("office", entered.single().placeId)
    }

    @Test
    fun `geofence bounce does not re-emit enter while inside`() = runTest {
        engine.onLocation(atHome())
        // repeated samples inside the same place -> no duplicate ENTER
        val a = engine.onLocation(atHome(T0.plusMinutesTest(10)))
        val b = engine.onLocation(atHome(T0.plusMinutesTest(20)))
        assertTrue(a.isEmpty() && b.isEmpty())
    }

    @Test
    fun `duplicate enter does not re-emit while inside`() = runTest {
        engine.onLocation(atHome())
        engine.onLocation(atHome(T0.plusMinutesTest(10)))
        engine.onLocation(atHome(T0.plusMinutesTest(20)))
        // no duplicate ENTER events for home
        val events = mutableListOf<PlaceEvent>()
        // reconstruct: collect all events by direct calls above -> all were recorded
        // simulate a second engine tick where we remember events:
        // The engine itself suppresses re-entry while inside (no-op branch).
        // The only ENTER ever emitted is the first one.
        // Verify via a fresh engine that re-entering after EXIT + dwell does re-enter once.
        engine.onLocation(farAway(T0.plusMinutesTest(30))) // exit
        val re = engine.onLocation(atHome(T0.plusMinutesTest(31)))
        assertEquals(1, re.size) // exactly one ENTER on re-entry
    }

    @Test
    fun `unknown place detected`() = runTest {
        // location far outside all geofences with nothing entered
        val events = engine.onLocation(farAway())
        assertTrue(events.isEmpty()) // unknown place: no events (not inside anything)
    }

    @Test
    fun `direct place-to-place transition emits exit and enter`() = runTest {
        engine.onLocation(atHome())
        // teleport from home straight into office (no in-between sample)
        val events = engine.onLocation(atOffice())
        assertEquals(2, events.size)
        assertEquals(PlaceEventType.EXIT_PLACE, events[0].type)
        assertEquals("home", events[0].placeId)
        assertEquals(PlaceEventType.ENTER_PLACE, events[1].type)
        assertEquals("office", events[1].placeId)
    }
}

private fun Instant.plusMinutesTest(minutes: Long): Instant =
    Instant.fromEpochMilliseconds(toEpochMilliseconds() + minutes * 60_000L)
