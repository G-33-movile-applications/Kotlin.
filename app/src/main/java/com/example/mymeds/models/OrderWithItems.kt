package com.example.mymeds.models

import androidx.room.Embedded
import androidx.room.Relation
import com.example.mymeds.data.local.room.entities.OrderEntity
import com.example.mymeds.data.local.room.entitites.OrderItemEntity

/**
 * Data class para JOIN de Order con sus Items
 * Representa un pedido completo con todos sus ítems
 */
data class OrderWithItems(
    @Embedded val order: OrderEntity,
    @Relation(
        parentColumn = "orderId",
        entityColumn = "orderId"
    )
    val items: List<OrderItemEntity>
)