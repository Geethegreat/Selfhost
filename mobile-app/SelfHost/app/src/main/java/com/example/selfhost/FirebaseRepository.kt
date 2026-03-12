package com.example.selfhost

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object FirebaseRepository {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    // ── Auth ──────────────────────────────────────────────────────────────────

    val currentFirebaseUser: FirebaseUser? get() = auth.currentUser

    val isSignedIn: Boolean get() = auth.currentUser != null

    suspend fun signInWithCredential(idToken: String): Result<UserModel> {
        return try {
            val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
            val result = auth.signInWithCredential(credential).await()
            val firebaseUser = result.user ?: error("No user after sign in")
            val user = getOrCreateUser(firebaseUser)
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut() {
        auth.signOut()
    }

    // ── Firestore ─────────────────────────────────────────────────────────────

    private suspend fun getOrCreateUser(firebaseUser: FirebaseUser): UserModel {
        val ref = db.collection("users").document(firebaseUser.uid)
        val snapshot = ref.get().await()
        return if (snapshot.exists()) {
            snapshot.toObject(UserModel::class.java) ?: buildUserModel(firebaseUser)
        } else {
            val newUser = buildUserModel(firebaseUser)
            ref.set(newUser).await()
            newUser
        }
    }

    private fun buildUserModel(firebaseUser: FirebaseUser) = UserModel(
        uid = firebaseUser.uid,
        name = firebaseUser.displayName ?: "User",
        email = firebaseUser.email ?: "",
        photoUrl = firebaseUser.photoUrl?.toString() ?: "",
        plan = "free",
        createdAt = System.currentTimeMillis()
    )

    suspend fun getUser(uid: String): UserModel? {
        return try {
            val snapshot = db.collection("users").document(uid).get().await()
            snapshot.toObject(UserModel::class.java)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun updatePlan(uid: String, plan: String): Result<Unit> {
        return try {
            db.collection("users").document(uid).update("plan", plan).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveSlug(uid: String, slug: String): Result<Unit> {
        return try {
            val data = mapOf(
                "createdAt" to System.currentTimeMillis(),
                "isActive" to true
            )
            db.collection("users").document(uid)
                .collection("slugs").document(slug)
                .set(data).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deactivateSlug(uid: String, slug: String) {
        try {
            db.collection("users").document(uid)
                .collection("slugs").document(slug)
                .update("isActive", false).await()
        } catch (_: Exception) {}
    }
}
