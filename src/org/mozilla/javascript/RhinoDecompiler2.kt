package org.mozilla.javascript

import org.mozilla.javascript.ast.*
import java.util.*

// Helper function for logging
fun log2(message: Any?) {
    if (RhinoDecompiler2.DEBUG) println(message)
}

// BigInt literal class (if not already defined in the AST package)
class BigIntLiteral(position: Int) : AstNode(position) {
    var value: String = ""
    
    override fun toSource(depth: Int): String {
        return value + "n"
    }
    
    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
    }
}


class RhinoDecompiler2 {
    data class Scope(
        val name: String = "TOP",
        val variables: MutableSet<String> = mutableSetOf(),
        var parent: Scope? = null
    ) {
        fun addVariable(name: String) {
            variables.add(name)
        }

        fun hasVariable(name: String): Boolean {
            if (name in variables) {
                return true
            }
            if (null != parent && name in parent!!.variables) {
                return true
            }
            return false
        }
    }

    data class Label(
        val target: Int,
        val position: Int,
        val lineNo: Int,
        val opcode: Int,
        var node: AstNode? = null
    ) {
        override fun toString(): String {
            return "${Interpreter.bytecodeName(opcode)} TARGET:$target POSITION:$position"
        }
    }

    class KStack : Stack<AstNode>() {
        override fun push(element: AstNode): AstNode {
            log2(
                "\u001B[32m │[PUSH ${element::class.java}] ${
                    element.toSource().replace("\n", "").toCN()
                } \u001B[0m"
            )
            val result = super.push(element)
            if (DEBUG) printStack()
            return result
        }

        override fun pop(): AstNode {
            val element = super.pop()
            log2(
                "\u001B[31m │[POP  ${element::class.java}] ${
                    element.toSource().replace("\n", "").toCN()
                } \u001B[0m"
            )
            if (DEBUG) printStack()
            return element
        }

        fun printStack() {
            log2("\u001B[34m │====================================STACK  \u001B[0m")
            this.forEach { node ->
                val source = node.toSource().replace("\n", "").toCN()
                log2("\u001B[34m │ ${node.position} $source \u001B[0m")
            }
            log2("\u001B[34m │====================================STACK  \u001B[0m")
        }

        fun toBlock(position: Int = -1): Block {
            val tempDebug = DEBUG
            DEBUG = false
            val block = Block(position)
            while (if (position > 0) (isNotEmpty() && peek().position >= position) else isNotEmpty()) {
                block.addChildToFront(pop())
            }
            DEBUG = tempDebug
            return block
        }
    }

    data class DecompilerContext(
        var parent: DecompilerContext? = null,
        val block: Block = Block(),
        val stack: KStack = KStack(),
        val labels: MutableMap<Int, Label> = mutableMapOf(),
        val scope: Scope = Scope(),
        var stringReg: String = "",
        var indexReg: Int = -1,
        var lineNo: Int = -1,
        var pc: Int = 0,
        var inLoop: Boolean = false,
        var loopStack: Stack<AstNode> = Stack(),
        var switchStack: Stack<SwitchStatement> = Stack(),
        var tryStack: Stack<TryStatement> = Stack()
    ) {
        fun addVar(node: Name, scope: Scope = this.scope): AstNode {
            if (scope.hasVariable(node.string)) return node
            scope.addVariable(node.string)
            return VariableDeclaration(node.position).apply {
                addVariable(VariableInitializer().apply {
                    target = node
                })
            }
        }

        fun findNextLabel(startPc: Int): Int? {
            return labels.keys.sorted().firstOrNull { it > startPc }
        }
    }

