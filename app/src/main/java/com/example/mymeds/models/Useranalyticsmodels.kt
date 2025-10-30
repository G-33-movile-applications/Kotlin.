package com.example.mymeds.models

import java.util.Date

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MODELOS DE ANALÍTICAS - BQT2 IMPLEMENTATION
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Modelo principal de analíticas del usuario
 */
data class UserAnalytics(
    // Estadísticas de pedidos
    val totalOrders: Int = 0,
    val deliveryOrders: Int = 0,
    val pickupOrders: Int = 0,
    val deliveryPercentage: Float = 0f,
    val pickupPercentage: Float = 0f,
    val preferredMode: DeliveryMode = DeliveryMode.UNKNOWN,

    // BQT2 - Question 1: Total medication requests
    val totalMedicationRequests: Int = 0,
    val averageMedicationsPerOrder: Float = 0f,

    // BQT2 - Question 2: Last claim timing
    val lastClaimDate: Date? = null,
    val daysSinceLastClaim: Int = -1,
    val hasEverClaimed: Boolean = false,

    // Estadísticas adicionales
    val totalSpent: Double = 0.0,
    val averageOrderValue: Double = 0.0,
    val mostFrequentPharmacy: String = "",
    val activeOrders: Int = 0,
    val completedOrders: Int = 0,
    val cancelledOrders: Int = 0
)

/**
 * Modo de entrega preferido
 */
enum class DeliveryMode {
    DELIVERY,
    PICKUP,
    UNKNOWN;

    fun getDisplayName(): String = when (this) {
        DELIVERY -> "Domicilio"
        PICKUP -> "Recoger en tienda"
        UNKNOWN -> "Sin preferencia"
    }

    fun getIcon(): String = when (this) {
        DELIVERY -> "🚚"
        PICKUP -> "🏪"
        UNKNOWN -> "❓"
    }
}

/**
 * Modelo de pedido simplificado para analíticas
 */
data class OrderAnalytics(
    val orderId: String = "",
    val deliveryMode: String = "",
    val totalAmount: Double = 0.0,
    val medicationCount: Int = 0,
    val pharmacyName: String = "",
    val status: String = "",
    val orderDate: Date? = null,
    val claimedDate: Date? = null
)

/**
 * Resultado de cálculo de tiempo desde último reclamo
 */
data class ClaimTimingResult(
    val lastClaimDate: Date?,
    val daysSinceLastClaim: Int,
    val hasEverClaimed: Boolean,
    val timingDescription: String
) {
    fun getTimingDisplay(): String = when {
        !hasEverClaimed -> "Nunca has reclamado medicamentos"
        daysSinceLastClaim == 0 -> "Reclamaste hoy"
        daysSinceLastClaim == 1 -> "Reclamaste hace 1 día"
        daysSinceLastClaim < 7 -> "Reclamaste hace $daysSinceLastClaim días"
        daysSinceLastClaim < 30 -> "Reclamaste hace ${daysSinceLastClaim / 7} semanas"
        daysSinceLastClaim < 365 -> "Reclamaste hace ${daysSinceLastClaim / 30} meses"
        else -> "Reclamaste hace más de 1 año"
    }
}

/**
 * Estado de carga de analíticas
 */
sealed class AnalyticsUiState {
    object Loading : AnalyticsUiState()
    data class Success(val analytics: UserAnalytics) : AnalyticsUiState()
    data class Error(val message: String) : AnalyticsUiState()
}