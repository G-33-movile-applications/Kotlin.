package com.example.mymeds

import android.app.Application
import com.example.mymeds.data.local.room.AppDatabase
import com.example.mymeds.repository.GlobalMedicationRepository
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class MyMedsApplication : Application() {
    private val database by lazy { AppDatabase.getDatabase(this) }

    private val firestore by lazy { Firebase.firestore }

    val globalMedicationRepository by lazy {
        GlobalMedicationRepository(firestore, database.globalMedicationDao())
    }
}
