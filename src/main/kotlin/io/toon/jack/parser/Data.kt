package io.toon.jack.parser

import io.toon.jack.Kind
import io.toon.jack.SymbolTable
import io.toon.jack.parser.ClassVarStaticModifier.FIELD
import io.toon.jack.parser.Segment.*
import io.toon.jack.parser.SubroutineDeclarationType.CONSTRUCTOR


// @TODO have Node implement CodeGen
interface Node: XMLBuilder {}

data class ClassNode(
        val name: String,
        val classVarDeclarations: List<ClassVarDeclarationNode> = listOf(),
        val subroutineDeclarations: List<SubroutineDeclarationNode> = listOf()): Node, CodeGen {

    val fieldCount = classVarDeclarations.fold(0) { count, decl -> count + decl.fieldCount }

    override fun buildXML(): XML = xml("class") {
        keyword { "class" }
        identifier { name }
        symbol { "{" }
        classVarDeclarations.forEach { child { it } }
        subroutineDeclarations.forEach { child { it } }
        symbol { "}" }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, this) {
        subroutineDeclarations.forEach { addStatements(it) }
    }
}

data class ClassVarDeclarationNode(
        val staticModifier: ClassVarStaticModifier,
        val typeName: TypeName,
        val varNames: List<VarName>): Node {

    val fieldCount = if (staticModifier == FIELD) varNames.size else 0

    override fun buildXML(): XML = xml("classVarDec") {
        child { staticModifier }
        child { typeName }
        child { list(varNames) }
        symbol { ";" }
    }
}

enum class ClassVarStaticModifier(private val value: String): Node {
    STATIC("static"),
    FIELD("field");

    override fun buildXML(): XML = xml("keyword") { just { value } }
}

data class TypeName(val name: String): Node {
    private val tagName = if (name in listOf("void", "int", "char", "boolean")) "keyword" else "identifier"
    override fun buildXML(): XML = xml(tagName) { just { name } }
}

