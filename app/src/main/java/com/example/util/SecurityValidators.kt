package com.example.util

import com.google.firebase.FirebaseException
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * Coroutine-safe equivalent of [runCatching] that always rethrows [CancellationException]
 * so structured concurrency and lifecycle cancellation are never silently swallowed.
 */
inline fun <T> suspendRunCatching(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

object SecurityValidators {
    private val YOUTUBE_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val PLAYLIST_ID_REGEX = Regex("^[A-Za-z0-9_-]{2,128}$")
    private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")
    private val URL_IN_TEXT_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val PATH_OR_TOKEN_REGEX = Regex("(/[a-zA-Z0-9_.-]+){3,}|AIza[0-9A-Za-z_-]{10,}|Bearer\\s+\\S+")

    fun isSafeHttpsUrl(raw: String?, maxLength: Int = 2048): Boolean {
        val trimmed = raw?.trim() ?: return false
        if (trimmed.isEmpty() || trimmed.length > maxLength) return false
        if (trimmed.any { it.isISOControl() || it.isWhitespace() }) return false
        val parsed = trimmed.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.host.isNotBlank()
    }

    fun sanitizeHttpsUrl(raw: String?, maxLength: Int = 500): String? {
        val trimmed = raw?.trim() ?: return null
        return if (isSafeHttpsUrl(trimmed, maxLength)) trimmed else null
    }

    fun isValidYouTubeId(id: String?): Boolean {
        val trimmed = id?.trim() ?: return false
        return YOUTUBE_ID_REGEX.matches(trimmed)
    }

    fun isValidPlaylistId(id: String?): Boolean {
        val trimmed = id?.trim() ?: return false
        return PLAYLIST_ID_REGEX.matches(trimmed)
    }

    fun isValidHexColor(hex: String?): Boolean {
        val trimmed = hex?.trim() ?: return false
        return HEX_COLOR_REGEX.matches(trimmed)
    }

    fun isHostOrSubdomain(host: String, rootDomain: String): Boolean {
        val normalizedHost = host.trim().lowercase().trimEnd('.')
        val normalizedRoot = rootDomain.trim().lowercase().trimStart('.')
        return normalizedHost == normalizedRoot || normalizedHost.endsWith(".$normalizedRoot")
    }

    fun isYouTubeStreamHost(host: String): Boolean {
        return isHostOrSubdomain(host, "googlevideo.com") || isHostOrSubdomain(host, "youtube.com")
    }

    fun isKnownPlaybackStreamHost(host: String): Boolean {
        val normalized = host.trim().lowercase().trimEnd('.')
        return isYouTubeStreamHost(normalized) || normalized in KNOWN_MIRROR_STREAM_HOSTS
    }

    private val KNOWN_MIRROR_STREAM_HOSTS = setOf(
        "pipedapi.kavin.rocks",
        "pipedapi.adminforge.de",
        "api.piped.private.coffee",
        "inv.nadeko.net",
        "yewtu.be"
    )

    fun sha256Hex(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun safeUserErrorMessage(
        throwable: Throwable?,
        fallback: String = "Something went wrong. Please try again."
    ): String {
        return when (throwable) {
            null -> fallback
            is UnknownHostException -> "Unable to reach server. Check your internet connection."
            is SocketTimeoutException -> "Connection timed out. Please try again."
            is IOException -> "Network error occurred. Please try again."
            is FirebaseFirestoreException -> when (throwable.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "You do not have permission to access this data."
                FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                    "Session expired. Please sign in with Google again."
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                    "Cloud service temporarily unavailable. Changes are saved locally."
                else -> fallback
            }
            is FirebaseException -> "Account service unavailable. Please try again."
            else -> sanitizeErrorText(throwable.message, fallback)
        }
    }

    fun sanitizeErrorText(rawMessage: String?, fallback: String = "Operation failed. Please try again."): String {
        val cleaned = rawMessage?.trim()?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        if (cleaned.isEmpty() || cleaned.length > 140) return fallback
        if (URL_IN_TEXT_REGEX.containsMatchIn(cleaned) || PATH_OR_TOKEN_REGEX.containsMatchIn(cleaned)) {
            return fallback
        }
        if (cleaned.contains("com.example.") || cleaned.contains("Exception") || cleaned.contains("java.")) {
            return fallback
        }
        return cleaned
    }
}
