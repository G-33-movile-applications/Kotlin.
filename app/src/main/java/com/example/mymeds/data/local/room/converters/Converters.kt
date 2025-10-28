package com.example.mymeds.data.local.room.converters

import androidx.room.TypeConverter
import com.example.mymeds.models.DeliveryType
import com.example.mymeds.models.OrderStatus

/**
 * TypeConverter para Room
 * Convierte enums a/desde base de datos
 */
class Converters {

    @TypeConverter
    fun fromDeliveryType(value: DeliveryType): String = value.name

    @TypeConverter
    fun toDeliveryType(value: String): DeliveryType = DeliveryType.valueOf(value)

    @TypeConverter
    fun fromOrderStatus(value: OrderStatus): String = value.name

    @TypeConverter
    fun toOrderStatus(value: String): OrderStatus = OrderStatus.valueOf(value)
}