data class SubroutineDeclarationNode(
        val declarationType: SubroutineDeclarationType,
        val returnType: TypeName,
        val subroutineName: String,
        val parameterList: List<Parameter>,
        val body: SubroutineBodyNode
): Node, CodeGen {

    override fun buildXML(): XML = xml("subroutineDec") {
        child { declarationType }
        child { returnType }
        identifier { subroutineName }
        symbol { "(" }
        xml("parameterList") {
            if (parameterList.isNotEmpty()) child { list(parameterList) }
        }
        symbol { ")" }
        child { body }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {

        symbols.enterSubroutine(subroutineName)

        assert(classNode != null) { "Expected the classNode pased to generate the function name" }
        function("${classNode!!.name}.$subroutineName", body.varCount)
        if (declarationType == CONSTRUCTOR) {
            push(CONSTANT, classNode!!.fieldCount)
            call("Memory.alloc", 1)
            pop(POINTER, 0)
        }

        addStatements(body)

        symbols.leaveSubroutine()
    }
}

enum class SubroutineDeclarationType(val value: String): Node {
    CONSTRUCTOR("constructor"),
    FUNCTION("function"),
    METHOD("method");

    override fun buildXML(): XML = xml("keyword") { just { value } }
}

data class Parameter(
        val type: TypeName,
        val name: VarName
): Node {
    override fun buildXML(): XML = xmlList {
        child { type }
        child { name }
    }
}

data class SubroutineBodyNode(
        val varDeclarations: List<SubroutineVarDeclarationNode>,
        val statements: List<Statement>
): Node, CodeGen {

    val varCount = varDeclarations.fold(0) { total, declaration -> total + declaration.varCount }

    override fun buildXML(): XML = xml("subroutineBody") {
        symbol { "{" }

        varDeclarations.forEach { child { it } }

        xml("statements") {
            statements.forEach { child { it } }
        }

        symbol { "}" }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        statements.forEach { addStatements(it) }
    }
}

data class SubroutineVarDeclarationNode(
        val typeName: TypeName,
        val varNames: List<VarName>
): Node {

    val varCount: Int = varNames.size

    override fun buildXML(): XML = xml("varDec") {
        keyword { "var" }
        child { typeName }
        child { list(varNames) }
        symbol { ";" }

    }
}

interface Statement: Node, CodeGen

data class LetStatement private constructor(
        val varName: VarName?,
        val arrayAccess: ArrayAccess?,
        val rightExpression: Expression
): Statement {

    constructor(varName: VarName, rightExpression: Expression): this(varName, null, rightExpression)
    constructor(arrayAccess: ArrayAccess, rightExpression: Expression): this(null, arrayAccess, rightExpression)

    override fun buildXML(): XML {
        return xml("letStatement") {
            keyword { "let" }
            if (varName != null) {
                child { varName }
            } else {
                child { arrayAccess!! }
            }
            symbol { "=" }
            child { rightExpression }
            symbol { ";" }
        }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {

        addStatements(rightExpression)

        // @TODO array access
        if (varName != null) {
            // @TODO delegate to varName
            // @TODO better assertions
            val (_, type, kind, index) = symbols.get(varName!!.name)!!
            when(kind) {
                Kind.STATIC -> pop(STATIC, index)
                Kind.FIELD -> pop(THIS, index)
                Kind.ARGUMENT -> pop(ARGUMENT, index)
                Kind.VAR -> pop(LOCAL, index)
            }
        }
    }
}

data class IfStatement(
        val predicate: Expression,
        val statements: List<Statement>,
        val altStatements: List<Statement> = listOf()
): Statement {

    override fun buildXML(): XML = xml("ifStatement") {
        keyword { "if" }
        symbol { "(" }
        child { predicate }
        symbol { ")" }
        symbol { "{" }
        xml("statements") {
            statements.forEach { child { it } }
        }
        symbol { "}" }

        if (altStatements.isNotEmpty()) {
            keyword { "else" }
            symbol { "{" }
            xml("statements") {
                altStatements.forEach { child { it } }
            }
            symbol { "}" }
        }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        throw NotImplementedError()
    }
}

data class DoStatement(
        val subroutineCall: SubroutineCall
): Statement {
    override fun buildXML(): XML = xml("doStatement") {
        keyword { "do" }
        child { subroutineCall }
        symbol { ";" }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        addStatements(subroutineCall)
        pop(TEMP, 0)
    }
}

data class ReturnStatement(
        val expression: Expression? = null
): Statement {
    override fun buildXML(): XML = xml("returnStatement") {
        keyword { "return" }
        if (expression != null) child { expression }
        symbol { ";" }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        if (expression == null) push(CONSTANT, 0)
        else addStatements(expression)
        returnIt()
    }
}

data class WhileStatement(
        val predicate: Expression,
        val statements: List<Statement>
): Statement {
    override fun buildXML(): XML = xml("whileStatement") {
        keyword { "while" }
        symbol { "(" }
        child { predicate }
        symbol { ")" }
        symbol { "{" }
        xml("statements") {
            statements.forEach { child { it } }
        }
        symbol { "}" }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        throw NotImplementedError()
    }
}

data class Expression(
        val term: TermNode,
        val opTerm: OpTermNode? = null
): Node, CodeGen {

    override fun buildXML(): XML {
        return xml("expression") {
            xml("term") { child { term } }

            if (opTerm != null) { child { opTerm } }
        }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        addStatements(term)
        if (opTerm != null) addStatements(opTerm)
    }
}

data class OpTermNode(
    val operator: Operator,
    val term: TermNode
): Node, CodeGen {
    override fun buildXML(): XML = xmlList {
        child { operator }
        xml("term") { child { term } }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        addStatements(term)
        addStatements(operator)
    }
}

enum class Operator(val value: String): Node, CodeGen {
    PLUS("+") {
        override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
            add()
        }
    },
    MINUS("-") {
        override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
            throw NotImplementedError()
        }
    },
    MULTIPLY("*") {
        override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
            call("Math.multiply", 2)
        }
    },
    DIVIDED("/") {
        override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
            throw NotImplementedError()
        }
    },
    AND("&amp;") {
        override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
            throw NotImplementedError()
        }
    },
    OR("|") {
        override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
            throw NotImplementedError()
        }
    },
    LESS_THAN("&lt;") {
        override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
            throw NotImplementedError()
        }
    },
    GREATER_THAN("&gt;") {
        override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
            throw NotImplementedError()
        }
    },
    EQUALS("=") {
        override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
            throw NotImplementedError()
        }
    },
    NEGATE("~") {
        override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
            throw NotImplementedError()
        }
    };

    override fun buildXML(): XML = xml("symbol") { just { value } }
}

