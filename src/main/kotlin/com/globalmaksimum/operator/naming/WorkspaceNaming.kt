package com.globalmaksimum.operator.naming

import com.globalmaksimum.operator.Workspace
import java.util.Locale

object WorkspaceNaming {
    private const val VALID_NAME_LIMIT = 62
    private const val VALID_NAME_PREFIX = 'a'
    private const val VALID_NAME_SUFFIX = 'z'


    // ws-{identifier?}-{user}-{appDef}-{ws.uid}
    fun workspaceStorageName(ws: Workspace, identifier: String? = null): String {
        val user = ws.spec?.user
        val appDef = ws.spec?.appDefinition
        val uid = ws.metadata?.uid

        require(!uid.isNullOrBlank()) { "Workspace metadata.uid missing" }

        val shortUid = trimUid(uid)
        val userName = user?.substringBefore("@") // Theia behavior
        val (infoSegmentLength, shortenedIdentifier) =
            if (identifier.isNullOrBlank()) 17 to null else 11 to trimLength(identifier, 11)

        val shortUser = trimLength(userName, infoSegmentLength)
        val shortAppDef = trimLength(appDef, infoSegmentLength)

        // asValidName(prefix, identifier?, user?, appDef?, uidSuffix)
        return asValidNameSegments("ws", shortenedIdentifier, shortUser, shortAppDef, shortUid)
    }

    private fun trimUid(uid: String): String {
        return uid.takeLast(12)
    }

    private fun trimLength(text: String?, max: Int): String? {
        if (text.isNullOrBlank() || max <= 0) return null
        return text.take(max)
    }

    // Joins the segments
    private fun asValidNameSegments(vararg segments: String?): String {
        val joined = segments
            .filterNotNull()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("-")
        return asValidName(joined, VALID_NAME_LIMIT)
    }

    private fun asValidName(text: String, limit: Int): String {
        if (text.isEmpty()) return text

        // replace invalid chars with '-'
        var valid = text.replace(Regex("[^a-z0-9A-Z\\-]"), "-")

        // ensure starts with a letter
        if (valid.isNotEmpty() && !valid.first().isLetter()) {
            valid = VALID_NAME_PREFIX + valid
        }

        // trim to limit
        if (valid.length > limit) {
            valid = valid.substring(0, limit)
        }

        // ensure ends with alphanumeric
        if (valid.isNotEmpty() && !valid.last().isLetterOrDigit()) {
            valid = valid.dropLast(1) + VALID_NAME_SUFFIX
        }

        return valid.lowercase(Locale("en", "US"))
    }

    fun deriveWorkspaceShortName(wsIdOrName: String, user: String): String {
        val sanitizedUser = user
            .replace(Regex("[^a-zA-Z0-9-]"), "")
            .replace(Regex("-+"), "-")
            .trim('-')

        val prefix = "$sanitizedUser-"
        return if (wsIdOrName.startsWith(prefix) && wsIdOrName.length > prefix.length) {
            wsIdOrName.removePrefix(prefix)
        } else {
            wsIdOrName
        }
    }

}