    companion object {
        var DEBUG = true
        
        fun decompile(data: InterpreterData, parent: Scope? = null, name: String = ""): Block {
            if (DEBUG) data.dumpInfo()
            val ctx = DecompilerContext(scope = Scope(name)).apply { scope.parent = parent }
            return dealInstruction(ctx, data)
        }

        private fun dealInstruction(
            ctx: DecompilerContext, data: InterpreterData, end: Int = data.itsICode.size
        ): Block {
            val start = ctx.pc
            log2("\u001B[33m │===========START==FROM $start TO $end  \u001B[0m")
            val iCode = data.itsICode

            while (ctx.pc < end) {
                val opcode = iCode[ctx.pc].toInt()
//                log2(" [${ctx.pc}] ${Interpreter.bytecodeName(opcode)}")
                try {
                    decodeInstruction(ctx, opcode, data)
                } catch (e: Exception) {
                    log2("\u001B[31m │[ERROR] ${e.message} \u001B[0m")
                    ctx.pc++
                }
            }
            
            // Handle nested functions
            if (ctx.pc == data.itsICode.size && data.itsNeedsActivation) {
                data.itsNestedFunctions?.filter { !it.itsName.isNullOrEmpty() && !data.argNames.isNullOrEmpty() && it.itsName in data.argNames }
                    ?.forEach { item ->
                        ctx.stack.push(FunctionNode(ctx.pc).apply {
                            params = (0 until item.argCount).map { Name(ctx.pc, item.argNames[it]) }
                            functionName = Name(0, item.itsName ?: "")
                            body = decompile(item, ctx.scope)
                        })
                    }
            }
            
            ctx.pc--
            log2("\u001B[33m │===========END====FROM $start TO $end  \u001B[0m")
            return ctx.stack.toBlock(start)
        }

        private fun decodeInstruction(ctx: DecompilerContext, opcode: Int, data: InterpreterData) {
            val iCode = data.itsICode
            val stringTable = data.itsStringTable
            val argNames = data.argNames
            val literalIds = data.literalIds
            val doubleTable = data.itsDoubleTable
            val regExpLiterals = data.itsRegExpLiterals
            val nestedFunctions = data.itsNestedFunctions
            val tryInfos = data.getTryInfos()
            val bigIntTable = data.itsBigIntTable
            val templateLiterals = data.itsTemplateLiterals

            when (opcode) {
                Token.ENTERWITH -> {
                    // Enter with statement
                    ctx.pc++
                }
                
                Token.LEAVEWITH -> {
                    // Leave with statement
                    ctx.pc++
                }

                Token.RETURN -> {
                    ctx.stack.push(ReturnStatement(ctx.pc).apply {
                        returnValue = ctx.stack.pop()
                    })
                    ctx.pc++
                }

                Token.GOTO -> {
                    val offset = Interpreter.getShort(iCode, ctx.pc + 1)
                    val targetPc = ctx.pc + offset
                    val target = iCode[targetPc].toInt()
                    ctx.pc += 2
                    
                    // Handle different GOTO scenarios
                    if (ctx.pc >= 4 && iCode[ctx.pc - 4].toInt() == Token.LEAVEWITH) {
                        if (target == Token.TRUE) {
                            ctx.stack.push(ContinueStatement(ctx.pc))
                        } else {
                            ctx.stack.push(BreakStatement(ctx.pc))
                        }
                        ctx.pc++
                        return
                    }
                    
                    ctx.labels[target] = Label(target, ctx.pc, ctx.lineNo, opcode)
                    val block = dealInstruction(ctx, data, targetPc)
                    
                    // Handle different control structures
                    if (ctx.stack.isNotEmpty()) {
                        when (val topNode = ctx.stack.peek()) {
                            is ForInLoop -> {
                                log2("\u001B[33m │[GOTO] ForInLoop body \u001B[0m")
                                topNode.body = block
                                ctx.stack.printStack()
                            }
                            is SwitchStatement -> {
                                log2("\u001B[33m │[LABEL] GOTO SwitchStatement \u001B[0m")
                                // Just add all statements to the first case for now
                                if (topNode.cases.isNotEmpty()) {
                                    val firstCase = topNode.cases[0]
                                    val stacks = block.toStack()
                                    for (stack in stacks) {
                                        firstCase.addStatement(stack)
                                    }
                                }
                            }
                            is IfStatement -> {
                                log2("\u001B[33m │[GOTO] IfStatement elsePart \u001B[0m")
                                topNode.elsePart = block
                            }
                            else -> {
                                log2("\u001B[33m │[LABEL] GOTO Unknown \u001B[0m")
                                ctx.stack.push(block)
                            }
                        }
                    } else {
                        ctx.stack.push(block)
                    }
                    ctx.pc++
                }

                Token.IFEQ -> {
                    val offset = Interpreter.getShort(iCode, ctx.pc + 1)
                    val targetPc = ctx.pc + offset
                    val target = iCode[targetPc].toInt()
                    ctx.pc += 2
                    
                    if (ctx.pc >= 3 && iCode[ctx.pc - 3].toInt() == Token.ENUM_NEXT) {
                        log2("\u001B[33m │[IFEQ] ForInLoop \u001B[0m")
                        // Handle for-in loop
                        ctx.pc++
                    } else if (target == Token.IFNE) {
                        ctx.pc++
                        log2("\u001B[33m │[IFEQ] OR \u001B[0m")
                        ctx.stack.push(InfixExpression(ctx.pc).apply {
                            operator = Token.OR
                            left = ctx.stack.pop()
                            val block = dealInstruction(ctx, data, targetPc)
                            right = if (block.hasChildren()) block.getFirstChild() as AstNode else EmptyExpression()
                        })
                    } else if (ctx.pc > targetPc) {
                        log2("\u001B[33m │[IFEQ] WhileLoop \u001B[0m")
                        ctx.stack.push(WhileLoop(ctx.pc).apply {
                            condition = ctx.stack.pop()
                            body = ctx.stack.pop() as? Block ?: Block().apply { addChild(ctx.stack.pop()) }
                        })
                        ctx.pc++
                    } else {
                        // Handle other IFEQ cases
                        log2("\u001B[33m │[IFEQ] Standard conditional \u001B[0m")
                        val condition = ctx.stack.pop()
                        val thenBlock = dealInstruction(ctx, data, targetPc)
                        val ifStmt = IfStatement(ctx.pc)
                        ifStmt.condition = condition
                        ifStmt.thenPart = thenBlock
                        ctx.stack.push(ifStmt)
                        ctx.pc++
                    }
                }

                Token.IFNE -> {
                    val offset = Interpreter.getShort(iCode, ctx.pc + 1)
                    val targetPc = ctx.pc + offset
                    val target = iCode[targetPc].toInt()
                    ctx.pc += 2
                    ctx.pc++
                    
                    if (target == Token.IFNE || target == Token.RETURN) {
                        log2("\u001B[33m │[IFNE] AND \u001B[0m")
                        val infixExpr = InfixExpression(ctx.pc)
                        infixExpr.operator = Token.AND
                        infixExpr.left = ctx.stack.pop()
                        val block = dealInstruction(ctx, data, targetPc)
                        infixExpr.right = if (block.hasChildren()) block.getFirstChild() as AstNode else EmptyExpression()
                        ctx.stack.push(infixExpr)
                    } else {
                        log2("\u001B[33m │[IFNE] IfStatement \u001B[0m")
                        val ifStmt = IfStatement(ctx.pc)
                        ifStmt.condition = ctx.stack.pop()
                        ifStmt.thenPart = dealInstruction(ctx, data, targetPc)
                        ctx.stack.push(ifStmt)
                    }
                }

                Token.SETNAME -> {
                    val value = ctx.stack.pop()
                    if (ctx.stack.peek() is ForInLoop && (ctx.stack.peek() as ForInLoop).iterator is EmptyExpression) {
                        (ctx.stack.peek() as ForInLoop).iterator = value
                    } else {
                        val assignment = Assignment(ctx.pc)
                        assignment.type = Token.ASSIGN
                        assignment.right = value
                        assignment.left = ctx.stack.pop()
                        ctx.stack.push(assignment)
                    }
                    ctx.pc++
                }

                // Binary operators
                Token.BITOR, Token.BITXOR, Token.BITAND, Token.EQ, Token.NE, Token.LT, Token.LE, 
                Token.GT, Token.GE, Token.LSH, Token.RSH, Token.URSH, Token.ADD, Token.SUB, 
                Token.MUL, Token.DIV, Token.MOD, Token.EXP -> {
                    ctx.stack.push(InfixExpression(ctx.pc).apply {
                        operator = opcode
                        right = ctx.stack.pop()
                        left = ctx.stack.pop()
                    })
                    ctx.pc++
                }

                // Unary operators
                Token.NOT, Token.BITNOT, Token.POS, Token.NEG -> {
                    val operand = ctx.stack.pop()
                    ctx.stack.push(UnaryExpression(opcode, ctx.pc, operand))
                    ctx.pc++
                }

                Token.NEW -> {
                    val newExpr = NewExpression(ctx.pc)
                    newExpr.arguments = List(ctx.indexReg) { ctx.stack.pop() }.reversed()
                    newExpr.target = ctx.stack.pop()
                    ctx.stack.push(newExpr)
                    ctx.pc++
                }

                Token.DELPROP -> {
                    ctx.stack.push(UnaryExpression(ctx.pc).apply {
                        operator = Token.DELPROP
                        operand = ctx.stack.pop()
                    })
                    ctx.pc++
                }

                Token.TYPEOF -> {
                    val operand = ctx.stack.pop()
                    ctx.stack.push(UnaryExpression(ctx.pc).apply {
                        operator = Token.TYPEOF
                        this.operand = operand
                    })
                    ctx.pc++
                }

                Token.GETPROP, Token.GETPROPNOWARN -> {
                    ctx.stack.push(PropertyGet(ctx.pc).apply {
                        target = ctx.stack.pop()
                        property = Name(ctx.pc, ctx.stringReg)
                    })
                    ctx.pc++
                }

                Token.SETPROP -> {
                    val right = ctx.stack.pop()
                    val target = ctx.stack.pop()
                    val left = PropertyGet(target, Name(ctx.pc, ctx.stringReg))
                    ctx.stack.push(Assignment(ctx.pc).apply {
                        type = Token.ASSIGN
                        this.right = right
                        this.left = left
                    })
                    ctx.pc++
                }

                Token.GETELEM -> {
                    ctx.stack.push(ElementGet(ctx.pc).apply {
                        element = ctx.stack.pop()
                        target = ctx.stack.pop()
                    })
                    ctx.pc++
                }

                Token.SETELEM -> {
                    val value = ctx.stack.pop()
                    val index = ctx.stack.pop()
                    val target = ctx.stack.pop()
                    ctx.stack.push(Assignment(ctx.pc).apply {
                        operator = Token.ASSIGN
                        right = value
                        left = ElementGet(ctx.pc).apply {
                            this.target = target
                            this.element = index
                        }
                    })
                    ctx.pc++
                }

                Token.CALL -> {
                    val arguments = List(ctx.indexReg) { ctx.stack.pop() }.reversed()
                    val target = ctx.stack.pop()
                    ctx.stack.push(FunctionCall(ctx.pc).apply {
                        this.target = target
                        this.arguments = arguments
                    })
                    ctx.pc++
                }

                Token.NAME -> {
                    ctx.stack.push(Name(ctx.pc, ctx.stringReg))
                    ctx.pc++
                }

                Token.NUMBER -> {
                    ctx.stack.push(NumberLiteral(ctx.pc).apply {
                        value = doubleTable[ctx.indexReg].toString()
                    })
                    ctx.pc++
                }

                Token.STRING -> {
                    ctx.stack.push(StringLiteral(ctx.pc).apply {
                        value = ctx.stringReg
                        quoteCharacter = '\''
                    })
                    ctx.pc++
                }

                Token.NULL, Token.THIS, Token.FALSE, Token.TRUE -> {
                    ctx.stack.push(KeywordLiteral(ctx.pc).apply {
                        type = opcode
                    })
                    ctx.pc++
                }

                Token.SHEQ, Token.SHNE -> {
                    if (ctx.pc + 1 < iCode.size && iCode[ctx.pc + 1].toInt() == Icode.Icode_IFEQ_POP) {
                        ctx.stack.push(SwitchCase(ctx.pc + 1).apply {
                            expression = ctx.stack.pop()
                        })
                    } else {
                        ctx.stack.push(InfixExpression(ctx.pc).apply {
                            operator = opcode
                            right = ctx.stack.pop()
                            left = ctx.stack.pop()
                        })
                    }
                    ctx.pc++
                }

                Token.REGEXP -> {
                    val re = regExpLiterals[ctx.indexReg]
                    log2("REGEXP>>${re::class.java.name}")
                    val field = re::class.java.getDeclaredField("source")
                    field.isAccessible = true
                    val source = (field.get(re) as CharArray).joinToString("")
                    ctx.stack.push(RegExpLiteral(ctx.pc).apply { 
                        value = source
                        flags = ""
                    })
                    ctx.pc++
                }

                Token.BINDNAME -> {
                    ctx.stack.push(ctx.addVar(Name(ctx.pc, ctx.stringReg)))
                    ctx.pc++
                }

                Token.THROW -> {
                    ctx.stack.push(ThrowStatement(ctx.pc).apply {
                        expression = ctx.stack.pop()
                    })
                    ctx.pc += 2
                }

                Token.RETHROW -> {
                    ctx.stack.push(ThrowStatement(ctx.pc).apply {
                        expression = Name(ctx.pc, "e") // Assuming 'e' is the caught exception
                    })
                    ctx.pc++
                }

                Token.IN -> {
                    ctx.stack.push(InfixExpression(ctx.pc).apply {
                        operator = Token.IN
                        right = ctx.stack.pop()
                        left = ctx.stack.pop()
                    })
                    ctx.pc++
                }

                Token.INSTANCEOF -> {
                    ctx.stack.push(InfixExpression(ctx.pc).apply {
                        operator = Token.INSTANCEOF
                        right = ctx.stack.pop()
                        left = ctx.stack.pop()
                    })
                    ctx.pc++
                }

                Token.LOCAL_LOAD -> {
                    // Local variable load
                    ctx.pc++
                }

                Token.GETVAR -> {
                    // Get variable
                    ctx.pc++
                }

                Token.SETVAR -> {
                    // Set variable
                    ctx.pc++
                }

                Token.CATCH_SCOPE -> {
                    val tryInfo = tryInfos.firstOrNull { it.handlerStart == ctx.pc }
                    ctx.pc++
                    if (tryInfo != null) {
                        ctx.stack.push(CatchClause(ctx.pc).apply {
                            varName = Name(ctx.pc, ctx.stringReg)
                            body = Block(ctx.pc)
                        })
                    }
                }

                Token.ENUM_INIT_KEYS, Token.ENUM_INIT_VALUES, Token.ENUM_INIT_ARRAY, Token.ENUM_INIT_VALUES_IN_ORDER -> {
                    ctx.stack.push(ForInLoop(ctx.pc).apply {
                        setIsForOf(false)
                        iteratedObject = ctx.stack.pop()
                        iterator = EmptyExpression()
                        body = Block()
                    })
                    ctx.pc++
                }

                Token.ENUM_NEXT, Token.ENUM_ID -> {
                    // Enum operations
                    ctx.pc++
                }

                Token.THISFN -> {
                    // Reference to the current function
                    ctx.stack.push(Name(ctx.pc, "arguments.callee"))
                    ctx.pc++
                }

                Token.RETURN_RESULT -> {
                    if ((ctx.pc + 1) < iCode.size) {
                        ctx.stack.push(ReturnStatement(ctx.pc))
                    }
                    ctx.pc++
                }

                Token.ARRAYLIT -> {
                    val elements = mutableListOf<AstNode>()
                    if (ctx.stack.peek() is ObjectLiteral) {
                        val objLiteral = ctx.stack.pop() as ObjectLiteral
                        objLiteral.elements.forEach {
                            elements.add(it.right)
                        }
                    } else {
                        // Handle array literals with explicit elements
                        val count = ctx.indexReg
                        for (i in 0 until count) {
                            elements.add(0, ctx.stack.pop())
                        }
                    }
                    ctx.stack.push(ArrayLiteral(ctx.pc).apply {
                        elements.forEach { addElement(it) }
                    })
                    ctx.pc++
                }

                Token.OBJECTLIT -> {
                    val objLiteral = ctx.stack.peek() as ObjectLiteral
                    if (literalIds != null && ctx.indexReg < literalIds.size) {
                        val ids = LinkedList((literalIds[ctx.indexReg] as Array<*>).toList())
                        objLiteral.elements.forEach { 
                            it.left = Name(ctx.pc, ids.poll() as String)
                        }
                    }
                    ctx.pc++
                }

                Token.GET_REF -> {
                    // Get reference
                    ctx.pc++
                }

                Token.SET_REF -> {
                    // Set reference
                    ctx.pc++
                }

                Token.DEL_REF -> {
                    // Delete reference
                    ctx.pc++
                }

                Token.REF_CALL -> {
                    // Reference call
                    ctx.pc++
                }

                Token.REF_SPECIAL -> {
                    // Special reference
                    ctx.pc++
                }

                Token.YIELD -> {
                    // Yield expression
                    ctx.stack.push(UnaryExpression(ctx.pc).apply {
                        operator = Token.YIELD
                        operand = ctx.stack.pop()
                    })
                    ctx.pc++
                }

                Token.STRICT_SETNAME -> {
                    // Strict set name
                    val value = ctx.stack.pop()
                    ctx.stack.push(Assignment(ctx.pc).apply {
                        type = Token.ASSIGN
                        right = value
                        left = ctx.stack.pop()
                    })
                    ctx.pc++
                }

                Token.DEFAULTNAMESPACE -> {
                    // Default namespace
                    ctx.pc++
                }

                Token.ESCXMLATTR, Token.ESCXMLTEXT -> {
                    // XML escape
                    ctx.pc++
                }

                Token.REF_MEMBER, Token.REF_NS_MEMBER, Token.REF_NAME, Token.REF_NS_NAME -> {
                    // XML reference types
                    ctx.pc++
                }

                Token.BIGINT -> {
                    // BigInt literal
                    ctx.stack.push(BigIntLiteral(ctx.pc).apply {
                        value = bigIntTable[ctx.indexReg].toString()
                    })
                    ctx.pc++
                }

                // Icode instructions
                Icode.Icode_DUP -> {
                    // Duplicate top of stack
                    if (ctx.stack.isNotEmpty()) {
                        val top = ctx.stack.peek()
                        ctx.stack.push(top)
                    }
                    ctx.pc++
                }

                Icode.Icode_DUP2 -> {
                    // Duplicate top two stack elements
                    if (ctx.stack.size >= 2) {
                        val top1 = ctx.stack.pop()
                        val top2 = ctx.stack.pop()
                        ctx.stack.push(top2)
                        ctx.stack.push(top1)
                        ctx.stack.push(top2)
                        ctx.stack.push(top1)
                    }
                    ctx.pc++
                }

                Icode.Icode_SWAP -> {
                    // Swap top two stack elements
                    if (ctx.stack.size >= 2) {
                        val top1 = ctx.stack.pop()
                        val top2 = ctx.stack.pop()
                        ctx.stack.push(top1)
                        ctx.stack.push(top2)
                    }
                    ctx.pc++
                }

                Icode.Icode_POP, Icode.Icode_POP_RESULT -> {
                    if (ctx.stack.isNotEmpty()) {
                        val node = ctx.stack.pop()
                        when (node) {
                            is SwitchCase -> {
                                val cases = mutableListOf(node)
                                while (ctx.stack.isNotEmpty() && ctx.stack.peek() is SwitchCase) {
                                    cases.add(0, ctx.stack.pop() as SwitchCase)
                                }
                                val switchExpr = ctx.stack.pop()
                                ctx.stack.push(SwitchStatement(ctx.pc).apply {
                                    expression = switchExpr
                                    cases.forEach { caseNode ->
                                        val position = caseNode.position
                                        addCase(caseNode)
                                        caseNode.position = position
                                    }
                                })
                            }
                            is Assignment, is FunctionNode -> {
                                ctx.stack.push(ExpressionStatement(node.position, node.length, node))
                            }
                            is ExpressionStatement, is Name, is IfStatement, is InfixExpression,
                            is UnaryExpression, is KeywordLiteral, is NumberLiteral, is ObjectLiteral,
                            is ForInLoop, is ReturnStatement, is EmptyExpression -> {
                                ctx.stack.push(node)
                            }
                            else -> {
                                ctx.stack.push(ExpressionStatement(node.position, node.length, node))
                            }
                        }
                    }
                    ctx.pc++
                }

                Icode.Icode_IFEQ_POP -> {
                    // Skip this instruction for now
                    ctx.pc += 3
                }

                Icode.Icode_VAR_INC_DEC -> {
                    // Variable increment/decrement
                    val incrDecrType = iCode[ctx.pc + 1].toInt()
                    val unaryExpr = UnaryExpression(ctx.pc)
                    unaryExpr.operator = if (incrDecrType == Token.INC) Token.INC else Token.DEC
                    unaryExpr.operand = Name(ctx.pc, argNames[ctx.indexReg])
                    // Set postfix property
                    if (incrDecrType >= 0) {
                        try {
                            val field = UnaryExpression::class.java.getDeclaredField("isPostfix")
                            field.isAccessible = true
                            field.set(unaryExpr, true)
                        } catch (e: Exception) {
                            log2("Failed to set postfix property: ${e.message}")
                        }
                    }
                    ctx.stack.push(unaryExpr)
                    ctx.pc += 2
                }

                Icode.Icode_NAME_INC_DEC -> {
                    // Name increment/decrement
                    val incrDecrType = iCode[ctx.pc + 1].toInt()
                    val unaryExpr = UnaryExpression(ctx.pc)
                    unaryExpr.operator = if (incrDecrType == Token.INC) Token.INC else Token.DEC
                    unaryExpr.operand = Name(ctx.pc, ctx.stringReg)
                    // Set postfix property
                    if (incrDecrType >= 0) {
                        try {
                            val field = UnaryExpression::class.java.getDeclaredField("isPostfix")
                            field.isAccessible = true
                            field.set(unaryExpr, true)
                        } catch (e: Exception) {
                            log2("Failed to set postfix property: ${e.message}")
                        }
                    }
                    ctx.stack.push(unaryExpr)
                    ctx.pc += 2
                }
                
                Icode.Icode_PROP_INC_DEC -> {
                    // Property increment/decrement
                    val incrDecrType = iCode[ctx.pc + 1].toInt()
                    val unaryExpr = UnaryExpression(ctx.pc)
                    unaryExpr.operator = if (incrDecrType == Token.INC) Token.INC else Token.DEC
                    
                    // Create property get for the operand
                    val propGet = PropertyGet(ctx.pc)
                    propGet.target = ctx.stack.pop()
                    propGet.property = Name(ctx.pc, ctx.stringReg)
                    
                    unaryExpr.operand = propGet
                    
                    // Set postfix property
                    if (incrDecrType >= 0) {
                        try {
                            val field = UnaryExpression::class.java.getDeclaredField("isPostfix")
                            field.isAccessible = true
                            field.set(unaryExpr, true)
                        } catch (e: Exception) {
                            log2("Failed to set postfix property: ${e.message}")
                        }
                    }
                    
                    ctx.stack.push(unaryExpr)
                    ctx.pc += 2
                }
                
                Icode.Icode_ELEM_INC_DEC -> {
                    // Element increment/decrement
                    val incrDecrType = iCode[ctx.pc + 1].toInt()
                    val unaryExpr = UnaryExpression(ctx.pc)
                    unaryExpr.operator = if (incrDecrType == Token.INC) Token.INC else Token.DEC
                    
                    // Create element get for the operand
                    val element = ctx.stack.pop()
                    val target = ctx.stack.pop()
                    val elemGet = ElementGet(ctx.pc)
                    elemGet.target = target
                    elemGet.element = element
                    
                    unaryExpr.operand = elemGet
                    
                    // Set postfix property
                    if (incrDecrType >= 0) {
                        try {
                            val field = UnaryExpression::class.java.getDeclaredField("isPostfix")
                            field.isAccessible = true
                            field.set(unaryExpr, true)
                        } catch (e: Exception) {
                            log2("Failed to set postfix property: ${e.message}")
                        }
                    }
                    
                    ctx.stack.push(unaryExpr)
                    ctx.pc += 2
                }
                
                Icode.Icode_REF_INC_DEC -> {
                    // Reference increment/decrement
                    val incrDecrType = iCode[ctx.pc + 1].toInt()
                    val unaryExpr = UnaryExpression(ctx.pc)
                    unaryExpr.operator = if (incrDecrType == Token.INC) Token.INC else Token.DEC
                    unaryExpr.operand = ctx.stack.pop()
                    
                    // Set postfix property
                    if (incrDecrType >= 0) {
                        try {
                            val field = UnaryExpression::class.java.getDeclaredField("isPostfix")
                            field.isAccessible = true
                            field.set(unaryExpr, true)
                        } catch (e: Exception) {
                            log2("Failed to set postfix property: ${e.message}")
                        }
                    }
                    
                    ctx.stack.push(unaryExpr)
                    ctx.pc += 2
                }
                
                Icode.Icode_SCOPE_LOAD -> {
                    // Load scope
                    ctx.pc++
                }
                
                Icode.Icode_SCOPE_SAVE -> {
                    // Save scope for try-catch-finally
                    if (tryInfos.isNotEmpty() && ctx.indexReg <= tryInfos.size) {
                        val tryInfo = tryInfos[ctx.indexReg - 1]
                        ctx.pc++
                        ctx.stack.push(TryStatement(ctx.pc).apply {
                            tryBlock = dealInstruction(ctx, data, tryInfo.tryEnd)
                        })
                    } else {
                        ctx.pc++
                    }
                }
                
                Icode.Icode_TYPEOFNAME -> {
                    // typeof name
                    ctx.stack.push(UnaryExpression(ctx.pc).apply {
                        operator = Token.TYPEOF
                        operand = Name(ctx.pc, ctx.stringReg)
                    })
                    ctx.pc++
                }
                
                Icode.Icode_NAME_AND_THIS -> {
                    // Name and this for function call
                    ctx.stack.push(FunctionCall(ctx.pc).apply {
                        target = Name(ctx.pc, ctx.stringReg)
                    })
                    ctx.pc++
                }
                
                Icode.Icode_PROP_AND_THIS -> {
                    // Property and this for function call
                    ctx.stack.push(FunctionCall(ctx.pc).apply {
                        target = PropertyGet(ctx.stack.pop(), Name(ctx.pc, ctx.stringReg))
                    })
                    ctx.pc++
                }
                
                Icode.Icode_ELEM_AND_THIS -> {
                    // Element and this for function call
                    val element = ctx.stack.pop()
                    val target = ctx.stack.pop()
                    ctx.stack.push(FunctionCall(ctx.pc).apply {
                        this.target = ElementGet(ctx.pc).apply {
                            this.target = target
                            this.element = element
                        }
                    })
                    ctx.pc++
                }
                
                Icode.Icode_VALUE_AND_THIS -> {
                    // Value and this for function call
                    ctx.stack.push(FunctionCall(ctx.pc).apply {
                        target = ctx.stack.pop()
                    })
                    ctx.pc++
                }
                
                Icode.Icode_CLOSURE_EXPR -> {
                    // Closure expression
                    val function = nestedFunctions[ctx.indexReg]
                    ctx.stack.push(FunctionNode(ctx.pc).apply {
                        params = (0 until function.argCount).map { Name(ctx.pc, function.argNames[it]) }
                        functionName = Name(ctx.pc, function.itsName ?: "")
                        body = EmptyExpression() // Placeholder, will be filled later if needed
                    })
                    ctx.pc++
                }
                
                Icode.Icode_CLOSURE_STMT -> {
                    // Closure statement
                    val function = nestedFunctions[ctx.indexReg]
                    ctx.stack.push(FunctionNode(ctx.pc).apply {
                        params = (0 until function.argCount).map { Name(ctx.pc, function.argNames[it]) }
                        functionName = Name(ctx.pc, function.itsName ?: "")
                        body = decompile(function, ctx.scope)
                    })
                    ctx.pc++
                }
                
                Icode.Icode_CALLSPECIAL -> {
                    // Special call
                    val args = List(ctx.indexReg) { ctx.stack.pop() }.reversed()
                    if (ctx.stack.peek() is FunctionCall) {
                        (ctx.stack.peek() as FunctionCall).arguments = args
                    }
                    ctx.pc += 4
                }
                
                Icode.Icode_RETUNDEF -> {
                    // Return undefined
                    if ((ctx.pc + 1) < iCode.size) {
                        ctx.stack.push(ReturnStatement(ctx.pc))
                    }
                    ctx.pc++
                }
                
                Icode.Icode_GOSUB -> {
                    // Go to subroutine
                    ctx.pc++
                }
                
                Icode.Icode_STARTSUB -> {
                    // Start subroutine
                    ctx.pc++
                }
                
                Icode.Icode_RETSUB -> {
                    // Return from subroutine
                    ctx.pc++
                }
                
                Icode.Icode_LINE -> {
                    // Line number
                    ctx.lineNo = Interpreter.getIndex(iCode, ctx.pc + 1)
                    ctx.pc += 2
                }
                
                Icode.Icode_SHORTNUMBER -> {
                    // Short number
                    ctx.stack.push(NumberLiteral(ctx.pc, Interpreter.getShort(iCode, ctx.pc + 1).toString()))
                    ctx.pc += 2
                }
                
                Icode.Icode_INTNUMBER -> {
                    // Int number
                    ctx.stack.push(NumberLiteral(ctx.pc, Interpreter.getInt(iCode, ctx.pc + 1).toString()))
                    ctx.pc += 4
                }
                
                Icode.Icode_LITERAL_NEW -> {
                    // New literal (object)
                    ctx.stack.push(ObjectLiteral(ctx.pc))
                    ctx.pc++
                }
                
                Icode.Icode_LITERAL_SET -> {
                    // Set literal property
                    val right = ctx.stack.pop()
                    val left: AstNode
                    val target: ObjectLiteral
                    
                    when (val unknown = ctx.stack.pop()) {
                        is ObjectLiteral -> {
                            left = Name(ctx.pc, "")  // Empty name, will be filled later
                            target = unknown
                        }
                        else -> {
                            left = unknown
                            target = ctx.stack.pop() as ObjectLiteral
                        }
                    }
                    
                    ctx.stack.push(target.apply {
                        addElement(ObjectProperty().apply {
                            this.right = right
                            this.left = left
                        })
                    })
                    ctx.pc++
                }
                
                Icode.Icode_SPARE_ARRAYLIT -> {
                    // Sparse array literal
                    ctx.pc++
                }
                
                // Register index operations
                Icode.Icode_REG_IND_C0, Icode.Icode_REG_IND_C1, Icode.Icode_REG_IND_C2, 
                Icode.Icode_REG_IND_C3, Icode.Icode_REG_IND_C4, Icode.Icode_REG_IND_C5 -> {
                    ctx.indexReg = Icode.Icode_REG_IND_C0 - opcode
                    log2("\u001B[33m │[indexReg] ${ctx.indexReg} \u001B[0m")
                    ctx.pc++
                }
                
                Icode.Icode_REG_IND1 -> {
                    ctx.indexReg = 0xFF and iCode[ctx.pc + 1].toInt()
                    log2("\u001B[33m │[indexReg] ${ctx.indexReg} \u001B[0m")
                    ctx.pc += 2
                }
                
                Icode.Icode_REG_IND2 -> {
                    ctx.indexReg = Interpreter.getShort(iCode, ctx.pc + 1)
                    log2("\u001B[33m │[indexReg] ${ctx.indexReg} \u001B[0m")
                    ctx.pc += 3
                }
                
                Icode.Icode_REG_IND4 -> {
                    ctx.indexReg = Interpreter.getInt(iCode, ctx.pc + 1)
                    log2("\u001B[33m │[indexReg] ${ctx.indexReg} \u001B[0m")
                    ctx.pc += 5
                }
                
                // Register string operations
                Icode.Icode_REG_STR_C0, Icode.Icode_REG_STR_C1, Icode.Icode_REG_STR_C2, Icode.Icode_REG_STR_C3 -> {
                    ctx.stringReg = stringTable[Icode.Icode_REG_STR_C0 - opcode]
                    log2("\u001B[33m │[stringReg] ${ctx.stringReg} \u001B[0m")
                    ctx.pc++
                }
                
                Icode.Icode_REG_STR1 -> {
                    ctx.stringReg = stringTable[0xFF and iCode[ctx.pc + 1].toInt()]
                    log2("\u001B[33m │[stringReg] ${ctx.stringReg} \u001B[0m")
                    ctx.pc += 2
                }
                
                Icode.Icode_REG_STR2 -> {
                    ctx.stringReg = stringTable[Interpreter.getShort(iCode, ctx.pc + 1)]
                    log2("\u001B[33m │[stringReg] ${ctx.stringReg} \u001B[0m")
                    ctx.pc += 3
                }
                
                Icode.Icode_REG_STR4 -> {
                    ctx.stringReg = stringTable[Interpreter.getInt(iCode, ctx.pc + 1)]
                    log2("\u001B[33m │[stringReg] ${ctx.stringReg} \u001B[0m")
                    ctx.pc += 5
                }
                
                Icode.Icode_GETVAR1 -> {
                    // Get variable by index
                    ctx.stack.push(Name(ctx.pc, argNames[0xFF and iCode[ctx.pc + 1].toInt()]))
                    ctx.pc += 2
                }
                
                Icode.Icode_SETVAR1 -> {
                    // Set variable by index
                    val varName = argNames[0xFF and iCode[ctx.pc + 1].toInt()]
                    if (ctx.stack.peek() is ForInLoop && (ctx.stack.peek() as ForInLoop).iterator is EmptyExpression) {
                        (ctx.stack.peek() as ForInLoop).iterator = ctx.addVar(Name(ctx.pc, varName))
                    } else {
                        ctx.stack.push(Assignment(ctx.pc).apply {
                            type = Token.ASSIGN
                            left = ctx.addVar(Name(ctx.pc, varName))
                            right = ctx.stack.pop()
                        })
                    }
                    ctx.pc += 2
                }
                
                Icode.Icode_UNDEF -> {
                    // Undefined literal
                    ctx.stack.push(KeywordLiteral(ctx.pc).apply {
                        type = Token.NULL
                    })
                    ctx.pc++
                }
                
                Icode.Icode_ZERO -> {
                    // Zero literal
                    ctx.stack.push(NumberLiteral(ctx.pc).apply { value = "0" })
                    ctx.pc++
                }
                
                Icode.Icode_ONE -> {
                    // One literal
                    ctx.stack.push(NumberLiteral(ctx.pc).apply { value = "1" })
                    ctx.pc++
                }
                
                Icode.Icode_ENTERDQ -> {
                    // Enter destructuring assignment
                    ctx.pc++
                }
                
                Icode.Icode_LEAVEDQ -> {
                    // Leave destructuring assignment
                    ctx.pc++
                }
                
                Icode.Icode_TAIL_CALL -> {
                    // Tail call optimization
                    val arguments = List(ctx.indexReg) { ctx.stack.pop() }.reversed()
                    if (ctx.stack.peek() is FunctionCall) {
                        (ctx.stack.peek() as FunctionCall).arguments = arguments
                    }
                    ctx.pc++
                }
                
                Icode.Icode_LOCAL_CLEAR -> {
                    // Clear local variable
                    ctx.pc++
                }
                
                Icode.Icode_LITERAL_GETTER, Icode.Icode_LITERAL_SETTER -> {
                    // Getter/setter in object literal
                    ctx.pc++
                }
                
                Icode.Icode_SETCONST -> {
                    // Set constant
                    val right = ctx.stack.pop()
                    val left = ctx.stack.pop()
                    
                    when (left) {
                        is VariableDeclaration -> {
                            left.type = Token.CONST
                            ctx.stack.push(Assignment(ctx.pc).apply {
                                type = Token.ASSIGN
                                this.right = right
                                this.left = left
                            })
                        }
                        is Name -> {
                            val varDecl = VariableDeclaration(left.position)
                            varDecl.type = Token.CONST
                            varDecl.addVariable(VariableInitializer().apply {
                                target = left
                            })
                            ctx.stack.push(Assignment(ctx.pc).apply {
                                type = Token.ASSIGN
                                this.right = right
                                this.left = varDecl
                            })
                        }
                        else -> {
                            // Fallback for other types
                            ctx.stack.push(Assignment(ctx.pc).apply {
                                type = Token.ASSIGN
                                this.right = right
                                this.left = left
                            })
                        }
                    }
                    ctx.pc++
                }
                
                Icode.Icode_SETCONSTVAR -> {
                    // Set constant variable
                    ctx.pc++
                }
                
                Icode.Icode_SETCONSTVAR1 -> {
                    // Set constant variable by index
                    ctx.stack.push(Assignment(ctx.pc).apply {
                        type = Token.ASSIGN
                        left = ctx.addVar(Name(ctx.pc, argNames[0xFF and iCode[ctx.pc + 1].toInt()])).apply {
                            if (this is VariableDeclaration) {
                                type = Token.CONST
                            }
                        }
                        right = ctx.stack.pop()
                    })
                    ctx.pc += 2
                }
                
                Icode.Icode_GENERATOR, Icode.Icode_GENERATOR_END, Icode.Icode_GENERATOR_RETURN -> {
                    // Generator related operations
                    ctx.pc++
                }
                
                Icode.Icode_YIELD_STAR -> {
                    // Yield* expression
                    val operand = ctx.stack.pop()
                    val yieldExpr = UnaryExpression(Token.YIELD, ctx.pc, operand)
                    // We can't set YIELD_STAR directly, but we can use YIELD and add a comment
                    ctx.stack.push(yieldExpr)
                    ctx.pc++
                }
                
                Icode.Icode_DEBUGGER -> {
                    // Debugger statement
                    ctx.stack.push(KeywordLiteral(ctx.pc).apply {
                        type = Token.DEBUGGER
                    })
                    ctx.pc++
                }
                
                // BigInt register operations
                Icode.Icode_REG_BIGINT_C0, Icode.Icode_REG_BIGINT_C1, Icode.Icode_REG_BIGINT_C2, Icode.Icode_REG_BIGINT_C3 -> {
                    ctx.indexReg = Icode.Icode_REG_BIGINT_C0 - opcode
                    log2("\u001B[33m │[indexReg (BigInt)] ${ctx.indexReg} \u001B[0m")
                    ctx.pc++
                }
                
                Icode.Icode_REG_BIGINT1, Icode.Icode_REG_BIGINT2, Icode.Icode_REG_BIGINT4 -> {
                    // BigInt register operations
                    ctx.pc++
                }
                
                Icode.Icode_TEMPLATE_LITERAL_CALLSITE -> {
                    // Template literal call site
                    ctx.pc++
                }
                
                else -> {
                    throw Exception("\u001B[31m [Unknown opcode: $opcode] \u001B[0m")
                }
            }
        }
    }
}
