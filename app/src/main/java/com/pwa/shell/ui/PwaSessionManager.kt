package com.pwa.shell.ui

enum class PwaActivationSource {
    HOME,
    DRAWER,
    GESTURE,
    NOTIFICATION,
    SHORTCUT
}

enum class PwaSessionPhase {
    ACTIVE,
    WARM,
    ATTENTION,
    PENDING_CLOSE
}

enum class PwaGestureDirection {
    OLDER,
    NEWER
}

data class PwaSessionEntry(
    val pwaId: Long,
    val phase: PwaSessionPhase,
    val lastActivatedElapsedMs: Long,
    val inactiveSinceElapsedMs: Long?
)

data class PwaSessionSnapshot(
    val activePwaId: Long?,
    val liveSessions: List<PwaSessionEntry>,
    val recentPwaIds: List<Long>,
    val gestureSequence: List<Long>
)

/**
 * Pure session state machine. Android WebView/Bundle/timer side effects stay in the
 * Compose host so the MRU and eviction rules remain deterministic and unit-testable.
 */
class PwaSessionManager(
    initialRecentPwaIds: List<Long> = emptyList(),
    private val maxLiveSessions: Int = MAX_LIVE_SESSIONS,
    private val maxRecentPwas: Int = MAX_RECENT_PWAS
) {
    private data class MutableSession(
        val pwaId: Long,
        var phase: PwaSessionPhase,
        var lastActivatedElapsedMs: Long,
        var inactiveSinceElapsedMs: Long?
    )

    private val liveSessions = linkedMapOf<Long, MutableSession>()
    private val recentPwaIds = initialRecentPwaIds.distinct().take(maxRecentPwas).toMutableList()
    private val attentionPwaIds = mutableSetOf<Long>()
    private var activePwaId: Long? = null
    private var gestureSequence = emptyList<Long>()
    private var gestureIndex = 0
    private var backgroundedAtElapsedMs: Long? = null

    fun snapshot(): PwaSessionSnapshot = PwaSessionSnapshot(
        activePwaId = activePwaId,
        liveSessions = liveSessions.values.map { session ->
            PwaSessionEntry(
                pwaId = session.pwaId,
                phase = when {
                    session.pwaId == activePwaId -> PwaSessionPhase.ACTIVE
                    session.phase == PwaSessionPhase.PENDING_CLOSE ->
                        PwaSessionPhase.PENDING_CLOSE
                    session.pwaId in attentionPwaIds -> PwaSessionPhase.ATTENTION
                    else -> PwaSessionPhase.WARM
                },
                lastActivatedElapsedMs = session.lastActivatedElapsedMs,
                inactiveSinceElapsedMs = session.inactiveSinceElapsedMs
            )
        },
        recentPwaIds = recentPwaIds.toList(),
        gestureSequence = gestureSequence
    )

    fun activate(
        pwaId: Long,
        source: PwaActivationSource,
        nowElapsedMs: Long
    ): Set<Long> {
        require(pwaId > 0L) { "PWA id must be positive" }
        if (source != PwaActivationSource.GESTURE) {
            clearGesture()
        }

        activePwaId?.takeIf { it != pwaId }?.let { previousId ->
            liveSessions[previousId]?.let { previous ->
                previous.phase = if (previousId in attentionPwaIds) {
                    PwaSessionPhase.ATTENTION
                } else {
                    PwaSessionPhase.WARM
                }
                previous.inactiveSinceElapsedMs = nowElapsedMs
            }
        }

        val target = liveSessions.getOrPut(pwaId) {
            MutableSession(
                pwaId = pwaId,
                phase = PwaSessionPhase.WARM,
                lastActivatedElapsedMs = nowElapsedMs,
                inactiveSinceElapsedMs = null
            )
        }
        target.phase = PwaSessionPhase.ACTIVE
        target.lastActivatedElapsedMs = nowElapsedMs
        target.inactiveSinceElapsedMs = null
        activePwaId = pwaId
        attentionPwaIds.remove(pwaId)
        recentPwaIds.remove(pwaId)
        recentPwaIds.add(0, pwaId)
        trimRecents()

        val evicted = mutableSetOf<Long>()
        while (liveSessions.size > maxLiveSessions) {
            val victim = liveSessions.values
                .asSequence()
                .filter { it.pwaId != activePwaId }
                .minWithOrNull(
                    compareBy<MutableSession>(
                        { evictionPriority(it.phase) },
                        { it.lastActivatedElapsedMs }
                    )
                )
                ?: break
            liveSessions.remove(victim.pwaId)
            attentionPwaIds.remove(victim.pwaId)
            evicted += victim.pwaId
        }
        return evicted
    }

    fun goHome(nowElapsedMs: Long) {
        activePwaId?.let { activeId ->
            liveSessions[activeId]?.let { active ->
                active.phase = if (activeId in attentionPwaIds) {
                    PwaSessionPhase.ATTENTION
                } else {
                    PwaSessionPhase.WARM
                }
                active.inactiveSinceElapsedMs = nowElapsedMs
            }
        }
        activePwaId = null
        clearGesture()
    }

    fun markAttention(pwaId: Long, hasAttention: Boolean) {
        if (hasAttention) attentionPwaIds += pwaId else attentionPwaIds -= pwaId
        val session = liveSessions[pwaId] ?: return
        if (pwaId != activePwaId && session.phase != PwaSessionPhase.PENDING_CLOSE) {
            session.phase = if (hasAttention) {
                PwaSessionPhase.ATTENTION
            } else {
                PwaSessionPhase.WARM
            }
        }
    }

    fun beginPendingClose(pwaId: Long): Boolean {
        val session = liveSessions[pwaId] ?: return false
        if (pwaId == activePwaId) return false
        session.phase = PwaSessionPhase.PENDING_CLOSE
        recentPwaIds.remove(pwaId)
        attentionPwaIds.remove(pwaId)
        clearGesture()
        return true
    }

    fun undoPendingClose(pwaId: Long): Boolean {
        val session = liveSessions[pwaId]
            ?.takeIf { it.phase == PwaSessionPhase.PENDING_CLOSE }
            ?: return false
        session.phase = PwaSessionPhase.WARM
        recentPwaIds.remove(pwaId)
        recentPwaIds.add(0, pwaId)
        trimRecents()
        return true
    }

    fun finalizePendingClose(pwaId: Long): Boolean {
        val pending = liveSessions[pwaId]
            ?.takeIf { it.phase == PwaSessionPhase.PENDING_CLOSE }
            ?: return false
        liveSessions.remove(pending.pwaId)
        attentionPwaIds.remove(pending.pwaId)
        clearGesture()
        return true
    }

    fun invalidate(pwaId: Long): Boolean {
        if (activePwaId == pwaId) activePwaId = null
        attentionPwaIds.remove(pwaId)
        clearGesture()
        return liveSessions.remove(pwaId) != null
    }

    fun removePwa(pwaId: Long): Boolean {
        recentPwaIds.remove(pwaId)
        return invalidate(pwaId)
    }

    fun reconcile(validPwaIds: Set<Long>): Set<Long> {
        val removed = liveSessions.keys.filterTo(mutableSetOf()) { it !in validPwaIds }
        removed.forEach {
            liveSessions.remove(it)
            attentionPwaIds.remove(it)
        }
        recentPwaIds.retainAll(validPwaIds)
        if (activePwaId !in validPwaIds) activePwaId = null
        gestureSequence = gestureSequence.filter { it in validPwaIds }
        gestureIndex = gestureIndex.coerceAtMost((gestureSequence.size - 1).coerceAtLeast(0))
        return removed
    }

    fun beginGesture(): List<Long> {
        if (gestureSequence.isEmpty()) {
            val current = activePwaId ?: return emptyList()
            gestureSequence = buildList {
                add(current)
                addAll(recentPwaIds.asSequence().filter { it != current }.take(3))
            }
            gestureIndex = gestureSequence.indexOf(current).coerceAtLeast(0)
        }
        return gestureSequence
    }

    fun gestureTarget(direction: PwaGestureDirection): Long? {
        if (gestureSequence.size < 2) return null
        gestureIndex = when (direction) {
            PwaGestureDirection.OLDER -> (gestureIndex + 1) % gestureSequence.size
            PwaGestureDirection.NEWER ->
                (gestureIndex - 1 + gestureSequence.size) % gestureSequence.size
        }
        return gestureSequence[gestureIndex]
    }

    fun clearGesture() {
        gestureSequence = emptyList()
        gestureIndex = 0
    }

    fun evictIdleWarmSessions(
        nowElapsedMs: Long,
        idleTimeoutMs: Long = FOREGROUND_IDLE_TIMEOUT_MS
    ): Set<Long> = removeNonActiveSessions { session ->
        session.phase == PwaSessionPhase.WARM &&
            session.inactiveSinceElapsedMs?.let {
                nowElapsedMs - it >= idleTimeoutMs
            } == true
    }

    fun onAppBackgrounded(nowElapsedMs: Long) {
        backgroundedAtElapsedMs = nowElapsedMs
    }

    fun onAppForegrounded(
        nowElapsedMs: Long,
        backgroundTimeoutMs: Long = BACKGROUND_WARM_TIMEOUT_MS
    ): Set<Long> {
        val backgroundedAt = backgroundedAtElapsedMs
        backgroundedAtElapsedMs = null
        return if (
            backgroundedAt != null &&
            nowElapsedMs - backgroundedAt >= backgroundTimeoutMs
        ) {
            removeNonActiveSessions { true }
        } else {
            emptySet()
        }
    }

    fun onMemoryPressure(): Set<Long> = removeNonActiveSessions { true }

    private fun removeNonActiveSessions(
        predicate: (MutableSession) -> Boolean
    ): Set<Long> {
        val removed = liveSessions.values
            .filter { it.pwaId != activePwaId && predicate(it) }
            .mapTo(mutableSetOf()) { it.pwaId }
        removed.forEach {
            liveSessions.remove(it)
            attentionPwaIds.remove(it)
        }
        if (removed.isNotEmpty()) clearGesture()
        return removed
    }

    private fun trimRecents() {
        while (recentPwaIds.size > maxRecentPwas) {
            recentPwaIds.removeAt(recentPwaIds.lastIndex)
        }
    }

    private fun evictionPriority(phase: PwaSessionPhase): Int = when (phase) {
        PwaSessionPhase.WARM -> 0
        PwaSessionPhase.ATTENTION -> 1
        PwaSessionPhase.PENDING_CLOSE -> 2
        PwaSessionPhase.ACTIVE -> 3
    }

    companion object {
        const val MAX_LIVE_SESSIONS = 4
        const val MAX_RECENT_PWAS = 8
        const val FOREGROUND_IDLE_TIMEOUT_MS = 10 * 60 * 1000L
        const val BACKGROUND_WARM_TIMEOUT_MS = 2 * 60 * 1000L
    }
}
