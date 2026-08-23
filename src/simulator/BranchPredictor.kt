package simulator

/**
 * Branch predictor interface. Phase 2 ships with a trace-aware stub so mispredictions
 * can be exercised; later phases swap in real predictor structures and leakage models.
 */
interface BranchPredictor {
    fun predict(pc: Long, type: InstructionType): Boolean

    /** Static leakage power in watts (stub for energy threshold analysis). */
    fun staticLeakagePowerWatts(): Double

    /** Predictor storage footprint in bits (stub for area/leakage scaling). */
    fun storageBits(): Long
}

/**
 * Always-not-taken predictor — guarantees mispredictions on taken branches.
 */
class AlwaysNotTakenPredictor : BranchPredictor {
    override fun predict(pc: Long, type: InstructionType): Boolean =
        type == InstructionType.UNCONDITIONAL

    override fun staticLeakagePowerWatts(): Double = 0.05

    override fun storageBits(): Long = 0L
}

/**
 * Oracle predictor — never mispredicts. Useful as an upper-bound baseline.
 */
class OraclePredictor(private val trace: List<Instruction>) : BranchPredictor {
    private val outcomeByPc = trace.associate { it.pc to it.actualOutcome }

    override fun predict(pc: Long, type: InstructionType): Boolean =
        when (type) {
            InstructionType.NON_BRANCH -> false
            InstructionType.UNCONDITIONAL -> true
            InstructionType.CONDITIONAL -> outcomeByPc[pc] ?: false
        }

    override fun staticLeakagePowerWatts(): Double = 0.0

    override fun storageBits(): Long = 0L
}
