package simulator

/**
 * Orchestrates the 5-stage cycle-accurate pipeline, FTQ redirection, and
 * gradual wrong-path drain on branch misprediction.
 */
class PipelineManager(
    private val trace: List<Instruction>,
    private val predictor: BranchPredictor,
    private val powerTracker: PowerTracker = PowerTracker(),
    private val ftq: FetchTargetQueue = FetchTargetQueue(),
    private val flushController: FlushController = FlushController()
) {
    private val fetchStage = FetchStage(trace, ftq)
    private val decodeStage = DecodeStage()
    private val executeStage = ExecuteStage()
    private val memoryStage = MemoryStage()
    private val writebackStage = WritebackStage()

    // Inter-stage registers (classic IF/ID, ID/EX, EX/MEM, MEM/WB)
    private var ifId = PipelineSlot.empty()
    private var idEx = PipelineSlot.empty()
    private var exMem = PipelineSlot.empty()
    private var memWb = PipelineSlot.empty()

    var cycleCount: Long = 0
        private set
    var mispredictions: Long = 0
        private set

    val committedInstructions: Long
        get() = writebackStage.committedInstructions

    /**
     * Single cycle of the event-driven pipeline. Stages execute reverse order
     * (WB first) so each register holds the value produced in the prior cycle,
     * matching standard pipeline timing.
     */
    fun cycle(): Boolean {
        if (trace.isEmpty()) return false

        cycleCount++
        val context = PipelineContext(
            cycle = cycleCount,
            predictor = predictor,
            powerTracker = powerTracker,
            flushController = flushController
        )

        powerTracker.tickStaticLeakage(predictor)

        // --- Writeback ---
        writebackStage.process(memWb, context)

        // --- Memory ---
        val nextMemWb = memoryStage.process(exMem, context)

        // --- Execute (branch resolution) ---
        val nextExMem = executeStage.process(idEx, context)

        // --- Decode ---
        var nextIdEx = decodeStage.process(ifId, context)

        // --- Fetch ---
        var nextIfId = fetchStage.process(PipelineSlot.empty(), context)

        // Handle misprediction redirect after Execute resolves
        if (context.mispredictDetectedThisCycle) {
            handleMisprediction(context.redirectPc)
            // Squash younger in-flight ops (IF/ID and ID/EX); EX/MEM holds the branch itself
            nextIfId = squashIfValid(nextIfId)
            nextIdEx = squashIfValid(nextIdEx)
        }

        // Commit register updates for next cycle
        memWb = nextMemWb
        exMem = nextExMem
        idEx = nextIdEx
        ifId = nextIfId

        flushController.tick()

        return cycleCount <= maxCycles() && !simulationComplete()
    }

    /**
     * Misprediction handler: FTQ flush + gradual wrong-path penalty instead of
     * instantaneous architectural repair.
     */
    private fun handleMisprediction(correctPc: Long) {
        mispredictions++
        val squashedFtqEntries = ftq.flush()
        powerTracker.recordFlush(squashedFtqEntries.size)
        flushController.startFlush(correctPc)
    }

    private fun squashIfValid(slot: PipelineSlot): PipelineSlot {
        if (slot.isActive && slot.state == SlotState.VALID) {
            val squashed = slot.squash()
            powerTracker.recordStageTransition("SquashMark", squashed)
            return squashed
        }
        return slot
    }

    private fun maxCycles(): Long =
        trace.size.toLong() * 8 + flushController.wrongPathPenaltyCycles * 4 + 16

    private fun simulationComplete(): Boolean {
        val pipelineEmpty = ifId.isEmpty && idEx.isEmpty && exMem.isEmpty && memWb.isEmpty
        return fetchStage.traceExhausted && pipelineEmpty && !flushController.stillDrainingWrongPath()
    }

    fun run(): SimulationResult {
        while (cycle()) { /* event loop */ }
        return SimulationResult(
            cycles = cycleCount,
            committedInstructions = committedInstructions,
            mispredictions = mispredictions,
            powerTracker = powerTracker
        )
    }

    fun pipelineSnapshot(): PipelineSnapshot = PipelineSnapshot(
        cycle = cycleCount,
        ifId = ifId,
        idEx = idEx,
        exMem = exMem,
        memWb = memWb,
        ftqDepth = ftq.size,
        flushActive = flushController.stillDrainingWrongPath(),
        flushPenaltyRemaining = flushController.penaltyCyclesRemaining
    )
}

data class PipelineSnapshot(
    val cycle: Long,
    val ifId: PipelineSlot,
    val idEx: PipelineSlot,
    val exMem: PipelineSlot,
    val memWb: PipelineSlot,
    val ftqDepth: Int,
    val flushActive: Boolean,
    val flushPenaltyRemaining: Int
)

data class SimulationResult(
    val cycles: Long,
    val committedInstructions: Long,
    val mispredictions: Long,
    val powerTracker: PowerTracker
) {
    fun report(): String = buildString {
        appendLine("=== Simulation Result ===")
        appendLine("Cycles:               $cycles")
        appendLine("Committed:            $committedInstructions")
        appendLine("Mispredictions:       $mispredictions")
        append(powerTracker.summary())
    }
}
