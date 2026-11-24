package com.example.data.cloud

data class MergeItem<T>(
    val key: String,
    val clientUpdatedAt: Long,
    val deleted: Boolean,
    val value: T? = null
)

data class MergeDecision<T>(
    val key: String,
    val clientUpdatedAt: Long,
    val deleted: Boolean,
    val value: T?,
    val applyLocally: Boolean,
    val pushToCloud: Boolean,
    val pushTombstoneToCloud: Boolean
)

/**
 * Pure Kotlin per-item last-write-wins merge engine with tombstone propagation.
 * Known limitation: relies on device clocks via [MergeItem.clientUpdatedAt].
 */
object MergeEngine {

    fun <T> mergeMaps(
        local: Map<String, MergeItem<T>>,
        cloud: Map<String, MergeItem<T>>,
        areEqual: (T?, T?) -> Boolean = { a, b -> a == b }
    ): List<MergeDecision<T>> {
        val allKeys = (local.keys + cloud.keys).toSortedSet()
        val decisions = ArrayList<MergeDecision<T>>(allKeys.size)

        for (key in allKeys) {
            val l = local[key]
            val c = cloud[key]
            when {
                l != null && c == null -> {
                    decisions.add(
                        MergeDecision(
                            key = key,
                            clientUpdatedAt = l.clientUpdatedAt,
                            deleted = l.deleted,
                            value = l.value,
                            applyLocally = false,
                            pushToCloud = !l.deleted,
                            pushTombstoneToCloud = l.deleted
                        )
                    )
                }
                l == null && c != null -> {
                    decisions.add(
                        MergeDecision(
                            key = key,
                            clientUpdatedAt = c.clientUpdatedAt,
                            deleted = c.deleted,
                            value = c.value,
                            applyLocally = true,
                            pushToCloud = false,
                            pushTombstoneToCloud = false
                        )
                    )
                }
                l != null && c != null -> {
                    when {
                        c.clientUpdatedAt > l.clientUpdatedAt -> {
                            val changedLocally = if (c.deleted) !l.deleted else (l.deleted || !areEqual(l.value, c.value))
                            decisions.add(
                                MergeDecision(
                                    key = key,
                                    clientUpdatedAt = c.clientUpdatedAt,
                                    deleted = c.deleted,
                                    value = c.value,
                                    applyLocally = changedLocally,
                                    pushToCloud = false,
                                    pushTombstoneToCloud = false
                                )
                            )
                        }
                        l.clientUpdatedAt > c.clientUpdatedAt -> {
                            decisions.add(
                                MergeDecision(
                                    key = key,
                                    clientUpdatedAt = l.clientUpdatedAt,
                                    deleted = l.deleted,
                                    value = l.value,
                                    applyLocally = false,
                                    pushToCloud = !l.deleted && (c.deleted || !areEqual(l.value, c.value)),
                                    pushTombstoneToCloud = l.deleted && !c.deleted
                                )
                            )
                        }
                        else -> {
                            // Equal timestamps: prefer deleted tombstone deterministically to prevent resurrection
                            if (l.deleted && !c.deleted) {
                                decisions.add(
                                    MergeDecision(
                                        key = key,
                                        clientUpdatedAt = l.clientUpdatedAt,
                                        deleted = true,
                                        value = null,
                                        applyLocally = false,
                                        pushToCloud = false,
                                        pushTombstoneToCloud = true
                                    )
                                )
                            } else if (c.deleted && !l.deleted) {
                                decisions.add(
                                    MergeDecision(
                                        key = key,
                                        clientUpdatedAt = c.clientUpdatedAt,
                                        deleted = true,
                                        value = null,
                                        applyLocally = true,
                                        pushToCloud = false,
                                        pushTombstoneToCloud = false
                                    )
                                )
                            } else {
                                val differ = !c.deleted && !areEqual(l.value, c.value)
                                decisions.add(
                                    MergeDecision(
                                        key = key,
                                        clientUpdatedAt = c.clientUpdatedAt,
                                        deleted = c.deleted,
                                        value = c.value ?: l.value,
                                        applyLocally = differ,
                                        pushToCloud = false,
                                        pushTombstoneToCloud = false
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        return decisions
    }
}
