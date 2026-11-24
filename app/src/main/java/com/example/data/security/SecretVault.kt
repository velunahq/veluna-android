package com.example.data.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecretVaultCorruptionException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

interface VaultCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertextWithIv: ByteArray): ByteArray
    fun deleteKey()
}

enum class KeySecurityLevel {
    SOFTWARE,
    TRUSTED_ENVIRONMENT,
    STRONGBOX,
    UNKNOWN_HARDWARE,
    UNAVAILABLE
}

data class KeySecurityReport(
    val keyAlias: String,
    val securityLevel: KeySecurityLevel,
    val isInsideSecureHardware: Boolean,
    val apiLevel: Int,
    val details: String
)

class AndroidKeyStoreVaultCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : VaultCipher {

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    fun inspectKeySecurity(): KeySecurityReport {
        val api = Build.VERSION.SDK_INT
        return try {
            val key = getOrCreateKey()
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val keyInfo = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val rawLevel = keyInfo.securityLevel
                val level = when (rawLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeySecurityLevel.STRONGBOX
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeySecurityLevel.TRUSTED_ENVIRONMENT
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeySecurityLevel.SOFTWARE
                    KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> KeySecurityLevel.UNKNOWN_HARDWARE
                    else -> KeySecurityLevel.SOFTWARE
                }
                val inHardware = level == KeySecurityLevel.STRONGBOX ||
                    level == KeySecurityLevel.TRUSTED_ENVIRONMENT ||
                    level == KeySecurityLevel.UNKNOWN_HARDWARE
                KeySecurityReport(
                    keyAlias = keyAlias,
                    securityLevel = level,
                    isInsideSecureHardware = inHardware,
                    apiLevel = api,
                    details = "API $api KeyInfo.securityLevel=$rawLevel ($level)"
                )
            } else {
                @Suppress("DEPRECATION")
                val inHardware = keyInfo.isInsideSecureHardware
                val level = if (inHardware) KeySecurityLevel.TRUSTED_ENVIRONMENT else KeySecurityLevel.SOFTWARE
                KeySecurityReport(
                    keyAlias = keyAlias,
                    securityLevel = level,
                    isInsideSecureHardware = inHardware,
                    apiLevel = api,
                    details = if (inHardware) {
                        "API $api KeyInfo.isInsideSecureHardware=true (TEE/StrongBox indistinct below API 31)"
                    } else {
                        "API $api KeyInfo.isInsideSecureHardware=false (software-backed Keystore)"
                    }
                )
            }
        } catch (t: Throwable) {
            KeySecurityReport(
                keyAlias = keyAlias,
                securityLevel = KeySecurityLevel.UNAVAILABLE,
                isInsideSecureHardware = false,
                apiLevel = api,
                details = "KeyInfo inspection failed: ${t::class.java.simpleName}: ${t.message}"
            )
        }
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        // Android Keystore requires provider-generated IV when setRandomizedEncryptionRequired(true)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv != null && iv.size == GCM_IV_LENGTH) {
            "Android Keystore failed to generate a valid 12-byte GCM IV"
        }
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    override fun decrypt(ciphertextWithIv: ByteArray): ByteArray {
        require(ciphertextWithIv.size > GCM_IV_LENGTH + GCM_TAG_BYTES) { "Invalid encrypted payload" }
        val iv = ciphertextWithIv.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = ciphertextWithIv.copyOfRange(GCM_IV_LENGTH, ciphertextWithIv.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    override fun deleteKey() {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(keyAlias)) {
                ks.deleteEntry(keyAlias)
            }
        }
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "veluna_secret_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    }
}

/**
 * Software AES-256-GCM cipher used strictly for unit/Robolectric tests where AndroidKeyStore
 * provider is absent on the JVM.
 */
