package com.example.data.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MergeEngineTest {

    @Test
    fun mergeMaps_newerTombstonePreventsResurrectionInBothDirections() {
        val local = mapOf(
            "trk1" to MergeItem<String>(key = "trk1", clientUpdatedAt = 1000L, deleted = false, value = "Song 1"),
            "trk2" to MergeItem<String>(key = "trk2", clientUpdatedAt = 3000L, deleted = true, value = null)
        )
        val cloud = mapOf(
            "trk1" to MergeItem<String>(key = "trk1", clientUpdatedAt = 2000L, deleted = true, value = null),
            "trk2" to MergeItem<String>(key = "trk2", clientUpdatedAt = 1500L, deleted = false, value = "Song 2")
        )

        val decisions = MergeEngine.mergeMaps<String>(local, cloud).associateBy { it.key }

        val d1 = decisions.getValue("trk1")
        assertThat(d1.deleted).isTrue()
        assertThat(d1.applyLocally).isTrue()
        assertThat(d1.pushToCloud).isFalse()

        val d2 = decisions.getValue("trk2")
        assertThat(d2.deleted).isTrue()
        assertThat(d2.applyLocally).isFalse()
        assertThat(d2.pushTombstoneToCloud).isTrue()
    }

    @Test
    fun mergeMaps_equalTimestampsPreferTombstoneDeterministically() {
        val local = mapOf(
            "a" to MergeItem<String>(key = "a", clientUpdatedAt = 5000L, deleted = true, value = null),
            "b" to MergeItem<String>(key = "b", clientUpdatedAt = 5000L, deleted = false, value = "Active B")
        )
        val cloud = mapOf(
            "a" to MergeItem<String>(key = "a", clientUpdatedAt = 5000L, deleted = false, value = "Active A"),
            "b" to MergeItem<String>(key = "b", clientUpdatedAt = 5000L, deleted = true, value = null)
        )

        val decisions = MergeEngine.mergeMaps<String>(local, cloud).associateBy { it.key }

        assertThat(decisions.getValue("a").deleted).isTrue()
        assertThat(decisions.getValue("a").pushTombstoneToCloud).isTrue()
        assertThat(decisions.getValue("b").deleted).isTrue()
        assertThat(decisions.getValue("b").applyLocally).isTrue()
    }

    @Test
    fun mergeMaps_onlyLocalOrOnlyCloudAreRoutedCorrectly() {
        val local = mapOf(
            "onlyLocalActive" to MergeItem<String>("onlyLocalActive", 100L, deleted = false, value = "L"),
            "onlyLocalTomb" to MergeItem<String>("onlyLocalTomb", 200L, deleted = true, value = null)
        )
        val cloud = mapOf(
            "onlyCloudActive" to MergeItem<String>("onlyCloudActive", 300L, deleted = false, value = "C"),
            "onlyCloudTomb" to MergeItem<String>("onlyCloudTomb", 400L, deleted = true, value = null)
        )

        val decisions = MergeEngine.mergeMaps<String>(local, cloud).associateBy { it.key }

        assertThat(decisions.getValue("onlyLocalActive").pushToCloud).isTrue()
        assertThat(decisions.getValue("onlyLocalActive").applyLocally).isFalse()

        assertThat(decisions.getValue("onlyLocalTomb").pushTombstoneToCloud).isTrue()
        assertThat(decisions.getValue("onlyLocalTomb").pushToCloud).isFalse()

        assertThat(decisions.getValue("onlyCloudActive").applyLocally).isTrue()
        assertThat(decisions.getValue("onlyCloudActive").pushToCloud).isFalse()

        assertThat(decisions.getValue("onlyCloudTomb").applyLocally).isTrue()
        assertThat(decisions.getValue("onlyCloudTomb").deleted).isTrue()
    }

    @Test
    fun mergeMaps_twoAccountsIsolatedBySeparateMaps() {
        val accountA = MergeEngine.mergeMaps<String>(
            local = mapOf("k" to MergeItem("k", 100L, deleted = false, value = "A")),
            cloud = emptyMap()
        ).associateBy { it.key }
        val accountB = MergeEngine.mergeMaps<String>(
            local = emptyMap(),
            cloud = emptyMap()
        )

        assertThat(accountA.getValue("k").value).isEqualTo("A")
        assertThat(accountB).isEmpty()
    }
}
