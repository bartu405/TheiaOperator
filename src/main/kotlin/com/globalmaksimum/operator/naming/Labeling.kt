// File: Labeling.kt
package com.globalmaksimum.operator.naming

import kotlin.text.iterator

object Labeling {
    private const val MAX_LABEL_VALUE_LEN = 63

    // Max 63 characters
    // Only: letters, numbers and these three -, _, .
    // Must start and end with alphanumeric character
    fun toLabelValue(raw: String?): String? {

        if (raw.isNullOrBlank()) return null

        // Remove the trailing and leading spaces, make lowercase
        val trimmed = raw.trim().lowercase()

        // Replace invalid characters with "-"
        val mapped = buildString(trimmed.length) {
            for (ch in trimmed) {
                append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '-')
            }
        }

        // Collapse multiple dashes in a row
        val collapsed = mapped.replace(Regex("-+"), "-")

        // Trim the special characters at the start/end
        val cleaned = collapsed.trim('-', '_', '.')

        val limited =
            // If more than 63, take first 63 characters and trim again
            if (cleaned.length > MAX_LABEL_VALUE_LEN) cleaned.substring(0, MAX_LABEL_VALUE_LEN).trim('-', '_', '.')
            else cleaned

        return limited.ifBlank { null }
    }
}