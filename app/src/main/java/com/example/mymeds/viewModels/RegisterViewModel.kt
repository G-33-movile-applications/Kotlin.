package com.example.mymeds.viewModels

import android.content.Context
import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*

class RegisterViewModel : ViewModel() {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    fun register(
        context: Context,
        fullName: String,
        email: String,
        password: String,
        phoneNumber: String,
        address: String,
        city: String,
        department: String,
        zipCode: String,
        onResult: (Boolean, String) -> Unit
    ) {
        // 1. Crear usuario en Firebase Auth
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { authTask ->
                if (authTask.isSuccessful) {
                    val userId = auth.currentUser?.uid ?: return@addOnCompleteListener

                    // 2. Guardar datos en Firestore
                    val userMap = hashMapOf(
                        "uid" to userId,
                        "fullName" to fullName,
                        "email" to email,
                        "phoneNumber" to phoneNumber,
                        "address" to address,
                        "city" to city,
                        "department" to department,
                        "zipCode" to zipCode,
                        "createdAt" to Date()
                    )

                    firestore.collection("users").document(userId)
                        .set(userMap)
                        .addOnSuccessListener {
                            onResult(true, "Usuario registrado correctamente")
                        }
                        .addOnFailureListener { e ->
                            onResult(false, "Error al guardar en Firestore: ${e.message}")
                        }

                } else {
                    onResult(false, authTask.exception?.message ?: "Error al registrar usuario")
                }
            }
    }
}
