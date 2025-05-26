package org.mozilla.javascript

import org.mozilla.javascript.ast.* // Import all AST nodes
import java.util.Stack


// Placeholder for ControlFlowGraph, remove or replace when actual CFG is integrated
class RhinoDecompilerControlFlowAnalyzer {
    // Minimal BasicBlock definition for CFG structure.
    data class BasicBlock(
        val id: Int,
        val startOffset: Int,
        var endOffset: Int, // Last instruction PC in the block
        val successors: MutableList<BasicBlock> = mutableListOf(),
        val predecessors: MutableList<BasicBlock> = mutableListOf()
        // Potentially more fields like isLoopHeader, exceptionHandler, etc.
    )

    // Minimal ControlFlowGraph definition.
    data class IfStructure(
        val conditionBlock: BasicBlock, // Block ending with IFEQ/IFNE
        val thenEntryBlock: BasicBlock, // First block of the 'then' branch
        val elseEntryBlock: BasicBlock?, // First block of the 'else' branch (optional)
        val endOfIfBlock: BasicBlock // Block where execution merges to after the if/else
    )

    enum class LoopType { WHILE, FOR_IN, DO_WHILE }

    data class LoopStructure(
        val loopHeader: BasicBlock,     // Block containing the loop condition (for WHILE, DO_WHILE) or init (FOR_IN)
        val bodyEntryBlock: BasicBlock, // First block of the loop body
        val loopType: LoopType, val endOfLoopBlock: BasicBlock, // Block executed after the loop terminates
        val continueTarget: BasicBlock? = null, // For continue statements (often loopHeader or a part of it)
        val backEdgeSource: BasicBlock? = null // Block that jumps back to header (often last block in body)
    )

    // Generic structure holder
    sealed interface ControlStructure
    data class IfStructWrapper(val ifStructure: IfStructure) : ControlStructure
    data class LoopStructWrapper(val loopStructure: LoopStructure) : ControlStructure
    data class SwitchStructWrapper(val switchStructure: SwitchStructure) : ControlStructure

    data class SwitchStructure(
        val discriminantBlock: BasicBlock, // Block that computes the value being switched on
        // List of (case expression AstNode? (null for default), entry BasicBlock for case body)
        val caseEntries: List<Pair<AstNode?, BasicBlock>>,
        val endOfSwitchBlock: BasicBlock // Block where execution merges after the switch
    )

    data class ControlFlowGraph(
        val blocks: MutableMap<Int, BasicBlock> = mutableMapOf(),
        val entryBlock: BasicBlock? = null,
        val structures: MutableList<ControlStructure> = mutableListOf() // Stores identified structures
    ) {
        // Dummy analyze method for now
        fun analyze(iCode: ByteArray): ControlFlowGraph {
            if (iCode.isEmpty()) return ControlFlowGraph(mutableMapOf(), null, mutableListOf())

            val structures = mutableListOf<ControlStructure>()
            val blocksMap = mutableMapOf<Int, BasicBlock>()
            var currentOffset = 0

            // Try to create a simple If-Else structure
            // B0 (if cond) -> B1 (then), B2 (else); B1/B2 -> B3 (merge)
            if (currentOffset + 10 <= iCode.size) { // Min size for a basic if-else
                val b0_if = BasicBlock(id = 0, startOffset = currentOffset, endOffset = currentOffset + 3)
                val b1_then = BasicBlock(id = 1, startOffset = currentOffset + 4, endOffset = currentOffset + 6)
                val b2_else = BasicBlock(id = 2, startOffset = currentOffset + 7, endOffset = currentOffset + 8)
                val b3_merge_if = BasicBlock(id = 3, startOffset = currentOffset + 9, endOffset = currentOffset + 9)

                b0_if.successors.add(b1_then); b0_if.successors.add(b2_else)
                b1_then.successors.add(b3_merge_if); b2_else.successors.add(b3_merge_if)
                blocksMap[0] = b0_if; blocksMap[1] = b1_then; blocksMap[2] = b2_else; blocksMap[3] = b3_merge_if
                structures.add(IfStructWrapper(IfStructure(b0_if, b1_then, b2_else, b3_merge_if)))
                currentOffset += 10
            }

            // Try to create a simple WHILE loop structure after the if, if space allows
            val loopStartOffset = currentOffset
            if (loopStartOffset + 7 <= iCode.size) {
                val b_loop_header =
                    BasicBlock(id = blocksMap.size, startOffset = loopStartOffset, endOffset = loopStartOffset + 2)
                val b_loop_body = BasicBlock(
                    id = blocksMap.size + 1, startOffset = loopStartOffset + 3, endOffset = loopStartOffset + 5
                )
                val b_after_loop = BasicBlock(
                    id = blocksMap.size + 2, startOffset = loopStartOffset + 6, endOffset = loopStartOffset + 6
                )

                b_loop_header.successors.add(b_loop_body)
                b_loop_header.successors.add(b_after_loop)
                b_loop_body.successors.add(b_loop_header)

                blocksMap[b_loop_header.id] = b_loop_header; blocksMap[b_loop_body.id] =
                    b_loop_body; blocksMap[b_after_loop.id] = b_after_loop

                structures.add(
                    LoopStructWrapper(
                        LoopStructure(
                            loopHeader = b_loop_header,
                            bodyEntryBlock = b_loop_body,
                            loopType = LoopType.WHILE,
                            endOfLoopBlock = b_after_loop,
                            backEdgeSource = b_loop_body
                        )
                    )
                )
                currentOffset = loopStartOffset + 7
            }

            // Try to create a simple Switch structure
            val switchStartOffset = currentOffset
            // Need: discriminant, case1_expr_setup, IFEQ_POP, case1_body, GOTO end, case2_expr_setup, IFEQ_POP, case2_body, GOTO end, default_body, end
            // Roughly: disc(2) + case1_setup(1) + IFEQ_POP(1) + body1(1) + GOTO(3) + default_body(1) + end(0) = 8 bytes minimum for 1 case + default
            if (switchStartOffset + 10 <= iCode.size) { // Simplified length check
                val b_switch_disc =
                    BasicBlock(id = blocksMap.size, startOffset = switchStartOffset, endOffset = switchStartOffset + 1)
                val b_case1_setup = BasicBlock(
                    id = blocksMap.size + 1, startOffset = switchStartOffset + 2, endOffset = switchStartOffset + 2
                ) // Pushes case value
                val b_case1_check = BasicBlock(
                    id = blocksMap.size + 2, startOffset = switchStartOffset + 3, endOffset = switchStartOffset + 3
                ) // ICODE_IFEQ_POP
                val b_case1_body = BasicBlock(
                    id = blocksMap.size + 3, startOffset = switchStartOffset + 4, endOffset = switchStartOffset + 5
                )
                val b_default_body = BasicBlock(
                    id = blocksMap.size + 4, startOffset = switchStartOffset + 6, endOffset = switchStartOffset + 8
                ) // Assume a GOTO from case1_body leads here or to end
                val b_switch_end = BasicBlock(
                    id = blocksMap.size + 5, startOffset = switchStartOffset + 9, endOffset = switchStartOffset + 9
                )

                b_switch_disc.successors.add(b_case1_setup)
                b_case1_setup.successors.add(b_case1_check)
                b_case1_check.successors.add(b_case1_body) // If case matches (IFEQ_POP logic is complex)
                b_case1_check.successors.add(b_default_body) // If case does not match, falls to next check or default
                b_case1_body.successors.add(b_switch_end) // Assume break (GOTO)
                b_default_body.successors.add(b_switch_end)

                blocksMap[b_switch_disc.id] = b_switch_disc; blocksMap[b_case1_setup.id] =
                    b_case1_setup; blocksMap[b_case1_check.id] = b_case1_check;
                blocksMap[b_case1_body.id] = b_case1_body; blocksMap[b_default_body.id] =
                    b_default_body; blocksMap[b_switch_end.id] = b_switch_end

                // Placeholder case expressions (would normally be AstNodes if available from earlier decompilation stages)
                val case1Expr = StringLiteral().apply { value = "case1_placeholder" } // Placeholder

                structures.add(
                    SwitchStructWrapper(
                        SwitchStructure(
                            discriminantBlock = b_switch_disc, caseEntries = listOf(
                                Pair(case1Expr, b_case1_body), Pair(null, b_default_body)
                            ), // null for default case
                            endOfSwitchBlock = b_switch_end
                        )
                    )
                )
                currentOffset = switchStartOffset + 10
            }

            if (blocksMap.isEmpty() && iCode.isNotEmpty()) {
                val singleBlock = BasicBlock(id = 99, startOffset = 0, endOffset = iCode.size - 1)
                blocksMap[99] = singleBlock
            } else if (currentOffset < iCode.size) { // Add a final block for any remaining code
                val lastBlockId = (blocksMap.keys.maxOrNull() ?: -1) + 1
                val finalBlock = BasicBlock(id = lastBlockId, startOffset = currentOffset, endOffset = iCode.size - 1)
                blocksMap[lastBlockId] = finalBlock
            }

            return ControlFlowGraph(blocksMap, blocksMap.values.firstOrNull(), structures)
        }
    }
}


class NewRhinoDecompiler {