interface TermNode: Node, CodeGen

data class IntegerConstant(val value: Int): TermNode {
    override fun buildXML(): XML = xml("integerConstant") {
        just { value.toString() }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        push(CONSTANT, value)
    }
}

data class StringConstant(val value: String): TermNode {
    override fun buildXML(): XML = xml("stringConstant") {
        just { value  }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        throw NotImplementedError()
    }
}

data class KeywordConstant(val value: String): TermNode {
    override fun buildXML(): XML = xml("keyword") {
        just { value }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        // @TODO how do we know its only used as a termnode here?
        if (value == "this") push(POINTER, 0)
        else throw NotImplementedError()
    }
}

data class VarName(val name: String): TermNode {
    override fun buildXML(): XML = xml("identifier") { just { name } }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        // @TODO we dont know if its for accessing or assignment
        // @TODO better assertions
        val (_, type, kind, index) = symbols.get(name)!!

        when(kind) {
            Kind.STATIC -> push(STATIC, index)
            Kind.FIELD -> throw NotImplementedError("FIELd varName not implemeneted")
            Kind.ARGUMENT -> push(ARGUMENT, index)
            Kind.VAR -> push(LOCAL, index)
        }

    }
}

data class ArrayAccess(val varName: VarName, val expression: Expression): TermNode {
    override fun buildXML(): XML = xmlList {
        child { varName }
        symbol { "[" }
        child { expression }
        symbol { "]" }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        throw NotImplementedError()
    }
}

data class TermExpression(val expression: Expression): TermNode {
    override fun buildXML(): XML = xmlList {
        symbol { "(" }
        child { expression }
        symbol { ")" }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        // @TODO should change order of operations
        addStatements(expression)
    }
}

data class UnaryOp(val op: Operator, val term: TermNode): TermNode {
    override fun buildXML(): XML = xmlList {
        child { op }
        xml("term") {
            child { term }
        }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        addStatements(term)
        addStatements(op)
    }
}

interface SubroutineCall: TermNode, CodeGen

data class SimpleSubroutineCall(val subroutineName: String, val expressions: List<Expression> = listOf()): SubroutineCall {
    override fun buildXML(): XML = xmlList {
        identifier { subroutineName }
        symbol { "(" }
        xml("expressionList") {
            if (expressions.isNotEmpty()) child { list(expressions) }
        }
        symbol { ")" }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        expressions.forEach { addStatements(it) }
        call("subroutineName", expressions.size)
    }
}

data class ComplexSubroutineCall(val identifier_: String, val subroutineName: String, val expressions: List<Expression> = listOf()): SubroutineCall {
    override fun buildXML(): XML = xmlList {
        identifier { identifier_ }
        symbol { "." }
        identifier { subroutineName }
        symbol { "(" }
        xml("expressionList") {
            if (expressions.isNotEmpty()) child { list(expressions) }
        }
        symbol { ")" }
    }

    override fun genCode(symbols: SymbolTable, classNode: ClassNode?): List<String> = genVMCode(symbols, classNode) {
        expressions.forEach { addStatements(it) }
        call("$identifier_.$subroutineName", expressions.size)
    }
}

private fun list(items: List<Node>, separator: String = ","): XMLBuilder = object: XMLBuilder {
    override fun buildXML(): XML = xmlList {
        for ((index, name) in items.withIndex()) {
            child { name }
            if (index+1 != items.size) {
                symbol { separator }
            }
        }
    }
}