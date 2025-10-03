package com.example.mymeds.viewModels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage

class RegisterViewModel : ViewModel() {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()

    fun register(
        context: Context,
        name: String,
        email: String,
        password: String,
        phoneNumber: String?,
        address: String,
        city: String,
        state: String,
        zipCode: String,
        profilePictureUri: Uri?, // 👈 opcional
        idPictureUri: Uri?,      // 👈 obligatoria
        onResult: (Boolean, String) -> Unit
    ) {
        if (idPictureUri == null) {
            onResult(false, "The ID picture is mandatory for registration.")
            return
        }

        // 1. Crear usuario en Firebase Authentication
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { authTask ->
                if (authTask.isSuccessful) {
                    val userId = auth.currentUser?.uid ?: return@addOnCompleteListener

                    // 2. Subir imágenes al Storage
                    uploadImages(
                        userId,
                        profilePictureUri,
                        idPictureUri,
                        onUploadFinished = { profileUrl, idUrl ->
                            if (idUrl == null) {
                                onResult(false, "Failed to upload ID picture")
                                return@uploadImages
                            }

                            // 3. Guardar datos en Firestore
                            saveUserToFirestore(
                                userId,
                                name,
                                email,
                                phoneNumber,
                                address,
                                city,
                                state,
                                zipCode,
                                profileUrl,
                                idUrl,
                                onResult
                            )
                        }
                    )

                } else {
                    onResult(false, authTask.exception?.message ?: "Registration failed")
                }
            }
    }

    private fun uploadImages(
        userId: String,
        profilePictureUri: Uri?,
        idPictureUri: Uri,
        onUploadFinished: (profileUrl: String?, idUrl: String?) -> Unit
    ) {
        var profileUrl: String? = null
        var idUrl: String? = null

        // Subir perfil si existe
        if (profilePictureUri != null) {
            val refProfile = storage.reference.child("users/$userId/profile.jpg")
            refProfile.putFile(profilePictureUri).continueWithTask {
                refProfile.downloadUrl
            }.addOnSuccessListener { uri ->
                profileUrl = uri.toString()

                // Cuando ya tengamos la de perfil e ID, avisamos
                if (idUrl != null) {
                    onUploadFinished(profileUrl, idUrl)
                }
            }.addOnFailureListener {
                // si falla, igual seguimos porque es opcional
                if (idUrl != null) {
                    onUploadFinished(null, idUrl)
                }
            }
        }

        // Subir ID (obligatoria)
        val refId = storage.reference.child("users/$userId/id.jpg")
        refId.putFile(idPictureUri).continueWithTask {
            refId.downloadUrl
        }.addOnSuccessListener { uri ->
            idUrl = uri.toString()

            // Cuando tengamos ID y (si aplica) perfil
            onUploadFinished(profileUrl, idUrl)
        }.addOnFailureListener {
            onUploadFinished(profileUrl, null)
        }
    }

    private fun saveUserToFirestore(
        userId: String,
        name: String,
        email: String,
        phoneNumber: String?,
        address: String,
        city: String,
        state: String,
        zipCode: String,
        profilePictureUrl: String?,
        idPictureUrl: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val userMap = hashMapOf(
            "name" to name,
            "email" to email,
            "phone_number" to (phoneNumber ?: ""),
            "address" to address,
            "city" to city,
            "state" to state,
            "zip_code" to zipCode,
            "profile_picture" to profilePictureUrl,
            "id_picture" to idPictureUrl
        )

        firestore.collection("users").document(userId)
            .set(userMap)
            .addOnSuccessListener {
                onResult(true, "Registration successful!")
            }
            .addOnFailureListener { e ->
                onResult(false, "Failed to save user data: ${e.message}")
            }
    }
}
