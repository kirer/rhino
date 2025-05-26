package org.mozilla.javascript

// 根据Rhino 1.7.14字节码实际值定义常量
private const val ICODE_GOTO = Token.GOTO
private const val ICODE_IFEQ = Token.IFEQ
private const val ICODE_IFNE = Token.IFNE
private const val ICODE_RETURN = Token.RETURN
private const val ICODE_RETURN_RESULT = Icode.Icode_RETSUB.toInt()

/**
 * Rhino字节码控制流分析器
 * 用于分析Rhino字节码并构建控制流图，以便更好地反编译JavaScript代码
 */
class RhinoDecompilerControlFlowAnalyzer {
    companion object {
        // 调试模式开关
        private val DEBUG = RhinoDecompiler3.DEBUG

        /**
         * 分析字节码构建控制流图
         */
        fun analyze(iCode: ByteArray): ControlFlowGraph {
            if (DEBUG) println("开始控制流分析...")

            // 创建控制流图
            val cfg = ControlFlowGraph()

            // 1. 识别基本块
            val basicBlocks = identifyBasicBlocks(iCode)
            cfg.blocks.addAll(basicBlocks)

            if (DEBUG) println("识别到 ${basicBlocks.size} 个基本块")

            // 2. 构建控制流边
            buildControlFlowEdges(cfg, iCode)

            // 3. 识别高级控制结构
            identifyControlStructures(cfg, iCode)

            if (DEBUG) println("识别到 ${cfg.structures.size} 个控制结构")

            return cfg
        }

        /**
         * 识别基本块
         * 基本块是一段连续执行的代码，没有跳转进入（除了块的开始）和跳转出去（除了块的结束）
         */
        private fun identifyBasicBlocks(iCode: ByteArray): List<BasicBlock> {
            val leaders = mutableSetOf<Int>() // 基本块的起始位置

            // 第一个指令总是一个基本块的起始
            leaders.add(0)

            // 扫描字节码，标记所有跳转目标和跳转指令之后的指令
            var pc = 0
            while (pc < iCode.size) {
                val opcode = iCode[pc].toInt() and 0xFF

                // 处理跳转指令
                when (opcode) {
                    Token.GOTO, Token.IFEQ, Token.IFNE -> {
                        val offset = getJumpOffset(iCode, pc)
                        val targetPC = pc + offset

                        // 跳转目标是基本块的起始
                        if (targetPC >= 0 && targetPC < iCode.size) {
                            leaders.add(targetPC)
                        }

                        // 跳转指令之后的指令也是基本块的起始
                        val nextPC = pc + getInstructionLength(iCode, pc)
                        if (nextPC < iCode.size) {
                            leaders.add(nextPC)
                        }
                    }

                    Token.RETURN, Token.RETHROW -> {
                        // 返回指令之后的指令是基本块的起始
                        val nextPC = pc + 1
                        if (nextPC < iCode.size) {
                            leaders.add(nextPC)
                        }
                    }
                }

                // 移动到下一条指令
                pc += getInstructionLength(iCode, pc)
            }

            // 根据起始位置创建基本块
            val sortedLeaders = leaders.sorted()
            val blocks = mutableListOf<BasicBlock>()

            for (i in sortedLeaders.indices) {
                val start = sortedLeaders[i]
                val end = if (i < sortedLeaders.size - 1) sortedLeaders[i + 1] - 1 else iCode.size - 1

                blocks.add(BasicBlock(start, end))
            }

            return blocks
        }

        /**
         * 构建控制流边
         * 连接基本块之间的控制流关系
         */
        private fun buildControlFlowEdges(cfg: ControlFlowGraph, iCode: ByteArray) {
            // 为每个基本块构建后继块
            for (block in cfg.blocks) {
                // 获取块的最后一条指令
                var pc = block.end
                while (pc >= block.start) {
                    // 找到最后一条有效指令
                    if (pc < iCode.size) {
                        val opcode = iCode[pc].toInt() and 0xFF

                        // 处理不同类型的指令
                        when (opcode) {
                            Token.GOTO -> {
                                // 无条件跳转，只有一个后继
                                val offset = getJumpOffset(iCode, pc)
                                val targetPC = pc + offset

                                // 找到目标基本块
                                val targetBlock = cfg.blocks.find { it.start == targetPC }
                                if (targetBlock != null) {
                                    block.successors.add(targetBlock)
                                }
                            }

                            Token.IFEQ, Token.IFNE -> {
                                // 条件跳转，有两个后继
                                val offset = getJumpOffset(iCode, pc)
                                val targetPC = pc + offset

                                // 跳转目标
                                val targetBlock = cfg.blocks.find { it.start == targetPC }
                                if (targetBlock != null) {
                                    block.successors.add(targetBlock)
                                }

                                // 下一条指令
                                val nextPC = pc + getInstructionLength(iCode, pc)
                                val nextBlock = cfg.blocks.find { it.start == nextPC }
                                if (nextBlock != null) {
                                    block.successors.add(nextBlock)
                                }
                            }

                            Token.RETURN, Token.RETHROW -> {
                                // 返回指令没有后继
                            }

                            else -> {
                                // 普通指令，后继是下一个基本块
                                val nextPC = pc + getInstructionLength(iCode, pc)
                                val nextBlock = cfg.blocks.find { it.start == nextPC }
                                if (nextBlock != null) {
                                    block.successors.add(nextBlock)
                                }
                            }
                        }

                        // 找到了最后一条指令，退出循环
                        break
                    }
                    pc--
                }
            }

            // 为每个基本块构建前驱块
            for (block in cfg.blocks) {
                for (successor in block.successors) {
                    successor.predecessors.add(block)
                }
            }
        }

        /**
         * 识别高级控制结构
         * 如if-else, 循环等
         */
        private fun identifyControlStructures(cfg: ControlFlowGraph, iCode: ByteArray) {
            // 识别if-else结构
            identifyIfStructures(cfg, iCode)

            // 识别循环结构
            identifyLoopStructures(cfg, iCode)
        }

        /**
         * 识别if-else结构
         */
        private fun identifyIfStructures(cfg: ControlFlowGraph, iCode: ByteArray) {
            // 遍历所有基本块
            for (block in cfg.blocks) {
                // 检查块的最后一条指令是否是条件跳转
                val lastPC = block.end
                if (lastPC < iCode.size) {
                    val opcode = iCode[lastPC].toInt() and 0xFF

                    if (opcode == Token.IFEQ || opcode == Token.IFNE) {
                        // 找到条件跳转的目标块和下一个块
                        val offset = getJumpOffset(iCode, lastPC)
                        val targetPC = lastPC + offset
                        val targetBlock = cfg.blocks.find { it.start == targetPC }

                        val nextPC = lastPC + getInstructionLength(iCode, lastPC)
                        val nextBlock = cfg.blocks.find { it.start == nextPC }

                        if (targetBlock != null && nextBlock != null) {
                            // 创建if结构
                            val ifStructure = IfStructure(
                                condition = block,
                                thenBlock = if (opcode == Token.IFEQ) targetBlock else nextBlock,
                                elseBlock = if (opcode == Token.IFEQ) nextBlock else targetBlock
                            )

                            cfg.structures.add(ifStructure)
                        }
                    }
                }
            }
        }

        /**
         * 识别循环结构
         */
        private fun identifyLoopStructures(cfg: ControlFlowGraph, iCode: ByteArray) {
            // 寻找强连通分量(SCC)，这些是循环的候选者
            val sccs = findStronglyConnectedComponents(cfg)

            for (scc in sccs) {
                if (scc.size > 1 || (scc.size == 1 && scc[0].successors.contains(scc[0]))) {
                    // 找到循环的头部（有来自循环外的边指向的节点）
                    val header = scc.find { block ->
                        block.predecessors.any { it !in scc }
                    } ?: scc[0]

                    // 确定循环类型
                    val loopType = determineLoopType(header, scc, iCode)

                    // 创建循环结构
                    val loopStructure = LoopStructure(
                        type = loopType, header = header, body = scc
                    )

                    cfg.structures.add(loopStructure)
                }
            }
        }

        /**
         * 确定循环类型
         */
        private fun determineLoopType(
            header: BasicBlock, loopBlocks: List<BasicBlock>, iCode: ByteArray
        ): RhinoDecompiler3.LoopType {
            // 检查循环头部的指令模式来确定循环类型
            val headerPC = header.start

            // 检查是否有ENUM_NEXT指令，表示for-in循环
            for (block in loopBlocks) {
                for (pc in block.start..block.end) {
                    if (pc < iCode.size && iCode[pc].toInt() == Token.ENUM_NEXT) {
                        return RhinoDecompiler3.LoopType.FOR_IN
                    }
                }
            }

            // 检查是否有典型的for循环模式（初始化、条件检查、递增）
            // 这需要更复杂的模式匹配...

            // 默认为while循环
            return RhinoDecompiler3.LoopType.WHILE
        }

        /**
         * 寻找强连通分量(SCC)
         * 使用Tarjan算法
         */
        private fun findStronglyConnectedComponents(cfg: ControlFlowGraph): List<List<BasicBlock>> {
            val result = mutableListOf<List<BasicBlock>>()
            val indexMap = mutableMapOf<BasicBlock, Int>()
            val lowlinkMap = mutableMapOf<BasicBlock, Int>()
            val onStackSet = mutableSetOf<BasicBlock>()
            val stack = mutableListOf<BasicBlock>()
            var index = 0

            fun strongConnect(v: BasicBlock) {
                // 设置索引和lowlink
                indexMap[v] = index
                lowlinkMap[v] = index
                index++
                stack.add(v)
                onStackSet.add(v)

                // 考虑后继
                for (w in v.successors) {
                    if (w !in indexMap) {
                        // 后继尚未访问，递归
                        strongConnect(w)
                        lowlinkMap[v] = minOf(lowlinkMap[v]!!, lowlinkMap[w]!!)
                    } else if (w in onStackSet) {
                        // 后继在栈上，是一个回边
                        lowlinkMap[v] = minOf(lowlinkMap[v]!!, indexMap[w]!!)
                    }
                }

                // 检查是否是SCC的根
                if (lowlinkMap[v] == indexMap[v]) {
                    // 从栈中弹出SCC
                    val scc = mutableListOf<BasicBlock>()
                    var w: BasicBlock
                    do {
                        w = stack.removeAt(stack.size - 1)
                        onStackSet.remove(w)
                        scc.add(w)
                    } while (w != v)

                    result.add(scc)
                }
            }

            // 对每个未访问的节点运行strongConnect
            for (v in cfg.blocks) {
                if (v !in indexMap) {
                    strongConnect(v)
                }
            }

            return result
        }

        /**
         * 获取跳转偏移量（16位有符号整数）
         */
        private fun getJumpOffset(iCode: ByteArray, pc: Int): Int {
            val byte1 = iCode[pc + 1].toInt() and 0xFF
            val byte2 = iCode[pc + 2].toInt() and 0xFF
            var offset = (byte1 shl 8) or byte2
            // 处理负数偏移（补码转换）
            if (offset and 0x8000 != 0) {
                offset = offset or 0xFFFF0000.toInt()
            }
            return offset
        }

        /**
         * 获取指令长度
         */
        private fun getInstructionLength(iCode: ByteArray, pc: Int): Int {
            if (pc >= iCode.size) return 1

            val opcode = iCode[pc].toInt() and 0xFF
            return when (opcode) {
                Token.GOTO, Token.IFEQ, Token.IFNE -> 3
                Icode.Icode_GETVAR1, Icode.Icode_SETVAR1, Icode.Icode_REG_IND1, Icode.Icode_REG_STR1 -> 2
                Icode.Icode_INTNUMBER, Icode.Icode_REG_STR4 -> 4
                Icode.Icode_SHORTNUMBER, Icode.Icode_REG_STR2, Icode.Icode_LINE -> 2
                else -> 1
            }
        }
    }
}

