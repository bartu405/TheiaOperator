// File: SessionTimeoutReaper.kt
package com.example.hello

import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionTimeoutReaper(
    private val client: KubernetesClient
) {

    private val log = LoggerFactory.getLogger(SessionTimeoutReaper::class.java)

    // single-threaded scheduler, like BasicTheiaCloudOperator
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    fun start() {
        log.info("Starting SessionTimeoutReaper (runs every 1 minute)")
        scheduler.scheduleWithFixedDelay(
            { runOnceSafe() },
            1, // initial delay (minutes)
            1, // period (minutes)
            TimeUnit.MINUTES
        )
    }

    private fun runOnceSafe() {
        try {
            runOnce()
        } catch (t: Throwable) {
            log.error("Error while running SessionTimeoutReaper", t)
        }
    }

    private fun runOnce() {
        val now = Instant.now()
        log.debug("SessionTimeoutReaper tick at {}", now)

        // list all sessions in all namespaces (or restrict to one if you prefer)
        val sessions = client.resources(Session::class.java)
            .inAnyNamespace()
            .list()
            .items

        if (sessions.isEmpty()) {
            log.debug("No Session resources found, nothing to check for timeout")
            return
        }

        val toDelete = mutableListOf<Pair<String, String>>() // (namespace, name)

        sessions.forEach { session ->
            val meta = session.metadata
            val spec = session.spec

            val ns = meta?.namespace ?: "default"
            val sessionName = meta?.name ?: return@forEach

            val appDefName = spec?.appDefinition
            if (appDefName.isNullOrBlank()) {
                log.debug(
                    "Session {}/{} has no spec.appDefinition, skipping timeout check",
                    ns, sessionName
                )
                return@forEach
            }

            // Load AppDefinition to read spec.timeout
            val appDef = client.resources(AppDefinition::class.java)
                .inNamespace(ns)
                .withName(appDefName)
                .get()

            if (appDef == null) {
                log.debug(
                    "AppDefinition '{}' not found in ns {}, skipping timeout for Session {}/{}",
                    appDefName, ns, sessionName
                )
                return@forEach
            }

            val timeoutMinutes = appDef.spec?.timeout
            if (timeoutMinutes == null || timeoutMinutes <= 0) {
                log.trace(
                    "Session {}/{} (appDefinition={}) has no positive timeout, skipping",
                    ns, sessionName, appDefName
                )
                return@forEach
            }

            val creationTs = meta.creationTimestamp
            if (creationTs.isNullOrBlank()) {
                log.warn(
                    "Session {}/{} has no metadata.creationTimestamp, cannot compute timeout",
                    ns, sessionName
                )
                return@forEach
            }

            val creationInstant = try {
                Instant.parse(creationTs)
            } catch (e: Exception) {
                log.warn(
                    "Failed to parse creationTimestamp '{}' for Session {}/{}, skipping timeout",
                    creationTs, ns, sessionName, e
                )
                return@forEach
            }

            val ageMinutes = ChronoUnit.MINUTES.between(creationInstant, now)
            if (ageMinutes > timeoutMinutes.toLong()) {
                log.info(
                    "Session {}/{} exceeded timeout: age={} min > limit={} min, marking for deletion",
                    ns, sessionName, ageMinutes, timeoutMinutes
                )
                toDelete += ns to sessionName
            } else {
                log.trace(
                    "Session {}/{} age={} min <= limit={} min, keep running",
                    ns, sessionName, ageMinutes, timeoutMinutes
                )
            }
        }

        // Perform deletions
        toDelete.forEach { (ns, name) ->
            try {
                val deleted = client.resources(Session::class.java)
                    .inNamespace(ns)
                    .withName(name)
                    .delete()

                log.info(
                    "Deleted timed-out Session {}/{} (result={})",
                    ns, name, deleted
                )
            } catch (e: Exception) {
                log.error(
                    "Failed to delete timed-out Session {}/{}",
                    ns, name, e
                )
            }
        }
    }
}
