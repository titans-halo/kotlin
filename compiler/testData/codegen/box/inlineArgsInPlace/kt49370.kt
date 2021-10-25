// WITH_RUNTIME

fun box(): String {
    1L.mod("123a".indexOfAny("a".toCharArray()))
    return "OK"
}
