package com.example.execution.domain.place

import kotlinx.datetime.Instant

/** Raw location sample from the device. */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Instant
)

enum class PlaceEventType { ENTER_PLACE, EXIT_PLACE }

/** A detected geofence transition. */
data class PlaceEvent(
    val type: PlaceEventType,
    val placeId: String,
    val placeName: String,
    val timestamp: Instant
)

/**
 * Dependency inversion (§2.6): the Android side implements this with
 * FusedLocationProvider; fakes are used in tests.
 */
interface LocationProvider {
    suspend fun currentLocation(): LocationSample?
}

/** Registered geofences the app knows about. */
interface PlaceRepository {
    suspend fun getAll(): List<Place>
}

/**
 * Fase 16: pure geofence logic. Consumes location samples and emits
 * ENTER/EXIT events:
 *  - enter when crossing into a geofence
 *  - exit when leaving the current geofence
 *  - direct place-to-place transition -> EXIT + ENTER
 *  - while inside the same place -> no duplicate ENTER
 *  - unknown place (inside nothing) -> no events
 */
class GeofenceEngine(
    private val places: PlaceRepository
) {
    private var insidePlaceId: String? = null

    /** Process one location sample; returns events to act on (may be empty). */
    suspend fun onLocation(sample: LocationSample): List<PlaceEvent> {
        val events = mutableListOf<PlaceEvent>()
        val known = places.getAll()

        val inside = known.firstOrNull { place ->
            GeofenceEngine.haversine(sample.latitude, sample.longitude, place.latitude, place.longitude) <= place.radiusMeters
        }

        when {
            inside != null && insidePlaceId == inside.id -> {
                // still inside the same place: no event
            }
            inside != null && insidePlaceId == null -> {
                insidePlaceId = inside.id
                events += PlaceEvent(PlaceEventType.ENTER_PLACE, inside.id, inside.name, sample.timestamp)
            }
            inside != null && insidePlaceId != null -> {
                // moved directly from one place to another
                events += PlaceEvent(PlaceEventType.EXIT_PLACE, insidePlaceId!!, "", sample.timestamp)
                events += PlaceEvent(PlaceEventType.ENTER_PLACE, inside.id, inside.name, sample.timestamp)
                insidePlaceId = inside.id
            }
            inside == null && insidePlaceId != null -> {
                events += PlaceEvent(PlaceEventType.EXIT_PLACE, insidePlaceId!!, "", sample.timestamp)
                insidePlaceId = null
            }
            else -> {
                // unknown place / nowhere: no event
            }
        }
        return events
    }

    companion object {
        /** Haversine distance in meters. */
        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return 2 * r * Math.asin(Math.sqrt(a))
        }
    }
}