class InMemoryAesGcmVaultCipher(
    keyBytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
) : VaultCipher {
    private var keySpec: SecretKeySpec? = SecretKeySpec(keyBytes.copyOf(32), "AES")
    private val random = SecureRandom()

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val key = keySpec ?: throw IllegalStateException("Vault key was wiped")
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertextWithIv: ByteArray): ByteArray {
        val key = keySpec ?: throw IllegalStateException("Vault key was wiped")
        require(ciphertextWithIv.size > 12 + 16) { "Invalid encrypted payload" }
        val iv = ciphertextWithIv.copyOfRange(0, 12)
        val ciphertext = ciphertextWithIv.copyOfRange(12, ciphertextWithIv.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    override fun deleteKey() {
        keySpec = null
    }
}

class SecretVault(
    private val storageFile: File,
    private val cipher: VaultCipher
) {
    constructor(context: Context) : this(
        storageFile = File(context.applicationContext.noBackupFilesDir, VAULT_FILENAME),
        cipher = AndroidKeyStoreVaultCipher()
    )

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    fun put(name: String, value: String) {
        synchronized(lock) {
            val map = readMapLocked().toMutableMap()
            if (value.isEmpty()) {
                map.remove(name)
            } else {
                map[name] = value
            }
            writeMapLocked(map)
        }
    }

    fun putAll(entries: Map<String, String>) {
        synchronized(lock) {
            val map = readMapLocked().toMutableMap()
            entries.forEach { (k, v) ->
                if (v.isEmpty()) map.remove(k) else map[k] = v
            }
            writeMapLocked(map)
        }
    }

    fun get(name: String): String? = synchronized(lock) {
        readMapLocked()[name]
    }

    fun getAll(): Map<String, String> = synchronized(lock) {
        readMapLocked().toMap()
    }

    fun delete(name: String) {
        synchronized(lock) {
            val map = readMapLocked().toMutableMap()
            if (map.remove(name) != null) {
                writeMapLocked(map)
            }
        }
    }

    fun wipeAll() {
        synchronized(lock) {
            runCatching {
                if (storageFile.exists()) {
                    storageFile.delete()
                }
                val tmp = File(storageFile.parentFile, "$VAULT_FILENAME.tmp")
                if (tmp.exists()) {
                    tmp.delete()
                }
            }
            cipher.deleteKey()
        }
    }

    private fun readMapLocked(): Map<String, String> {
        if (!storageFile.exists()) return emptyMap()
        val encoded = try {
            storageFile.readText(Charsets.UTF_8).trim()
        } catch (t: Throwable) {
            throw SecretVaultCorruptionException("Failed to read secret vault payload", t)
        }
        if (encoded.isEmpty()) {
            throw SecretVaultCorruptionException("Secret vault file is empty or truncated")
        }
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val plainJson = cipher.decrypt(payload).toString(Charsets.UTF_8)
            json.decodeFromString<Map<String, String>>(plainJson)
        } catch (t: Throwable) {
            throw SecretVaultCorruptionException("Failed to decrypt or decode secret vault payload", t)
        }
    }

    private fun writeMapLocked(map: Map<String, String>) {
        if (map.isEmpty()) {
            if (storageFile.exists() && !storageFile.delete()) {
                throw IOException("Failed to remove empty secret vault file")
            }
            return
        }
        storageFile.parentFile?.mkdirs()
        val plainBytes = json.encodeToString(map).toByteArray(Charsets.UTF_8)
        val encrypted = cipher.encrypt(plainBytes)
        val encodedBytes = Base64.encodeToString(encrypted, Base64.NO_WRAP).toByteArray(Charsets.UTF_8)
        val tmp = File(storageFile.parentFile, "$VAULT_FILENAME.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(encodedBytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(storageFile)) {
                try {
                    Files.move(
                        tmp.toPath(),
                        storageFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: UnsupportedOperationException) {
                    Files.move(
                        tmp.toPath(),
                        storageFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }
        } catch (t: Throwable) {
            runCatching { if (tmp.exists()) tmp.delete() }
            throw t
        }
    }

    companion object {
        const val VAULT_FILENAME = "secrets.bin"

        const val KEY_LASTFM_KEY = "lastfm_key"
        const val KEY_LASTFM_SECRET = "lastfm_secret"
        const val KEY_LASTFM_SESSION = "lastfm_session"
        const val KEY_LISTENBRAINZ_TOKEN = "listenbrainz_token"
    }
}
