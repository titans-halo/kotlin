/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.calls

import org.jetbrains.kotlin.analysis.api.ValidityTokenOwner
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.types.KtSubstitutor
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Call information at call site.
 */
public sealed class KtCallInfo : ValidityTokenOwner

/**
 * Successfully resolved call.
 */
public abstract class KtSuccessCallInfo : KtCallInfo() {
    public abstract val call: KtCall
}

/**
 * Call that contains errors.
 */
public abstract class KtErrorCallInfo : KtCallInfo() {
    public abstract val candidateCalls: List<KtCall>
}

/**
 * A call to a function, a simple/complex access to a property, or a simple/complex access through `get` and `set` convention.
 */
public sealed class KtCall : ValidityTokenOwner {
    /**
     * The substitutor to substitute type parameters in referenced symbols with types at the call site.
     */
    public abstract val substitutor: KtSubstitutor
}

/**
 * A callable symbol bound with receivers.
 */
// TODO: make this not abstract
public abstract class KtSymbolAndReceiver<out S : KtCallableSymbol> : ValidityTokenOwner {
    /**
     * The function or variable (property) declaration.
     */
    public abstract val symbol: KtCallableSymbol

    /**
     * The dispatch receiver for this symbol access. Dispatch receiver is available if the symbol is declared inside a class or object.
     */
    public abstract val dispatchReceiver: KtReceiverValue?

    /**
     * The extension receiver for this symbol access. Extension receiver is available if the symbol is declared with an extension receiver.
     */
    public abstract val extensionReceiver: KtReceiverValue?
}

/**
 * A call to a function, or a simple/complex access to a property.
 */
public sealed class KtCallableMemberCall : KtCall() {
    public abstract val symbolAndReceiver: KtSymbolAndReceiver<KtCallableSymbol>
}

/**
 * A call to a function.
 */
// TODO: make this not abstract
public abstract class KtFunctionCall : KtCallableMemberCall() {
    /**
     * The function and receivers for this call.
     */
    abstract override val symbolAndReceiver: KtSymbolAndReceiver<KtFunctionLikeSymbol>

    /**
     * The mapping from argument to parameter declaration. In case of vararg parameters, multiple arguments may be mapped to the same
     * `KtValueParameterSymbol`.
     */
    public abstract val argumentMapping: LinkedHashMap<KtExpression, KtValueParameterSymbol>

    /**
     * Whether this function call is an implicit invoke call on a value that has an `invoke` member function. See
     * https://kotlinlang.org/docs/operator-overloading.html#invoke-operator for more details.
     */
    public abstract val isImplicitInvoke: Boolean
}

/**
 * An access to variables (including properties). For example
 * ```
 * fun test() {
 *   var i = 0
 *   i += 1
 *   // symbolAndReceiver: {
 *   //   symbol: `i`
 *   //   dispatchReceiver: null
 *   //   extensionReceiver: null
 *   // }
 *   // accessType: OpAssign {
 *   //   kind: PLUS
 *   //   operand: 1
 *   //   operationSymbol: Int.plus()
 *   // }
 *
 *   i++
 *   // symbolAndReceiver: {
 *   //   symbol: `i`
 *   //   dispatchReceiver: null
 *   //   extensionReceiver: null
 *   // }
 *   // accessType: IncDec {
 *   //   kind: INC
 *   //   precedence: POSTFIX
 *   //   operationSymbol: Int.inc()
 *   // }
 * }
 * ```
 * Note that if the variable has a `<op>Assign` member, then it's represented as a simple `KtFunctionCall`. For example,
 * ```
 * fun test(m: MutableList<String>) {
 *   m += "a" // A simple `KtFunctionCall` to `MutableList.plusAssign`, not a `KtVariableAccessCall`. However, the dispatch receiver of this
 *            // call, `m`, is a simple read access represented as a `KtVariableAccessCall`
 * }
 * ```
 */
// TODO: make this not abstract
public abstract class KtVariableAccessCall : KtCallableMemberCall() {
    /**
     * The variable/property and receivers for this access.
     */
    abstract override val symbolAndReceiver: KtSymbolAndReceiver<KtVariableLikeSymbol>

    /**
     * The type of access to this property.
     */
    public abstract val accessType: KtAccessType
}

