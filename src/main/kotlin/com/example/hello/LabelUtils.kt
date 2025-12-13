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
    val mapped = trimmed.map { ch ->
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '-'
    }.joinToString("")

    val cleaned = mapped.trim('-', '_', '.')
    return if (cleaned.isBlank()) null else cleaned
}
