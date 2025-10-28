package com.example.mymeds.models

/**
 * Estado del pedido
 */
enum class OrderStatus {
    PENDING,      // Pendiente
    CONFIRMED,    // Confirmado
    IN_TRANSIT,   // En camino
    DELIVERED,    // Entregado
    CANCELLED     // Cancelado
}