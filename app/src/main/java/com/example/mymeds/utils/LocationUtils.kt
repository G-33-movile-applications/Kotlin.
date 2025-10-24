package com.example.mymeds.utils
import com.example.mymeds.models.PhysicalPoint
import com.google.android.gms.maps.model.LatLng
import kotlin.math.*

object LocationUtils {

    fun calculateDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val earthRadius = 6371000.0 // Radio de la Tierra en metros

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }


    fun findNearestPharmacies(
        userLocation: LatLng,
        pharmacies: List<PhysicalPoint>,
        count: Int = 3
    ): List<Pair<PhysicalPoint, Double>> {
        return pharmacies
            .map { pharmacy ->
                val distance = calculateDistance(
                    userLocation.latitude,
                    userLocation.longitude,
                    pharmacy.location.latitude,
                    pharmacy.location.longitude
                )
                Pair(pharmacy, distance)
            }
            .sortedBy { it.second }
            .take(count)
    }


    fun formatDistance(distanceInMeters: Double): String {
        return when {
            distanceInMeters < 1000 -> "${distanceInMeters.toInt()} m"
            else -> String.format("%.2f km", distanceInMeters / 1000)
        }
    }
}