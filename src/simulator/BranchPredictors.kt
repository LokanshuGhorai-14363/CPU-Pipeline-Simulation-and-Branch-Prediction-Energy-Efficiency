package simulator

/**
 * Phase 3 realistic branch predictor implementations with train/update support
 * and bit-accurate storage/leakage models.
 */

/** 2-bit saturating counter (values 0..3; predict taken when >= 2). */
internal class SaturatingCounter2Bit(initial: Int = 1) {
    private var value: Int = initial.coerceIn(0, 3)

    fun predictTaken(): Boolean = value >= 2

    fun update(actualTaken: Boolean) {
        value = when {
            actualTaken && value < 3 -> value + 1
            !actualTaken && value > 0 -> value - 1
            else -> value
        }
    }
}

/** Clamp signed weight to 8-bit range for perceptron storage model. */
internal fun clampWeight8(w: Int): Int = w.coerceIn(-128, 127)

/**
 * Bimodal predictor: table of 2-bit saturating counters indexed by PC mod tableSize.
 * Storage: tableSize × 2 bits.
 */
class BimodalPredictor(private val tableSize: Int) : BranchPredictor {
    init {
        require(tableSize > 0) { "tableSize must be positive" }
    }

    private val counters = Array(tableSize) { SaturatingCounter2Bit(1) }

    private fun index(pc: Long): Int =
        (pc % tableSize).toInt().let { if (it < 0) it + tableSize else it }

    override fun predict(pc: Long, type: InstructionType): Boolean =
        when (type) {
            InstructionType.UNCONDITIONAL -> true
            InstructionType.NON_BRANCH -> false
            InstructionType.CONDITIONAL -> counters[index(pc)].predictTaken()
        }

    override fun update(pc: Long, actualOutcome: Boolean, predictedOutcome: Boolean) {
        counters[index(pc)].update(actualOutcome)
    }

    override fun storageBits(): Long =
        tableSize.toLong() * PredictorPowerModel.COUNTER_BITS

    override fun staticLeakagePowerWatts(): Double =
        PredictorPowerModel.staticLeakage(storageBits())
}

/**
 * Gshare predictor: GHR XOR branch PC indexes 2-bit saturating counters.
 * Storage: tableSize × 2 bits + globalHistoryBits (GHR register).
 */
class GsharePredictor(
    private val globalHistoryBits: Int,
    private val tableSize: Int
) : BranchPredictor {
    init {
        require(globalHistoryBits in 1..63) { "globalHistoryBits must be in 1..63" }
        require(tableSize > 0) { "tableSize must be positive" }
    }

    private val counters = Array(tableSize) { SaturatingCounter2Bit(1) }
    private var ghr: Long = 0L
    private val ghrMask: Long = (1L shl globalHistoryBits) - 1L

    private fun index(pc: Long): Int {
        val pcBits = (pc ushr 2).toInt()
        val hashed = (ghr.toInt() xor pcBits) and (tableSize - 1)
        return if (tableSize and (tableSize - 1) == 0) {
            hashed
        } else {
            ((ghr xor (pc ushr 2)) % tableSize).toInt().let { if (it < 0) it + tableSize else it }
        }
    }

    private fun shiftGhr(actualTaken: Boolean) {
        ghr = ((ghr shl 1) or if (actualTaken) 1L else 0L) and ghrMask
    }

    override fun predict(pc: Long, type: InstructionType): Boolean =
        when (type) {
            InstructionType.UNCONDITIONAL -> true
            InstructionType.NON_BRANCH -> false
            InstructionType.CONDITIONAL -> counters[index(pc)].predictTaken()
        }

    override fun update(pc: Long, actualOutcome: Boolean, predictedOutcome: Boolean) {
        counters[index(pc)].update(actualOutcome)
        shiftGhr(actualOutcome)
    }

    override fun storageBits(): Long =
        tableSize.toLong() * PredictorPowerModel.COUNTER_BITS + globalHistoryBits

    override fun staticLeakagePowerWatts(): Double =
        PredictorPowerModel.staticLeakage(storageBits())
}

/**
 * Perceptron branch predictor (Jimenez & Lin style): dot-product of weight
 * vector against global history, thresholded for taken/not-taken.
 *
 * Storage: tableSize × (historyLength + 1) × 8-bit weights + historyLength GHR
 *          + 8-bit threshold register.
 */
class PerceptronPredictor(
    private val historyLength: Int,
    private val threshold: Int,
    private val tableSize: Int = 4096
) : BranchPredictor {
    init {
        require(historyLength in 1..62) { "historyLength must be in 1..62" }
        require(tableSize > 0) { "tableSize must be positive" }
        require(threshold >= 0) { "threshold must be non-negative" }
    }

    private val weights: Array<IntArray> = Array(tableSize) { IntArray(historyLength + 1) }
    private var ghr: Long = 0L
    private val ghrMask: Long = (1L shl historyLength) - 1L

    private fun index(pc: Long): Int =
        ((pc ushr 2) xor ghr).toInt().let { raw ->
            (raw % tableSize).let { if (it < 0) it + tableSize else it }
        }

    private fun historySign(bitIndex: Int): Int =
        if ((ghr shr bitIndex) and 1L == 1L) 1 else -1

    private fun dotProduct(entry: IntArray): Int {
        var sum = entry[0]
        for (i in 0 until historyLength) {
            sum += entry[i + 1] * historySign(i)
        }
        return sum
    }

    private fun shiftGhr(actualTaken: Boolean) {
        ghr = ((ghr shl 1) or if (actualTaken) 1L else 0L) and ghrMask
    }

    override fun predict(pc: Long, type: InstructionType): Boolean =
        when (type) {
            InstructionType.UNCONDITIONAL -> true
            InstructionType.NON_BRANCH -> false
            InstructionType.CONDITIONAL -> dotProduct(weights[index(pc)]) >= threshold
        }

    override fun update(pc: Long, actualOutcome: Boolean, predictedOutcome: Boolean) {
        val idx = index(pc)
        val entry = weights[idx]
        val y = dotProduct(entry)
        val actualSign = if (actualOutcome) 1 else -1
        val predictedSign = if (predictedOutcome) 1 else -1

        if (predictedSign != actualSign || kotlin.math.abs(y) <= threshold) {
            entry[0] = clampWeight8(entry[0] + actualSign)
            for (i in 0 until historyLength) {
                entry[i + 1] = clampWeight8(entry[i + 1] + actualSign * historySign(i))
            }
        }
        shiftGhr(actualOutcome)
    }

    override fun storageBits(): Long {
        val weightStorage = tableSize.toLong() * (historyLength + 1) * PredictorPowerModel.WEIGHT_BITS
        val thresholdBits = 8L
        return weightStorage + historyLength + thresholdBits
    }

    override fun staticLeakagePowerWatts(): Double =
        PredictorPowerModel.staticLeakage(storageBits())
}
