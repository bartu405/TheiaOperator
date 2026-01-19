package com.globalmaksimum.designeroperator.naming

object SessionNaming {

    fun sessionProxyCmName(user: String, appDefName: String, sessionUid: String): String =
        "session-proxy-${safeNamePart(user, 17)}-${safeNamePart(appDefName, 11)}-${shortUid(sessionUid)}"

    fun sessionEmailCmName(user: String, appDefName: String, sessionUid: String): String =
        "session-email-${safeNamePart(user, 17)}-${safeNamePart(appDefName, 11)}-${shortUid(sessionUid)}"

    fun sessionResourceBaseName(user: String, appDefName: String, sessionUid: String): String =
        "session-${safeNamePart(user, 17)}-${safeNamePart(appDefName, 17)}-${shortUid(sessionUid)}"

    private fun safeNamePart(s: String, max: Int): String =
        s.lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(max)

    private fun shortUid(sessionUid: String): String =
        sessionUid.replace("-", "").takeLast(12)

}
