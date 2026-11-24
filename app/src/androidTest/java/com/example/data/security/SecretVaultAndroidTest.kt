package com.example.data.security

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecretVaultAndroidTest {

    private lateinit var testAlias: String
    private lateinit var tempDir: File
    private lateinit var cipher: AndroidKeyStoreVaultCipher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        testAlias = "veluna_test_key_$suffix"
        tempDir = File(context.cacheDir, "vault_android_test_$suffix").apply { mkdirs() }
        cipher = AndroidKeyStoreVaultCipher(testAlias)
    }

    @After
    fun tearDown() {
        cipher.deleteKey()
        tempDir.deleteRecursively()
    }

    @Test
    fun androidKeyStoreCipher_usesUniqueRandomIvAndRoundTripsPlaintext() {
        val payload = "sensitive-lastfm-session-key".toByteArray(Charsets.UTF_8)
        val first = cipher.encrypt(payload)
        val second = cipher.encrypt(payload)

        // Fresh 12-byte GCM IV + 16-byte tag ensures distinct ciphertexts for same plaintext
        assertThat(first.size).isGreaterThan(12 + 16)
        assertThat(first.toList()).isNotEqualTo(second.toList())
        assertThat(cipher.decrypt(first)).isEqualTo(payload)
        assertThat(cipher.decrypt(second)).isEqualTo(payload)
    }

    @Test
    fun androidKeyStoreCipher_rejectsMissingTruncatedAndTamperedCiphertext() {
        val payload = "roundtrip-secret".toByteArray(Charsets.UTF_8)
        val valid = cipher.encrypt(payload)

        // Missing IV / too-short payload
        val truncated = valid.copyOf(12)
        var failedShort = false
        try {
            cipher.decrypt(truncated)
        } catch (_: IllegalArgumentException) {
            failedShort = true
        }
        assertThat(failedShort).isTrue()

        // Tampered IV byte must fail GCM authentication
        val badIv = valid.copyOf()
        badIv[0] = (badIv[0].toInt() xor 0x5A).toByte()
        var failedIv = false
        try {
            cipher.decrypt(badIv)
        } catch (_: Exception) {
            failedIv = true
        }
        assertThat(failedIv).isTrue()

        // Tampered ciphertext/tag byte must fail GCM authentication
        val badBody = valid.copyOf()
        badBody[badBody.lastIndex] = (badBody[badBody.lastIndex].toInt() xor 0x01).toByte()
        var failedBody = false
        try {
            cipher.decrypt(badBody)
        } catch (_: Exception) {
            failedBody = true
        }
        assertThat(failedBody).isTrue()
    }

    @Test
    fun androidKeyStoreCipher_reportsActualKeyInfoSecurityLevel() {
        val ciphertext = cipher.encrypt("probe".toByteArray(Charsets.UTF_8))
        assertThat(ciphertext).isNotEmpty()

        val report = cipher.inspectKeySecurity()
        val summary = "KEYSTORE_SECURITY_REPORT alias=${report.keyAlias} " +
            "securityLevel=${report.securityLevel} " +
            "isInsideSecureHardware=${report.isInsideSecureHardware} " +
            "apiLevel=${report.apiLevel} " +
            "details=${report.details}"

        Log.i("SecretVaultAndroidTest", summary)
        println(summary)

        assertThat(report.keyAlias).isEqualTo(testAlias)
        assertThat(report.securityLevel).isNotEqualTo(KeySecurityLevel.UNAVAILABLE)
        // Do not claim hardware/StrongBox unless KeyInfo itself reports hardware backing.
        val expectedHardware = report.securityLevel == KeySecurityLevel.STRONGBOX ||
            report.securityLevel == KeySecurityLevel.TRUSTED_ENVIRONMENT ||
            report.securityLevel == KeySecurityLevel.UNKNOWN_HARDWARE
        assertThat(report.isInsideSecureHardware).isEqualTo(expectedHardware)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            assertThat(report.securityLevel).isNotEqualTo(KeySecurityLevel.STRONGBOX)
        }
    }

    @Test
    fun secretVault_withAndroidKeyStore_persistsAcrossRestartWipesAndDetectsTampering() {
        val storageFile = File(tempDir, SecretVault.VAULT_FILENAME)
        val vault = SecretVault(storageFile, cipher)

        vault.putAll(
            mapOf(
                SecretVault.KEY_LASTFM_KEY to "k_prod_123",
                SecretVault.KEY_LASTFM_SECRET to "sec_prod_789",
                SecretVault.KEY_LASTFM_SESSION to "s_prod_456",
                SecretVault.KEY_LISTENBRAINZ_TOKEN to "lb_tok_999"
            )
        )

        // Simulate app restart with fresh SecretVault + fresh AndroidKeyStoreVaultCipher pointing at same alias/file
        val restartedVault = SecretVault(storageFile, AndroidKeyStoreVaultCipher(testAlias))
        assertThat(restartedVault.get(SecretVault.KEY_LASTFM_KEY)).isEqualTo("k_prod_123")
        assertThat(restartedVault.get(SecretVault.KEY_LASTFM_SECRET)).isEqualTo("sec_prod_789")
        assertThat(restartedVault.get(SecretVault.KEY_LASTFM_SESSION)).isEqualTo("s_prod_456")
        assertThat(restartedVault.get(SecretVault.KEY_LISTENBRAINZ_TOKEN)).isEqualTo("lb_tok_999")

        // Snapshot ciphertext, then wipeAll() (deletes file and Keystore key). Even if old ciphertext were
        // restored to disk afterward, the regenerated Keystore key must NOT decrypt old data.
        val savedCiphertext = storageFile.readText(Charsets.UTF_8)
        restartedVault.wipeAll()
        assertThat(storageFile.exists()).isFalse()
        assertThat(restartedVault.getAll()).isEmpty()

        storageFile.writeText(savedCiphertext, Charsets.UTF_8)
        var threwAfterKeyDeletion = false
        try {
            restartedVault.getAll()
        } catch (_: SecretVaultCorruptionException) {
            threwAfterKeyDeletion = true
        }
        assertThat(threwAfterKeyDeletion).isTrue()

        // Tamper ciphertext byte -> must raise SecretVaultCorruptionException rather than returning partial/spoofed data
        val corrupted = savedCiphertext.toCharArray()
        corrupted[corrupted.size / 2] = if (corrupted[corrupted.size / 2] == 'A') 'B' else 'A'
        storageFile.writeText(String(corrupted), Charsets.UTF_8)

        var threwCorruption = false
        try {
            restartedVault.getAll()
        } catch (_: SecretVaultCorruptionException) {
            threwCorruption = true
        }
        assertThat(threwCorruption).isTrue()
    }
}

