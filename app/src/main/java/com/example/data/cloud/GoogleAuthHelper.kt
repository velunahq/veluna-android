package com.example.data.cloud

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.BuildConfig
import com.example.R
import com.example.util.SecurityValidators
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

sealed interface GoogleSignInOutcome {
    data class Success(val user: FirebaseUser) : GoogleSignInOutcome
    data object Cancelled : GoogleSignInOutcome
    data class Error(val message: String) : GoogleSignInOutcome
}

object GoogleAuthHelper {
    private const val TAG = "Auth"
    private const val RECENT_LOGIN_WINDOW_MS = 5L * 60L * 1000L

    tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun webClientId(context: Context): String {
        val appContext = context.applicationContext
        val id = runCatching { appContext.getString(R.string.default_web_client_id) }.getOrNull().orEmpty()
        if (id.isBlank() && BuildConfig.DEBUG) {
            Log.e(TAG, "default_web_client_id string resource is missing; check google-services.json")
        }
        return id
    }

    suspend fun attemptAutoSignIn(context: Context): Result<FirebaseUser?> {
        Firebase.auth.currentUser?.let { existing ->
            return Result.success(existing)
        }
        val appContext = context.applicationContext
        val activityRef = WeakReference(context.findActivity())
        if (activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed } == null) {
            return Result.success(null)
        }
        val clientId = webClientId(appContext)
        if (clientId.isBlank()) {
            return Result.failure(IllegalStateException("Google Sign-In client configuration is missing."))
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val result = withContext(Dispatchers.Main.immediate) {
                val activity = activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                    ?: return@withContext null
                val credentialManager = CredentialManager.create(activity)
                credentialManager.getCredential(activity, request)
            } ?: return Result.success(null)
            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCred = GoogleAuthProvider.getCredential(googleCred.idToken, null)
                val authResult = Firebase.auth.signInWithCredential(firebaseCred).await()
                Result.success(authResult.user)
            } else {
                Result.success(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Silent auto sign-in cancelled")
            Result.success(null)
        } catch (_: NoCredentialException) {
            Result.success(null)
        } catch (e: GetCredentialException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Silent auto sign-in unavailable: ${e.type}")
            Result.success(null)
        } catch (e: GoogleIdTokenParsingException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to parse Google ID token on auto sign-in")
            Result.failure(e)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Unexpected auto sign-in error: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    suspend fun signInWithGoogle(context: Context): GoogleSignInOutcome {
        val appContext = context.applicationContext
        val activityRef = WeakReference(context.findActivity())
        if (activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed } == null) {
            return GoogleSignInOutcome.Error("Unable to open Google Sign-In from this screen.")
        }
        val clientId = webClientId(appContext)
        if (clientId.isBlank()) {
            return GoogleSignInOutcome.Error("Google Sign-In is not configured for this build.")
        }

        val signInOption = GetSignInWithGoogleOption.Builder(clientId)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        return try {
            val result = withContext(Dispatchers.Main.immediate) {
                val activity = activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                    ?: return@withContext null
                val credentialManager = CredentialManager.create(activity)
                credentialManager.getCredential(activity, request)
            } ?: return GoogleSignInOutcome.Cancelled
            val credential = result.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return GoogleSignInOutcome.Error("Unsupported credential returned by Google.")
            }

            val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCred = GoogleAuthProvider.getCredential(googleCred.idToken, null)
            val authResult = Firebase.auth.signInWithCredential(firebaseCred).await()
            val user = authResult.user
            if (user != null) {
                GoogleSignInOutcome.Success(user)
            } else {
                GoogleSignInOutcome.Error("Google Sign-In did not return a user account.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Google sign-in cancelled by user")
            GoogleSignInOutcome.Cancelled
        } catch (e: NoCredentialException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "No Google accounts found on device")
            GoogleSignInOutcome.Error("No Google account found on this device. Add one in Android Settings first.")
        } catch (e: GoogleIdTokenParsingException) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Invalid Google ID token response")
            GoogleSignInOutcome.Error("Failed to verify Google ID token. Please try again.")
        } catch (e: GetCredentialException) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Google credential error: ${e.type}")
            GoogleSignInOutcome.Error(
                SecurityValidators.sanitizeErrorText(
                    e.errorMessage?.toString(),
                    "Google Sign-In failed. Please try again."
                )
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Firebase Google authentication failed: ${e.javaClass.simpleName}")
            GoogleSignInOutcome.Error(
                SecurityValidators.safeUserErrorMessage(e, "Google Sign-In failed.")
            )
        }
    }

    fun needsRecentSignIn(user: FirebaseUser, nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastSignIn = user.metadata?.lastSignInTimestamp ?: 0L
        return lastSignIn <= 0L || (nowMs - lastSignIn) > RECENT_LOGIN_WINDOW_MS
    }

    suspend fun reauthenticateWithGoogle(context: Context, user: FirebaseUser): GoogleSignInOutcome {
        val appContext = context.applicationContext
        val activityRef = WeakReference(context.findActivity())
        if (activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed } == null) {
            return GoogleSignInOutcome.Error("Re-authentication requires an active screen.")
        }
        val clientId = webClientId(appContext)
        if (clientId.isBlank()) {
            return GoogleSignInOutcome.Error("Google Sign-In is not configured for this build.")
        }

        val signInOption = GetSignInWithGoogleOption.Builder(clientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()

        return try {
            val result = withContext(Dispatchers.Main.immediate) {
                val activity = activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                    ?: return@withContext null
                val credentialManager = CredentialManager.create(activity)
                credentialManager.getCredential(activity, request)
            } ?: return GoogleSignInOutcome.Cancelled
            val credential = result.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return GoogleSignInOutcome.Error("Unsupported credential returned by Google.")
            }
            val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCred = GoogleAuthProvider.getCredential(googleCred.idToken, null)
            user.reauthenticate(firebaseCred).await()
            GoogleSignInOutcome.Success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (_: GetCredentialCancellationException) {
            GoogleSignInOutcome.Cancelled
        } catch (e: Exception) {
            GoogleSignInOutcome.Error(
                SecurityValidators.safeUserErrorMessage(e, "Re-authentication failed.")
            )
        }
    }

    suspend fun deleteAuthUser(user: FirebaseUser) {
        user.delete().await()
    }

    suspend fun signOut(context: Context, firebaseAuth: FirebaseAuth = Firebase.auth) {
        val appContext = context.applicationContext
        withContext(NonCancellable) {
            withTimeoutOrNull(2_000L) {
                runCatching {
                    CredentialManager.create(appContext).clearCredentialState(ClearCredentialStateRequest())
                }.onFailure { err ->
                    if (BuildConfig.DEBUG) Log.w(TAG, "clearCredentialState skipped: ${err.javaClass.simpleName}")
                }
            }
            firebaseAuth.signOut()
        }
    }
}
