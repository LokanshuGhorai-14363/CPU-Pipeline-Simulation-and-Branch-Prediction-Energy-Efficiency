package simulator

/**
 * Phase 3 energy pareto sweep harness — runs multiple predictor configurations
 * through the cycle-accurate pipeline and collects accuracy/energy metrics.
 */
data class PredictorConfig(
    val name: String,
    val factory: () -> BranchPredictor
)

data class SweepRow(
    val predictorType: String,
    val accuracyPercent: Double,
    val mispredictions: Long,
    val staticLeakageJ: Double,
    val dynamicEnergyJ: Double,
    val totalEnergyJ: Double,
    val cycles: Long,
    val storageBits: Long
)

class EnergySweep(
    private val trace: List<Instruction>,
    private val configs: List<PredictorConfig> = defaultPredictorConfigs()
) {
    fun run(): List<SweepRow> =
        configs.map { config ->
            val predictor = config.factory()
            val result = PipelineManager(trace, predictor).run()
            val branchCount = trace.count { it.type != InstructionType.NON_BRANCH }
            val correct = branchCount - result.mispredictions
            val accuracy = if (branchCount > 0) {
                correct.toDouble() / branchCount.toDouble() * 100.0
            } else {
                100.0
            }

            SweepRow(
                predictorType = config.name,
                accuracyPercent = accuracy,
                mispredictions = result.mispredictions,
                staticLeakageJ = result.powerTracker.staticLeakageJoules,
                dynamicEnergyJ = result.powerTracker.dynamicSwitchingJoules,
                totalEnergyJ = result.powerTracker.totalEnergyJoules(),
                cycles = result.cycles,
                storageBits = predictor.storageBits()
            )
        }

    fun formatTable(rows: List<SweepRow>): String {
        val header = String.format(
            "%-22s %10s %14s %16s %16s %16s",
            "Predictor Type",
            "Accuracy",
            "Mispredictions",
            "Static Leak (J)",
            "Dynamic (J)",
            "Total Energy (J)"
        )
        val divider = "-".repeat(header.length)
        val body = rows.joinToString("\n") { row ->
            String.format(
                "%-22s %9.2f%% %14d %16.6e %16.6e %16.6e",
                row.predictorType,
                row.accuracyPercent,
                row.mispredictions,
                row.staticLeakageJ,
                row.dynamicEnergyJ,
                row.totalEnergyJ
            )
        }
        return buildString {
            appendLine("=== Phase 3 Energy Pareto Sweep ===")
            appendLine("Trace instructions: ${trace.size}")
            appendLine("Branch instructions: ${trace.count { it.type != InstructionType.NON_BRANCH }}")
            appendLine()
            appendLine(header)
            appendLine(divider)
            append(body)
            appendLine()
            appendLine("Pareto hint: lowest total energy with acceptable accuracy wins the trade-off.")
        }
    }

    companion object {
        fun defaultPredictorConfigs(): List<PredictorConfig> = listOf(
            PredictorConfig("AlwaysNotTaken") { AlwaysNotTakenPredictor() },
            PredictorConfig("Bimodal-1K") { BimodalPredictor(tableSize = 1024) },
            PredictorConfig("Bimodal-4K") { BimodalPredictor(tableSize = 4096) },
            PredictorConfig("Gshare-8K") { GsharePredictor(globalHistoryBits = 13, tableSize = 8192) },
            PredictorConfig("Gshare-16K") { GsharePredictor(globalHistoryBits = 14, tableSize = 16384) },
            PredictorConfig("Perceptron") {
                PerceptronPredictor(historyLength = 24, threshold = 1, tableSize = 4096)
            }
        )
    }
}
