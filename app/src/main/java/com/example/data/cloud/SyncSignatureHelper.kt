package com.example.data.cloud

import com.example.data.local.VelunaUserPrefs
import com.example.model.SongItem
import com.example.model.TasteProfile
import com.example.model.TasteSeed
import com.example.util.SecurityValidators
import java.security.MessageDigest

internal object SyncSignatureHelper {

    fun likedSongSignature(song: SongItem): String {
        val raw = listOf(
            song.videoId.trim(),
            song.title.trim(),
            song.artists.trim(),
            song.album.orEmpty().trim(),
            song.duration.orEmpty().trim(),
            SecurityValidators.sanitizeHttpsUrl(song.thumbnail, 500).orEmpty(),
            song.explicit.toString()
        ).joinToString("|")
        return sha256Hex(raw)
    }

    fun playlistSignature(
        cloudKey: String,
        title: String,
        description: String,
        coverUri: String?,
        tracks: List<SongItem>,
        coverRemoved: Boolean = false
    ): String {
        val safeCover = if (coverRemoved) "" else SecurityValidators.sanitizeHttpsUrl(coverUri, 500).orEmpty()
        val raw = listOf(
            cloudKey.trim(),
            title.trim(),
            description.trim(),
            safeCover,
            coverRemoved.toString(),
            tracks.joinToString(",") { it.videoId.trim() }
        ).joinToString("|")
        return sha256Hex(raw)
    }

    fun tasteSeedSignature(kind: String, seed: TasteSeed): String {
        val raw = listOf(
            kind.uppercase().trim(),
            seed.name.trim(),
            seed.subtitle.orEmpty().trim(),
            SecurityValidators.sanitizeHttpsUrl(seed.thumbnail, 500).orEmpty()
        ).joinToString("|")
        return sha256Hex(raw)
    }

    fun hiddenArtistSignature(name: String, channelId: String?): String {
        val raw = listOf(
            name.trim().lowercase(),
            channelId.orEmpty().trim()
        ).joinToString("|")
        return sha256Hex(raw)
    }

    fun syncableProfilePrefsSignature(
        prefs: VelunaUserPrefs,
        taste: TasteProfile,
        displayName: String = prefs.userDisplayName
    ): String {
        val cleanName = displayName.trim().ifBlank { "Listener" }.take(80)
        val cleanAccent = prefs.customAccentHex?.trim()?.takeIf { SecurityValidators.isValidHexColor(it) }.orEmpty()
        val cleanGenres = taste.selectedGenres
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(25)
        val raw = listOf(
            cleanName,
            prefs.themePreset.id.take(32),
            prefs.themeMode.name,
            cleanAccent,
            prefs.adaptToArtwork.toString(),
            prefs.cornerRadiusDp.coerceIn(4, 24).toString(),
            prefs.audioQuality.name,
            prefs.autoplay.toString(),
            prefs.loudnessNormalization.toString(),
            prefs.hideMusicVideos.toString(),
            taste.onboardingCompleted.toString(),
            cleanGenres.joinToString(",")
        ).joinToString("|")
        return sha256Hex(raw)
    }

    fun cloudProfilePrefsSignature(profile: CloudUserProfile): String {
        val cleanName = profile.displayName.trim().ifBlank { "Listener" }.take(80)
        val cleanAccent = profile.customAccentHex?.trim()?.takeIf { SecurityValidators.isValidHexColor(it) }.orEmpty()
        val cleanGenres = profile.selectedGenres
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(25)
        val raw = listOf(
            cleanName,
            profile.themePreset.take(32),
            profile.themeMode,
            cleanAccent,
            profile.adaptToArtwork.toString(),
            profile.cornerRadiusDp.coerceIn(4L, 24L).toString(),
            profile.audioQuality,
            profile.autoplay.toString(),
            profile.loudnessNormalization.toString(),
            profile.hideMusicVideos.toString(),
            profile.onboardingCompleted.toString(),
            cleanGenres.joinToString(",")
        ).joinToString("|")
        return sha256Hex(raw)
    }

    fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
