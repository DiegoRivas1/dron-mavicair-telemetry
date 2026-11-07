package com.diegorivas.drone.utils

import com.diegorivas.drone.data.model.FlightRoute
import com.diegorivas.drone.data.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoUtilsTest {

    private val lat0 = -16.4090
    private val lon0 = -71.5375

    @Test
    fun destinationAndDistanceAreConsistent() {
        for (bearing in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 333.0)) {
            val (lat, lon) = GeoUtils.destination(lat0, lon0, bearing, 10.0)
            assertEquals(10.0, GeoUtils.distanceMeters(lat0, lon0, lat, lon), 0.01)
            val back = GeoUtils.bearingDegrees(lat0, lon0, lat, lon)
            val diff = ((back - bearing + 540) % 360) - 180
            assertEquals("bearing $bearing", 0.0, diff, 0.01)
        }
    }

    @Test
    fun northEastOffset() {
        val (latN, lonN) = GeoUtils.destination(lat0, lon0, 0.0, 5.0)
        val (n1, e1) = GeoUtils.offsetNorthEastMeters(lat0, lon0, latN, lonN)
        assertEquals(5.0, n1, 0.01)
        assertEquals(0.0, e1, 0.01)

        val (latE, lonE) = GeoUtils.destination(lat0, lon0, 90.0, 5.0)
        val (n2, e2) = GeoUtils.offsetNorthEastMeters(lat0, lon0, latE, lonE)
        assertEquals(0.0, n2, 0.01)
        assertEquals(5.0, e2, 0.01)
    }

    @Test
    fun arrivalTolerance() {
        val (lat, lon) = GeoUtils.destination(lat0, lon0, 30.0, 0.8)
        assertTrue(GeoUtils.hasArrived(lat0, lon0, lat, lon, 1.0))
        assertTrue(!GeoUtils.hasArrived(lat0, lon0, lat, lon, 0.5))
    }

    @Test
    fun distanceTrackerIgnoresJitter() {
        val tracker = DistanceTracker(minStepMeters = 0.3)
        tracker.update(lat0, lon0)
        val (j1, j2) = GeoUtils.destination(lat0, lon0, 10.0, 0.1)
        tracker.update(j1, j2)
        assertEquals(0.0, tracker.totalMeters, 1e-9)
        val (a, b) = GeoUtils.destination(lat0, lon0, 0.0, 3.0)
        tracker.update(a, b)
        assertEquals(3.0, tracker.totalMeters, 0.01)
    }

    @Test
    fun routeValidation() {
        val (bLat, bLon) = GeoUtils.destination(lat0, lon0, 0.0, 5.0)
        val good = FlightRoute("1", "ok", listOf(
            RoutePoint("A", lat0, lon0, 3f), RoutePoint("B", bLat, bLon, 3f)
        ))
        assertNull(FlightValidator.validateRoute(good))

        val tooClose = good.copy(points = listOf(
            RoutePoint("A", lat0, lon0, 3f), RoutePoint("B", lat0, lon0, 3f)
        ))
        assertNotNull(FlightValidator.validateRoute(tooClose))

        val tooHigh = good.copy(points = good.points.map { it.copy(altitudeMeters = 120f) })
        assertNotNull(FlightValidator.validateRoute(tooHigh))
    }
}
