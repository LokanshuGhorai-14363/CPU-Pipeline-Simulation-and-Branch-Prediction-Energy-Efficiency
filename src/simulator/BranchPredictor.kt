package simulator

/**
 * Branch predictor interface. Phase 3 adds train/update on branch resolution
 * and bit-accurate leakage scaling via [PredictorPowerModel].
 */
interface BranchPredictor {
    fun predict(pc: Long, type: InstructionType): Boolean

    /** Train predictor state after branch resolution in Execute. */
    fun update(pc: Long, actualOutcome: Boolean, predictedOutcome: Boolean)

    /** Static leakage power in watts, scaled from [storageBits]. */
    fun staticLeakagePowerWatts(): Double

    /** Total predictor storage footprint in bits. */
    fun storageBits(): Long
}

/** Shared hardware footprint and leakage scaling for all predictors. */
object PredictorPowerModel {
    const val LEAKAGE_WATTS_PER_BIT: Double = 1.5e-10

    fun staticLeakage(bits: Long): Double = bits * LEAKAGE_WATTS_PER_BIT

    const val COUNTER_BITS: Int = 2
    const val WEIGHT_BITS: Int = 8
}

/**
 * Always-not-taken for conditional branches; taken for unconditional.
 * Zero storage — lower-bound dynamic-flush baseline.
 */
class AlwaysNotTakenPredictor : BranchPredictor {
    override fun predict(pc: Long, type: InstructionType): Boolean =
        type == InstructionType.UNCONDITIONAL

    override fun update(pc: Long, actualOutcome: Boolean, predictedOutcome: Boolean) = Unit

    override fun staticLeakagePowerWatts(): Double =
        PredictorPowerModel.staticLeakage(storageBits())

    override fun storageBits(): Long = 0L
}

/**
 * Oracle predictor — never mispredicts. Useful as an accuracy upper bound.
 */
class OraclePredictor(private val trace: List<Instruction>) : BranchPredictor {
    private val outcomeByPc = trace.associate { it.pc to it.actualOutcome }

    override fun predict(pc: Long, type: InstructionType): Boolean =
        when (type) {
            InstructionType.NON_BRANCH -> false
            InstructionType.UNCONDITIONAL -> true
            InstructionType.CONDITIONAL -> outcomeByPc[pc] ?: false
        }

    override fun update(pc: Long, actualOutcome: Boolean, predictedOutcome: Boolean) = Unit

    override fun staticLeakagePowerWatts(): Double =
        PredictorPowerModel.staticLeakage(storageBits())

    override fun storageBits(): Long = 0L
}
