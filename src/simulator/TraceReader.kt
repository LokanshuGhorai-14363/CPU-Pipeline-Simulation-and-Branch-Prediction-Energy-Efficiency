package simulator

import java.io.File

// Phase 1: simulator.main.simulator.Instruction Data Structure and Trace Reader

enum class InstructionType {
    CONDITIONAL,
    UNCONDITIONAL,
    NON_BRANCH
}

data class Instruction(
    val pc: Long,
    val type: InstructionType,
    val actualOutcome: Boolean // true for Taken/Target reached, false for Not Taken
)

class TraceReader {
    fun parseTrace(filePath: String): List<Instruction> {
        val instructions = mutableListOf<Instruction>()
        val file = File(filePath)

        if (!file.exists()) {
            println("Trace file not found. Returning empty list.")
            return instructions
        }

        file.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val parts = trimmed.split("\\s+".toRegex())
                if (parts.size >= 3) {
                    val pc = parts[0].removePrefix("0x").toLong(16)
                    val type = when (parts[1].uppercase()) {
                        "COND", "C" -> InstructionType.CONDITIONAL
                        "UNCOND", "U" -> InstructionType.UNCONDITIONAL
                        else -> InstructionType.NON_BRANCH
                    }
                    val outcome = parts[2].toInt() == 1

                    instructions.add(Instruction(pc, type, outcome))
                }
            }
        }
        return instructions
    }
}
