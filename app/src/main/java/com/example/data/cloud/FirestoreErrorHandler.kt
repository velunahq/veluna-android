package com.example.data.cloud

import android.util.Log
import com.example.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.security.MessageDigest

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(exception: Throwable, operationType: OperationType, path: String?): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val uid = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
    val uidHash = uid?.let { sha256Prefix(it) }
    val codeName = (rootError as? FirebaseFirestoreException)?.code?.name ?: rootError.javaClass.simpleName

    val errorInfoJson = JSONObject().apply {
        put("code", codeName)
        put("operationType", operationType.value)
        put("path", path)
        if (uidHash != null) put("uidHash", uidHash)
    }

    val jsonString = errorInfoJson.toString()
    if (BuildConfig.DEBUG) {
        Log.e("FirestoreError", "Firestore Error: $jsonString")
    }
    return jsonString
}

private fun sha256Prefix(raw: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.take(6).joinToString("") { "%02x".format(it) }
}