    data class DecompilerContext(
        var pc: Int = 0,
        val stack: KStack = KStack(),
        var scope: Scope? = null,
        var stringReg: String? = null,
        var indexReg: Int = 0,
        var lineNo: Int = 0,
        val labels: MutableMap<Int, String> = mutableMapOf(),
        val controlBlocks: MutableList<ControlBlock> = mutableListOf(),
        val loopInfo: MutableMap<Int, LoopInfo> = mutableMapOf(),
        val forLoops: MutableSet<Int> = mutableSetOf(),
        val forInLoops: MutableSet<Int> = mutableSetOf(),
        var lastCondition: AstNode? = null,
        val visitedBlocks: MutableSet<RhinoDecompilerControlFlowAnalyzer.BasicBlock> = mutableSetOf(),
        var currentCatchBlockExceptionVarName: String? = null // For CATCH_SCOPE
    )

    class KStack : Stack<AstNode>() {
        override fun push(item: AstNode): AstNode {
            if (DEBUG) {
                println("PUSH ${item.toSource(0)}")
            }
            return super.push(item)
        }

        override fun pop(): AstNode {
            val item = super.pop()
            if (DEBUG) {
                println("POP ${item.toSource(0)}")
            }
            return item
        }
    }

    data class Scope(
        val parent: Scope?,
        val name: String?, // Added name for the scope (e.g., function name)
        val children: MutableList<Scope> = mutableListOf(),
        val variables: MutableSet<String> = mutableSetOf() // Store variable names as Strings
    ) {
        fun addVariable(name: String) {
            variables.add(name)
        }

        fun hasVariable(name: String): Boolean {
            return variables.contains(name) || parent?.hasVariable(name) ?: false
        }
    }

    // Placeholder for ControlBlock and LoopInfo until they are defined
    data class ControlBlock(val type: String, val start: Int, val end: Int) // TODO: Define properly
    data class LoopInfo(val loopStart: Int, val continueTarget: Int, val breakTarget: Int) // TODO: Define properly


    private fun createInfix(type: Int, left: AstNode, right: AstNode): InfixExpression {
        val node = InfixExpression(left, right)
        node.operator = type
        return node
    }

    // Placeholder for BigIntLiteral if not available in ast package
    // open class BigIntLiteralNode(val value: String) : AstNode(Token.BIGINT) { ... }

    // Extension properties for AST nodes if they cannot be modified directly
// These would ideally be part of the AST library's FunctionNode and YieldExpression classes.
    var FunctionNode.isGenerator: Boolean
        get() = this.isGeneratorFunction // Assuming FunctionNode has isGeneratorFunction
        set(value) {
            this.isGeneratorFunction = value
        }

    var YieldExpression.isStar: Boolean
        get() = ủyieldAll // Assuming a hypothetical field or method in YieldExpression
        set(value) {
            ủyieldAll = value
        } // Assuming a hypothetical field or method

    // Placeholder if YieldExpression.isStar (or similar) is not available
    private var ủyieldAll: Boolean = false // Companion object might be better for a real scenario


    private fun createUnary(type: Int, operand: AstNode): UnaryExpression {
        val node = UnaryExpression(operand)
        node.operator = type
        node.setIsPostfix(false) // Most JS unary ops are prefix
        return node
    }


    private fun decodeNextInstruction(
        ctx: DecompilerContext, data: InterpreterData, cfg: RhinoDecompilerControlFlowAnalyzer.ControlFlowGraph?
    ) {
        val offset = ctx.pc
        val iCode = data.itsICode
        val opcode = iCode[ctx.pc].toInt() and 0xFF
        ctx.pc++ // Default increment, will be overridden by opcodes with specific lengths

        if (DEBUG) {
            println("PC: $offset Opcode: ${Token.name(opcode)} (${opcode}) Stack: ${ctx.stack.size}")
        }

        when (opcode) {
            // Loop Related Opcodes (Stack Manipulation)
            Token.ENUM_INIT_KEYS -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for ENUM_INIT_KEYS"); return
                }
                // val obj = ctx.stack.pop() // Object to iterate
                // For now, assume the object itself can act as its iterator for ENUM_NEXT
                // Or push a placeholder for a dedicated iterator object if CFG/runtime creates one.
                // ctx.stack.push(Name("${obj.toSource(0)}_iterator"))
                // Simplification: keep the object on stack, ENUM_NEXT will expect it.
            }

