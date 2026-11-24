package com.example.data.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class InnerTubeParsersTest {
    @Test
    fun findAll_handlesDeepJsonWithoutRecursiveCallStack() {
        var root: JsonElement = JsonPrimitive("leaf")
        repeat(20_000) {
            root = JsonObject(mapOf("child" to root))
        }

        assertThat(InnerTubeParsers.findAll(root, "missing")).isEmpty()
    }

    @Test
    fun findAll_preservesDepthFirstDocumentOrder() {
        val root = JsonObject(
            mapOf(
                "items" to JsonArray(
                    listOf(
                        JsonObject(mapOf("target" to JsonPrimitive("first"))),
                        JsonObject(mapOf("target" to JsonPrimitive("second")))
                    )
                )
            )
        )

        assertThat(InnerTubeParsers.findAll(root, "target").map { it.toString() })
            .containsExactly("\"first\"", "\"second\"").inOrder()
    }
}