/**
 * 控制流图
 * 表示代码的控制流结构
 */
class ControlFlowGraph {
    // 基本块列表
    val blocks = mutableListOf<BasicBlock>()

    // 控制流结构
    val structures = mutableListOf<ControlStructure>()
}

/**
 * 基本块
 * 表示一段连续执行的代码
 */
class BasicBlock(
    val start: Int, val end: Int
) {
    // 后继基本块
    val successors = mutableListOf<BasicBlock>()

    // 前驱基本块
    val predecessors = mutableListOf<BasicBlock>()

    override fun toString(): String {
        return "BasicBlock[$start-$end]"
    }
}

/**
 * 控制结构
 * 表示if-else, 循环等高级控制结构
 */
sealed class ControlStructure

/**
 * if结构
 */
class IfStructure(
    val condition: BasicBlock, val thenBlock: BasicBlock, val elseBlock: BasicBlock? = null
) : ControlStructure() {
    override fun toString(): String {
        return "If[condition=${condition}, then=${thenBlock}, else=${elseBlock}]"
    }
}

/**
 * 循环结构
 */
class LoopStructure(
    val type: RhinoDecompiler3.LoopType, val header: BasicBlock, val body: List<BasicBlock>
) : ControlStructure() {
    override fun toString(): String {
        return "Loop[type=${type}, header=${header}, body=${body.size}块]"
    }
}
