package simulator

/**
 * Energy accounting stubs. Dynamic switching is charged per active stage transition;
 * static leakage is charged every cycle from the branch predictor footprint.
 */
class PowerTracker(
    private val cycleTimeNs: Double = 0.5,
    private val joulesPerStageTransition: Double = 1.0e-12,
    private val joulesPerSquashedStageTransition: Double = 1.2e-12,
    private val joulesPerFlushEvent: Double = 5.0e-12,
    private val joulesPerFtqSquashEntry: Double = 0.8e-12
) {
    var staticLeakageJoules: Double = 0.0
        private set
    var dynamicSwitchingJoules: Double = 0.0
        private set
    var squashedInstructionCycles: Long = 0
        private set
    var flushEvents: Long = 0
        private set

    fun tickStaticLeakage(predictor: BranchPredictor) {
        val watts = predictor.staticLeakagePowerWatts()
        staticLeakageJoules += watts * (cycleTimeNs * 1e-9)
    }

    fun recordStageTransition(stageName: String, slot: PipelineSlot) {
        if (!slot.isActive) return
        val cost = if (slot.isSquashed) joulesPerSquashedStageTransition else joulesPerStageTransition
        dynamicSwitchingJoules += cost
        if (slot.isSquashed) squashedInstructionCycles++
    }

    fun recordFlush(ftqSquashCount: Int) {
        flushEvents++
        dynamicSwitchingJoules += joulesPerFlushEvent
        dynamicSwitchingJoules += ftqSquashCount * joulesPerFtqSquashEntry
    }

    fun recordWrongPathFetch() {
        dynamicSwitchingJoules += joulesPerSquashedStageTransition
    }

    fun totalEnergyJoules(): Double = staticLeakageJoules + dynamicSwitchingJoules

    fun summary(): String = buildString {
        appendLine("=== Power Summary ===")
        appendLine("Static leakage:       ${"%.6e".format(staticLeakageJoules)} J")
        appendLine("Dynamic switching:    ${"%.6e".format(dynamicSwitchingJoules)} J")
        appendLine("Total energy:         ${"%.6e".format(totalEnergyJoules())} J")
        appendLine("Flush events:         $flushEvents")
        appendLine("Squashed instr-cycles: $squashedInstructionCycles")
    }
}
