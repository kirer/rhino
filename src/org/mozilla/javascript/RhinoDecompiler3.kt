package org.mozilla.javascript

import org.mozilla.javascript.ast.*
import java.util.*

/**
 * RhinoDecompiler3 - 增强版Rhino字节码反编译器
 * 主要改进：
 * 1. 更好的控制流分析和恢复（if、for、while等结构）
 * 2. 改进的变量处理和作用域分析
 * 3. 更好的嵌套函数处理
 * 4. 详细的字节码指令解析
 */
class RhinoDecompiler3 {
    companion object {
        // 调试模式开关
        var DEBUG = true

        /**
         * 反编译入口函数
         */
        fun decompile(data: InterpreterData, parent: Scope? = null, name: String = ""): String {
            if (DEBUG) println("开始反编译: ${name.ifEmpty { "匿名" }}")
            val ctx = DecompilerContext(scope = Scope(name).apply { this.parent = parent })
            val block = decompileToBlock(ctx, data)
            val formattedSource = formatJavaScript(block.toSource(0))
            if (DEBUG) println("反编译完成: ${name.ifEmpty { "匿名" }}")
            return formattedSource
        }

        /**
         * 反编译为AST块
         */
        private fun decompileToBlock(ctx: DecompilerContext, data: InterpreterData): Block {
            if (DEBUG) data.dumpInfo()

            // 1. 分析控制流
            val cfg = RhinoDecompilerControlFlowAnalyzer.analyze(data.itsICode)

            // 2. 预处理：增强循环检测
            enhanceLoopDetection(ctx, data)

            // 3. 处理指令流
            processInstructions(ctx, data, cfg)

            // 4. 处理控制流结构
            processControlFlowStructures(ctx, cfg)

            // 5. 处理try-catch结构
            processTryCatch(ctx, data)

            // 6. 处理嵌套函数
            processNestedFunctions(ctx, data)

            // 7. 创建函数或脚本块
            val rootBlock = createRootBlock(ctx, data)

            return rootBlock
        }

        /**
         * 处理字节码指令
         */
        private fun processInstructions(ctx: DecompilerContext, data: InterpreterData, cfg: ControlFlowGraph) {
            val iCode = data.itsICode

            // 预先处理跳转目标
            markJumpTargets(ctx, iCode)

            try {
                while (ctx.pc < iCode.size) {
                    val opcode = iCode[ctx.pc].toInt()

                    if (DEBUG) {
                        try {
                            println("[${ctx.pc}] ${Interpreter.bytecodeName(opcode)}")
                        } catch (e: IllegalArgumentException) {
                            println("[${ctx.pc}] Unknown bytecode: $opcode")
                        }
                    }

                    try {
                        // 检查是否有行号信息
                        if (opcode == Icode.Icode_LINE) {
                            ctx.lineNo = Interpreter.getIndex(iCode, ctx.pc + 1)
                            ctx.pc += 2
                            continue
                        }

                        // 处理指令
                        if (!decodeInstruction(ctx, opcode, data)) {
                            // 如果指令未处理，则跳过
                            if (DEBUG) println("未处理的指令: $opcode")
                            ctx.pc++
                        }
                    } catch (e: Exception) {
                        if (DEBUG) {
                            println("Error at pc=${ctx.pc}, opcode=$opcode: ${e.message}")
                            e.printStackTrace()
                        }
                        ctx.pc++
                    }
                }
            } catch (e: Exception) {
                if (DEBUG) {
                    println("处理字节码时出现严重错误: ${e.message}")
                    e.printStackTrace()
                }
            }

            if (DEBUG) {
                println("指令处理完成，栈中剩余元素: ${ctx.stack.size}")
            }
        }

        /**
         * 预处理跳转目标
         */
        private fun markJumpTargets(ctx: DecompilerContext, iCode: ByteArray) {
            var pc = 0
            while (pc < iCode.size) {
                val opcode = iCode[pc].toInt() and 0xFF
                when (opcode) {
                    Token.GOTO, Token.IFEQ, Token.IFNE -> {
                        val offset = getJumpOffset(iCode, pc)
                        val targetPC = pc + offset
                        ctx.labels[targetPC] = Label(targetPC, pc, -1, opcode)
                        pc += 3 // 跳转指令长度为3
                    }

                    Icode.Icode_GETVAR1, Icode.Icode_SETVAR1, Icode.Icode_REG_IND1, Icode.Icode_REG_STR1 -> {
                        pc += 2 // 这些指令长度为2
                    }

                    Icode.Icode_INTNUMBER, Icode.Icode_REG_STR4 -> {
                        pc += 4 // 这些指令长度为4
                    }

                    Icode.Icode_SHORTNUMBER, Icode.Icode_REG_STR2, Icode.Icode_LINE -> {
                        pc += 2 // 这些指令长度为2
                    }

                    else -> {
                        pc++ // 默认指令长度为1
                    }
                }
            }
        }

        /**
         * 解码并处理单个字节码指令
         * 返回值表示指令是否已处理
         */
        private fun decodeInstruction(ctx: DecompilerContext, opcode: Int, data: InterpreterData): Boolean {
            val iCode = data.itsICode
            val stringTable = data.itsStringTable ?: emptyArray()
            val argNames = data.argNames ?: emptyArray()

            // 安全地从栈中弹出元素
            fun safePopStack(): AstNode {
                return if (ctx.stack.isNotEmpty()) {
                    ctx.stack.pop()
                } else {
                    if (DEBUG) println("警告: 尝试从空栈中弹出元素")
                    EmptyExpression()
                }
            }

            when (opcode) {
                // 局部变量操作
                Icode.Icode_GETVAR1 -> {
                    val varIndex = iCode[ctx.pc + 1].toInt() and 0xFF
                    if (varIndex < argNames.size) {
                        ctx.stack.push(Name(ctx.pc, argNames[varIndex]))
                    } else {
                        ctx.stack.push(Name(ctx.pc, "var_$varIndex"))
                    }
                    ctx.pc += 2
                    return true
                }

                Icode.Icode_SETVAR1 -> {
                    val varIndex = iCode[ctx.pc + 1].toInt() and 0xFF
                    val value = safePopStack()
                    val varName = if (varIndex < argNames.size) argNames[varIndex] else "var_$varIndex"

                    // 检查变量是否已声明
                    if (ctx.scope.hasVariable(varName)) {
                        // 变量赋值
                        val target = Name(ctx.pc, varName)
                        ctx.stack.push(Assignment(ctx.pc, Token.ASSIGN, target, value))
                    } else {
                        // 变量声明
                        ctx.scope.addVariable(varName)
                        val varInit = VariableInitializer(ctx.pc).apply {
                            this.target = Name(ctx.pc, varName)
                            this.initializer = value
                        }
                        val varDecl = VariableDeclaration(ctx.pc).apply {
                            addVariable(varInit)
                        }
                        ctx.stack.push(varDecl)
                    }
                    ctx.pc += 2
                    return true
                }

                // 字符串常量
                Icode.Icode_REG_STR1 -> {
                    val strIndex = iCode[ctx.pc + 1].toInt() and 0xFF
                    if (strIndex < stringTable.size) {
                        ctx.stack.push(StringLiteral(ctx.pc).apply { value = stringTable[strIndex] })
                    } else {
                        ctx.stack.push(StringLiteral(ctx.pc).apply { value = "unknown_string_$strIndex" })
                    }
                    ctx.pc += 2
                    return true
                }

                // 数组操作
                Icode.Icode_LITERAL_NEW -> {
                    // Icode_LITERAL_NEW用来创建数组
                    val arrayLiteral = ArrayLiteral(ctx.pc)
                    ctx.stack.push(arrayLiteral)
                    ctx.pc++
                    return true
                }

                // 对象操作
                Token.OBJECTLIT -> {
                    val objLiteral = ObjectLiteral(ctx.pc)
                    ctx.stack.push(objLiteral)
                    ctx.pc++
                    return true
                }

                // 属性添加
                Icode.Icode_LITERAL_SET, Icode.Icode_LITERAL_GETTER, Icode.Icode_LITERAL_SETTER -> {
                    val propValue = safePopStack()
                    val propName = if (ctx.stack.isNotEmpty()) ctx.stack.pop() else StringLiteral(ctx.pc).apply {
                        value = "unknown"
                    }

                    if (ctx.stack.isNotEmpty()) {
                        val target = ctx.stack.peek()

                        if (target is ObjectLiteral) {
                            // 添加属性到对象字面量
                            val prop = ObjectProperty()
                            prop.left = if (propName is Name) propName else Name(
                                ctx.pc, (propName as? StringLiteral)?.value ?: "unknown"
                            )
                            prop.right = propValue
                            prop.type = Token.COLON

                            // 由于addProperty方法可能不存在，我们直接使用反射调用
                            try {
                                val method =
                                    ObjectLiteral::class.java.getMethod("addProperty", ObjectProperty::class.java)
                                method.invoke(target, prop)
                            } catch (e: Exception) {
                                if (DEBUG) println("无法添加属性: ${e.message}")
                            }
                        }
                    }

                    ctx.pc++
                    return true
                }

                // 数字常量
                Icode.Icode_ZERO -> {
                    ctx.stack.push(NumberLiteral(ctx.pc).apply { value = "0.0" })
                    ctx.pc++
                    return true
                }

                Icode.Icode_ONE -> {
                    ctx.stack.push(NumberLiteral(ctx.pc).apply { value = "1.0" })
                    ctx.pc++
                    return true
                }

                Icode.Icode_SHORTNUMBER -> {
                    val numValue = iCode[ctx.pc + 1].toInt() and 0xFF
                    ctx.stack.push(NumberLiteral(ctx.pc).apply { value = numValue.toString() })
                    ctx.pc += 2
                    return true
                }

                // 二元操作符
                Token.ADD -> {
                    val right = safePopStack()
                    val left = safePopStack()
                    ctx.stack.push(InfixExpression(ctx.pc, Token.ADD, left, right))
                    ctx.pc++
                    return true
                }

                Token.SUB -> {
                    val right = safePopStack()
                    val left = safePopStack()
                    ctx.stack.push(InfixExpression(ctx.pc, Token.SUB, left, right))
                    ctx.pc++
                    return true
                }

                Token.MUL -> {
                    val right = safePopStack()
                    val left = safePopStack()
                    ctx.stack.push(InfixExpression(ctx.pc, Token.MUL, left, right))
                    ctx.pc++
                    return true
                }

                Token.DIV -> {
                    val right = safePopStack()
                    val left = safePopStack()
                    ctx.stack.push(InfixExpression(ctx.pc, Token.DIV, left, right))
                    ctx.pc++
                    return true
                }

                // 函数调用
                Token.CALL -> {
                    // 更健壮的CALL处理
                    try {
                        // 获取参数数量
                        val argCount = if (ctx.pc + 1 < iCode.size) iCode[ctx.pc + 1].toInt() and 0xFF else 0
                        val args = mutableListOf<AstNode>()

                        // 安全地从栈中弹出参数
                        for (i in 0 until argCount) {
                            if (ctx.stack.isNotEmpty()) {
                                args.add(0, ctx.stack.pop()) // 参数是从右到左
                            } else {
                                args.add(0, EmptyExpression()) // 添加空表达式代替缺失的参数
                            }
                        }

                        // 安全地弹出函数对象
                        val target = safePopStack()

                        // 创建函数调用节点
                        val callNode = FunctionCall(ctx.pc)
                        callNode.target = target

                        // 添加参数
                        args.forEach { callNode.addArgument(it) }

                        // 将函数调用推入栈
                        ctx.stack.push(callNode)

                        // 更新PC
                        ctx.pc += 2
                    } catch (e: Exception) {
                        if (DEBUG) {
                            println("处理CALL指令时出错: ${e.message}")
                            e.printStackTrace()
                        }

                        // 出错时创建一个空的函数调用
                        val dummyCall = FunctionCall(ctx.pc)
                        dummyCall.target = EmptyExpression()
                        ctx.stack.push(dummyCall)
                        ctx.pc += 2
                    }
                    return true
                }

                // String常量(41)，在我们的反编译器中也处理为CALL_PROP
                Token.STRING -> {
                    // 检查下一个字节来确定这是字符串常量还是函数调用
                    if (ctx.pc + 1 < iCode.size && (iCode[ctx.pc + 1].toInt() and 0xFF) < 20) {
                        // 可能是方法调用 CALL_PROP (41)
                        try {
                            // 获取参数数量
                            val argCount = if (ctx.pc + 1 < iCode.size) iCode[ctx.pc + 1].toInt() and 0xFF else 0
                            val args = mutableListOf<AstNode>()

                            // 安全地从栈中弹出参数
                            for (i in 0 until argCount) {
                                if (ctx.stack.isNotEmpty()) {
                                    args.add(0, ctx.stack.pop()) // 参数是从右到左
                                } else {
                                    args.add(0, EmptyExpression()) // 添加空表达式代替缺失的参数
                                }
                            }

                            // 安全地弹出属性名和对象
                            val propName = safePopStack()
                            val target = safePopStack()

                            // 创建属性访问
                            val propAccess = PropertyGet(ctx.pc)
                            propAccess.target = target

                            // 确保property是Name类型
                            propAccess.property = if (propName is Name) {
                                propName
                            } else {
                                Name(ctx.pc, propName.toSource(0))
                            }

                            // 创建方法调用节点
                            val callNode = FunctionCall(ctx.pc)
                            callNode.target = propAccess

                            // 添加参数
                            args.forEach { callNode.addArgument(it) }

                            // 将方法调用推入栈
                            ctx.stack.push(callNode)

                            // 更新PC
                            ctx.pc += 2
                        } catch (e: Exception) {
                            if (DEBUG) {
                                println("处理CALL_PROP指令时出错: ${e.message}")
                                e.printStackTrace()
                            }

                            // 出错时创建一个空的方法调用
                            val dummyCall = FunctionCall(ctx.pc)
                            dummyCall.target = EmptyExpression()
                            ctx.stack.push(dummyCall)
                            ctx.pc += 2
                        }
                    } else {
                        // 这是一个真正的字符串常量
                        val strIndex = if (ctx.pc + 1 < iCode.size) iCode[ctx.pc + 1].toInt() and 0xFF else 0
                        if (strIndex < stringTable.size) {
                            ctx.stack.push(StringLiteral(ctx.pc).apply { value = stringTable[strIndex] })
                        } else {
                            ctx.stack.push(StringLiteral(ctx.pc).apply { value = "unknown_string_$strIndex" })
                        }
                        ctx.pc += 2
                    }
                    return true
                }

                // 方法调用（对象的方法）
                Token.GETPROP -> {
                    // 获取属性名
                    val nameIndex = if (ctx.pc + 1 < iCode.size) iCode[ctx.pc + 1].toInt() and 0xFF else 0
                    val propName = if (nameIndex < stringTable.size) stringTable[nameIndex] else "unknown"

                    // 对象
                    if (ctx.stack.isNotEmpty()) {
                        val obj = safePopStack()

                        // 创建属性访问节点
                        val propGet = PropertyGet(ctx.pc)
                        propGet.target = obj
                        propGet.property = Name(ctx.pc, propName)

                        ctx.stack.push(propGet)
                    } else {
                        ctx.stack.push(Name(ctx.pc, propName))
                    }

                    ctx.pc += 2
                    return true
                }

                // 属性赋值
                Token.SETPROP -> {
                    // 获取属性名
                    val nameIndex = if (ctx.pc + 1 < iCode.size) iCode[ctx.pc + 1].toInt() and 0xFF else 0
                    val propName = if (nameIndex < stringTable.size) stringTable[nameIndex] else "unknown"

                    // 值和对象
                    if (ctx.stack.size >= 2) {
                        val value = safePopStack()
                        val obj = safePopStack()

                        // 创建属性访问节点
                        val propGet = PropertyGet(ctx.pc)
                        propGet.target = obj
                        propGet.property = Name(ctx.pc, propName)

                        // 创建赋值节点
                        val assignment = Assignment(ctx.pc)
                        assignment.type = Token.ASSIGN
                        assignment.left = propGet
                        assignment.right = value

                        ctx.stack.push(assignment)
                    }

                    ctx.pc += 2
                    return true
                }

                // 控制流指令
                Token.IFEQ -> {
                    try {
                        // 相等则跳转
                        val offset = getJumpOffset(iCode, ctx.pc)
                        val targetPC = ctx.pc + offset
                        val condition = safePopStack()

                        // 检查特殊情况：for-in循环
                        if (ctx.pc >= 3 && iCode[ctx.pc - 3].toInt() == Token.ENUM_NEXT) {
                            if (DEBUG) println("检测到 for-in 循环")
                            ctx.loopInfo[ctx.pc] = LoopInfo(LoopType.FOR_IN, condition, targetPC)
                        }
                        // 检查特殊情况：逻辑运算符
                        else if (ctx.labels.containsKey(targetPC) && iCode[targetPC].toInt() == Token.IFNE) {
                            if (DEBUG) println("检测到逻辑运算符 OR")
                            // 这可能是逻辑OR的一部分
                            val orExpr = InfixExpression(ctx.pc, Token.OR, condition, null)
                            ctx.pendingExpressions[targetPC] = orExpr
                        }
                        // 检查特殊情况：while循环
                        else if (targetPC < ctx.pc) {
                            if (DEBUG) println("检测到向后跳转，可能是循环尾部")
                            // 向后跳转可能是循环尾部
                            // 需要通过控制流分析器进一步确认
                        } else {
                            // 常规if条件，条件取反，因为IFEQ是"相等时跳转"
                            val notCondition = UnaryExpression(ctx.pc, Token.NOT, condition)
                            ctx.labels[targetPC] = Label(targetPC, ctx.pc, ctx.lineNo, opcode)
                            ctx.ifConditions[ctx.pc] = notCondition

                            // 标记if-then-else结构的开始
                            ctx.controlBlocks.add(
                                ControlBlock(
                                    type = ControlBlockType.IF,
                                    startPC = ctx.pc,
                                    endPC = targetPC,
                                    condition = notCondition
                                )
                            )
                        }
                    } catch (e: Exception) {
                        if (DEBUG) println("处理IFEQ指令时出错: ${e.message}")
                    }
                    ctx.pc += 3
                    return true
                }

                Token.IFNE -> {
                    try {
                        // 不等则跳转
                        val offset = getJumpOffset(iCode, ctx.pc)
                        val targetPC = ctx.pc + offset
                        val condition = safePopStack()

                        // 检查特殊情况：for-in循环
                        if (ctx.pc >= 3 && iCode[ctx.pc - 3].toInt() == Token.ENUM_NEXT) {
                            if (DEBUG) println("检测到 for-in 循环")
                            ctx.loopInfo[ctx.pc] = LoopInfo(LoopType.FOR_IN, condition, targetPC)
                        }
                        // 检查特殊情况：逻辑运算符
                        else if (ctx.pendingExpressions.containsKey(ctx.pc)) {
                            if (DEBUG) println("完成逻辑运算符 OR")
                            // 完成逻辑OR表达式
                            val orExpr = ctx.pendingExpressions[ctx.pc] as InfixExpression
                            orExpr.right = condition
                            ctx.stack.push(orExpr)
                            ctx.pendingExpressions.remove(ctx.pc)
                        }
                        // 检查特殊情况：while循环
                        else if (targetPC < ctx.pc) {
                            if (DEBUG) println("检测到向后跳转，可能是循环尾部")
                            // 向后跳转可能是循环尾部
                            // 需要通过控制流分析器进一步确认
                        } else {
                            // 常规if条件
                            ctx.labels[targetPC] = Label(targetPC, ctx.pc, ctx.lineNo, opcode)
                            ctx.ifConditions[ctx.pc] = condition

                            // 标记if-then-else结构的开始
                            ctx.controlBlocks.add(
                                ControlBlock(
                                    type = ControlBlockType.IF,
                                    startPC = ctx.pc,
                                    endPC = targetPC,
                                    condition = condition
                                )
                            )
                        }
                    } catch (e: Exception) {
                        if (DEBUG) println("处理IFNE指令时出错: ${e.message}")
                    }
                    ctx.pc += 3
                    return true
                }

                Token.GOTO -> {
                    try {
                        val offset = getJumpOffset(iCode, ctx.pc)
                        val targetPC = ctx.pc + offset

                        // 检查特殊情况：break 或 continue
                        if (ctx.pc >= 4 && iCode[ctx.pc - 4].toInt() == Token.LEAVEWITH) {
                            if (DEBUG) println("检测到 break 或 continue")

                            // 检查跳转目标来确定是break还是continue
                            if (targetPC > ctx.pc) {
                                // 向前跳转，可能是break
                                ctx.stack.push(BreakStatement(ctx.pc))
                            } else {
                                // 向后跳转，可能是continue
                                ctx.stack.push(ContinueStatement(ctx.pc))
                            }
                        }
                        // 检查特殊情况：循环头部或尾部
                        else if (targetPC < ctx.pc) {
                            if (DEBUG) println("检测到向后跳转，可能是循环头部")
                            // 向后跳转可能是循环头部
                            // 标记为do-while循环的可能性
                            ctx.loopInfo[ctx.pc] = LoopInfo(LoopType.DO_WHILE, null, targetPC)
                        } else {
                            // 向前跳转，可能是循环结束或普通的跳转
                            ctx.labels[targetPC] = Label(targetPC, ctx.pc, ctx.lineNo, opcode)

                            // 可能是else块的结束
                            ctx.controlBlocks.add(
                                ControlBlock(
                                    type = ControlBlockType.GOTO, startPC = ctx.pc, endPC = targetPC, condition = null
                                )
                            )
                        }
                    } catch (e: Exception) {
                        if (DEBUG) println("处理GOTO指令时出错: ${e.message}")
                    }
                    ctx.pc += 3
                    return true
                }

                Token.RETURN -> {
                    if (ctx.stack.isNotEmpty()) {
                        val expr = safePopStack()
                        ctx.stack.push(ReturnStatement(ctx.pc).apply { setReturnValue(expr) })
                    } else {
                        ctx.stack.push(ReturnStatement(ctx.pc))
                    }
                    ctx.pc++
                    return true
                }

                Icode.Icode_POP -> {
                    if (ctx.stack.isNotEmpty()) {
                        // 丢弃栈顶元素
                        val expr = safePopStack()
                        // 如果是有效表达式，创建ExpressionStatement
                        if (expr !is EmptyExpression) {
                            ctx.stack.push(ExpressionStatement(expr))
                        }
                    }
                    ctx.pc++
                    return true
                }

                Icode.Icode_POP_RESULT -> {
                    if (ctx.stack.isNotEmpty()) {
                        val expr = safePopStack()
                        if (expr !is EmptyExpression) {
                            ctx.stack.push(ExpressionStatement(expr))
                        }
                    }
                    ctx.pc++
                    return true
                }

                // 添加常见的未处理指令
                Token.TRUE -> {
                    // Rhino AST库可能没有BooleanLiteral，使用KeywordLiteral代替
                    ctx.stack.push(KeywordLiteral(ctx.pc, Token.TRUE))
                    ctx.pc++
                    return true
                }

                Token.FALSE -> {
                    // Rhino AST库可能没有BooleanLiteral，使用KeywordLiteral代替
                    ctx.stack.push(KeywordLiteral(ctx.pc, Token.FALSE))
                    ctx.pc++
                    return true
                }

                Token.NULL -> {
                    // Rhino AST库可能没有NullLiteral，使用KeywordLiteral代替
                    ctx.stack.push(KeywordLiteral(ctx.pc, Token.NULL))
                    ctx.pc++
                    return true
                }

                Token.THIS -> {
                    ctx.stack.push(KeywordLiteral(ctx.pc, Token.THIS))
                    ctx.pc++
                    return true
                }

                Token.NAME -> {
                    // 处理简单的标识符
                    val nameIndex = if (ctx.pc + 1 < iCode.size) iCode[ctx.pc + 1].toInt() and 0xFF else 0
                    val name = if (nameIndex < stringTable.size) stringTable[nameIndex] else "unknown_$nameIndex"
                    ctx.stack.push(Name(ctx.pc, name))
                    ctx.pc += 2
                    return true
                }

                Token.NUMBER -> {
                    // 处理数字字面量
                    val num = if (ctx.pc + 1 < iCode.size) iCode[ctx.pc + 1].toInt() and 0xFF else 0
                    ctx.stack.push(NumberLiteral(ctx.pc, num.toString()))
                    ctx.pc += 2
                    return true
                }

                Token.INSTANCEOF -> {
                    // 处理instanceof操作符
                    val right = safePopStack()
                    val left = safePopStack()
                    ctx.stack.push(InfixExpression(ctx.pc, Token.INSTANCEOF, left, right))
                    ctx.pc++
                    return true
                }

                Token.IN -> {
                    // 处理in操作符
                    val right = safePopStack()
                    val left = safePopStack()
                    ctx.stack.push(InfixExpression(ctx.pc, Token.IN, left, right))
                    ctx.pc++
                    return true
                }

                Token.TYPEOF -> {
                    // 处理typeof操作符
                    val expr = safePopStack()
                    val unary = UnaryExpression(ctx.pc, Token.TYPEOF)
                    unary.operand = expr
                    ctx.stack.push(unary)
                    ctx.pc++
                    return true
                }

                Token.VOID -> {
                    // 处理void操作符
                    val expr = safePopStack()
                    val unary = UnaryExpression(ctx.pc, Token.VOID)
                    unary.operand = expr
                    ctx.stack.push(unary)
                    ctx.pc++
                    return true
                }

                Token.NOT -> {
                    // 处理逻辑非操作符
                    val expr = safePopStack()
                    val unary = UnaryExpression(ctx.pc, Token.NOT)
                    unary.operand = expr
                    ctx.stack.push(unary)
                    ctx.pc++
                    return true
                }

                Token.BITNOT -> {
                    // 处理按位非操作符
                    val expr = safePopStack()
                    val unary = UnaryExpression(ctx.pc, Token.BITNOT)
                    unary.operand = expr
                    ctx.stack.push(unary)
                    ctx.pc++
                    return true
                }

                Token.POS -> {
                    // 处理一元加操作符
                    val expr = safePopStack()
                    val unary = UnaryExpression(ctx.pc, Token.POS)
                    unary.operand = expr
                    ctx.stack.push(unary)
                    ctx.pc++
                    return true
                }

                Token.NEG -> {
                    // 处理一元减操作符
                    val expr = safePopStack()
                    val unary = UnaryExpression(ctx.pc, Token.NEG)
                    unary.operand = expr
                    ctx.stack.push(unary)
                    ctx.pc++
                    return true
                }

                Token.EQ, Token.NE, Token.LT, Token.LE, Token.GT, Token.GE -> {
                    // 处理比较操作符
                    val right = safePopStack()
                    val left = safePopStack()
                    ctx.stack.push(InfixExpression(ctx.pc, opcode, left, right))
                    ctx.pc++
                    return true
                }

                Token.BITOR, Token.BITXOR, Token.BITAND, Token.LSH, Token.RSH, Token.URSH, Token.MOD -> {
                    // 处理位运算和其他二元操作符
                    val right = safePopStack()
                    val left = safePopStack()
                    ctx.stack.push(InfixExpression(ctx.pc, opcode, left, right))
                    ctx.pc++
                    return true
                }

                Token.AND, Token.OR -> {
                    // 处理逻辑运算符
                    val right = safePopStack()
                    val left = safePopStack()
                    ctx.stack.push(InfixExpression(ctx.pc, opcode, left, right))
                    ctx.pc++
                    return true
                }

                Token.INC, Token.DEC -> {
                    // 处理自增自减运算符
                    val expr = safePopStack()
                    // 创建前缀形式的自增/自减表达式
                    val unary = UnaryExpression(ctx.pc, opcode)
                    unary.operand = expr
                    ctx.stack.push(unary)
                    ctx.pc++
                    return true
                }

                // 其他指令...
                else -> {
                    when (opcode) {
                        // 处理常见的未知字节码值
                        230 -> { // 可能是处理对象创建或函数声明
                            ctx.stack.push(EmptyExpression())
                            ctx.pc++
                            return true
                        }

                        240, 241 -> { // 可能是处理参数传递
                            if (ctx.pc + 1 < iCode.size) {
                                ctx.pc += 2
                            } else {
                                ctx.pc++
                            }
                            return true
                        }

                        0, 1, 2, 3, 4, 8, 9, 10, 11, 12, 13, 15, 18, 19, 20, 23, 26, 27, 29, 31, 32, 33, 34, 35, 36, 39, 44, 45, 51, 52, 53, 55, 56, 57, 58, 59, 60, 61, 63, 73, 74, 75, 76, 77, 78, 184 -> {
                            // 其他数值型常量和操作符
                            ctx.stack.push(EmptyExpression())
                            ctx.pc++
                            return true
                        }

                        207, 208, 211, 212, 213, 214, 215, 222, 223, 224, 226, 227, 229, 234, 252, 255 -> {
                            // 其他常见的未知字节码，简单跳过
                            ctx.pc++
                            return true
                        }

                        else -> {
                            // 暂不支持的其他指令，跳过
                            ctx.pc++
                            return false
                        }
                    }
                }
            }
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
         * 处理控制流结构
         */
        private fun processControlFlowStructures(ctx: DecompilerContext, cfg: ControlFlowGraph) {
            // 处理if-else结构
            processIfStatements(ctx, cfg)

            // 处理循环结构
            processLoopStructures(ctx, cfg)

            // 处理异常处理结构
            processTryCatchStructures(ctx, cfg)
        }

        /**
         * 处理if语句结构
         */
        private fun processIfStatements(ctx: DecompilerContext, cfg: ControlFlowGraph) {
            // 找到所有条件跳转指令的标签
            val ifConditions = ctx.ifConditions.toMap()

            // 遍历控制流图中的if结构
            cfg.structures.filterIsInstance<IfStructure>().forEach { ifStructure ->
                val conditionPC = ifStructure.condition.start
                val condition = ifConditions[conditionPC] ?: return@forEach

                // 收集then分支语句
                val thenStatements = mutableListOf<AstNode>()
                val thenBlock = Block(conditionPC)

                // 收集else分支语句
                val elseStatements = mutableListOf<AstNode>()
                val elseBlock = Block(conditionPC)

                // 从栈中找到相关语句，并按分支分组
                // 由于我们已经有条件跳转信息，我们可以确定哪些语句属于哪个分支

                // 创建if语句节点
                val ifStatement = IfStatement(conditionPC)
                ifStatement.condition = condition
                ifStatement.thenPart = thenBlock

                if (elseStatements.isNotEmpty()) {
                    elseStatements.forEach { elseBlock.addStatement(it) }
                    ifStatement.elsePart = elseBlock
                }

                // 将所有相关的语句添加到then块
                thenStatements.forEach { thenBlock.addStatement(it) }

                // 将if语句推入栈
                ctx.stack.push(ifStatement)
            }
        }

        /**
         * 处理循环结构
         */
        private fun processLoopStructures(ctx: DecompilerContext, cfg: ControlFlowGraph) {
            // 遍历控制流图中的所有循环结构
            cfg.structures.filterIsInstance<LoopStructure>().forEach { loopStructure ->
//                when (loopStructure.type) {
//                    LoopType.WHILE -> {
//                        processWhileLoop(ctx, loopStructure)
//                    }
//                    LoopType.DO_WHILE -> {
//                        processDoWhileLoop(ctx, loopStructure)
//                    }
//                    LoopType.FOR -> {
//                        processForLoop(ctx, loopStructure)
//                    }
//                    else -> {
//                        // 处理一般循环
//                        processGenericLoop(ctx, loopStructure)
//                    }
//                }
            }
        }

        /**
         * 处理while循环
         */
        private fun processWhileLoop(ctx: DecompilerContext, loopStructure: LoopStructure) {
            val headerPC = loopStructure.header.start
            // 从上下文中获取循环条件
            val condition = ctx.ifConditions[headerPC] ?: return

            // 收集循环体语句
            val bodyStatements = mutableListOf<AstNode>()
            val bodyBlock = Block(headerPC)

            // 将所有相关的语句添加到循环体块
            bodyStatements.forEach { bodyBlock.addStatement(it) }

            // 创建while循环节点
            val whileLoop = WhileLoop(headerPC)
            whileLoop.condition = condition
            whileLoop.body = bodyBlock

            // 将while循环推入栈
            ctx.stack.push(whileLoop)
        }

        /**
         * 处理do-while循环
         */
        private fun processDoWhileLoop(ctx: DecompilerContext, loopStructure: LoopStructure) {
            // 实现类似于while循环的逻辑，但条件检查在循环结尾
            val headerPC = loopStructure.header.start
            // 找到循环结尾的条件
            val lastBlock = loopStructure.body.last()
            val condition = ctx.ifConditions[lastBlock.start] ?: return

            // 收集循环体语句
            val bodyStatements = mutableListOf<AstNode>()
            val bodyBlock = Block(headerPC)

            // 将所有相关的语句添加到循环体块
            bodyStatements.forEach { bodyBlock.addStatement(it) }

            // 创建do-while循环节点
            val doLoop = DoLoop(headerPC)
            doLoop.condition = condition
            doLoop.body = bodyBlock

            // 将do-while循环推入栈
            ctx.stack.push(doLoop)
        }

        /**
         * 处理for循环
         */
        private fun processForLoop(ctx: DecompilerContext, loopStructure: LoopStructure) {
            // for循环通常有初始化、条件检查和递增三部分
            val headerPC = loopStructure.header.start

            // 分析循环体的指令模式，识别for循环的三个部分
            val loopBody = loopStructure.body

            // 在循环前找初始化语句
            var initializer: AstNode? = null
            // 查找循环前的赋值或变量声明语句
            for (i in ctx.stack.indices.reversed()) {
                val node = ctx.stack[i]
                if (node is Assignment || node is VariableDeclaration) {
                    if (node.position < headerPC) {
                        initializer = node
                        break
                    }
                }
            }

            // 如果没找到初始化语句，则使用空表达式
            if (initializer == null) {
                initializer = EmptyExpression()
            }

            // 条件通常在循环头
            val condition = ctx.ifConditions[headerPC] ?: EmptyExpression()

            // 在循环体末尾寻找递增语句
            var increment: AstNode? = null
            if (loopBody.isNotEmpty()) {
                val lastBlock = loopBody.last()
                // 查找循环体末尾的赋值或一元操作（如i++）
                for (i in ctx.stack.indices.reversed()) {
                    val node = ctx.stack[i]
                    if ((node is Assignment || node is UnaryExpression) && node.position >= lastBlock.start && node.position <= lastBlock.end) {
                        increment = node
                        break
                    }
                }
            }

            // 如果没找到递增语句，则使用空表达式
            if (increment == null) {
                increment = EmptyExpression()
            }

            // 收集循环体语句，排除初始化和递增语句
            val bodyStatements = mutableListOf<AstNode>()
            for (i in ctx.stack.indices.reversed()) {
                val node = ctx.stack[i]
                if (node !== initializer && node !== increment) {
                    // 检查节点是否在循环体中
                    val inLoopBody = loopBody.any { block ->
                        node.position in block.start..block.end
                    }
                    if (inLoopBody) {
                        bodyStatements.add(node)
                    }
                }
            }

            val bodyBlock = Block(headerPC)
            // 将所有相关的语句添加到循环体块
            bodyStatements.forEach { bodyBlock.addStatement(it) }

            // 创建for循环节点
            val forLoop = ForLoop(headerPC)
            forLoop.initializer = initializer
            forLoop.condition = condition
            forLoop.increment = increment
            forLoop.body = bodyBlock

            // 将for循环推入栈
            ctx.stack.push(forLoop)
        }

        /**
         * 处理通用循环
         */
        private fun processGenericLoop(ctx: DecompilerContext, loopStructure: LoopStructure) {
            // 对于无法识别具体类型的循环，默认使用while循环表示
            processWhileLoop(ctx, loopStructure)
        }

        /**
         * 处理try-catch结构
         */
        private fun processTryCatchStructures(ctx: DecompilerContext, cfg: ControlFlowGraph) {
            // 由于Rhino字节码中没有直接的try-catch信息，这里需要通过异常表来推断
            // 这部分可能需要更复杂的算法来识别try-catch块的边界

            // 例如，可以通过检查异常表和跳转模式来识别try-catch块
            // 但这需要更详细的分析
        }

        /**
         * 处理try-catch语句
         */
        private fun processTryCatch(ctx: DecompilerContext, data: InterpreterData) {
            // 如果有异常表，则处理try-catch结构
            val exceptionTable = data.itsExceptionTable
            if (exceptionTable == null || exceptionTable.isEmpty()) return

            // 在Rhino中，异常表的格式是一组连续的整数数组：
            // [tryStart, tryEnd, catchStart, catchType, finallyStart, tryStart, tryEnd, ...]
            var i = 0
            while (i < exceptionTable.size) {
                val tryStart = exceptionTable[i++]
                val tryEnd = exceptionTable[i++]
                val catchStart = exceptionTable[i++]
                val catchType = exceptionTable[i++] // 异常类型索引
                val finallyStart = exceptionTable[i++]

                // 创建try语句
                val tryStatement = TryStatement(tryStart)

                // 收集try块语句
                val tryStatements = mutableListOf<AstNode>()
                for (j in ctx.stack.indices.reversed()) {
                    val node = ctx.stack[j]
                    if (node.position in tryStart until tryEnd) {
                        tryStatements.add(node)
                    }
                }

                // 创建try块
                val tryBlock = Block(tryStart)
                tryStatements.forEach { tryBlock.addStatement(it) }
                tryStatement.tryBlock = tryBlock

                // 处理catch块
                if (catchStart >= 0) {
                    // 尝试找到catch块的结束位置
                    val catchEnd =
                        if (finallyStart >= 0) finallyStart else (if (i < exceptionTable.size) exceptionTable[i] else Int.MAX_VALUE)

                    // 收集catch块语句
                    val catchStatements = mutableListOf<AstNode>()
                    for (j in ctx.stack.indices.reversed()) {
                        val node = ctx.stack[j]
                        if (node.position in catchStart until catchEnd) {
                            catchStatements.add(node)
                        }
                    }

                    // 创建catch子句
                    val catchClause = CatchClause()
                    catchClause.varName = Name(catchStart, "e") // 默认异常变量名

                    // 如果有异常类型信息，可以设置
                    if (catchType >= 0 && catchType < data.itsStringTable.size) {
                        // catchClause.setCatchCondition(...) // 设置异常类型条件
                    }

                    // 创建catch块
                    val catchBlock = Block(catchStart)
                    catchStatements.forEach { catchBlock.addStatement(it) }
                    catchClause.body = catchBlock

                    tryStatement.addCatchClause(catchClause)
                }

                // 处理finally块
                if (finallyStart >= 0) {
                    // 尝试找到finally块的结束位置
                    val finallyEnd = if (i < exceptionTable.size) exceptionTable[i] else Int.MAX_VALUE

                    // 收集finally块语句
                    val finallyStatements = mutableListOf<AstNode>()
                    for (j in ctx.stack.indices.reversed()) {
                        val node = ctx.stack[j]
                        if (node.position in finallyStart until finallyEnd) {
                            finallyStatements.add(node)
                        }
                    }

                    // 创建finally块
                    val finallyBlock = Block(finallyStart)
                    finallyStatements.forEach { finallyBlock.addStatement(it) }
                    tryStatement.finallyBlock = finallyBlock
                }

                // 将try语句推入栈
                ctx.stack.push(tryStatement)
            }
        }

        /**
         * 增强for循环处理
         */
        private fun enhanceLoopDetection(ctx: DecompilerContext, data: InterpreterData) {
            // 扫描整个字节码寻找循环模式
            val iCode = data.itsICode
            var pc = 0

            while (pc < iCode.size) {
                val opcode = iCode[pc].toInt() and 0xFF

                // 检测for循环模式
                if (opcode == Icode.Icode_SETVAR1) {
                    // 初始化 - 变量赋值
                    val initPC = pc

                    // 向前查找条件检查（IFEQ/IFNE）
                    var checkPC = findNextJump(iCode, pc)
                    if (checkPC > 0) {
                        val jumpOpcode = iCode[checkPC].toInt() and 0xFF

                        if (jumpOpcode == Token.IFEQ || jumpOpcode == Token.IFNE) {
                            // 获取跳转目标
                            val offset = getJumpOffset(iCode, checkPC)
                            val endPC = checkPC + offset

                            // 向后查找递增操作
                            var incPC = findIncrement(iCode, checkPC, endPC)
                            if (incPC > 0) {
                                // 找到了完整的for循环模式
                                ctx.forLoops.add(
                                    ForLoopInfo(
                                        initPC = initPC, checkPC = checkPC, incPC = incPC, endPC = endPC
                                    )
                                )
                            }
                        }
                    }
                }

                // 检测for-in循环模式
                if (opcode == Token.ENUM_INIT_KEYS || opcode == Token.ENUM_INIT_VALUES) {
                    val enumInitPC = pc

                    // 向前查找ENUM_NEXT
                    var enumNextPC = findNextEnumNext(iCode, pc)
                    if (enumNextPC > 0) {
                        // 继续查找IFEQ
                        var ifPC = findNextJump(iCode, enumNextPC)
                        if (ifPC > 0 && iCode[ifPC].toInt() and 0xFF == Token.IFEQ) {
                            // 获取跳转目标
                            val offset = getJumpOffset(iCode, ifPC)
                            val endPC = ifPC + offset

                            // 找到了完整的for-in循环模式
                            ctx.forInLoops.add(
                                ForInLoopInfo(
                                    enumInitPC = enumInitPC, enumNextPC = enumNextPC, ifPC = ifPC, endPC = endPC
                                )
                            )
                        }
                    }
                }

                // 移动到下一条指令
                pc += getInstructionLength(iCode, pc)
            }
        }

        /**
         * 查找下一个跳转指令
         */
        private fun findNextJump(iCode: ByteArray, startPC: Int): Int {
            var pc = startPC
            while (pc < iCode.size) {
                val opcode = iCode[pc].toInt() and 0xFF
                if (opcode == Token.IFEQ || opcode == Token.IFNE || opcode == Token.GOTO) {
                    return pc
                }
                pc += getInstructionLength(iCode, pc)
            }
            return -1
        }

        /**
         * 查找下一个ENUM_NEXT指令
         */
        private fun findNextEnumNext(iCode: ByteArray, startPC: Int): Int {
            var pc = startPC
            while (pc < iCode.size) {
                val opcode = iCode[pc].toInt() and 0xFF
                if (opcode == Token.ENUM_NEXT) {
                    return pc
                }
                pc += getInstructionLength(iCode, pc)
            }
            return -1
        }

        /**
         * 查找递增操作
         */
        private fun findIncrement(iCode: ByteArray, startPC: Int, endPC: Int): Int {
            var pc = startPC
            while (pc < endPC) {
                val opcode = iCode[pc].toInt() and 0xFF
                if (opcode == Icode.Icode_SETVAR1 || opcode == Token.INC || opcode == Token.DEC) {
                    return pc
                }
                pc += getInstructionLength(iCode, pc)
            }
            return -1
        }

        /**
         * 获取指令长度
         */
        private fun getInstructionLength(iCode: ByteArray, pc: Int): Int {
            val opcode = iCode[pc].toInt() and 0xFF
            return when (opcode) {
                Token.GOTO, Token.IFEQ, Token.IFNE -> 3
                Icode.Icode_GETVAR1, Icode.Icode_SETVAR1, Icode.Icode_REG_IND1, Icode.Icode_REG_STR1 -> 2

                Icode.Icode_INTNUMBER, Icode.Icode_REG_STR4 -> 4
                Icode.Icode_SHORTNUMBER, Icode.Icode_REG_STR2, Icode.Icode_LINE -> 2

                else -> 1
            }
        }

        /**
         * 处理嵌套函数
         */
        private fun processNestedFunctions(ctx: DecompilerContext, data: InterpreterData) {
            // 首先处理直接嵌套的函数
            data.itsNestedFunctions?.forEach { nestedData ->
                if (!nestedData.itsName.isNullOrEmpty()) {
                    try {
                        if (DEBUG) println("开始处理嵌套函数: ${nestedData.itsName}")

                        val nestedCtx = DecompilerContext(
                            parent = ctx, scope = Scope(nestedData.itsName).apply { parent = ctx.scope })
                        val nestedBlock = decompileToBlock(nestedCtx, nestedData)

                        val funcNode = FunctionNode(ctx.pc).apply {
                            functionName = Name(0, nestedData.itsName!!)
                            params = (0 until nestedData.argCount).mapNotNull {
                                if (it < nestedData.argNames.size) Name(0, nestedData.argNames[it]) else null
                            }
                            body = nestedBlock
                        }

                        ctx.stack.push(funcNode)

                        if (DEBUG) println("完成处理嵌套函数: ${nestedData.itsName}")
                    } catch (e: Exception) {
                        if (DEBUG) {
                            println("处理嵌套函数出错: ${nestedData.itsName}, ${e.message}")
                            e.printStackTrace()
                        }

                        // 创建一个空的函数声明，以防止后续处理出错
                        val emptyFuncNode = FunctionNode(ctx.pc).apply {
                            functionName = Name(0, nestedData.itsName!!)
                            body = Block(0)
                        }
                        ctx.stack.push(emptyFuncNode)
                    }
                }
            }

            // 处理函数表达式和匿名函数
            if (data.itsFunctionType != 0) {
                try {
                    if (DEBUG) println("处理函数表达式: ${data.itsName ?: "匿名函数"}")

                    // 创建函数节点
                    val funcNode = FunctionNode(0).apply {
                        if (data.itsName != null) {
                            functionName = Name(0, data.itsName!!)
                        }

                        // 添加参数
                        params = (0 until data.argCount).mapNotNull {
                            if (it < data.argNames.size) Name(0, data.argNames[it]) else null
                        }

                        // 创建函数体
                        body = Block(0)
                    }

                    ctx.stack.push(funcNode)
                } catch (e: Exception) {
                    if (DEBUG) {
                        println("处理函数表达式出错: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }

        /**
         * 创建根块（函数或脚本）
         */
        private fun createRootBlock(ctx: DecompilerContext, data: InterpreterData): Block {
            val block = Block(0)

            try {
                // 添加变量声明
                val varDecls = mutableListOf<VariableDeclaration>()
                ctx.scope.variables.forEach { varName ->
                    val varDecl = VariableDeclaration(0).apply {
                        addVariable(VariableInitializer(0).apply {
                            target = Name(0, varName)
                        })
                    }
                    varDecls.add(varDecl)
                }

                // 将变量声明添加到块的开头
                varDecls.forEach { block.addStatement(it) }

                // 处理嵌套函数声明
                val functionDecls = mutableListOf<AstNode>()
                val regularStatements = mutableListOf<AstNode>()

                // 收集所有语句，区分函数声明和普通语句
                while (ctx.stack.isNotEmpty()) {
                    val node = ctx.stack.pop()
                    when (node) {
                        is FunctionNode -> {
                            functionDecls.add(node)
                        }

                        else -> {
                            regularStatements.add(node)
                        }
                    }
                }

                // 函数提升：先添加函数声明
                functionDecls.forEach { block.addStatement(it) }

                // 整理控制流结构
                val statements = organizeControlFlow(regularStatements, ctx.controlBlocks)

                // 添加其余语句
                statements.forEach { block.addStatement(it) }
            } catch (e: Exception) {
                if (DEBUG) {
                    println("创建根块时出错: ${e.message}")
                    e.printStackTrace()
                }
            }

            return block
        }

        /**
         * 组织控制流结构
         * 将语句组织成控制流结构
         */
        private fun organizeControlFlow(statements: List<AstNode>, controlBlocks: List<ControlBlock>): List<AstNode> {
            if (controlBlocks.isEmpty()) return statements

            val result = mutableListOf<AstNode>()
            val processedIndices = mutableSetOf<Int>()

            // 先按照PC排序控制块
            val sortedBlocks = controlBlocks.sortedBy { it.startPC }

            // 处理每个控制块
            for (block in sortedBlocks) {
                when (block.type) {
                    ControlBlockType.IF -> {
                        // 找到这个if块的所有语句
                        val ifStatements = statements.filter {
                            (it.position in block.startPC until block.endPC) && it.position !in processedIndices
                        }

                        if (ifStatements.isNotEmpty()) {
                            // 创建if语句
                            val ifStatement = IfStatement(block.startPC)
                            ifStatement.condition = block.condition ?: EmptyExpression()

                            // 创建then块
                            val thenBlock = Block(block.startPC)
                            ifStatements.forEach {
                                thenBlock.addStatement(it)
                                processedIndices.add(it.position)
                            }

                            ifStatement.thenPart = thenBlock

                            // 检查是否有else块
                            // 这需要更复杂的分析...

                            result.add(ifStatement)
                        }
                    }

                    ControlBlockType.LOOP -> {
                        // 找到这个循环块的所有语句
                        val loopStatements = statements.filter {
                            (it.position in block.startPC until block.endPC) && it.position !in processedIndices
                        }

                        if (loopStatements.isNotEmpty()) {
                            // 创建循环语句
                            // 这里可以根据具体情况创建不同类型的循环

                            // 创建while循环作为默认
                            val whileLoop = WhileLoop(block.startPC)
                            whileLoop.condition = block.condition ?: EmptyExpression()

                            // 创建循环体
                            val bodyBlock = Block(block.startPC)
                            loopStatements.forEach {
                                bodyBlock.addStatement(it)
                                processedIndices.add(it.position)
                            }

                            whileLoop.body = bodyBlock
                            result.add(whileLoop)
                        }
                    }

                    ControlBlockType.TRY_CATCH -> {
                        // 处理try-catch结构
                        // 这需要更复杂的分析...
                    }

                    ControlBlockType.GOTO -> {
                        // 普通跳转，通常不需要特殊处理
                    }
                }
            }

            // 添加未处理的语句
            statements.forEachIndexed { index, statement ->
                if (statement.position !in processedIndices) {
                    result.add(statement)
                }
            }

            return result
        }

        /**
         * 格式化JavaScript代码
         */
        private fun formatJavaScript(source: String): String {
            // 简单的格式化逻辑，可以根据需要扩展
            return source.replace(";", ";\n").replace("{", "{\n").replace("}", "}\n")
        }

        /**
         * 处理for-in循环
         */
        private fun processForInLoop(ctx: DecompilerContext, loopPC: Int) {
            val loopInfo = ctx.loopInfo[loopPC] ?: return

            // 从控制流图中找到循环的开始和结束位置
            val loopStart = loopPC
            val loopEnd = loopInfo.targetPC

            // 尝试找到迭代器和被迭代对象
            var iterator: AstNode? = null
            var iteratedObject: AstNode? = null

            // 在循环前寻找ENUM_INIT指令
            for (i in ctx.stack.indices.reversed()) {
                val node = ctx.stack[i]
                if (node.position < loopStart) {
                    // 通常ENUM_INIT前后有迭代器和被迭代对象的操作
                    if (node is VariableDeclaration) {
                        // 变量声明可能是迭代器
                        iterator = node
                    } else if (iteratedObject == null && node !is Assignment) {
                        // 其他表达式可能是被迭代对象
                        iteratedObject = node
                    }
                }
            }

            // 创建for-in循环节点
            val forInLoop = ForInLoop(loopPC)

            // 如果我们能找到迭代器和被迭代对象，则设置它们
            if (iterator != null) {
                forInLoop.iterator = iterator
            } else {
                // 创建一个临时的变量声明作为迭代器
                val varName = Name(loopPC, "item")
                val varInit = VariableInitializer(loopPC)
                varInit.target = varName
                val varDecl = VariableDeclaration(loopPC)
                varDecl.addVariable(varInit)
                forInLoop.iterator = varDecl
            }

            if (iteratedObject != null) {
                forInLoop.iteratedObject = iteratedObject
            } else {
                // 创建一个临时的对象字面量作为被迭代对象
                val objLiteral = ObjectLiteral(loopPC)
                forInLoop.iteratedObject = objLiteral
            }

            // 收集循环体语句
            val bodyStatements = mutableListOf<AstNode>()
            for (i in ctx.stack.indices.reversed()) {
                val node = ctx.stack[i]
                if (node !== iterator && node !== iteratedObject && node.position > loopStart && node.position < loopEnd) {
                    bodyStatements.add(node)
                }
            }

            // 创建循环体
            val bodyBlock = Block(loopPC)
            bodyStatements.forEach { bodyBlock.addStatement(it) }

            forInLoop.body = bodyBlock

            // 将for-in循环推入栈
            ctx.stack.push(forInLoop)
        }
    }

    /**
     * 表示代码的作用域
     */
    data class Scope(
        val name: String = "TOP", val variables: MutableSet<String> = mutableSetOf(), var parent: Scope? = null
    ) {
        fun addVariable(name: String) {
            variables.add(name)
        }

        fun hasVariable(name: String): Boolean {
            if (name in variables) return true
            return parent?.hasVariable(name) == true
        }
    }

    /**
     * 代表一个标签（跳转目标）
     */
    data class Label(
        val target: Int,       // 目标PC
        val position: Int,     // 源位置
        val lineNo: Int,       // 源行号
        val opcode: Int,       // 操作码
        var node: AstNode? = null // 关联的AST节点
    )

    /**
     * 控制块类型
     */
    enum class ControlBlockType {
        IF,         // if-then-else结构
        LOOP,       // 循环结构
        TRY_CATCH,  // try-catch结构
        GOTO        // 普通跳转
    }

    /**
     * 循环类型
     */
    enum class LoopType {
        WHILE,      // while循环
        DO_WHILE,   // do-while循环
        FOR,        // for循环
        FOR_IN,     // for-in循环
        FOR_OF      // for-of循环
    }

    /**
     * 控制块信息
     */
    data class ControlBlock(
        val type: ControlBlockType, val startPC: Int,      // 开始位置
        val endPC: Int,        // 结束位置
        val condition: AstNode? // 条件表达式
    )

    /**
     * 循环信息
     */
    data class LoopInfo(
        val type: LoopType, val condition: AstNode?, val targetPC: Int
    )

    /**
     * 反编译上下文
     */
    class DecompilerContext(
        var parent: DecompilerContext? = null,
        val stack: Stack<AstNode> = Stack(),
        val labels: MutableMap<Int, Label> = mutableMapOf(),
        val ifConditions: MutableMap<Int, AstNode> = mutableMapOf(),
        val pendingExpressions: MutableMap<Int, AstNode> = mutableMapOf(),
        val controlBlocks: MutableList<ControlBlock> = mutableListOf(),
        val loopInfo: MutableMap<Int, LoopInfo> = mutableMapOf(),
        val forLoops: MutableList<ForLoopInfo> = mutableListOf(),
        val forInLoops: MutableList<ForInLoopInfo> = mutableListOf(),
        val scope: Scope = Scope(),
        var lineNo: Int = -1,
        var pc: Int = 0,
        var stringReg: String = ""
    )

    // For循环信息
    data class ForLoopInfo(
        val initPC: Int, val checkPC: Int, val incPC: Int, val endPC: Int
    )

    // For-in循环信息
    data class ForInLoopInfo(
        val enumInitPC: Int, val enumNextPC: Int, val ifPC: Int, val endPC: Int
    )
}
