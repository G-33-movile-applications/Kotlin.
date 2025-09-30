package com.example.mymeds.viewModels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymeds.models.LoginRequest
import com.example.mymeds.remote.RetrofitClient
import kotlinx.coroutines.launch

class LoginViewModel : ViewModel() {

    fun login(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val response = RetrofitClient.instance.login(LoginRequest(email, password))
                if (response.isSuccessful) {
                    // Devuelvo el token si la API lo envía
                    onResult(true, response.body()?.token)
                } else {
                    onResult(false, response.errorBody()?.string() ?: "Login failed")
                }
            } catch (e: Exception) {
                onResult(false, e.message ?: "Unknown error")
            }
        }
    }
}
