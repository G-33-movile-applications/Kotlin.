package com.example.mymeds.viewModels

import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth

class LoginViewModel : ViewModel() {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    fun login(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // ✅ Usuario autenticado correctamente
                    val user = auth.currentUser
                    onResult(true, user?.uid) // puedes devolver el UID o token
                } else {
                    // ❌ Falló el login
                    onResult(false, task.exception?.message ?: "Login failed")
                }
            }
    }

    fun register(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    onResult(true, user?.uid)
                } else {
                    onResult(false, task.exception?.message ?: "Register failed")
                }
            }
    }

    fun logout() {
        auth.signOut()
    }
}
