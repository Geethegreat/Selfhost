package com.example.selfhost

data class UserModel(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val plan: String = "free",
    val createdAt: Long = System.currentTimeMillis()
)