/**
 * A complex access using the array access convention. For example,
 * ```
 * fun test(m: MutableMap<String, String>) {
 *   m["a"] += "b"
 *   // indexArguments: ["a"]
 *   // getSymbolAndReceiver: {
 *   //   symbol: MutableMap.get()
 *   //   dispatchReceiver: `m`
 *   //   extensionReceiver: null
 *   // }
 *   // setSymbolAndReceiver: {
 *   //   symbol: MutableMap.set()
 *   //   dispatchReceiver: `m`
 *   //   extensionReceiver: null
 *   // }
 *   // accessType: OpAssign {
 *   //   kind: PLUS
 *   //   operand: "b"
 *   //   operationSymbol: String?.plus()
 *   // }
 * }
 * ```
 * Such a call always involve both calls to `get` and `set` functions. With the example above, a call to `String?.plus` is sandwiched
 * between `get` and `set` call to compute the new value passed to `set`.
 *
 * Note that simple access using the array access convention is not captured by this class. For example, assuming `ThrowingMap` throws
 * in case of absent key instead of returning `null`,
 * ```
 * fun test(m: ThrowingMap<String, MutableList<String>>) {
 *   m["a"] += "b"
 * }
 * ```
 * The above call is represented as a simple `KtFunctionCall` to `MutableList.plusAssign`, with the dispatch receiver referencing the
 * `m["a"]`, which is again a simple `KtFunctionCall` to `ThrowingMap.get`.
 */
// TODO: make this not abstract
public abstract class KtComplexArrayAccessCall : KtCall() {
    public abstract val indexArguments: List<KtExpression>

    /**
     * The `get` function that's invoked when reading values corresponding to the given [indexArguments].
     */
    public abstract val getSymbolAndReceiver: KtSymbolAndReceiver<KtFunctionSymbol>

    /**
     * The `set` function that's invoked when writing values corresponding to the given [indexArguments] and computed value from the
     * operation.
     */
    public abstract val setSymbolAndReceiver: KtSymbolAndReceiver<KtFunctionSymbol>

    /**
     * The type of this complex access.
     */
    public abstract val accessType: KtAccessType.Complex
}

/**
 * The type of access to a variable or using the array access convention.
 */
public sealed class KtAccessType {
    /**
     * A simple read access. For properties, this means a call to the property getter.
     */
    public object Read : KtAccessType()

    /**
     * A simple write access. For properties, this means a call to the property setter.
     */
    // TODO: make this not abstract
    public abstract class Write(public val value: KtExpression) : KtAccessType(), ValidityTokenOwner

    /**
     * A complex access that involves both read, write, and other operations.
     */
    public sealed class Complex : KtAccessType(), ValidityTokenOwner {
        public abstract val operationSymbol: KtFunctionSymbol
    }

    /**
     * A complex access that read, compute, and write the computed value back. Note that calls to `<op>Assign` is not represented by this.
     */
    // TODO: make this not abstract
    public abstract class OpAssign : Complex() {
        public abstract val kind: KtOpAssignKind
        public abstract val operand: KtExpression
    }

    /**
     * A complex access that read, increment or decrement, and write the computed value back.
     */
    // TODO: make this not abstract
    public abstract class IncDec : Complex() {
        public abstract val kind: KtIncDecKind
        public abstract val precedence: KtIncDecPrecedence
    }

    /**
     * This access needs to read the variable or invoke `get` for array access convention.
     */
    public fun hasRead(): Boolean = this !is Write
    /**
     * This access needs to write the variable or invoke `set` for array access convention.
     */
    public fun hasWrite(): Boolean = this != Read
}

public enum class KtOpAssignKind {
    PLUS, MINUS, MULTIPLY, DIVIDE, MODULO
}

public enum class KtIncDecKind {
    INC, DEC
}

public enum class KtIncDecPrecedence {
    PREFIX, POSTFIX
}

/**
 * A receiver value of a call.
 */
public sealed class KtReceiverValue : ValidityTokenOwner

/**
 * A explicit receiver. For example
 * ```
 *   "".length // explicit receiver `""`
 * ```
 */
public class KtExplicitReceiverValue(override val token: ValidityToken, private val _expression: KtExpression) : KtReceiverValue() {
    public val expression: KtExpression get() = withValidityAssertion { _expression }
}

/**
 * An implicit receiver. For example
 * ```
 * class A {
 *   val i: Int = 1
 *   fun test() {
 *     i // implicit receiver bound to class `A`
 *   }
 * }
 *
 * fun String.test() {
 *   length // implicit receiver bound to the `KtReceiverParameterSymbol` of type `String` declared by `test`.
 * }
 * ```
 */
public class KtImplicitReceiverValue(override val token: ValidityToken, private val _boundSymbol: KtSymbol) : KtReceiverValue() {
    public val boundSymbol: KtSymbol get() = withValidityAssertion { _boundSymbol }
}

/**
 * A smart-casted receiver. For example
 * ```
 * fun Any.test() {
 *   if (this is String) {
 *     length // smart-casted implicit receiver bound to the `KtReceiverParameterSymbol` of type `String` declared by `test`.
 *   }
 * }
 * ```
 */
public class KtSmartCastedReceiverValue(private val _original: KtReceiverValue, private val _smartCastType: KtType) : KtReceiverValue() {
    override val token: ValidityToken
        get() = _original.token
    public val original: KtReceiverValue get() = withValidityAssertion { _original }
    public val smartCastType: KtType get() = withValidityAssertion { _smartCastType }
}