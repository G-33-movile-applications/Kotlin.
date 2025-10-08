package com.example.mymeds.data

import android.util.Log
import com.example.mymeds.models.PhysicalPoint
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class PhysicalPointsRepository {

    private val pointsCollection = FirebaseFirestore.getInstance().collection("puntos_fisicos")

    suspend fun getAllPhysicalPoints(): Result<List<PhysicalPoint>> {
        return try {
            Log.d("Repository", "Fetching from Firestore collection: puntos_fisicos")

            val documents = pointsCollection.get().await()

            Log.d("Repository", "Documents received: ${documents.size()}")

            val pointsList = documents.documents.mapNotNull { doc ->
                try {
                    // Extraer los campos exactamente como están en Firestore
                    val nombre = doc.getString("nombre") ?: ""
                    val direccion = doc.getString("direccion") ?: ""
                    val latitud = doc.getDouble("latitud") ?: 0.0
                    val longitud = doc.getDouble("longitud") ?: 0.0
                    val cadena = doc.getString("cadena") ?: ""

                    // Extraer listas de forma segura
                    val horarioAtencion = (doc.get("horarioAtencion") as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?: emptyList()

                    val diasAtencion = (doc.get("diasAtencion") as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?: emptyList()

                    Log.d("Repository", "Processing: $nombre at ($latitud, $longitud)")

                    PhysicalPoint(
                        name = nombre,
                        address = direccion,
                        latitude = latitud,
                        longitude = longitud,
                        chain = cadena,
                        openingHours = horarioAtencion,
                        openingDays = diasAtencion
                    )
                } catch (e: Exception) {
                    Log.e("Repository", "Error mapping document ${doc.id}: ${e.message}", e)
                    null
                }
            }

            Log.d("Repository", "Successfully converted ${pointsList.size} points")
            pointsList.forEachIndexed { index, point ->
                Log.d("Repository", "Point $index: ${point.name} at (${point.latitude}, ${point.longitude})")
            }

            Result.success(pointsList)
        } catch (e: Exception) {
            Log.e("Repository", "ERROR fetching points: ${e.message}", e)
            Result.failure(e)
        }
    }
}