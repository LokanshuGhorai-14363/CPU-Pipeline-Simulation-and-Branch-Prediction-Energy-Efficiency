package simulator

import java.io.File

/**
 * Phase 3 entry point — comparative energy/accuracy sweep across predictor configs.
 */
fun main() {
    val trace = loadSweepTrace()
    println("Loaded ${trace.size} trace entries for energy sweep\n")

    val sweep = EnergySweep(trace)
    val rows = sweep.run()
    println(sweep.formatTable(rows))
}

private fun loadSweepTrace(): List<Instruction> {
    val bundledTrace = """
        # Phase 3 sweep trace — mixed branch patterns
        0x40001000 COND 0
        0x40001004 COND 0
        0x40001008 COND 0
        0x4000100C COND 1
        0x40001010 NON_BRANCH 0
        0x40001014 COND 1
        0x40001018 COND 1
        0x4000101C COND 0
        0x40001020 COND 1
        0x40001024 NON_BRANCH 0
        0x40001028 UNCOND 1
        0x4000102C COND 0
        0x40001030 COND 0
        0x40001034 COND 1
        0x40001038 COND 0
        0x4000103C NON_BRANCH 0
        0x40001040 COND 1
        0x40001044 COND 1
        0x40001048 COND 0
        0x4000104C COND 0
        0x40001050 COND 1
        0x40001054 NON_BRANCH 0
        0x40001058 COND 0
        0x4000105C COND 0
        0x40001060 COND 1
        0x40001064 COND 1
        0x40001068 UNCOND 1
        0x4000106C COND 0
        0x40001070 COND 1
        0x40001074 NON_BRANCH 0
    """.trimIndent()

    val tempFile = File.createTempFile("phase3_sweep_trace", ".txt")
    tempFile.writeText(bundledTrace)
    tempFile.deleteOnExit()
    return TraceReader().parseTrace(tempFile.absolutePath)
}
