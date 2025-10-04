// File: com/example/mymeds/viewModels/MapViewModel.kt
package com.example.mymeds.viewModels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymeds.data.PhysicalPointsRepository
import com.example.mymeds.models.PhysicalPoint
import kotlinx.coroutines.launch

/**
 * ViewModel for the Map Activity.
 * It is responsible for fetching data and preparing the state for the UI.
 */
class MapViewModel : ViewModel() {

    private val repository = PhysicalPointsRepository()

    // LiveData to expose the list of physical points to the Activity.
    private val _physicalPoints = MutableLiveData<List<PhysicalPoint>>()
    val physicalPoints: LiveData<List<PhysicalPoint>> = _physicalPoints

    // LiveData to communicate the loading state (loading, error, etc.).
    private val _loadingState = MutableLiveData<LoadingState>()
    val loadingState: LiveData<LoadingState> = _loadingState

    init {
        // When the ViewModel is created, start loading the points.
        loadPhysicalPoints()
    }

    /**
     * Initiates the coroutine to fetch data from the repository.
     */
    private fun loadPhysicalPoints() {
        _loadingState.value = LoadingState.LOADING
        viewModelScope.launch {
            val result = repository.getAllPhysicalPoints()
            result.onSuccess { pointsList ->
                _physicalPoints.value = pointsList
                _loadingState.value = LoadingState.SUCCESS
            }.onFailure {
                // Here you could log the error: Log.e("MapViewModel", "Error fetching points", it)
                _loadingState.value = LoadingState.ERROR
            }
        }
    }
}

/**
 * Enum to represent the different states of data loading.
 */
enum class LoadingState {
    LOADING,
    SUCCESS,
    ERROR
}
