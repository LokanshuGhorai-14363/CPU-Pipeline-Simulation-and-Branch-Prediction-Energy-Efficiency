package simulator

/**
 * Shared per-cycle context passed to every pipeline stage.
 */
data class PipelineContext(
    val cycle: Long,
    val predictor: BranchPredictor,
    val powerTracker: PowerTracker,
    val flushController: FlushController,
    var redirectPending: Boolean = false,
    var redirectPc: Long = 0L,
    var mispredictDetectedThisCycle: Boolean = false
)

/**
 * Controls gradual pipeline repair after a branch misprediction.
 * Fetch and FTQ continue emitting wrong-path work until the penalty drains.
 */
class FlushController(
    val wrongPathPenaltyCycles: Int = 2
) {
    var active: Boolean = false
        private set
    var penaltyCyclesRemaining: Int = 0
        private set
    var redirectPc: Long = 0L
        private set
    var flushComplete: Boolean = false
        private set

    fun startFlush(correctPc: Long) {
        active = true
        flushComplete = false
        penaltyCyclesRemaining = wrongPathPenaltyCycles
        redirectPc = correctPc
    }

    /** Advance flush state; returns true when repair is complete. */
    fun tick(): Boolean {
        if (!active) return false
        penaltyCyclesRemaining--
        if (penaltyCyclesRemaining <= 0) {
            active = false
            flushComplete = true
            return true
        }
        return false
    }

    fun stillDrainingWrongPath(): Boolean = active

    fun resetCompleteFlag() {
        flushComplete = false
    }
}

interface PipelineStage {
    val name: String
    fun process(input: PipelineSlot, context: PipelineContext): PipelineSlot
}

/** IF — consumes trace/FTQ and fills IF/ID. */
class FetchStage(
    private val trace: List<Instruction>,
    private val ftq: FetchTargetQueue
) : PipelineStage {
    override val name = "Fetch"

    private var traceIndex = 0
    private var fetchSeq = 0L
    private var fetchPc = trace.firstOrNull()?.pc ?: 0L
    private var wrongPathPc = 0L

    val traceExhausted: Boolean get() = traceIndex >= trace.size

    fun resetToPc(pc: Long) {
        fetchPc = pc
        traceIndex = trace.indexOfFirst { it.pc >= pc }.let { if (it == -1) trace.size else it }
    }

    fun currentPc(): Long = fetchPc

    override fun process(input: PipelineSlot, context: PipelineContext): PipelineSlot {
        // Fetch ignores its input register (IF/ID is output of this stage).
        val flush = context.flushController

        if (flush.stillDrainingWrongPath()) {
            val ftqEntry = ftq.peek()
            val slot = if (ftqEntry != null) {
                ftq.dequeue()
                context.powerTracker.recordWrongPathFetch()
                PipelineSlot.wrongPathBubble(ftqEntry.targetPc, fetchSeq++)
            } else {
                wrongPathPc += 4
                context.powerTracker.recordWrongPathFetch()
                PipelineSlot.wrongPathBubble(wrongPathPc, fetchSeq++)
            }
            context.powerTracker.recordStageTransition(name, slot)
            return slot
        }

        if (flush.flushComplete) {
            resetToPc(flush.redirectPc)
            ftq.purgeSquashed()
            flush.resetCompleteFlag()
        }

        if (traceIndex >= trace.size) {
            return PipelineSlot.empty()
        }

        val instruction = trace[traceIndex]
        fetchPc = instruction.pc
        val predictedTaken = when (instruction.type) {
            InstructionType.NON_BRANCH -> null
            else -> context.predictor.predict(instruction.pc, instruction.type)
        }

        val slot = PipelineSlot.fromInstruction(instruction, fetchSeq++, predictedTaken)

        if (instruction.type != InstructionType.NON_BRANCH && predictedTaken == true) {
            val fallThroughPc = instruction.pc + 4
            val targetPc = if (traceIndex + 1 < trace.size) trace[traceIndex + 1].pc else fallThroughPc + 4
            ftq.enqueue(targetPc, instruction.pc)
            wrongPathPc = fallThroughPc
        }

        traceIndex++
        fetchPc += 4
        context.powerTracker.recordStageTransition(name, slot)
        return slot
    }
}

/** ID — decode and pass operands (stubbed). */
class DecodeStage : PipelineStage {
    override val name = "Decode"

    override fun process(input: PipelineSlot, context: PipelineContext): PipelineSlot {
        if (input.isEmpty) return input
        if (context.flushController.stillDrainingWrongPath() && input.state == SlotState.VALID) {
            val squashed = input.squash()
            context.powerTracker.recordStageTransition(name, squashed)
            return squashed
        }
        context.powerTracker.recordStageTransition(name, input)
        return input
    }
}

/** EX — branch resolution and misprediction detection. */
class ExecuteStage : PipelineStage {
    override val name = "Execute"

    override fun process(input: PipelineSlot, context: PipelineContext): PipelineSlot {
        if (input.isEmpty) return input

        context.powerTracker.recordStageTransition(name, input)

        if (input.isSquashed || input.isBubble) return input
        if (!input.isBranch || input.instruction == null) return input

        val actualTaken = when (input.instruction.type) {
            InstructionType.UNCONDITIONAL -> true
            InstructionType.CONDITIONAL -> input.instruction.actualOutcome
            InstructionType.NON_BRANCH -> false
        }
        val predictedTaken = input.predictedTaken ?: false

        if (predictedTaken != actualTaken) {
            context.mispredictDetectedThisCycle = true
            context.redirectPending = true
            val correctPc = if (actualTaken) {
                input.pc + 8
            } else {
                input.pc + 4
            }
            context.redirectPc = correctPc
        }

        return input
    }
}

/** MEM — memory access stub; squashed ops still toggle datapath power. */
class MemoryStage : PipelineStage {
    override val name = "Memory"

    override fun process(input: PipelineSlot, context: PipelineContext): PipelineSlot {
        if (input.isEmpty) return input
        context.powerTracker.recordStageTransition(name, input)
        return input
    }
}

/** WB — writeback/commit. Squashed and bubble ops do not commit. */
class WritebackStage : PipelineStage {
    override val name = "Writeback"

    var committedInstructions: Long = 0
        private set

    override fun process(input: PipelineSlot, context: PipelineContext): PipelineSlot {
        if (input.isEmpty) return PipelineSlot.empty()
        context.powerTracker.recordStageTransition(name, input)
        if (input.state == SlotState.VALID) {
            committedInstructions++
        }
        return PipelineSlot.empty()
    }
}
