/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend.evaluate

import org.jetbrains.kotlin.ir.interpreter.EvaluationMode
import org.jetbrains.kotlin.ir.interpreter.IrCompileTimeChecker
import org.jetbrains.kotlin.ir.interpreter.IrInterpreter
import org.jetbrains.kotlin.ir.interpreter.toIrConst
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid

fun evaluateConstants(irModuleFragment: IrModuleFragment) {
    val irConstTransformer = IrConstTransformer(irModuleFragment)
    irModuleFragment.files.forEach { it.transformChildren(irConstTransformer, null) }
}

//TODO create abstract class that will be common for this and lowering
class IrConstTransformer(irModuleFragment: IrModuleFragment) : IrElementTransformerVoid() {
    private val interpreter = IrInterpreter(irModuleFragment)

    private fun IrExpression.replaceIfError(original: IrExpression): IrExpression {
        return if (this !is IrErrorExpression) this else original
    }

    override fun visitCall(expression: IrCall): IrExpression {
        if (expression.accept(IrCompileTimeChecker(mode = EvaluationMode.ONLY_BUILTINS), null)) {
            return interpreter.interpret(expression).replaceIfError(expression)
        }
        return expression
    }

    override fun visitField(declaration: IrField): IrStatement {
        transformAnnotations(declaration)

        val initializer = declaration.initializer
        val expression = initializer?.expression ?: return declaration
        if (expression is IrConst<*>) return declaration
        val isConst = declaration.correspondingPropertySymbol?.owner?.isConst == true
        if (isConst && expression.accept(IrCompileTimeChecker(declaration, mode = EvaluationMode.ONLY_BUILTINS), null)) {
            initializer.expression = interpreter.interpret(expression).replaceIfError(expression)
        }

        return declaration
    }

    override fun visitDeclaration(declaration: IrDeclaration): IrStatement {
        transformAnnotations(declaration)
        return super.visitDeclaration(declaration)
    }

    private fun transformAnnotations(annotationContainer: IrAnnotationContainer) {
        annotationContainer.annotations.forEach {
            for (i in 0 until it.valueArgumentsCount) {
                val arg = it.getValueArgument(i) ?: continue
                if (arg.accept(IrCompileTimeChecker(mode = EvaluationMode.ONLY_BUILTINS), null)) {
                    val const = interpreter.interpret(arg).replaceIfError(arg)
                    it.putValueArgument(i, const.convertToConstIfPossible(it.symbol.owner.valueParameters[i].type))
                }
            }
        }
    }

    private fun IrExpression.convertToConstIfPossible(type: IrType): IrExpression {
        if (this !is IrConst<*>) return this
        if (type.isArray()) return this.convertToConstIfPossible((type as IrSimpleType).arguments.single().typeOrNull!!)
        return this.value.toIrConst(type, this.startOffset, this.endOffset)
    }
}