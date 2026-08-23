package simulator

enum class SlotState {
    EMPTY,
    VALID,
    SQUASHED,
    BUBBLE
}

/**
 * Contents of an inter-stage pipeline register.
 * SQUASHED slots represent wrong-path instructions that continue to move through
 * the datapath for power accounting but must not commit architectural state.
 */
data class PipelineSlot(
    val instruction: Instruction? = null,
    val state: SlotState = SlotState.EMPTY,
    val pc: Long = 0L,
    val fetchSeq: Long = 0L,
    val predictedTaken: Boolean? = null,
    val isBranch: Boolean = false
) {
    val isEmpty: Boolean get() = state == SlotState.EMPTY
    val isSquashed: Boolean get() = state == SlotState.SQUASHED
    val isBubble: Boolean get() = state == SlotState.BUBBLE
    val isActive: Boolean get() = state == SlotState.VALID || state == SlotState.SQUASHED

    fun squash(): PipelineSlot = copy(state = SlotState.SQUASHED)

    fun asBubble(): PipelineSlot = copy(
        instruction = null,
        state = SlotState.BUBBLE,
        isBranch = false,
        predictedTaken = null
    )

    companion object {
        fun empty() = PipelineSlot()

        fun fromInstruction(
            instruction: Instruction,
            fetchSeq: Long,
            predictedTaken: Boolean? = null
        ): PipelineSlot {
            val isBranch = instruction.type != InstructionType.NON_BRANCH
            return PipelineSlot(
                instruction = instruction,
                state = SlotState.VALID,
                pc = instruction.pc,
                fetchSeq = fetchSeq,
                predictedTaken = predictedTaken,
                isBranch = isBranch
            )
        }

        /** Synthetic wrong-path fetch used after an FTQ redirection. */
        fun wrongPathBubble(pc: Long, fetchSeq: Long): PipelineSlot = PipelineSlot(
            instruction = Instruction(pc, InstructionType.NON_BRANCH, actualOutcome = false),
            state = SlotState.SQUASHED,
            pc = pc,
            fetchSeq = fetchSeq,
            isBranch = false
        )
    }
}
