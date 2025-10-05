package com.example.mymeds.viewModels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymeds.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.io.path.exists

data class UserProfile(
    val fullName: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val address: String = ""
)

class ProfileViewModel : ViewModel() {

    // --- Firebase Instances ---
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    // --- LiveData remains the same ---
    private val _profile = MutableLiveData<UserProfile?>()
    val profile: LiveData<UserProfile?> = _profile

    private val _message = MutableLiveData<String>()
    val message: LiveData<String> = _message

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    // This 'loadProfile' function no longer needs the email,
    // as it can get the current user directly from Firebase Auth.
    fun loadProfile() {
        viewModelScope.launch {
            _loading.postValue(true)
            try {
                // 1. Get the current user's ID from Firebase Auth
                val userId = auth.currentUser?.uid
                if (userId == null) {
                    _message.postValue("Error: No user is currently logged in.")
                    _loading.postValue(false)
                    return@launch
                }

                Log.d("MyMedsApp_Firebase", "Fetching profile for user ID: $userId")

                // 2. Fetch the user document from Firestore using the user ID
                val documentSnapshot = firestore.collection("usuarios").document(userId).get().await()

                if (documentSnapshot.exists()) {
                    // 3. Convert the Firestore document to our UserProfile data class
                    val user = documentSnapshot.toObject(UserProfile::class.java)
                    _profile.postValue(user)
                    Log.d("MyMedsApp_Firebase", "SUCCESS! Profile data loaded: $user")
                } else {
                    _message.postValue("Error: User data not found in Firestore.")
                    Log.e("MyMedsApp_Firebase", "FAILURE! No document found for user ID: $userId")
                }

            } catch (e: Exception) {
                _message.postValue("An error occurred: ${e.message}")
                Log.e("MyMedsApp_Firebase", "EXCEPTION! Error fetching profile", e)
            } finally {
                _loading.postValue(false)
            }
        }
    }
}
