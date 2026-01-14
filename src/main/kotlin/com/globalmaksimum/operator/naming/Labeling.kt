// File: Labeling.kt
package com.globalmaksimum.operator.naming

import kotlin.text.iterator

object Labeling {
    private const val MAX_LABEL_VALUE_LEN = 63

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