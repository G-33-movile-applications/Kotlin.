package com.example.mymeds.models

data class UserProfileResponse(
    val fullName: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val address: String = ""
)