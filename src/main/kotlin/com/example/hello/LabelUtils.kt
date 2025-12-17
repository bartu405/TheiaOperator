// File: LabelUtils.kt
package com.example.hello

/**
 * Normalizes arbitrary strings into safe Kubernetes label values:
 * - trim + lowercase
 * - replace invalid chars with '-'
 * - trim leading/trailing non-alphanumeric
 * - return null if result is blank
 */
fun toHenkanLabelValue(raw: String?): String? {
    if (raw.isNullOrBlank()) return null

    val trimmed = raw.trim().lowercase()

    val mapped = buildString(trimmed.length) {
        for (ch in trimmed) {
            append(
                if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '-'
            )
        }
    }

    val collapsed = mapped.replace(Regex("-+"), "-")
    val cleaned = collapsed.trim('-', '_', '.')
    val limited = if (cleaned.length > 63) cleaned.substring(0, 63).trim('-', '_', '.') else cleaned

    return limited.ifBlank { null }
}
