package com.example.mymeds.viewModels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymeds.models.PasswordResetRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PasswordResetViewModel : ViewModel() {

    fun sendPasswordReset(
        request: PasswordResetRequest,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                // 🔹 Simulación mientras se conecta a Firebase
                delay(1500) // Simula red

                if (request.email.contains("@")) {
                    // Aquí luego iría FirebaseAuth.getInstance().sendPasswordResetEmail(request.email)
                    onResult(true, "Se envió un enlace de recuperación a ${request.email}")
                } else {
                    onResult(false, "Correo inválido")
                }
            } catch (e: Exception) {
                onResult(false, e.message ?: "Error desconocido")
            }
        }
    }
}
