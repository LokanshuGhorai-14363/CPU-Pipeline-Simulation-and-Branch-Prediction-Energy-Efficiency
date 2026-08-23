package simulator

import java.io.File

/**
 * Phase 2 entry point — wires trace input, branch predictor, and the
 * cycle-accurate pipeline event loop.
 */
fun runPipelineSimulation(
    trace: List<Instruction>,
    predictor: BranchPredictor = AlwaysNotTakenPredictor()
): SimulationResult {
    val manager = PipelineManager(trace, predictor)
    return manager.run()
}

fun main() {
    val mockTraceData = """
        # PC         Type        Outcome (1=taken)
        0x40001000 COND 0
        0x40001004 NON_BRANCH 0
        0x40001008 COND 1
        0x4000100C NON_BRANCH 0
        0x40001010 UNCOND 1
        0x40001014 NON_BRANCH 0
        0x40001018 COND 0
        0x4000101C NON_BRANCH 0
    """.trimIndent()

    val tempFile = File.createTempFile("phase2_trace", ".txt")
    tempFile.writeText(mockTraceData)

    val trace = TraceReader().parseTrace(tempFile.absolutePath)
    println("Loaded ${trace.size} trace entries\n")

    println("--- Always-Not-Taken Predictor (exercises mispredictions) ---")
    val resultAnt = runPipelineSimulation(trace, AlwaysNotTakenPredictor())
    println(resultAnt.report())

    println("\n--- Oracle Predictor (no mispredictions baseline) ---")
    val resultOracle = runPipelineSimulation(trace, OraclePredictor(trace))
    println(resultOracle.report())

    tempFile.delete()
}
