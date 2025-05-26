package org.mozilla.javascript

import org.mozilla.javascript.RhinoDecompiler.Companion.DEBUG
import org.mozilla.javascript.ast.*
import java.util.*

fun log(message: Any?) {
    if (DEBUG) println(message)
}

class RhinoDecompiler {
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
            log(
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
            log(
                "\u001B[31m │[POP  ${element::class.java}] ${
                    element.toSource().replace("\n", "").toCN()
                } \u001B[0m"
            )
            if (DEBUG) printStack()
            return element
        }

        fun printStack() {
//            log("\u001B[34m │====================================BLOCK  \u001B[0m")
//            this.map {
//                val source = it.toSource().replace("\n", "").toCN()
//                log("\u001B[34m │ ${it.position} $source \u001B[0m")
//            }
//            log("\u001B[34m │====================================BLOCK  \u001B[0m")
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
    ) {
        fun addVar(node: Name, scope: Scope = this.scope): AstNode {
            if (scope.hasVariable(node.string)) return node
            scope.addVariable(node.string)
            return VariableDeclaration(node.position).apply {
                addVariable(VariableInitializer().apply {
                    target = node;
                })
            }
        }
//        fun findNextIFEQ_POP(): Int? {
//            return labels.keys.sorted().firstOrNull { it > pc }
//        }
    }

