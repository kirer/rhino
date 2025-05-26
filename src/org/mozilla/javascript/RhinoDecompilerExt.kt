package org.mozilla.javascript

import org.mozilla.javascript.ast.*
import java.util.*

fun String.toCN(): String {
    return this.replace(Regex("(?<!\\\\)\\\\u([0-9A-Fa-f]{4})")) {
        String(Character.toChars(Integer.parseInt(it.groupValues[1], 16)))
    }
}

fun InterpreterData.dumpInfo() {
    Interpreter.dumpICode(this)
    println("\u001B[33m │[Size] ${this.itsICode.size} \u001B[0m")
    println("\u001B[33m │[Name] ${this.itsName} \u001B[0m")
    println("\u001B[33m │[paramCount] ${this.paramCount} \u001B[0m")
    println("\u001B[33m │[argNames] ${this.argNames.joinToString(",")} \u001B[0m")
    println("\u001B[33m │[itsStringTable] ${this.itsStringTable?.joinToString(",")} \u001B[0m")
    println("\u001B[33m │[literalIds] ${
        this.literalIds?.joinToString {
            "[${
                (it as? Array<*>)?.joinToString(
                    ","
                ).toString()
            }],"
        }
    } \u001B[0m")
    println("\u001B[33m │[itsNestedFunctions] ${this.itsNestedFunctions?.joinToString(",") { it?.itsName.toString() }} \u001B[0m")
    println("\u001B[33m │[itsBigIntTable] ${this.itsBigIntTable?.joinToString(",")} \u001B[0m")
    println("\u001B[33m │[itsDoubleTable] ${this.itsDoubleTable?.joinToString(",")} \u001B[0m")
    println("\u001B[33m │[itsRegExpLiterals] ${this.itsRegExpLiterals?.joinToString(",")} \u001B[0m")
    println("\u001B[33m │[itsTemplateLiterals] ${this.itsTemplateLiterals?.joinToString(",")} \u001B[0m")
    println("\u001B[33m │[itsExceptionTable] ${this.itsExceptionTable?.joinToString(",")} \u001B[0m")

//    println(this.toJsonWithNestedFunctions())
}

fun InterpreterData.findFunction(functionName: String): InterpreterData? {
    if (itsName == functionName) return this
    if (itsNestedFunctions.isNullOrEmpty()) return null
    for (function in itsNestedFunctions) {
        val result = function.findFunction(functionName)
        if (result != null) return result
    }
    return null
}

data class TryInfo(
    val tryStart: Int,
    val tryEnd: Int,
    val handlerStart: Int,
    val type: String,
    val exceptionLocal: Int
)

fun InterpreterData.getTryInfos(): List<TryInfo> {
    val infos = mutableListOf<TryInfo>()
    val table = itsExceptionTable
    if (table != null) {
        var i = 0
        while (i != table.size) {
            val tryStart = table[i + Interpreter.EXCEPTION_TRY_START_SLOT]
            val tryEnd = table[i + Interpreter.EXCEPTION_TRY_END_SLOT]
            val handlerStart = table[i + Interpreter.EXCEPTION_HANDLER_SLOT]
            val type = table[i + Interpreter.EXCEPTION_TYPE_SLOT]
            val exceptionLocal = table[i + Interpreter.EXCEPTION_LOCAL_SLOT]
            infos.add(
                TryInfo(
                    tryStart,
                    tryEnd,
                    handlerStart,
                    (if (type == 0) "catch" else "finally"),
                    exceptionLocal
                )
            )
            i += Interpreter.EXCEPTION_SLOT_SIZE
        }
    }
    return infos
}

fun Block.toList(): List<AstNode> {
    val list = mutableListOf<AstNode>()
    this.forEach {
        list.add(it as AstNode)
    }
    return list
}

fun Block.toStack(): Stack<AstNode> {
    val stack = Stack<AstNode>()
    this.forEach { stack.push(it as AstNode) }
    return stack
}

fun AstNode.wrapper(): AstNode {
    return when (this) {
        is IfStatement -> {
            if (null != thenPart) {
                val last = thenPart.lastChild
                if (last is Block) {
                    thenPart.removeChild(last)
                    if (last.firstChild is IfStatement) {
                        val elseifStatement = last.firstChild as AstNode
                        elsePart = elseifStatement
                        last.removeChild(elseifStatement)
                    } else {
                        elsePart = last
                    }
                }
            }
            if (null != thenPart && thenPart.length == 1 && null != elsePart && elsePart.length == 1 && (thenPart.firstChild != null && (thenPart.firstChild as AstNode).toSource()
                    .indexOf("\n") == -1 && (thenPart.firstChild as AstNode).toSource()
                    .indexOf(";") == -1
//                        && thenPart.firstChild !is ReturnStatement
//                        && thenPart.firstChild !is ExpressionStatement
//                        && thenPart.firstChild !is FunctionCall
//                        && thenPart.firstChild !is IfStatement
                        )
            ) {
                ConditionalExpression(position).also {
                    it.testExpression = condition
                    it.trueExpression =
                        if (thenPart.firstChild != null) thenPart.firstChild as AstNode else EmptyExpression()
                    it.falseExpression = elsePart.firstChild as AstNode
                }
            } else {
                this
            }
        }

        is ExpressionStatement -> {
            expression
        }

        is NewExpression -> {
            if (target is Name && (target as Name).identifier == "XML") {
                arguments[0]
            } else {
                this
            }
        }

        is PropertyGet -> this
        is InfixExpression -> ParenthesizedExpression(this)
        else -> this
    }
}

fun AstNode.wrapperCondition(): AstNode {
    return when (this) {
        is InfixExpression -> {
            this.left = this.left.wrapperCondition()
            this.right = this.right.wrapperCondition()
            this
        }

        is ExpressionStatement -> this.expression
        else -> this
    }
}

fun InfixExpression.wrapper(): InfixExpression {
    left?.apply {
        when (this) {
            is Block -> left = left.firstChild as AstNode
        }
    }
    right?.apply {
        when (this) {
            is Block -> right = right.firstChild as AstNode
        }
    }
    return this
}

