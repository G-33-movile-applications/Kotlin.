package com.example.mymeds.viewModels

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymeds.data.PhysicalPointsRepository
import com.example.mymeds.models.PhysicalPoint
import kotlinx.coroutines.launch

class MapViewModel : ViewModel() {

    private val repository = PhysicalPointsRepository()

    private val _physicalPoints = MutableLiveData<List<PhysicalPoint>>()
    val physicalPoints: LiveData<List<PhysicalPoint>> = _physicalPoints

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
                    pointsList.forEachIndexed { index, point ->
                        Log.d("MapViewModel", "Point $index: ${point.name} (${point.latitude}, ${point.longitude})")
                    }
                    _physicalPoints.value = pointsList
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
}

enum class LoadingState {
    LOADING,
    SUCCESS,
    ERROR
}