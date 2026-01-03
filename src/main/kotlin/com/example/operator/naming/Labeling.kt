// File: Labeling.kt
package com.example.operator.naming

import kotlin.text.iterator

object Labeling {
    private const val MAX_LABEL_VALUE_LEN = 63

    /**
     * Sanitizes a raw string into a Kubernetes-label-safe value using Henkan conventions.
     * - lowercase
     * - allow [a-z0-9-_.], replace others with '-'
     * - collapse multiple '-' into one
     * - trim '-', '_', '.' from start/end
     * - limit to 63 chars (and re-trim)
     */
    fun toLabelValue(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        val trimmed = raw.trim().lowercase()

        val mapped = buildString(trimmed.length) {
            for (ch in trimmed) {
                append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '-')
            }
        }

        val collapsed = mapped.replace(Regex("-+"), "-")
        val cleaned = collapsed.trim('-', '_', '.')
        val limited =
            if (cleaned.length > MAX_LABEL_VALUE_LEN)
                cleaned.substring(0, MAX_LABEL_VALUE_LEN).trim('-', '_', '.')
            else cleaned

        return limited.ifBlank { null }
    }
}