package com.example.mymeds.viewModels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymeds.data.PhysicalPointsRepository
import com.example.mymeds.models.PhysicalPoint
import com.example.mymeds.utils.LocationUtils
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.launch

class MapViewModel : ViewModel() {

    private val repository = PhysicalPointsRepository()

    private val _physicalPoints = MutableLiveData<List<PhysicalPoint>>()
    val physicalPoints: LiveData<List<PhysicalPoint>> = _physicalPoints

    private val _nearestPharmacies = MutableLiveData<List<Pair<PhysicalPoint, Double>>>()
    val nearestPharmacies: LiveData<List<Pair<PhysicalPoint, Double>>> = _nearestPharmacies

    private val _userLocation = MutableLiveData<LatLng?>()
    val userLocation: LiveData<LatLng?> = _userLocation

    private val _loadingState = MutableLiveData<LoadingState>()
    val loadingState: LiveData<LoadingState> = _loadingState

    init {
        Log.d("MapViewModel", "ViewModel initialized")
        loadPhysicalPoints()
    }

    private fun loadPhysicalPoints() {
        Log.d("MapViewModel", "Starting to load physical points")
        _loadingState.value = LoadingState.LOADING

        viewModelScope.launch {
            try {
                val result = repository.getAllPhysicalPoints()

                result.onSuccess { pointsList ->
                    Log.d("MapViewModel", "✓ Success! Loaded ${pointsList.size} points")
                    _physicalPoints.value = pointsList

                    // Si ya tenemos la ubicación del usuario, calcular las más cercanas
                    _userLocation.value?.let { location ->
                        updateNearestPharmacies(location, pointsList)
                    }

                    _loadingState.value = LoadingState.SUCCESS
                }.onFailure { exception ->
                    Log.e("MapViewModel", "✗ Error loading points: ${exception.message}", exception)
                    _loadingState.value = LoadingState.ERROR
                }
            } catch (e: Exception) {
                Log.e("MapViewModel", "✗ Exception: ${e.message}", e)
                _loadingState.value = LoadingState.ERROR
            }
        }
    }

    fun updateUserLocation(location: LatLng) {
        Log.d("MapViewModel", "User location updated: ${location.latitude}, ${location.longitude}")
        _userLocation.value = location

        // Calcular las farmacias más cercanas
        _physicalPoints.value?.let { pharmacies ->
            updateNearestPharmacies(location, pharmacies)
        }
    }

    private fun updateNearestPharmacies(userLocation: LatLng, pharmacies: List<PhysicalPoint>) {
        val nearest = LocationUtils.findNearestPharmacies(userLocation, pharmacies, 3)
        _nearestPharmacies.value = nearest

        Log.d("MapViewModel", "=== Top 3 Nearest Pharmacies ===")
        nearest.forEachIndexed { index, (pharmacy, distance) ->
            Log.d("MapViewModel", "${index + 1}. ${pharmacy.name}")
            Log.d("MapViewModel", "   Distance: ${LocationUtils.formatDistance(distance)}")
            Log.d("MapViewModel", "   Address: ${pharmacy.address}")
        }
    }
}

enum class LoadingState {
    LOADING,
    SUCCESS,
    ERROR
}