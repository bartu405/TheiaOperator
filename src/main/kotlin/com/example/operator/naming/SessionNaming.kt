// File: SessionNaming.kt
package com.example.operator.naming

object SessionNaming {


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

    fun sessionProxyCmName(user: String, appDefName: String, sessionUid: String): String =
        "session-proxy-${safeNamePart(user, 20)}-${safeNamePart(appDefName, 15)}-${shortUid(sessionUid)}"

    fun sessionEmailCmName(user: String, appDefName: String, sessionUid: String): String =
        "session-email-${safeNamePart(user, 20)}-${safeNamePart(appDefName, 15)}-${shortUid(sessionUid)}"

    fun sessionResourceBaseName(user: String, appDefName: String, sessionUid: String): String {
        val uidSuffix = sessionUid.replace("-", "").takeLast(12)  // ✅ Always 12 chars
        val safeUser = safeNamePart(user, 20)
        val safeAppDef = safeNamePart(appDefName, 20)
        return "session-${safeUser}-${safeAppDef}-${uidSuffix}"
    }

    private fun safeNamePart(s: String, max: Int): String =
        s.lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(max)

    private fun shortUid(sessionUid: String): String =
        sessionUid.replace("-", "").takeLast(12)

}
