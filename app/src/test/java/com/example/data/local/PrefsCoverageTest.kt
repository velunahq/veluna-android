package com.example.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class PrefsCoverageTest {

    @Test
    fun everyUserPrefProperty_hasActiveSetterOrVaultBindingAndNoDeadSettings() {
        val fieldNames = VelunaUserPrefs::class.java.declaredFields
            .map { it.name }
            .filterNot { it == "Companion" || it.startsWith("$") }
            .toSet()

        // Explicitly removed dead settings must not reappear
        assertThat(fieldNames).doesNotContain("crossfadeSecs")
        assertThat(fieldNames).doesNotContain("translateLyrics")

        val mainSrcRoot = listOf(
            File("app/src/main/java"),
            File("src/main/java")
        ).firstOrNull { it.exists() }
            ?: error("Could not locate main source root from ${File(".").absolutePath}")

        val allKotlinText = mainSrcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.name.endsWith("UserPrefsRepository.kt") }
            .joinToString("\n") { it.readText() }

        fieldNames.forEach { prop ->
            assertThat(allKotlinText.contains(prop)).isTrue()
        }
    }
}
