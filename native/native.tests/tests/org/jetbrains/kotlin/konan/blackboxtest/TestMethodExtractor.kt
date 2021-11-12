/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import org.junit.jupiter.api.Assertions.assertEquals

internal fun TestExecutable.extractTestMethodNames(): Map<PackageName, List<String> {
    val process = ProcessBuilder(listOf(executableFile.path, "--ktest_list_tests"))
        .directory(executableFile.parentFile)
        .start()

    // TODO: log execution

    val exitCode = process.waitFor()
    assertEquals(0, exitCode) // TODO: describe why

    val stdOut = process.inputStream.bufferedReader().readText()


    TODO()
}
