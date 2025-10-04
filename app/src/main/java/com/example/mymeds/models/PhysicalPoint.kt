// File: com/example/mymeds/models/PhysicalPoint.kt
package com.example.mymeds.models

import com.google.firebase.firestore.GeoPoint // Recommended for location data

/**
 * Represents a physical point like a pharmacy or distribution center.
 * The property names must match the field names in the Firestore document.
 * An empty constructor is required for Firestore's automatic data mapping.
 *
 * @property name The name of the establishment (e.g., "Central Pharmacy").
 * @property address The full address of the location.
 * @property chain The name of the chain it belongs to (e.g., "Walgreens").
 * @property location GeoPoint containing latitude and longitude for Firestore queries.
 * @property openingHours A list of opening hour ranges.
 * @property openingDays A list of days of the week it is open.
 */
data class PhysicalPoint(
    val name: String = "",
    val address: String = "",
    val chain: String = "",
    val location: GeoPoint? = null,
    val openingHours: List<String> = emptyList(),
    val openingDays: List<String> = emptyList()
)
