/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import com.intellij.testFramework.TestDataFile
import org.jetbrains.kotlin.konan.blackboxtest.util.getAbsoluteFile
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(NativeBlackBoxTestSupport::class)
abstract class AbstractNativeBlackBoxTest {
    internal lateinit var testRunProvider: TestRunProvider

    fun runTest(@TestDataFile testDataFilePath: String) {
        val testRun = testRunProvider.getSingleTestRun(getAbsoluteFile(testDataFilePath))
        runTest(testRun)
    }

    fun dynamicTest(@TestDataFile testDataFilePath: String): List<DynamicNode> {
        val testRuns = testRunProvider.getTestRuns(getAbsoluteFile(testDataFilePath))

        return testRuns.mapIndexed { index, testRun ->
            val testName = testRun.visibleName ?: "test #$index"
            dynamicTest(testName) { runTest(testRun) }
        }
    }

    private fun runTest(testRun: TestRun) {
        val testRunner = testRunProvider.createRunner(testRun)
        testRunner.run()
    }
}
