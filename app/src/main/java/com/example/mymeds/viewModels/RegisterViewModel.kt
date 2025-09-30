package com.example.mymeds.viewModels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymeds.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody // <-- NEW IMPORT for deprecated fix
import java.io.File

class RegisterViewModel : ViewModel() {

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
        profilePictureUri: Uri?,
        idPictureUri: Uri?,
        onResult: (Boolean, String) -> Unit
    ) {
        if (idPictureUri == null) {
            onResult(false, "The ID picture is mandatory for registration.")
            return
        }

        viewModelScope.launch {
            try {
                val contentResolver = context.contentResolver

                // --- Helper function for file conversion (can be local or private) ---
                fun uriToMultipart(uri: Uri?, paramName: String, fileName: String = "image.jpg"): MultipartBody.Part? {
                    uri ?: return null
                    val tempFile = File(context.cacheDir, "${paramName}_${System.currentTimeMillis()}.jpg")
                    contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: return null

                    val requestBody = tempFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
                    return MultipartBody.Part.createFormData(paramName, fileName, requestBody)
                }

                // --- 2. Prepare Multipart Parts (Executed on IO thread) ---
                // Explicitly define the return type (Pair) to fix "Unresolved reference 'first/second'"
                val uriToMultipart: Pair<MultipartBody.Part?, MultipartBody.Part?> = withContext(Dispatchers.IO) {
                    val profilePicturePart = uriToMultipart(profilePictureUri, "profile_picture")
                    val idPicturePart = uriToMultipart(idPictureUri, "id_picture")

                    if (idPicturePart == null) {
                        // If mandatory ID picture fails, notify and return a null Pair to exit cleanly
                        onResult(false, "Failed to prepare ID picture for upload.")
                        return@withContext Pair(null, null)
                    }

                    // Successful return of the parts
                    Pair(profilePicturePart, idPicturePart)
                }

                // If I/O failed and returned null Pair, exit the launch block
                val idPicturePart = uriToMultipart.second
                if (idPicturePart == null) return@launch // Exit if the mandatory part is missing

                val profilePicturePart = uriToMultipart.first


                // --- 3. Prepare String Data Map (Fixes Deprecation Warnings) ---
                // Use string.toRequestBody() extension function
                val textRequestBodyMap = mapOf(
                    "name" to name.toRequestBody("text/plain".toMediaTypeOrNull()),
                    "email" to email.toRequestBody("text/plain".toMediaTypeOrNull()),
                    "password" to password.toRequestBody("text/plain".toMediaTypeOrNull()),
                    "phone_number" to (phoneNumber ?: "").toRequestBody("text/plain".toMediaTypeOrNull()),
                    "address" to address.toRequestBody("text/plain".toMediaTypeOrNull()),
                    "city" to city.toRequestBody("text/plain".toMediaTypeOrNull()),
                    "state" to state.toRequestBody("text/plain".toMediaTypeOrNull()),
                    "zip_code" to zipCode.toRequestBody("text/plain".toMediaTypeOrNull())
                )

                // --- 4. Send the request to the backend ---
                val response = RetrofitClient.instance.register(
                    data = textRequestBodyMap,
                    profilePicture = profilePicturePart,
                    idPicture = idPicturePart // Now guaranteed non-null by the check above
                )

                // --- 5. Handle the response ---
                if (response.isSuccessful) {
                    onResult(true, "Registration successful!")
                } else {
                    val errorMessage = response.errorBody()?.string() ?: "Registration failed with status: ${response.code()}"
                    onResult(false, errorMessage)
                }
            } catch (e: Exception) {
                onResult(false, e.message ?: "An unknown error occurred during registration.")
            }
        }
    }
}