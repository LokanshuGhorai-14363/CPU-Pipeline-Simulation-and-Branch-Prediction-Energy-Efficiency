package simulator

/**
 * Fetch Target Queue (FTQ) decouples branch prediction from instruction fetch.
 * On redirection the queue is logically flushed, but squashed entries may still
 * be emitted for wrong-path execution before the repair completes.
 */
data class FTQEntry(
    val targetPc: Long,
    val sourceBranchPc: Long,
    val squashed: Boolean = false,
    val enqueueSeq: Long = 0L
)

class FetchTargetQueue(private val capacity: Int = 8) {
    private val entries = ArrayDeque<FTQEntry>()
    private var seqCounter = 0L

    val size: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()

    fun enqueue(targetPc: Long, sourceBranchPc: Long): Boolean {
        if (entries.size >= capacity) return false
        entries.addLast(
            FTQEntry(
                targetPc = targetPc,
                sourceBranchPc = sourceBranchPc,
                enqueueSeq = seqCounter++
            )
        )
        return true
    }

    /** Peek/dequeue the head entry even if squashed (wrong-path modeling). */
    fun peek(): FTQEntry? = entries.firstOrNull()

    fun dequeue(): FTQEntry? = entries.removeFirstOrNull()

    /**
     * Mark every queued entry as squashed and return the entries that were
     * invalidated so the power model can account for wasted fetch bandwidth.
     */
    fun flush(): List<FTQEntry> {
        val squashed = entries.map { it.copy(squashed = true) }
        entries.clear()
        entries.addAll(squashed)
        return squashed
    }

    /** Drop all squashed entries once wrong-path drain completes. */
    fun purgeSquashed() {
        entries.removeAll { it.squashed }
    }

    fun clear() {
        entries.clear()
    }
}
