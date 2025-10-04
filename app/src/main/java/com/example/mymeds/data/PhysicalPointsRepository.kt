
package com.example.mymeds.data

import com.example.mymeds.models.PhysicalPoint
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Repository for managing the retrieval of Physical Points data from Firebase.
 * It encapsulates the data source access logic (Firestore).
 */
class PhysicalPointsRepository {

    // "physicalPoints" is the name of our collection in the Firestore database.
    private val pointsCollection = FirebaseFirestore.getInstance().collection("physicalPoints")

    /**
     * Fetches all physical points from the Firestore collection.
     * Uses coroutines to perform the network operation asynchronously.
     *
     * @return Result<List<PhysicalPoint>> which contains the list on success, or an exception on failure.
     */
    suspend fun getAllPhysicalPoints(): Result<List<PhysicalPoint>> {
        return try {
            val documents = pointsCollection.get().await()
            // .toObjects() automatically converts each document into a PhysicalPoint object.
            val pointsList = documents.toObjects(PhysicalPoint::class.java)
            Result.success(pointsList)
        } catch (e: Exception) {
            // If an error occurs (e.g., no internet connection), we return it.
            Result.failure(e)
        }
    }
}