            Token.ENUM_INIT_VALUES -> { // Similar to ENUM_INIT_KEYS for for-each-in
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for ENUM_INIT_VALUES"); return
                }
            }

            Token.ENUM_NEXT -> { // Pops iterator, pushes next key.
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for ENUM_NEXT (iterator)"); return
                }
                // val iterator = ctx.stack.pop()
                // In a real scenario, this would advance the iterator and push the next key.
                // For placeholder, push a generic name.
                ctx.stack.push(Name("enum_next_key_placeholder"))
            }

            Token.ENUM_ID -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for ENUM_ID (iterator)"); return
                }
                ctx.stack.push(Name("enum_id_value_placeholder"))
            }

            // Generator Opcodes
            Icode.ICODE_GENERATOR -> {
                if (DEBUG) println("ICODE_GENERATOR: Marking last function as generator.")
                // Attempt to find the FunctionNode on stack (e.g. from CLOSURE_EXPR)
                // This is heuristic; proper tracking might be needed if it's not on stack top.
                val lastNode = if (ctx.stack.isNotEmpty()) ctx.stack.peek() else null
                if (lastNode is FunctionNode) {
                    lastNode.isGenerator = true
                } else {
                    if (DEBUG) println("ICODE_GENERATOR: Could not find FunctionNode on stack top to mark as generator.")
                    // Potentially, this opcode is used when the function being defined is implicit (e.g. top-level script context)
                    // Or it applies to a function whose definition is handled by other opcodes not leaving it on stack.
                }
                // This opcode itself does not push a node.
            }

            Token.YIELD -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for YIELD value"); return
                }
                val yieldExpr = YieldExpression().apply {
                    expression = ctx.stack.pop()
                    isStar = false
                }
                ctx.stack.push(yieldExpr)
            }

            Icode.ICODE_YIELD_STAR -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for YIELD_STAR value"); return
                }
                val yieldExpr = YieldExpression().apply {
                    expression = ctx.stack.pop()
                    isStar = true
                }
                ctx.stack.push(yieldExpr)
            }

            Icode.ICODE_GENERATOR_END -> {
                if (DEBUG) println("ICODE_GENERATOR_END (No AST change)")
                // Typically pops the generator object from a dedicated register or stack frame.
                // No direct AST node, but important for runtime.
            }

            Icode.ICODE_GENERATOR_RETURN -> {
                val retStmt = ReturnStatement()
                if (ctx.stack.isNotEmpty()) {
                    retStmt.returnValue = ctx.stack.pop()
                }
                ctx.stack.push(retStmt)
            }

            // E4X Opcodes (Placeholders)
            Token.XMLATTR -> { // '@'
                ctx.stack.push(Name("@")) // Placeholder for attribute access start
            }

            Icode.ICODE_ENTERDQ -> { // E4X .. or .() descendant/filter
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for ENTERDQ"); return
                }
                ctx.stack.pop() // Pop the object being queried
                ctx.stack.push(Name("E4X_DotQueryStart_placeholder"))
            }

            Icode.ICODE_LEAVEDQ -> { // End of E4X .. or .()
                if (DEBUG) println("ICODE_LEAVEDQ (No AST change, PC advanced)")
                // PC already advanced by 1 at start of when block.
                // If it has operands (e.g. jump offset), advance further. Assuming 0 operands for now.
            }

            Token.COLONCOLON -> { // '::'
                ctx.stack.push(Name("E4X_NamespaceSeparator_placeholder"))
            }

            Token.DOTDOT -> { // '..'
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for DOTDOT target"); return
                }
                val target = ctx.stack.pop()
                ctx.stack.push(FunctionCall(Name("E4X_Descendants_placeholder"), listOf(target)))
            }

            Icode.ICODE_REF_SPECIAL -> { // E4X @attr or other special references
                val strIndex = getShort(iCode, ctx.pc); ctx.pc += 2
                val name = data.getString(strIndex)
                if (name.startsWith("@")) {
                    if (ctx.stack.isEmpty()) {
                        if (DEBUG) println("Stack underflow for ICODE_REF_SPECIAL target (@)"); return
                    }
                    val target = ctx.stack.pop()
                    ctx.stack.push(PropertyGet(target, Name(name)))
                } else {
                    ctx.stack.push(Name("E4X_RefSpecial_${name}_placeholder"))
                }
            }

            Icode.ICODE_ESCXMLATTR -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for ESCXMLATTR"); return
                }
                val value = ctx.stack.pop()
                ctx.stack.push(FunctionCall(Name("E4X_EscapeXmlAttr_placeholder"), listOf(value)))
            }

            Icode.ICODE_ESCXMLTEXT -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for ESCXMLTEXT"); return
                }
                val value = ctx.stack.pop()
                ctx.stack.push(FunctionCall(Name("E4X_EscapeXmlText_placeholder"), listOf(value)))
            }

            Token.DEFAULTNAMESPACE -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for DEFAULTNAMESPACE"); return
                }
                val namespace = ctx.stack.pop()
                ctx.stack.push(FunctionCall(Name("setDefaultXmlNamespace_placeholder"), listOf(namespace)))
            }
            // Other E4X REF_ opcodes - log and placeholder
            Token.REF_MEMBER, Token.REF_NS_MEMBER, Token.REF_NS_NAME -> {
                if (DEBUG) println("Unhandled E4X REF opcode: ${Token.name(opcode)}")
                // These often involve complex stack manipulation (object, property, namespace)
                // For now, push a generic placeholder.
                ctx.stack.push(Name("E4X_REF_Operation_placeholder"))
            }


            Icode.ICODE_LOCAL_CLEAR -> {
                val localIndex = iCode[ctx.pc].toInt() and 0xFF
                ctx.pc++
                if (DEBUG) println("ICODE_LOCAL_CLEAR for index $localIndex (No AST change)")
            }

            Icode.ICODE_CALLSPECIAL -> {
                val callType = iCode[ctx.pc].toInt() and 0xFF; ctx.pc++
                val isNewByte = iCode[ctx.pc].toInt() and 0xFF; ctx.pc++
                val isNew = isNewByte != 0

                when (callType) {
                    ScriptRuntime.SPECIALCALL_EVAL -> {
                        val argCount = ctx.indexReg
                        val args = mutableListOf<AstNode>()
                        if (ctx.stack.size < argCount) {
                            if (DEBUG) println("Stack underflow for CALLSPECIAL_EVAL args"); return
                        }
                        for (i in 0 until argCount) {
                            args.add(0, ctx.stack.pop())
                        }
                        val evalCall = FunctionCall().apply { target = Name("eval"); arguments = args }
                        ctx.stack.push(if (isNew) NewExpression().apply {
                            target = evalCall.target; arguments = evalCall.arguments
                        } else evalCall)
                    }

                    ScriptRuntime.SPECIALCALL_WITH -> {
                        if (DEBUG) println("ICODE_CALLSPECIAL: SPECIALCALL_WITH encountered. Expression for with should be on stack.")
                    }

                    else -> {
                        if (DEBUG) println("Unhandled ICODE_CALLSPECIAL type: $callType")
                        val argCount = ctx.indexReg
                        repeat(argCount) { if (ctx.stack.isNotEmpty()) ctx.stack.pop() }
                        if (ctx.stack.isNotEmpty()) ctx.stack.pop()
                        ctx.stack.push(Name("ERROR_UNKNOWN_CALLSPECIAL_$callType"))
                    }
                }
            }

            Icode.ICODE_TAIL_CALL -> {
                val argCount = ctx.indexReg
                if (ctx.stack.size < argCount + 1) {
                    if (DEBUG) println("Stack underflow for TAIL_CALL (target or arguments)")
                    repeat(argCount) { if (ctx.stack.isNotEmpty()) ctx.stack.pop() }
                    if (ctx.stack.isNotEmpty()) ctx.stack.pop()
                    ctx.stack.push(Name("ERROR_TAIL_CALL_STACK_UNDERFLOW"))
                    return
                }
                val args = mutableListOf<AstNode>()
                for (i in 0 until argCount) {
                    args.add(0, ctx.stack.pop())
                }
                val target = ctx.stack.pop()
                val callNode = FunctionCall().apply {
                    this.target = target
                    this.arguments = args
                }
                ctx.stack.push(callNode)
            }

            Token.SEALED -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for SEALED"); return
                }
                val objToSeal = ctx.stack.pop()
                val objectName = Name("Object")
                val sealName = Name("seal")
                val propGet = PropertyGet(objectName, sealName)
                val callNode = FunctionCall().apply {
                    target = propGet
                    addArgument(objToSeal)
                }
                ctx.stack.push(callNode)
            }


            // Stack Manipulation
            Icode.ICODE_DUP2 -> { // DUP2
                if (ctx.stack.size >= 2) {
                    val v1 = ctx.stack.pop()
                    val v2 = ctx.stack.pop()
                    ctx.stack.push(v2)
                    ctx.stack.push(v1)
                    ctx.stack.push(v2)
                    ctx.stack.push(v1)
                } else {
                    if (DEBUG) println("Stack underflow for DUP2")
                }
            }

            Icode.ICODE_SWAP -> { // SWAP
                if (ctx.stack.size >= 2) {
                    val v1 = ctx.stack.pop()
                    val v2 = ctx.stack.pop()
                    ctx.stack.push(v1)
                    ctx.stack.push(v2)
                } else {
                    if (DEBUG) println("Stack underflow for SWAP")
                }
            }

            // Return Variants
            Icode.ICODE_RETUNDEF -> { // RETUNDEF
                val returnStmt = ReturnStatement()
                // Optional: push an explicit undefined Name node as the return value
                // val undefName = Name(); undefName.identifier = "undefined"; returnStmt.returnValue = undefName
                ctx.stack.push(returnStmt)
            }

            Token.RETURN_RESULT -> { // RETURN_RESULT (was Token.RETURN in RhinoDecompiler2)
                val returnStmt = ReturnStatement()
                if (ctx.stack.isNotEmpty()) { // RhinoDecompiler2 used pop() directly
                    returnStmt.returnValue = ctx.stack.pop()
                }
                // ctx.result is not directly used here as per RhinoDecompiler2's RETURN handling.
                // It seems RETURN_RESULT implies the result is already on top of the main stack.
                ctx.stack.push(returnStmt)
            }


            Token.STRING -> {
                val index = getShort(iCode, ctx.pc)
                ctx.stringReg = data.itsStringTable[index]
                val node = StringLiteral()
                node.value = ctx.stringReg
                ctx.stack.push(node)
                ctx.pc += 2
            }

            Token.NUMBER -> {
                val numberIndex = getShort(iCode, ctx.pc)
                val value = data.itsDoubleTable[numberIndex]
                val node = NumberLiteral()
                node.value = value
                // node.setNumber(value) // NumberLiteral constructor handles this
                ctx.stack.push(node)
                ctx.pc += 2
            }

            Token.TRUE -> ctx.stack.push(KeywordLiteral().apply { type = Token.TRUE })
            Token.FALSE -> ctx.stack.push(KeywordLiteral().apply { type = Token.FALSE })
            Token.NULL -> ctx.stack.push(KeywordLiteral().apply { type = Token.NULL })
            Token.THIS -> ctx.stack.push(KeywordLiteral().apply { type = Token.THIS })

            Icode.ICODE_DUP -> if (ctx.stack.isNotEmpty()) ctx.stack.push(ctx.stack.peek()) else if (DEBUG) println("Stack underflow for DUP")
            Icode.ICODE_POP -> if (ctx.stack.isNotEmpty()) ctx.stack.pop() else if (DEBUG) println("Stack underflow for POP")

            // Arithmetic/Bitwise Operators
            Token.ADD -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for ADD"); return
                }
                val right = ctx.stack.pop()
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.ADD, left, right))
            }

            Token.SUB -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for SUB"); return
                }
                val right = ctx.stack.pop()
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.SUB, left, right))
            }

            Token.MUL -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for MUL"); return
                }
                val right = ctx.stack.pop()
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.MUL, left, right))
            }

            Token.DIV -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for DIV"); return
                }
                val right = ctx.stack.pop()
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.DIV, left, right))
            }

            Token.MOD -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for MOD"); return
                }
                val right = ctx.stack.pop()
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.MOD, left, right))
            }

            Token.BITOR -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for BITOR"); return
                }
                val right = ctx.stack.pop()
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.BITOR, left, right))
            }

            Token.BITAND -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for BITAND"); return
                }
                val right = ctx.stack.pop()
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.BITAND, left, right))
            }

            Token.BITXOR -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for BITXOR"); return
                }
                val right = ctx.stack.pop()
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.BITXOR, left, right))
            }

            Token.LSH -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for LSH"); return
                }
                val right = ctx.stack.pop()
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.LSH, left, right))
            }

            Token.RSH -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for RSH"); return
                }
                val right = ctx.stack.pop()
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.RSH, left, right))
            }

            Token.URSH -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for URSH"); return
                }
                val right = ctx.stack.pop()
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.URSH, left, right))
            }

            // Unary Operators
            Token.NEG -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for NEG"); return
                }; ctx.stack.push(createUnary(Token.NEG, ctx.stack.pop()))
            }

            Token.POS -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for POS"); return
                }; ctx.stack.push(createUnary(Token.POS, ctx.stack.pop()))
            }

            Token.NOT -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for NOT"); return
                }; ctx.stack.push(createUnary(Token.NOT, ctx.stack.pop()))
            }

            Token.BITNOT -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for BITNOT"); return
                }; ctx.stack.push(createUnary(Token.BITNOT, ctx.stack.pop()))
            }

            // Variable Operations
            Icode.ICODE_GETVAR1 -> {
                val varIndex = iCode[ctx.pc].toInt() and 0xFF
                val varName = data.getParamOrVarName(varIndex) // Safe: InterpreterData ensures non-null
                val nameNode = Name()
                nameNode.identifier = varName
                ctx.stack.push(nameNode)
                ctx.pc++
            }

            Icode.ICODE_SETVAR1 -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for SETVAR1"); return
                }
                val varIndex = iCode[ctx.pc].toInt() and 0xFF
                val varName = data.getParamOrVarName(varIndex) // Safe
                val value = ctx.stack.pop()

                val nameNode = Name()
                nameNode.identifier = varName

                if (ctx.scope?.hasVariable(varName) == true) {
                    val assignment = Assignment(nameNode, value)
                    assignment.operator = Token.ASSIGN // Default assignment
                    ctx.stack.push(assignment)
                } else {
                    ctx.scope?.addVariable(varName) // Add to current scope
                    val varDecl = VariableDeclaration()
                    val varNode = VariableInitializer()
                    varNode.target = nameNode
                    varNode.initializer = value
                    varDecl.addVariable(varNode)
                    varDecl.setIsStatement(false)
                    ctx.stack.push(varDecl)
                }
                ctx.pc++
            }

            Token.BINDNAME -> { // Effectively declares a variable
                val varName = ctx.stringReg ?: run { if (DEBUG) println("stringReg is null for BINDNAME"); return }
                val nameNode = Name().apply { identifier = varName }
                ctx.scope?.addVariable(varName) // Add to current scope

                // BINDNAME itself usually just prepares the name. Assignment might follow via SETNAME etc.
                // It can appear as 'var x;' or 'function x() {}'
                // Pushing the name itself is often correct as it might be used by subsequent opcodes (e.g. SETCONST)
                // or as part of a function declaration.
                // If it's a standalone 'var x;', it will eventually become a VariableDeclaration statement.
                // For now, just push the name. The buildAst logic can wrap it if it's left as an expression.
                // ctx.stack.push(nameNode) // Old behavior

                // New behavior: Push a VariableDeclaration for "var varName;"
                val varDecl = VariableDeclaration()
                val varNode = VariableInitializer()
                varNode.target = nameNode // Name node created from stringReg
                varNode.initializer = null // No initializer for BINDNAME typically
                varDecl.addVariable(varNode)
                varDecl.type = Token.VAR // Mark as 'var'
                // isStatement will be handled by buildAst or assumed true for items directly in AstRoot
                ctx.stack.push(varDecl)
            }

            // Simple Jumps (decode and advance PC only for now, actual jumping handled by CFG)
            Token.GOTO -> {
                // val target = getShort(iCode, ctx.pc)
                ctx.pc += 2
            }

            Token.IFEQ -> {
                // val target = getShort(iCode, ctx.pc)
                if (ctx.stack.isNotEmpty()) {
                    ctx.lastCondition = ctx.stack.pop() // Store the condition
                } else {
                    if (DEBUG) println("Stack underflow for IFEQ condition")
                    ctx.lastCondition = Name("ERROR_IFEQ_NO_CONDITION") // Placeholder if stack is empty
                }
                ctx.pc += 2
            }

            Token.IFNE -> {
                // val target = getShort(iCode, ctx.pc)
                if (ctx.stack.isNotEmpty()) {
                    ctx.lastCondition = ctx.stack.pop() // Store the condition
                } else {
                    if (DEBUG) println("Stack underflow for IFNE condition")
                    ctx.lastCondition = Name("ERROR_IFNE_NO_CONDITION") // Placeholder if stack is empty
                }
                ctx.pc += 2
            }

            // Object/Property Access
            Token.GETPROP -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for GETPROP target"); return
                }
                val target = ctx.stack.pop()
                val propName = ctx.stringReg ?: run { if (DEBUG) println("stringReg is null for GETPROP"); return }
                val nameNode = Name().apply { identifier = propName }
                ctx.stack.push(PropertyGet(target, nameNode))
            }

            Token.SETPROP -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for SETPROP"); return
                }
                val value = ctx.stack.pop()
                val target = ctx.stack.pop()
                val propName = ctx.stringReg ?: run { if (DEBUG) println("stringReg is null for SETPROP"); return }
                val nameNode = Name().apply { identifier = propName }
                val propGet = PropertyGet(target, nameNode)
                ctx.stack.push(Assignment(propGet, value).apply { operator = Token.ASSIGN })
            }

            Token.GETELEM -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for GETELEM"); return
                }
                val element = ctx.stack.pop()
                val target = ctx.stack.pop()
                ctx.stack.push(ElementGet(target, element))
            }

            Token.SETELEM -> {
                if (ctx.stack.size < 3) {
                    if (DEBUG) println("Stack underflow for SETELEM"); return
                }
                val value = ctx.stack.pop()
                val element = ctx.stack.pop()
                val target = ctx.stack.pop()
                val elemGet = ElementGet(target, element)
                ctx.stack.push(Assignment(elemGet, value).apply { operator = Token.ASSIGN })
            }

            // Function Calls/Object Creation
            Token.CALL -> {
                val argCount = ctx.indexReg
                if (ctx.stack.size < argCount + 1) { // +1 for the target
                    if (DEBUG) println("Stack underflow for CALL (target or arguments)")
                    // Potentially push a placeholder or error node
                    repeat(argCount) { if (ctx.stack.isNotEmpty()) ctx.stack.pop() } // Clear potential args
                    if (ctx.stack.isNotEmpty()) ctx.stack.pop() // Clear potential target
                    ctx.stack.push(Name("ERROR_CALL_STACK_UNDERFLOW")) // Placeholder
                    return
                }
                val args = mutableListOf<AstNode>()
                for (i in 0 until argCount) {
                    args.add(0, ctx.stack.pop())
                }
                val target = ctx.stack.pop()
                val callNode = FunctionCall().apply {
                    this.target = target
                    this.arguments = args
                }
                ctx.stack.push(callNode)
            }

            Token.NEW -> {
                val argCount = ctx.indexReg
                if (ctx.stack.size < argCount + 1) { // +1 for the target
                    if (DEBUG) println("Stack underflow for NEW (target or arguments)")
                    repeat(argCount) { if (ctx.stack.isNotEmpty()) ctx.stack.pop() }
                    if (ctx.stack.isNotEmpty()) ctx.stack.pop()
                    ctx.stack.push(Name("ERROR_NEW_STACK_UNDERFLOW"))
                    return
                }
                val args = mutableListOf<AstNode>()
                for (i in 0 until argCount) {
                    args.add(0, ctx.stack.pop())
                }
                val target = ctx.stack.pop()
                val newNode = NewExpression().apply {
                    this.target = target
                    this.arguments = args
                }
                // Initializer handling (e.g. for ObjectLiteral with NEW) is more complex via Icode_LITERAL_NEW etc.
                ctx.stack.push(newNode)
            }

            // Register Index/String Loading (Continued)
            Icode.ICODE_REG_IND_C0 -> ctx.indexReg = 0
            Icode.ICODE_REG_IND_C1 -> ctx.indexReg = 1
            Icode.ICODE_REG_IND_C2 -> ctx.indexReg = 2
            Icode.ICODE_REG_IND_C3 -> ctx.indexReg = 3
            Icode.ICODE_REG_IND_C4 -> ctx.indexReg = 4
            Icode.ICODE_REG_IND_C5 -> ctx.indexReg = 5
            Icode.ICODE_REG_IND1 -> {
                ctx.indexReg = iCode[ctx.pc].toInt() and 0xFF; ctx.pc++
            }

            Icode.ICODE_REG_IND2 -> {
                ctx.indexReg = getShort(iCode, ctx.pc); ctx.pc += 2
            }

            Icode.ICODE_REG_IND4 -> {
                ctx.indexReg = getInt(iCode, ctx.pc); ctx.pc += 4
            }


            Icode.ICODE_REG_STR_C0 -> ctx.stringReg =
                data.itsStringTable.getOrElse(0) { "ERROR_STR_C0" } // Safe: getOrElse
            Icode.ICODE_REG_STR_C1 -> ctx.stringReg =
                data.itsStringTable.getOrElse(1) { "ERROR_STR_C1" } // Safe: getOrElse
            Icode.ICODE_REG_STR_C2 -> ctx.stringReg =
                data.itsStringTable.getOrElse(2) { "ERROR_STR_C2" } // Safe: getOrElse
            Icode.ICODE_REG_STR_C3 -> ctx.stringReg =
                data.itsStringTable.getOrElse(3) { "ERROR_STR_C3" } // Safe: getOrElse
            Icode.ICODE_REG_STR1 -> {
                val idx = iCode[ctx.pc].toInt() and 0xFF; ctx.stringReg =
                    data.itsStringTable.getOrElse(idx) { "ERROR_STR1_$idx" }; ctx.pc++
            } // Safe: getOrElse
            Icode.ICODE_REG_STR2 -> {
                val idx = getShort(iCode, ctx.pc); ctx.stringReg =
                    data.itsStringTable.getOrElse(idx) { "ERROR_STR2_$idx" }; ctx.pc += 2
            } // Safe: getOrElse
            Icode.ICODE_REG_STR4 -> {
                val idx = getInt(iCode, ctx.pc); ctx.stringReg =
                    data.itsStringTable.getOrElse(idx) { "ERROR_STR4_$idx" }; ctx.pc += 4
            } // Safe: getOrElse

            // BigInt register loading (Using NumberLiteral as placeholder if BigIntLiteralNode is not used/available)
            // Assuming data.itsBigIntTable exists and returns String representations for BigInts
            Icode.ICODE_REG_BIGINT_C0 -> {
                val bigIntValue =
                    data.itsBigIntTable.getOrElse(0) { "0" }; ctx.stack.push(NumberLiteral(bigIntValue + "n"))
            } // Placeholder, Safe: getOrElse
            Icode.ICODE_REG_BIGINT_C1 -> {
                val bigIntValue =
                    data.itsBigIntTable.getOrElse(1) { "0" }; ctx.stack.push(NumberLiteral(bigIntValue + "n"))
            } // Safe: getOrElse
            Icode.ICODE_REG_BIGINT_C2 -> {
                val bigIntValue =
                    data.itsBigIntTable.getOrElse(2) { "0" }; ctx.stack.push(NumberLiteral(bigIntValue + "n"))
            } // Safe: getOrElse
            Icode.ICODE_REG_BIGINT_C3 -> {
                val bigIntValue =
                    data.itsBigIntTable.getOrElse(3) { "0" }; ctx.stack.push(NumberLiteral(bigIntValue + "n"))
            } // Safe: getOrElse
            Icode.ICODE_REG_BIGINT1 -> {
                val idx = iCode[ctx.pc].toInt() and 0xFF;
                val biv = data.itsBigIntTable.getOrElse(idx) { "0" }; ctx.stack.push(NumberLiteral(biv + "n")); ctx.pc++
            } // Safe: getOrElse
            Icode.ICODE_REG_BIGINT2 -> {
                val idx = getShort(iCode, ctx.pc);
                val biv =
                    data.itsBigIntTable.getOrElse(idx) { "0" }; ctx.stack.push(NumberLiteral(biv + "n")); ctx.pc += 2
            } // Safe: getOrElse
            Icode.ICODE_REG_BIGINT4 -> {
                val idx = getInt(iCode, ctx.pc);
                val biv =
                    data.itsBigIntTable.getOrElse(idx) { "0" }; ctx.stack.push(NumberLiteral(biv + "n")); ctx.pc += 4
            } // Safe: getOrElse


            // Literal Creation
            Icode.ICODE_LITERAL_NEW -> {
                // indexReg often indicates type: 0 for object, 1 for array, 2 for RegExp (not handled here)
                // For now, assuming object or array based on some implicit context or a follow-up instruction.
                // RhinoDecompiler2 uses indexReg. Let's assume 0 for object, 1 for array for now.
                val literalType = ctx.indexReg // Expect this to be set by a preceding ICODE_REG_IND_Cn
                when (literalType) {
                    0 -> ctx.stack.push(ObjectLiteral()) // Type 0: Object Literal
                    1 -> ctx.stack.push(ArrayLiteral())  // Type 1: Array Literal
                    else -> {
                        if (DEBUG) println("Unsupported literal type for ICODE_LITERAL_NEW: $literalType")
                        ctx.stack.push(Name("ERROR_LITERAL_NEW_TYPE")) // Placeholder
                    }
                }
            }

            Icode.ICODE_LITERAL_SET -> { // Add element/property to literal
                if (ctx.stack.size < 3) {
                    if (DEBUG) println("Stack underflow for LITERAL_SET"); return
                }
                val value = ctx.stack.pop()
                val key = ctx.stack.pop() // String for object key, or index (as NumberLiteral) for array
                val literal = ctx.stack.peek() // The ObjectLiteral or ArrayLiteral

                when (literal) {
                    is ObjectLiteral -> {
                        val propName = when (key) {
                            is StringLiteral -> key.value
                            is Name -> key.identifier
                            is NumberLiteral -> key.value.toString() // Or format as string
                            else -> "ERROR_LITERAL_KEY_TYPE"
                        }
                        val objProp = ObjectProperty().apply {
                            this.left = StringLiteral().apply { this.value = propName } // Property name
                            this.right = value
                            // isGetter/isSetter handled by ICODE_LITERAL_GETTER/SETTER
                        }
                        literal.addElement(objProp)
                    }

                    is ArrayLiteral -> {
                        // Elements are typically added in order. indexReg might hold current index.
                        // Or key might be the index. Assuming direct add for now.
                        literal.addElement(value)
                    }

                    else -> if (DEBUG) println("LITERAL_SET target on stack is not a literal: ${literal?.javaClass?.simpleName}")
                }
            }

            Icode.ICODE_LITERAL_GETTER -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for LITERAL_GETTER"); return
                }
                val getterFunc = ctx.stack.pop() // Should be a FunctionNode
                val key = ctx.stack.pop()       // Property name (StringLiteral or Name)
                val objLiteral = ctx.stack.peek() as? ObjectLiteral
                    ?: run { if (DEBUG) println("LITERAL_GETTER not targeting ObjectLiteral"); return }

                val propName = when (key) {
                    is StringLiteral -> key.value
                    is Name -> key.identifier
                    else -> "ERROR_GETTER_KEY"
                }
                val prop = ObjectProperty().apply {
                    this.left = StringLiteral().apply { this.value = propName }
                    this.right = getterFunc
                    setIsGetter()
                }
                objLiteral.addElement(prop)
            }

            Icode.ICODE_LITERAL_SETTER -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for LITERAL_SETTER"); return
                }
                val setterFunc = ctx.stack.pop() // Should be a FunctionNode
                val key = ctx.stack.pop()       // Property name
                val objLiteral = ctx.stack.peek() as? ObjectLiteral
                    ?: run { if (DEBUG) println("LITERAL_SETTER not targeting ObjectLiteral"); return }
                val propName = when (key) {
                    is StringLiteral -> key.value
                    is Name -> key.identifier
                    else -> "ERROR_SETTER_KEY"
                }
                val prop = ObjectProperty().apply {
                    this.left = StringLiteral().apply { this.value = propName }
                    this.right = setterFunc
                    setIsSetter()
                }
                objLiteral.addElement(prop)
            }


            // Typeof / Delete
            Token.TYPEOF -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for TYPEOF"); return
                }; ctx.stack.push(createUnary(Token.TYPEOF, ctx.stack.pop()))
            }

            Icode.ICODE_TYPEOFNAME -> {
                val nameStr = ctx.stringReg ?: run { if (DEBUG) println("stringReg is null for TYPEOFNAME"); return }
                val nameNode = Name().apply { identifier = nameStr }
                // TYPEOFNAME is typeof someName, where someName is not evaluated as a variable first
                // It's effectively typeof 'someName' if 'someName' isn't defined, or typeof value of someName if defined.
                // For AST, we represent it as `typeof someName`.
                ctx.stack.push(createUnary(Token.TYPEOF, nameNode))
            }

            Token.DELPROP -> { // unary delete on a property access (which should be on stack)
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for DELPROP"); return
                }
                // DELPROP expects the result of a GETPROP or GETELEM on the stack
                // e.g., stack has (obj.prop) or (obj[elem]). `createUnary` wraps this.
                ctx.stack.push(createUnary(Token.DELPROP, ctx.stack.pop()))
            }

            Icode.ICODE_DELNAME -> {
                val nameStr = ctx.stringReg ?: run { if (DEBUG) println("stringReg is null for DELNAME"); return }
                val nameNode = Name().apply { identifier = nameStr }
                ctx.stack.push(createUnary(Token.DELPROP, nameNode)) // DELPROP is the token for 'delete' keyword
            }

            // Closures
            Icode.ICODE_CLOSURE_EXPR -> { // Function Expression
                val fnIndex = getShort(iCode, ctx.pc)
                ctx.pc += 2
                val nestedData = data.getFunction(fnIndex)
                val isNestedExprClosure =
                    nestedData.itsFunctionType == FunctionNode.ARROW_FUNCTION || true // CLOSURE_EXPR implies expression

                val fnNode = FunctionNode().apply {
                    functionName =
                        if (nestedData.itsName.isNotEmpty()) Name().apply { identifier = nestedData.itsName } else null
                    for (i in 0 until nestedData.argCount) {
                        addParam(Name().apply { identifier = nestedData.getParamOrVarName(i) })
                    }
                    this.isExpressionClosure = isNestedExprClosure // Mark as expression

                    // Recursive call to decompile the function body
                    val bodyAst = decompileToAstNode(nestedData, ctx.scope, isNestedExprClosure)
                    this.body =
                        if (isNestedExprClosure && bodyAst is AstRoot && bodyAst.children?.size == 1 && bodyAst.firstChild is ExpressionStatement) {
                            // For arrow functions returning a single expression, use the expression directly
                            (bodyAst.firstChild as ExpressionStatement).expression ?: bodyAst
                        } else {
                            bodyAst // Use AstRoot (effectively a Block) for regular function bodies or complex arrow functions
                        }
                }
                ctx.stack.push(fnNode)
            }

            Icode.ICODE_CLOSURE_STMT -> { // Function Statement
                val fnIndex = getShort(iCode, ctx.pc)
                ctx.pc += 2
                val nestedData = data.getFunction(fnIndex)
                // Function statements are not expression closures by default for their body representation
                val isNestedExprClosure = nestedData.itsFunctionType == FunctionNode.ARROW_FUNCTION

                val fnNode = FunctionNode().apply {
                    functionName = Name().apply { identifier = nestedData.itsName ?: "anonymous_${offset}" }
                    for (i in 0 until nestedData.argCount) {
                        addParam(Name().apply { identifier = nestedData.getParamOrVarName(i) })
                    }
                    this.isExpressionClosure = false // Function statements are not expression closures overall

                    // Recursive call
                    val bodyAst = decompileToAstNode(
                        nestedData, ctx.scope, isNestedExprClosure
                    ) // Pass appropriate isExpressionClosure for body
                    this.body =
                        if (isNestedExprClosure && bodyAst is AstRoot && bodyAst.children?.size == 1 && bodyAst.firstChild is ExpressionStatement) {
                            (bodyAst.firstChild as ExpressionStatement).expression ?: bodyAst
                        } else {
                            bodyAst
                        }
                }
                ctx.stack.push(fnNode)
            }

            // Basic Exception Handling
            Token.THROW -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for THROW"); return
                }
                ctx.stack.push(ThrowStatement(ctx.stack.pop()))
            }

            Token.RETHROW -> {
                // RETHROW typically re-throws the currently caught exception.
                // This might need special handling in CFG for catch blocks.
                // For now, create a ThrowStatement. It might need a placeholder for the caught exception.
                // RhinoDecompiler2 just creates a new ThrowStatement without an explicit expression.
                // This might be represented by a null expression in ThrowStatement, or a special marker.
                val ts = ThrowStatement()
                // ts.setExpression(null); // Or a special node indicating rethrow
                ctx.stack.push(ts)
            }

            // Scope-Related Opcodes
            Token.ENTERWITH -> {
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for ENTERWITH"); return
                }
                val scopeObj = ctx.stack.pop()
                val withStatement = WithStatement(scopeObj, null) // Body (Scope node) set later by CFG
                ctx.stack.push(withStatement) // Push the statement, its body processing will manage stack
            }

            Token.LEAVEWITH -> {
                // Marks end of WithStatement body. CFG will handle this.
                // Stack should ideally have the WithStatement, which is now complete.
                // Or, if body elements were pushed, they need to be associated with the WithStatement.
                // For now, this opcode itself doesn't push/pop anything directly related to AST structure here.
                // It's more of a control flow marker.
            }

            Token.CATCH_SCOPE -> {
                // This opcode is used to introduce the exception variable into the scope.
                // The actual exception object is assumed to be on top of JVM stack, not Rhino stack.
                // The variable name might come from stringReg or pre-set in context.
                val exceptionVarName = ctx.currentCatchBlockExceptionVarName ?: ctx.stringReg
                ?: data.argNames.getOrNull(ctx.indexReg) // Fallback if indexReg is used for var slot
                ?: "ex_${offset}" // Default if no name found

                if (DEBUG) println("CATCH_SCOPE for variable: $exceptionVarName at $offset")

                val nameNode = Name().apply { identifier = exceptionVarName }
                // The CatchClause node itself usually handles the variable declaration in the AST.
                // This opcode's role is more about runtime scope setup.
                // For AST, we don't push a new node here unless it's a specific declaration not covered by CatchClause.
                // We'll ensure the variable is in the current DecompilerContext.scope if needed,
                // but CatchClause AST node should be the primary source of the variable in the tree.
                ctx.scope?.addVariable(exceptionVarName)
                // We don't typically push an AST node for CATCH_SCOPE itself, as the CatchClause node represents this.
                // However, if a value is on stack representing the caught error that needs to be assigned, handle here.
                // For now, assume the CatchClause node handles the variable.
            }

            Icode.ICODE_IFEQ_POP -> { // Often used in switch cases
                if (ctx.stack.isEmpty()) {
                    if (DEBUG) println("Stack underflow for ICODE_IFEQ_POP"); return
                }
                val caseExpression = ctx.stack.pop()
                val switchCase = SwitchCase()
                switchCase.expression = caseExpression
                // Statements for this case will be added later by CFG analysis / block processing.
                ctx.stack.push(switchCase) // Push the case, it will be collected by SwitchStatement logic.
                // PC advancement for jump offset is handled by GOTO or other jump opcodes that follow.
                // This opcode itself is 1 byte.
            }

            // Other common tokens (from previous steps, ensure they are still correct)
            Token.NAME -> {
                val nameStr = ctx.stringReg ?: run { if (DEBUG) println("stringReg is null for NAME"); return }
                ctx.stack.push(Name().apply { identifier = nameStr })
            }

            Icode.ICODE_SHORTNUMBER -> {
                val v = iCode[ctx.pc].toShort().toDouble(); ctx.pc++; ctx.stack.push(NumberLiteral(v))
            }

            Icode.ICODE_INTNUMBER -> {
                val v = getInt(iCode, ctx.pc).toDouble(); ctx.pc += 4; ctx.stack.push(NumberLiteral(v))
            }

            Icode.ICODE_ZERO -> ctx.stack.push(NumberLiteral(0.0))
            Icode.ICODE_ONE -> ctx.stack.push(NumberLiteral(1.0))
            Icode.ICODE_UNDEF -> ctx.stack.push(Name().apply { identifier = "undefined" })

            // ARRAYLIT and OBJECTLIT are now primarily driven by ICODE_LITERAL_NEW and ICODE_LITERAL_SET.
            // The direct Token.ARRAYLIT/OBJECTLIT might still appear in some contexts,
            // potentially as markers or for empty literals if not preceded by ICODE_LITERAL_NEW.
            // For now, keeping the simplified versions if ICODE_LITERAL_... path isn't taken.
            Token.ARRAYLIT -> {
                val count = ctx.indexReg
                val elements = mutableListOf<AstNode>()
                if (ctx.stack.size < count) {
                    if (DEBUG) println("Stack underflow for ARRAYLIT elements"); return
                }
                for (i in 0 until count) {
                    elements.add(0, ctx.stack.pop())
                }
                ctx.stack.push(ArrayLiteral(elements))
            }

            Token.OBJECTLIT -> { // This will likely be superseded by ICODE_LITERAL_NEW logic
                ctx.stack.push(ObjectLiteral()) // Push empty, ICODE_LITERAL_SET will populate
            }

            // Miscellaneous Common Opcodes (Equality, Strict Equality, Instanceof, In)
            Token.EQ -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for EQ"); return
                }
                val right = ctx.stack.pop();
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.EQ, left, right))
            }

            Token.NE -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for NE"); return
                }
                val right = ctx.stack.pop();
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.NE, left, right))
            }

            Token.SHEQ -> { // Strict equality ===
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for SHEQ"); return
                }
                val right = ctx.stack.pop();
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.SHEQ, left, right))
            }

            Token.SHNE -> { // Strict inequality !==
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for SHNE"); return
                }
                val right = ctx.stack.pop();
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.SHNE, left, right))
            }

            Token.INSTANCEOF -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for INSTANCEOF"); return
                }
                val right = ctx.stack.pop();
                val left = ctx.stack.pop() // left instanceof right
                ctx.stack.push(createInfix(Token.INSTANCEOF, left, right))
            }

            Token.IN -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for IN"); return
                }
                val right = ctx.stack.pop();
                val left = ctx.stack.pop() // left in right
                ctx.stack.push(createInfix(Token.IN, left, right))
            }

            Token.LT -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for LT"); return
                }
                val right = ctx.stack.pop();
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.LT, left, right))
            }

            Token.LE -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for LE"); return
                }
                val right = ctx.stack.pop();
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.LE, left, right))
            }

            Token.GT -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for GT"); return
                }
                val right = ctx.stack.pop();
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.GT, left, right))
            }

            Token.GE -> {
                if (ctx.stack.size < 2) {
                    if (DEBUG) println("Stack underflow for GE"); return
                }
                val right = ctx.stack.pop();
                val left = ctx.stack.pop()
                ctx.stack.push(createInfix(Token.GE, left, right))
            }


            else -> {
                if (DEBUG) {
                    println(
                        "Unhandled opcode: ${Token.name(opcode)} ($opcode) at offset $offset Stack: ${
                        ctx.stack.joinToString {
                            it.javaClass.simpleName + ":" + it.toSource(
                                0
                            )
                        }
                    }")
                }
            }
        }
    }

    private fun getShort(code: ByteArray, offset: Int): Int {
        return (code[offset].toInt() and 0xFF shl 8) or (code[offset + 1].toInt() and 0xFF)
    }

    private fun getInt(code: ByteArray, offset: Int): Int {
        return (code[offset].toInt() and 0xFF shl 24) or (code[offset + 1].toInt() and 0xFF shl 16) or (code[offset + 2].toInt() and 0xFF shl 8) or (code[offset + 3].toInt() and 0xFF)
    }


    companion object {
        const val DEBUG = false // Set to true for debugging output

        // Public API
        fun decompile(data: InterpreterData, parentScope: Scope? = null): String {
            val resultNode = decompileToAstNode(data, parentScope, false) // Top-level is not an expression closure
            return resultNode.toSource(0) ?: "// Error: Decompiled node is null or produced null source"
        }

        private fun decompileToAstNode(
            data: InterpreterData, parentScopeExternal: Scope?, isExpressionClosure: Boolean = false
        ): AstNode {
            val newScope = Scope(name = data.itsName ?: "anonymous_${data.hashCode()}", parent = parentScopeExternal)
            // Add function parameters to the new scope
            for (i in 0 until data.argCount) {
                val paramName = data.getParamOrVarName(i) // Assuming getParamOrVarName covers arg names by index
                if (paramName.isNotEmpty()) { // Ensure param name is not empty
                    newScope.addVariable(paramName)
                }
            }

            val context = DecompilerContext(scope = newScope)
            // Note: 'isExpressionClosure' passed to this function might influence how buildAst behaves if that logic is added.
            // For now, buildAst always returns AstRoot.

            val cfAnalyzer = RhinoDecompilerControlFlowAnalyzer()
            val cfg = cfAnalyzer.analyze(data.itsICode)

            val decompiler = NewRhinoDecompiler()
            val mainStackForAst = KStack()

            // Process exception table for TryStatement structures first
            val exceptionTable = data.itsExceptionTable ?: emptyList()
            val processedTryStarts = mutableSetOf<Int>()

            for (exEntry in exceptionTable.sortedBy { it.tryStart }) {
                if (exEntry.tryStart in processedTryStarts) continue
                processedTryStarts.add(exEntry.tryStart)

                val tryStatement = TryStatement()
                val tryStartPc = exEntry.tryStart
                // Determine tryEnd: usually the start of the first handler or exEntry.tryEnd
                val firstHandlerPc = exceptionTable.filter { it.tryStart == tryStartPc }.minOfOrNull { it.handlerStart }
                    ?: exEntry.tryEnd
                val tryEndPc = minOf(exEntry.tryEnd, firstHandlerPc - 1)


                val tryBlocks = decompiler.getBlocksInRange(cfg, tryStartPc, tryEndPc, context.visitedBlocks)
                tryStatement.tryBlock = decompiler.decompileBranch(tryBlocks, data, context, cfg, "try")
                tryBlocks.forEach { context.visitedBlocks.add(it) }

                val handlersForThisTry =
                    exceptionTable.filter { it.tryStart == tryStartPc }.sortedBy { it.handlerStart }
                var currentHandlerEndPc = tryEndPc // End of the previous block (try or catch)

                for (handlerEntry in handlersForThisTry) {
                    context.visitedBlocks.add(cfg.blocks.values.find { it.startOffset == handlerEntry.handlerStart }
                        ?: continue) // Mark handler entry block

                    val catchVarName =
                        data.argNames.getOrNull(handlerEntry.localSlot) ?: "ex${handlerEntry.handlerStart}"

                    // Determine end of this handler: start of next handler, or overall tryEnd if this is the last.
                    // This is a simplification; real end needs CFG. For now, assume catch blocks are contiguous or end at tryEnd.
                    val nextHandlerStartForSameTry =
                        handlersForThisTry.filter { it.handlerStart > handlerEntry.handlerStart }
                            .minOfOrNull { it.handlerStart }
                    val catchEndPcCalc =
                        (nextHandlerStartForSameTry?.minus(1)) ?: exEntry.tryEnd // This is a rough estimate

                    val catchBlocksList = decompiler.getBlocksInRange(
                        cfg, handlerEntry.handlerStart, catchEndPcCalc, context.visitedBlocks
                    )

                    // Temporarily set exception var name for CATCH_SCOPE in this branch
                    val originalCatchVarName = context.currentCatchBlockExceptionVarName
                    context.currentCatchBlockExceptionVarName = catchVarName

                    val catchBody = decompiler.decompileBranch(catchBlocksList, data, context, cfg, "catch")
                    context.currentCatchBlockExceptionVarName = originalCatchVarName // Restore

                    catchBlocksList.forEach { context.visitedBlocks.add(it) }

                    if (handlerEntry.type == 0) { // Catch block
                        val catchClause = CatchClause().apply {
                            this.varName = Name().apply { identifier = catchVarName }
                            this.body = catchBody
                            // Catch condition (e.g., `if (e instanceof Error)`) is not directly in basic exception table.
                            // Would require more detailed bytecode pattern or CFG analysis.
                            // this.catchCondition = ...
                        }
                        tryStatement.addCatchClause(catchClause)
                    } else if (handlerEntry.type == 1) { // Finally block
                        tryStatement.finallyBlock = catchBody
                    }
                    currentHandlerEndPc = catchEndPcCalc
                }
                mainStackForAst.push(tryStatement)
            }


            // Main block processing loop (skips blocks already handled by TryStatements)
            if (cfg.entryBlock != null && cfg.blocks.isNotEmpty()) {
                val sortedBlocks = cfg.blocks.values.sortedBy { it.startOffset }

                for (currentBlock in sortedBlocks) {
                    if (currentBlock in context.visitedBlocks) {
                        if (DEBUG) println("Skipping already visited block ${currentBlock.id} (part of try or other structure)")
                        continue
                    }
                    context.visitedBlocks.add(currentBlock)

                    context.pc = currentBlock.startOffset
                    context.lastCondition = null
                    val blockStack = KStack() // Temporary stack for the current block's expressions

                    if (DEBUG) {
                        println("Processing BasicBlock ID: ${currentBlock.id}, Start: ${currentBlock.startOffset}, End: ${currentBlock.endOffset}")
                    }

                    while (context.pc <= currentBlock.endOffset && context.pc < data.itsICode.size) {
                        val previousPc = context.pc
                        decompiler.decodeNextInstruction(
                            context.copy(stack = blockStack), data, cfg
                        ) // Use blockStack for instruction decoding

                        if (context.pc == previousPc) {
                            if (DEBUG && context.pc <= currentBlock.endOffset) {
                                val currentOpcode = data.itsICode[context.pc].toInt() and 0xFF
                                println(
                                    "Warning: PC did not advance in block ${currentBlock.id} for opcode ${
                                        Token.name(
                                            currentOpcode
                                        )
                                    } at ${context.pc}. Forcing advance."
                                )
                            }
                            context.pc++
                        }
                        if (previousPc <= currentBlock.endOffset && context.pc > currentBlock.endOffset + 1 && context.pc < data.itsICode.size /* ensure not out of bounds */) {
                            if (DEBUG) println("PC ${context.pc} advanced past block ${currentBlock.id} end ${currentBlock.endOffset}. Breaking inner loop.")
                            break
                        }
                    }

                    // Check for known structures (If, Loop) starting at this block
                    var structureProcessed = false
                    for (structWrapper in cfg.structures) { // Iterate over ControlStructure wrappers
                        when (structWrapper) {
                            is IfStructWrapper -> {
                                val ifStructure = structWrapper.ifStructure
                                if (ifStructure.conditionBlock == currentBlock) {
                                    if (DEBUG) println("Found IfStructure for block ${currentBlock.id}")
                                    val conditionExpr = context.lastCondition ?: blockStack.firstOrNull()
                                    ?: Name("ERROR_NO_CONDITION_FOR_IF")
                                    if (context.lastCondition == null && blockStack.isNotEmpty()) blockStack.removeAt(0) // Consume if used from blockStack

                                    val thenBlocks = decompiler.getBlocksForBranch(
                                        cfg,
                                        ifStructure.thenEntryBlock,
                                        listOf(ifStructure.elseEntryBlock, ifStructure.endOfIfBlock),
                                        context.visitedBlocks
                                    )
                                    val thenPart = decompiler.decompileBranch(thenBlocks, data, context, cfg)

                                    var elsePart: AstNode? = null
                                    if (ifStructure.elseEntryBlock != null) {
                                        val elseBlocks = decompiler.getBlocksForBranch(
                                            cfg,
                                            ifStructure.elseEntryBlock,
                                            listOf(ifStructure.endOfIfBlock),
                                            context.visitedBlocks
                                        )
                                        elsePart = decompiler.decompileBranch(elseBlocks, data, context, cfg)
                                    }

                                    val ifStatement = IfStatement().apply {
                                        this.condition = conditionExpr
                                        this.thenPart = thenPart
                                        this.elsePart = elsePart
                                    }
                                    mainStackForAst.push(ifStatement)
                                    context.visitedBlocks.add(ifStructure.conditionBlock)
                                    thenBlocks.forEach { context.visitedBlocks.add(it) }
                                    if (ifStructure.elseEntryBlock != null) {
                                        val elseBlocks = decompiler.getBlocksForBranch(
                                            cfg,
                                            ifStructure.elseEntryBlock,
                                            listOf(ifStructure.endOfIfBlock),
                                            context.visitedBlocks
                                        )
                                        elseBlocks.forEach { context.visitedBlocks.add(it) }
                                    }
                                    structureProcessed = true
                                    break
                                }
                            }

                            is LoopStructWrapper -> {
                                val loopStructure = structWrapper.loopStructure
                                if (loopStructure.loopHeader == currentBlock) {
                                    if (DEBUG) println("Found LoopStructure (${loopStructure.loopType}) for block ${currentBlock.id}")

                                    when (loopStructure.loopType) {
                                        LoopType.WHILE -> {
                                            // For WHILE, the header block itself contains the condition.
                                            // We need to ensure `context.lastCondition` is set by processing this header block.
                                            // The current `blockStack` is for `currentBlock` (the header).
                                            // If `lastCondition` isn't set by an IFEQ/IFNE in header, the loop is malformed or complex.
                                            val conditionExpr = context.lastCondition ?: blockStack.firstOrNull()
                                            ?: Name("ERROR_NO_WHILE_CONDITION")
                                            if (context.lastCondition == null && blockStack.isNotEmpty() && conditionExpr !is Name) blockStack.removeAt(
                                                0
                                            )


                                            val bodyBlocks = decompiler.getBlocksForBranch(
                                                cfg,
                                                loopStructure.bodyEntryBlock,
                                                listOf(loopStructure.loopHeader, loopStructure.endOfLoopBlock),
                                                context.visitedBlocks
                                            )
                                            val bodyPart = decompiler.decompileBranch(bodyBlocks, data, context, cfg)

                                            val whileLoop = WhileLoop().apply {
                                                this.condition = conditionExpr
                                                this.body = bodyPart
                                            }
                                            mainStackForAst.push(whileLoop)
                                        }

                                        LoopType.FOR_IN -> {
                                            // The header block (currentBlock) should have ENUM_INIT_KEYS and ENUM_NEXT.
                                            // The blockStack for currentBlock will contain [iteratedObject_placeholder, key_placeholder]
                                            // after ENUM_INIT_KEYS and ENUM_NEXT.
                                            // The actual assignment to loop variable happens in the body or a small block after header.

                                            val iteratedObjectNode =
                                                blockStack.firstOrNull { it is Name && it.identifier != "enum_next_key_placeholder" }
                                                    ?: Name("ERROR_NO_ITERATED_OBJECT_FORIN")
                                            // The key placeholder is used to identify the assignment.
                                            // The loop variable is the LHS of assignment where RHS is "enum_next_key_placeholder".
                                            // This is a strong heuristic needed for this simplified stage.

                                            // We need to look into the first block of the body for the assignment.
                                            // This heuristic is very basic. A real system needs proper data flow analysis.
                                            var loopVarNode: AstNode = Name("!ERROR_loopVar_FORIN!")
                                            val firstBodyBlock = loopStructure.bodyEntryBlock // This is a BasicBlock

                                            // Create a temporary context to "peek" into the first body block's potential assignment
                                            val tempBodyBlockProcessingCtx =
                                                context.copy(stack = KStack(), lastCondition = null)
                                            var tempPc = firstBodyBlock.startOffset
                                            // Process only a few instructions in the first body block to find the assignment
                                            // This is still a heuristic and might not cover all patterns.
                                            while (tempPc <= firstBodyBlock.endOffset && tempPc < data.itsICode.size && tempBodyBlockProcessingCtx.stack.size < 2) { // Limit processing
                                                val prevTempPc = tempPc
                                                // Use a new context copy for each instruction to isolate stack effects
                                                val instrCtx =
                                                    tempBodyBlockProcessingCtx.copy(pc = tempPc, stack = KStack())
                                                decompiler.decodeNextInstruction(instrCtx, data, cfg)
                                                tempPc =
                                                    instrCtx.pc // Get PC from the context used by decodeNextInstruction
                                                // Add results to tempBodyBlockProcessingCtx stack for inspection
                                                tempBodyBlockProcessingCtx.stack.addAll(instrCtx.stack)
                                                if (prevTempPc == tempPc) tempPc++ // Ensure progress if instruction didn't advance
                                            }

                                            // Now inspect the tempBodyBlockProcessingCtx.stack for the assignment
                                            // The stack items are in reverse order of typical expression evaluation for assignments
                                            // e.g., for `v = key`, stack might have [v, key, Assignment(v,key)] or just [Assignment(v,key)]
                                            // We are looking for Assignment(X, Name("enum_next_key_placeholder"))
                                            // or VariableDeclaration(VariableInitializer(X, Name("enum_next_key_placeholder")))

                                            val assignmentNode =
                                                tempBodyBlockProcessingCtx.stack.firstNotNullOfOrNull { node ->
                                                    when (node) {
                                                        is Assignment -> if (node.right is Name && node.right.identifier == "enum_next_key_placeholder") node.left else null
                                                        is VariableDeclaration -> node.variables.firstOrNull()
                                                            ?.takeIf { it.initializer is Name && it.initializer.identifier == "enum_next_key_placeholder" }?.target

                                                        else -> null
                                                    }
                                                }
                                            if (assignmentNode != null) loopVarNode = assignmentNode
                                            else {
                                                if (DEBUG) println("FOR_IN: Could not find loop variable assignment for key 'enum_next_key_placeholder'")
                                            }
                                        }


                                            val bodyBlocks = decompiler.getBlocksForBranch(
                                            cfg,
                                            loopStructure.bodyEntryBlock,
                                            listOf(loopStructure.loopHeader, loopStructure.endOfLoopBlock),
                                            context.visitedBlocks
                                        )

                                            val bodyPart = decompiler.decompileBranch(bodyBlocks, data, context, cfg)
                                        // The bodyPart might redundantly include the loop variable assignment if it was in the first body block.
                                        // This would need refinement in a full system.

                                            val forInLoop = ForInLoop().apply {
                                            this.iterator = loopVarNode
                                            this.iteratedObject = iteratedObjectNode
                                            this.body = bodyPart
                                            this.isForEach = false
                                        }

                                        mainStackForAst.push(forInLoop)
                                    }
                                    LoopType.DO_WHILE -> {
                                        if (DEBUG) println("DO_WHILE processing not yet fully implemented.")
                                        mainStackForAst.addAll(blockStack)
                                    }
                                }
                                context.visitedBlocks.add(loopStructure.loopHeader)
                                // Mark body blocks as visited (getBlocksForBranch should not include already visited ones from outer context)
                                val bodyBlocks = decompiler.getBlocksForBranch(
                                    cfg,
                                    loopStructure.bodyEntryBlock,
                                    listOf(loopStructure.loopHeader, loopStructure.endOfLoopBlock),
                                    context.visitedBlocks
                                )
                                bodyBlocks.forEach { context.visitedBlocks.add(it) }
                                structureProcessed = true
                                break
                            }
                        }
                    }
                }

                if (!structureProcessed) {
                    if (context.lastCondition != null) {
                        blockStack.push(context.lastCondition!!)
                        context.lastCondition = null
                    }
                    mainStackForAst.addAll(blockStack)
                }
            }
        } else if (data .itsICode.isNotEmpty())
        {
            if (DEBUG) println("CFG analysis yielded no blocks/entry, or iCode is empty. Linear scan.")
            try {
                while (context.pc < data.itsICode.size) {
                    val previousPc = context.pc
                    decompiler.decodeNextInstruction(context, data, null) // Pass null for cfg
                    if (context.pc == previousPc) {
                        if (DEBUG && data.itsICode.isNotEmpty()) {
                            val currentOpcode = data.itsICode[context.pc].toInt() and 0xFF
                            println("Warning: PC did not advance (linear scan) for opcode ${Token.name(currentOpcode)} at ${context.pc}. Forcing advance.")
                        }
                        context.pc++
                    }
                }
            } catch (e: Exception) {
                if (DEBUG) {
                    println("Error during linear scan decompilation: ${e.message}")
                    e.printStackTrace()
                }
                return "// Error during linear scan decompilation: ${e.message}\n" + buildAst(context).toSource(0)
            }
        }


        return buildAst(context).toSource(0)
    }

    private fun buildAst(context: DecompilerContext /*, cfg: ControlFlowGraph? = null */): AstRoot {
        val astRoot = AstRoot()
        // Process remaining stack items. This might need more sophisticated handling
        // for merging into a coherent AST structure, especially with control flow.
        while (context.stack.isNotEmpty()) {
            var node = context.stack.removeAt(0) // Process from bottom of stack (earlier operations)

            // If node is a VariableDeclaration that is not a statement, it might be an unassigned part of an expression.
            // This can happen with BINDNAME if it wasn't part of a larger expression.
            // For now, we'll wrap loose expressions in ExpressionStatement.

            // Handle specific AstNode types that are inherently statements or should be treated as such at this stage.
            if (node is Statement) { // Catches ReturnStatement, ThrowStatement, WithStatement, etc.
                astRoot.addChild(node)
            } else if (node is FunctionNode && node.functionName != null && !node.isExpressionClosure) {
                // Function statement: function foo() {}
                // FunctionNode itself is not a Statement, but represents one here.
                astRoot.addChild(node)
            } else if (node is VariableDeclaration) {
                // VariableDeclaration: var x;, var x = 1;
                // VariableDeclaration is an Expression, but here it acts as a statement.
                // Ensure its 'type' (VAR, CONST, LET) is set correctly by producer opcodes.
                // If it's from BINDNAME, we set it to VAR. If from SETVAR1 for a new var, it's implicitly var.
                if (node.type == 0) node.type = Token.VAR // Default to VAR if not set (e.g. from SETVAR1)
                node.setIsStatement(true) // Mark it as a statement
                astRoot.addChild(node)
            } else {
                // Wrap other expressions (literals, calls, assignments if their result is used then popped, etc.)
                // in ExpressionStatement.
                val isStatementExpression = node !is Assignment && !node.hasSideEffects()
                val exprStmt = ExpressionStatement(node, isStatementExpression)
                astRoot.addChild(exprStmt)
            }
        }
        return astRoot
    }
}