    companion object {
        var DEBUG = false
        fun decompile(data: InterpreterData, parent: Scope? = null, name: String = ""): Block {
            if (DEBUG) data.dumpInfo()
            val ctx = DecompilerContext(scope = Scope(name)).apply { scope.parent = parent }
            return dealInstruction(ctx, data)
        }

        private fun dealInstruction(
            ctx: DecompilerContext, data: InterpreterData, end: Int = data.itsICode.size
        ): Block {
            val start = ctx.pc
            log("\u001B[33m │===========START==FROM $start TO $end  \u001B[0m")
            val iCode = data.itsICode




            while (ctx.pc < end) {
                val opcode = iCode[ctx.pc].toInt()
//                if(ctx.labels[ctx.pc]?.opcode == Icode.Icode_IFEQ_POP){
//                    val next = ctx.findNextIFEQ_POP()
//                    println(">>>>>>>>>>>>>>>>>${ctx.labels[ctx.pc]?.opcode} ${ctx.labels[ctx.pc]?.position} ${next}")
//                    println(">>>>>>>>>>>>>>>>>>>>>> ${ctx.labels.keys.sorted().joinToString(",")}")
//                    if (next != null) {
//                        val node = ctx.labels[ctx.pc]?.node as SwitchCase
//                        ctx.labels.remove(ctx.pc)
//                        node.statements = dealInstruction(ctx, data, next).toList()
//                        ctx.pc++
//                        ctx.stack.printStack()
//                        continue
//                    }
//                }
                log(" [${ctx.pc}] ${Interpreter.bytecodeName(opcode)}")
                try {
                    decodeInstruction(ctx, opcode, data)
                } catch (_: Exception) {
                    ctx.pc++
                }
            }
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
            log("\u001B[33m │===========END====FROM $start TO $end  \u001B[0m")
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


            when (opcode) {
                Token.ENTERWITH -> {}//ctx.stack.pop()
                Token.LEAVEWITH -> {}

                Token.RETURN -> ctx.stack.push(ReturnStatement(ctx.pc).apply {
                    returnValue = ctx.stack.pop()
                })

                Token.GOTO -> {
                    val pc = ctx.pc + Interpreter.getShort(iCode, ctx.pc + 1)
                    val target = iCode[pc].toInt()
//                    ctx.labels[pc] = Label(target, ctx.pc, ctx.lineNo, opcode)
                    ctx.pc += 2
                    ctx.pc++
//                    if(ctx.labels.contains(target)){
//                        ctx.stack.push(ContinueStatement(ctx.pc))
//                        return
//                    }
                    ctx.labels[target] = Label(target, ctx.pc, ctx.lineNo, opcode)
                    if (iCode[ctx.pc - 4].toInt() == Token.LEAVEWITH) {
                        if (target == Token.TRUE) {
                            ctx.stack.push(ContinueStatement(ctx.pc))
                        } else {
                            ctx.stack.push(BreakStatement(ctx.pc))
                        }
                        return
                    }
                    val block = dealInstruction(ctx, data, pc)
//                    if (ctx.stack.peek() as? IfStatement != null) {
//                        println("\u001B[33m │[GOTO] IfStatement elsePart \u001B[0m")
//                        (ctx.stack.peek() as? IfStatement)?.elsePart = block
//                        ctx.stack.printStack()
//                    } else
                    if (ctx.stack.isNotEmpty() && ctx.stack.peek() as? ForInLoop != null) {
                        log("\u001B[33m │[GOTO] ForInLoop body \u001B[0m")
                        (ctx.stack.peek() as? ForInLoop)?.body = block
                        ctx.stack.printStack()
                    } else if (ctx.stack.isNotEmpty() && ctx.stack.peek() as? SwitchStatement != null) {
                        log("\u001B[33m │[LABEL] GOTO SwitchStatement \u001B[0m")
                        val cases =
                            ((ctx.stack.peek() as SwitchStatement).cases).apply { sortBy { it.position } }
                        val stacks = block.toStack() //.apply { sortedBy { it.position } }
                        for (stack in stacks) {
                            for (i in cases.indices) {
                                val cur = cases[i]
                                val next = if (i + 1 < cases.size) cases[i + 1] else null
                                if (stack.position >= cur.length && (next == null || stack.position <= next.length)) {
                                    cur.addStatement(stack)
                                    break
                                }
                            }
                        }
                    } else {
                        log("\u001B[33m │[LABEL] GOTO Unknown \u001B[0m")
                        ctx.stack.push(block)
                    }
                }

                Token.IFEQ -> {
                    val pc = ctx.pc + Interpreter.getShort(iCode, ctx.pc + 1)
                    val target = iCode[pc].toInt()
                    ctx.pc += 2
                    if (iCode[ctx.pc - 3].toInt() == Token.ENUM_NEXT) {
                        log("\u001B[33m │[IFEQ] ForInLoop \u001B[0m")
                    } else if (target == Token.IFNE) {
                        ctx.pc++
                        log("\u001B[33m │[IFEQ] OR \u001B[0m")
                        ctx.stack.push(InfixExpression(ctx.pc).apply {
                            operator = Token.OR
                            left = ctx.stack.pop()
                            right = dealInstruction(ctx, data, pc).firstChild as AstNode
                        })
                    } else if (ctx.pc > pc) {
                        log("\u001B[33m │[IFEQ] WhileLoop \u001B[0m")
                        ctx.stack.push(WhileLoop(ctx.pc).apply {
                            condition = ctx.stack.pop()
                            body = ctx.stack.pop()
                        })
                    }
                }

                Token.IFNE -> {
                    val pc = ctx.pc + Interpreter.getShort(iCode, ctx.pc + 1)
                    val target = iCode[pc].toInt()
                    ctx.pc += 2
                    ctx.pc++
                    if (target == Token.IFNE || target == Token.RETURN) {
                        log("\u001B[33m │[IFNE] AND \u001B[0m")
                        ctx.stack.push(InfixExpression(ctx.pc).apply {
                            operator = Token.AND
                            left = ctx.stack.pop()
                            right = dealInstruction(ctx, data, pc)
                        }.wrapper())
                    } else {
                        log("\u001B[33m │[IFNE] IfStatement \u001B[0m")
                        ctx.stack.push(IfStatement(ctx.pc).apply {
                            condition = ctx.stack.pop().wrapperCondition()
                            thenPart = dealInstruction(ctx, data, pc)
                        }.wrapper())
                    }
                }

                Token.SETNAME -> {
                    val unknown = ctx.stack.pop()
                    if (ctx.stack.peek() is ForInLoop && (ctx.stack.peek() as ForInLoop).iterator is EmptyExpression) {
                        (ctx.stack.peek() as ForInLoop).iterator = unknown
                    } else {
                        ctx.stack.push(Assignment(ctx.pc).apply {
                            type = Token.ASSIGN
                            right = unknown
                            left = ctx.stack.pop().wrapper()
                        })
                    }
                }

                Token.BITOR, Token.BITXOR, Token.BITAND, Token.EQ, Token.NE, Token.LT, Token.LE, Token.GT, Token.GE, Token.LSH, Token.RSH, Token.URSH, Token.ADD, Token.SUB, Token.MUL, Token.DIV, Token.MOD -> {
                    ctx.stack.push(InfixExpression(ctx.pc).apply {
                        operator = opcode; right = ctx.stack.pop(); left = ctx.stack.pop()
                    })
                }

                Token.NOT -> ctx.stack.push(UnaryExpression(ctx.pc).apply {
                    operator = Token.NOT; operand = ctx.stack.pop()
                })

                Token.BITNOT -> ctx.stack.push(UnaryExpression(ctx.pc).apply {
                    this.operator = Token.BITNOT
                    this.operand = ctx.stack.pop()
                    ctx.pc++
                })

                Token.POS -> ctx.stack.push(UnaryExpression(ctx.pc).apply {
                    this.operator = Token.POS
                    this.operand = ctx.stack.pop()
                })
//                Token.NEG
                Token.NEW -> ctx.stack.push(NewExpression(ctx.pc).apply {
                    arguments = List(ctx.indexReg) { ctx.stack.pop() }; target = ctx.stack.pop()
                }.wrapper())
//                Token.DELPROP
                Token.TYPEOF -> {
                    val operand = ctx.stack.pop()
                    ctx.stack.push(UnaryExpression(ctx.pc).apply {
                        this.operator = Token.TYPEOF  // 设置操作符为 Token.TYPEOF
                        this.operand = operand   // 设置 typeof 的目标表达式
                    })
                }

                Token.GETPROP, Token.GETPROPNOWARN -> ctx.stack.push(PropertyGet(ctx.pc).apply {
                    target = ctx.stack.pop(); property = Name(ctx.pc, ctx.stringReg)
                })

                Token.SETPROP -> {
                    val right = ctx.stack.pop()
                    val left: AstNode
                    val unknown = ctx.stack.pop()
                    left = if (unknown.type == Token.THIS) {
                        PropertyGet(unknown, Name(ctx.pc, ctx.stringReg))
                    } else {
                        unknown
                    }
                    ctx.stack.push(Assignment(ctx.pc).apply {
                        type = Token.ASSIGN; this.right = right; this.left = left
                    })
                }

                Token.GETELEM -> ctx.stack.push(ElementGet(ctx.pc).apply {
                    element = ctx.stack.pop(); target = ctx.stack.pop()
                })

                Token.SETELEM -> {
                    val value = ctx.stack.pop() // 要设置的值
                    val index = ctx.stack.pop() // 索引（例如，数组下标）
                    val target = ctx.stack.pop()// 目标对象或数组
                    ctx.stack.push(Assignment(ctx.pc).apply {
                        operator = Token.ASSIGN
                        right = value
                        left = when (index) {
                            is ElementGet -> target
                            is Name -> PropertyGet(target, index)
                            is StringLiteral -> PropertyGet(target, Name(ctx.pc, index.value))
                            else -> EmptyExpression()
                        }
                    })
                }

                Token.CALL -> {
                    val arguments =
                        List(ctx.indexReg) { ctx.stack.pop().wrapper() }.reversed()
                    (ctx.stack.peek() as FunctionCall).arguments = arguments
                    ctx.stack.printStack()
                }

                Token.NAME -> ctx.stack.push(Name(ctx.pc, ctx.stringReg))
                Token.NUMBER -> ctx.stack.push(NumberLiteral(ctx.pc).apply {
                    value = doubleTable[ctx.indexReg].toString()
                })

                Token.STRING -> ctx.stack.push(StringLiteral(ctx.pc).apply {
                    value = ctx.stringReg; quoteCharacter = '\''
                })

                Token.NULL, Token.THIS, Token.FALSE, Token.TRUE -> ctx.stack.push(KeywordLiteral(ctx.pc).apply {
                    type = opcode
                })

                Token.SHEQ, Token.SHNE -> {
                    if (iCode[ctx.pc + 1].toInt() == Icode.Icode_IFEQ_POP) {
                        ctx.stack.push(SwitchCase(ctx.pc + 1).apply {
                            expression = ctx.stack.pop()
                        })
                    } else {
                        ctx.stack.push(Assignment(ctx.pc).apply {
                            operator = opcode; right = ctx.stack.pop(); left = ctx.stack.pop()
                        })
                    }
                }

                Token.REGEXP -> {
                    val re = regExpLiterals[ctx.indexReg]
                    log("REGEXP>>${re::class.java.name}")
                    val field = re::class.java.getDeclaredField("source")
                    field.isAccessible = true
                    val source = (field.get(re) as CharArray).joinToString("")
                    ctx.stack.push(RegExpLiteral(ctx.pc).apply { value = source; flags = "" })
                }

                Token.BINDNAME -> ctx.stack.push(ctx.addVar(Name(ctx.pc, ctx.stringReg)))
                Token.THROW -> ctx.stack.push(ThrowStatement(ctx.pc).apply {
                    expression = ctx.stack.pop(); ctx.pc += 2
                })
//                Token.RETHROW
//                Token.IN
//                Token.INSTANCEOF
                Token.LOCAL_LOAD -> {
                    // TODO:
                }
//                Token.GETVAR
//                Token.SETVAR
                Token.CATCH_SCOPE -> {
                    // TODO:
                    ctx.pc++
//                    val tryInfo = tryInfos[ctx.indexReg - 1]
//                    ctx.pc+=2
//                    ctx.stack.push(CatchClause(ctx.pc).apply {
//                        varName = Name(ctx.pc, ctx.stringReg)
//                        body = dealInstruction(ctx, data, tryInfo.tryEnd)
//                    })
                }

                Token.ENUM_INIT_KEYS, Token.ENUM_INIT_VALUES_IN_ORDER -> {
                    ctx.stack.push(ForInLoop(ctx.pc).apply {
                        setIsForOf(false)
                        iteratedObject = ctx.stack.pop()
                        iterator = EmptyExpression()
                        body = Block()
                    })
                }
//                Token.ENUM_INIT_VALUES
//                Token.ENUM_INIT_ARRAY
//                Token.ENUM_INIT_VALUES_IN_ORDER
//                Token.ENUM_INIT_VALUES_IN_ORDER
                Token.ENUM_NEXT, Token.ENUM_ID -> {}
//                Token.THISFN
                Token.RETURN_RESULT -> {
                    if ((ctx.pc + 1) < iCode.size) {
                        ctx.stack.push(ReturnStatement(ctx.pc))
                    }
                }

                Token.ARRAYLIT -> {
                    when (val node = ctx.stack.pop()) {
                        is ObjectLiteral -> {
                            ctx.stack.push(ArrayLiteral(ctx.pc).apply {
                                node.elements.forEach {
                                    this.addElement(it.right)
                                }
                            })
                        }
                    }
                }

                Token.OBJECTLIT -> {
                    (ctx.stack.peek() as ObjectLiteral).apply {
                        val ids =
                            LinkedList((literalIds[ctx.indexReg] as Array<*>).toList()).apply { reversed() }
                        elements.map { it.left = Name(ctx.pc, ids.poll() as String) }
                        ctx.stack.printStack()
                    }
                }
//                Token.GET_REF
//                Token.SET_REF
//                Token.DEL_REF
//                Token.REF_CALL
//                Token.REF_SPECIAL
//                Token.YIELD
//                Token.STRICT_SETNAME
//                Token.EXP
//                Token.DEFAULTNAMESPACE
                Token.ESCXMLATTR -> {}
//                Token.ESCXMLTEXT
//                Token.REF_MEMBER
//                Token.REF_NS_MEMBER
//                Token.REF_NAME
//                。。。。
                Icode.Icode_DUP -> {}
//                Icode.Icode_DUP2
//                Icode.Icode_SWAP
                Icode.Icode_POP, Icode.Icode_POP_RESULT -> {
                    if (ctx.stack.isNotEmpty()) {
                        ctx.stack.pop().apply {
                            when (this) {
                                is SwitchCase -> {
                                    val cases = mutableListOf(this)
                                    while (ctx.stack.peek() is SwitchCase) {
                                        cases.add(0, ctx.stack.pop() as SwitchCase)
                                    }
                                    SwitchStatement(ctx.pc).apply {
                                        expression = ctx.stack.pop()
                                        cases.forEach {
                                            val position = it.position
                                            addCase(it)
                                            it.position = position
                                        }
                                        ctx.stack.push(this)
                                    }
                                }

                                is Assignment, is FunctionNode -> ctx.stack.push(ExpressionStatement().also {
                                    it.position = this.position; it.expression = this
                                })

                                is ExpressionStatement, is Name, is IfStatement, is InfixExpression,
                                is UnaryExpression, is KeywordLiteral, is NumberLiteral, is ObjectLiteral,
                                is ForInLoop, is ReturnStatement, is EmptyExpression -> ctx.stack.push(
                                    this
                                )

                                else -> ctx.stack.push(ExpressionStatement().also {
                                    it.position = this.position; it.expression = this
                                })
                            }
                        }
                    }
                }

                Icode.Icode_IFEQ_POP -> {
                    val pc = ctx.pc + Interpreter.getShort(iCode, ctx.pc + 1)
                    val target = iCode[pc].toInt()
                    ctx.stack.peek().position = ctx.pc
                    ctx.stack.peek().length = pc
                    ctx.labels[pc] = Label(target, ctx.pc, ctx.lineNo, opcode, ctx.stack.peek())
                    ctx.pc += 2
                }

                Icode.Icode_VAR_INC_DEC -> {
//                    val incrDecrType = itsICode[++pc]
                    ctx.stack.push(UnaryExpression(ctx.pc).apply {
                        operator = Token.INC; operand = Name(ctx.pc, argNames[ctx.indexReg])
                    })
                }

                Icode.Icode_NAME_INC_DEC -> {
                    ctx.stack.push(UnaryExpression(ctx.pc).apply {
                        operator = Token.INC; operand = Name(ctx.pc, ctx.stringReg)
                    })
                }

                Icode.Icode_PROP_INC_DEC -> {
                    ctx.stack.push(UnaryExpression(ctx.pc).apply {
                        operator = Token.INC; operand = Name(ctx.pc, ctx.stringReg)
                    })
                }
//                Icode.Icode_ELEM_INC_DEC
//                Icode.Icode_REF_INC_DEC
//                Icode.Icode_SCOPE_LOAD
                Icode.Icode_SCOPE_SAVE -> {
                    val tryInfo = tryInfos[ctx.indexReg - 1]
                    ctx.pc++
                    ctx.stack.push(TryStatement(ctx.pc).apply {
                        tryBlock = dealInstruction(ctx, data, tryInfo.tryEnd)
                    })
                }

                Icode.Icode_TYPEOFNAME -> ctx.stack.push(UnaryExpression(ctx.pc).apply {
                    operator = Token.TYPEOF; operand = ctx.stack.pop()
                })

                Icode.Icode_NAME_AND_THIS -> ctx.stack.push(FunctionCall(ctx.pc).apply {
                    target = Name(ctx.pc, ctx.stringReg)
                })

                Icode.Icode_PROP_AND_THIS -> ctx.stack.push(FunctionCall(ctx.pc).apply {
                    this.target =
                        PropertyGet(ctx.stack.pop().wrapper(), Name(ctx.pc, ctx.stringReg))
                })
//                Icode.Icode_ELEM_AND_THIS
//                Icode.Icode_VALUE_AND_THIS
                Icode.Icode_CLOSURE_EXPR -> {
                    val function = nestedFunctions[ctx.indexReg]
                    ctx.stack.push(FunctionNode(ctx.pc).apply {
                        params =
                            (0 until function.argCount).map { Name(ctx.pc, function.argNames[it]) }
                        functionName = Name(ctx.pc, function.itsName ?: "")
                        body = EmptyExpression()//decompile(function, ctx.scope, function.itsName ?: "")
                    })
                }

                Icode.Icode_CLOSURE_STMT -> {
                    val function = nestedFunctions[ctx.indexReg]
                    ctx.stack.push(FunctionNode(ctx.pc).apply {
                        params =
                            (0 until function.argCount).map { Name(ctx.pc, function.argNames[it]) }
                        functionName = Name(ctx.pc, function.itsName ?: "")
                        body = decompile(function, ctx.scope)
                    })
                }

                Icode.Icode_CALLSPECIAL -> {
                    val args = List(ctx.indexReg) { ctx.stack.pop().wrapper() }
                    (ctx.stack.peek() as FunctionCall).arguments = args.reversed()
                    ctx.stack.printStack()
                    ctx.pc += 4
                }

                Icode.Icode_RETUNDEF -> {
                    if ((ctx.pc + 1) < iCode.size) {
                        ctx.stack.push(ReturnStatement(ctx.pc))
                    }
                }
//                Icode.Icode_GOSUB
//                Icode.Icode_STARTSUB
//                Icode.Icode_RETSUB
                Icode.Icode_LINE -> {
                    ctx.lineNo = Interpreter.getIndex(iCode, ctx.pc + 1)
//                    ctx.stack.push(EmptyExpression(ctx.pc))
                    ctx.pc += 2
                }

                Icode.Icode_SHORTNUMBER -> {
                    ctx.stack.push(
                        NumberLiteral(
                            ctx.pc, Interpreter.getShort(iCode, ++ctx.pc).toString()
                        )
                    )
                    ctx.pc++
                }

                Icode.Icode_INTNUMBER -> {
                    ctx.stack.push(NumberLiteral(0, Interpreter.getInt(iCode, ++ctx.pc).toString()))
                    ctx.pc += 3
                }

                Icode.Icode_LITERAL_NEW -> ctx.stack.push(ObjectLiteral(ctx.pc))
                Icode.Icode_LITERAL_SET -> {
                    val right = ctx.stack.pop()
                    val left: AstNode
                    val target: ObjectLiteral
                    when (val unknown = ctx.stack.pop()) {
                        is ObjectLiteral -> {
                            left = Name(ctx.pc); target = unknown
                        }

                        else -> {
                            left = unknown; target = ctx.stack.pop() as ObjectLiteral
                        }
                    }
                    ctx.stack.push(target.apply {
                        addElement(ObjectProperty().apply {
                            this.right = right; this.left = left
                        })
                    })
                }
//                Icode.Icode_SPARE_ARRAYLIT
                Icode.Icode_REG_IND_C0, Icode.Icode_REG_IND_C1, Icode.Icode_REG_IND_C2, Icode.Icode_REG_IND_C3, Icode.Icode_REG_IND_C4, Icode.Icode_REG_IND_C5 -> {
                    ctx.indexReg = Icode.Icode_REG_IND_C0 - opcode
                    log("\u001B[33m │[indexReg] ${ctx.indexReg} \u001B[0m")
                }

                Icode.Icode_REG_IND1 -> {
                    ctx.indexReg = 0xFF and iCode[++ctx.pc].toInt()
                    log("\u001B[33m │[indexReg] ${ctx.indexReg} \u001B[0m")
                }
//                Icode.Icode_REG_IND2
//                Icode.Icode_REG_IND4
                Icode.Icode_REG_STR_C0, Icode.Icode_REG_STR_C1, Icode.Icode_REG_STR_C2, Icode.Icode_REG_STR_C3 -> {
                    ctx.stringReg = stringTable[Icode.Icode_REG_STR_C0 - opcode]
                    log("\u001B[33m │[stringReg] ${ctx.stringReg} \u001B[0m")
                }

                Icode.Icode_REG_STR1, Icode.Icode_REG_STR2, Icode.Icode_REG_STR4 -> {
                    ctx.stringReg = stringTable[0xFF and iCode[++ctx.pc].toInt()]
                    log("\u001B[33m │[stringReg] ${ctx.stringReg} \u001B[0m")
                    if (opcode == Icode.Icode_REG_STR2) ctx.pc++
                    if (opcode == Icode.Icode_REG_STR4) ctx.pc += 3
                }

                Icode.Icode_GETVAR1 -> ctx.stack.push(
                    Name(
                        ctx.pc,
                        argNames[iCode[++ctx.pc].toInt()]
                    )
                )

                Icode.Icode_SETVAR1 -> {
                    val unknown = ctx.stack.peek()
                    if (unknown is ForInLoop) {
                        unknown.iterator =
                            ctx.addVar(Name(ctx.pc, argNames[iCode[++ctx.pc].toInt()]))
                        ctx.stack.printStack()
                    } else {
                        ctx.stack.push(Assignment(ctx.pc).apply {
                            type = Token.ASSIGN
                            left = ctx.addVar(Name(ctx.pc, argNames[iCode[++ctx.pc].toInt()]))
                            right = ctx.stack.pop().wrapper()
                        })
                    }
                }

                Icode.Icode_UNDEF -> ctx.stack.push(KeywordLiteral(ctx.pc).apply {
                    type = Token.NULL
                })

                Icode.Icode_ZERO -> ctx.stack.push(NumberLiteral(ctx.pc).apply { value = "0" })
                Icode.Icode_ONE -> ctx.stack.push(NumberLiteral(ctx.pc).apply { value = "1" })
//                Icode.Icode_ENTERDQ
//                Icode.Icode_LEAVEDQ
                Icode.Icode_TAIL_CALL -> {
                    val arguments = List(ctx.indexReg) { ctx.stack.pop().wrapper() }.reversed()
                    (ctx.stack.peek() as FunctionCall).arguments = arguments
                    ctx.stack.printStack()
                }

                Icode.Icode_LOCAL_CLEAR -> {}
//                Icode.Icode_LITERAL_GETTER
//                Icode.Icode_LITERAL_SETTER
                Icode.Icode_SETCONST -> {
                    ctx.stack.push(Assignment(ctx.pc).apply {
                        type = Token.ASSIGN
                        right = ctx.stack.pop()
                        left = ctx.stack.peek().apply {
                            when (this) {
                                is VariableDeclaration -> ctx.stack.pop()
                                    .apply { type = Token.CONST }

                                is Name -> VariableDeclaration(position).apply {
                                    addVariable(VariableInitializer().apply {
                                        target = ctx.stack.pop()
                                    })
                                }
                            }
                        }
                    })
                }
//                Icode.Icode_SETCONSTVAR
                Icode.Icode_SETCONSTVAR1 -> {
                    ctx.stack.push(Assignment(ctx.pc).apply {
                        type = Token.ASSIGN
                        left = ctx.addVar(Name(ctx.pc, argNames[iCode[++ctx.pc].toInt()]))
                            .apply { type = Token.CONST }
                        right = ctx.stack.pop()
                    })
                }
//                。。。。
                else -> throw Exception("\u001B[31m [Unknown opcode] \u001B[0m")
            }
            ctx.pc++
        }

    }
}
