package com.printscript.domain

import ast.Expr
import interpreter.ExecutionContext
import interpreter.ExpressionHandler
import interpreter.Output
import interpreter.handlers.expressions.ReadInputExpressionHandler
import runtime.valuedataclass.Value

/**
 * readInput que lee el input pero no escribe el prompt en la salida.
 *
 * El handler de la libreria escribe el prompt de readInput("...") en el mismo Output que println.
 * En los tests solo hay que comparar lo que el programa imprime, asi que para correrlos se usa este
 * handler: delega en el original pero con un Output que descarta el prompt.
 */
class SilentPromptReadInputHandler(
    private val delegate: ExpressionHandler = ReadInputExpressionHandler(),
) : ExpressionHandler {
    override fun canHandle(expr: Expr): Boolean = delegate.canHandle(expr)

    override fun evaluate(expr: Expr, context: ExecutionContext, evaluate: (Expr) -> Value): Value =
        delegate.evaluate(expr, context.copy(output = DiscardOutput), evaluate)

    private object DiscardOutput : Output {
        override fun write(text: String) = Unit
    }
}
