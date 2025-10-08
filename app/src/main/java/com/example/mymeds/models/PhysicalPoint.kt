package com.example.mymeds.models

data class PhysicalPoint(
    val name: String = "",
    val address: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val chain: String = "",
    val openingHours: List<String> = emptyList(),
    val openingDays: List<String> = emptyList()
)