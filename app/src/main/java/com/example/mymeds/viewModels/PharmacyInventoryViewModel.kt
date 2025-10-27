package com.example.mymeds.viewModels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.mymeds.models.Medication
import com.google.firebase.firestore.FirebaseFirestore

class PharmacyInventoryViewModel : ViewModel() {

    private val db = FirebaseFirestore.getInstance()

    private val _medications = MutableLiveData<List<Medication>>()
    val medications: LiveData<List<Medication>> = _medications

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    fun loadAllMedications() {
        _isLoading.value = true
        _errorMessage.value = null

        db.collection("medicamentosGlobales")
            .get()
            .addOnSuccessListener { documents ->
                val medicationsList = documents.mapNotNull { document ->
                    try {
                        val contraindicacionesList = document.get("contraindicaciones") as? List<*>
                        val contraindicaciones = contraindicacionesList?.mapNotNull { it as? String } ?: emptyList()

                        Medication(
                            id = document.id,
                            nombre = document.getString("nombre") ?: "",
                            descripcion = document.getString("descripcion") ?: "",
                            principioActivo = document.getString("principioActivo") ?: "",
                            presentacion = document.getString("presentacion") ?: "",
                            laboratorio = document.getString("laboratorio") ?: "",
                            imagenUrl = document.getString("imagenUrl") ?: "",
                            contraindicaciones = contraindicaciones
                        )
                    } catch (e: Exception) {
                        Log.e("PharmacyInventoryVM", "Error parsing medication: ${e.message}")
                        null
                    }
                }
                _medications.value = medicationsList
                _isLoading.value = false
                Log.d("PharmacyInventoryVM", "Loaded ${medicationsList.size} medications")
            }
            .addOnFailureListener { exception ->
                Log.e("PharmacyInventoryVM", "Error loading medications: ${exception.message}")
                _errorMessage.value = "Error al cargar medicamentos: ${exception.message}"
                _isLoading.value = false
            }
    }
}