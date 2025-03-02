// WITH_STDLIB
// WORKS_WHEN_VALUE_CLASS
// LANGUAGE: +ValueClasses, +GenericInlineClassParameter

OPTIONAL_JVM_INLINE_ANNOTATION
value class Z<T: Int>(val int: T)

OPTIONAL_JVM_INLINE_ANNOTATION
value class Str<T: String>(val string: T)

OPTIONAL_JVM_INLINE_ANNOTATION
value class NStr<T: String?>(val string: T)

fun <T: Int> fooZ(x: Z<T>) = x

fun <T: String> fooStr(x: Str<T>) = x

fun <T: String?> fooNStr(x: NStr<T>) = x


fun box(): String {
    val fnZ: (Z<Int>) -> Z<Int> = ::fooZ
    if (fnZ.invoke(Z(42)).int != 42) throw AssertionError()

    val fnStr: (Str<String>) -> Str<String> = ::fooStr
    if (fnStr.invoke(Str("str")).string != "str") throw AssertionError()

    val fnNStr: (NStr<String?>) -> NStr<String?> = ::fooNStr
    if (fnNStr.invoke(NStr(null)).string != null) throw AssertionError()
    if (fnNStr.invoke(NStr("nstr")).string != "nstr") throw AssertionError()

    return "OK"
}