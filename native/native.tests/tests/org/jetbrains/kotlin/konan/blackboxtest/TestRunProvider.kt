/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("KDocUnresolvedReference")

package org.jetbrains.kotlin.konan.blackboxtest

import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.konan.blackboxtest.TestCase.Extras.Companion.asStandaloneNoTestRunner
import org.jetbrains.kotlin.konan.blackboxtest.TestCase.Extras.Companion.asWithTestRunner
import org.jetbrains.kotlin.konan.blackboxtest.TestCompilationResult.Companion.assertSuccess
import org.jetbrains.kotlin.konan.blackboxtest.group.TestCaseGroupProvider
import org.jetbrains.kotlin.konan.blackboxtest.runner.AbstractRunner
import org.jetbrains.kotlin.konan.blackboxtest.runner.LocalTestRunner
import org.jetbrains.kotlin.konan.blackboxtest.util.ThreadSafeCache
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertTrue
import org.jetbrains.kotlin.test.services.JUnit5Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.File

internal class TestRunProvider(
    private val environment: TestEnvironment,
    private val testCaseGroupProvider: TestCaseGroupProvider
) : ExtensionContext.Store.CloseableResource {
    private val compilationFactory = TestCompilationFactory(environment)
    private val cachedCompilations = ThreadSafeCache<TestCompilationCacheKey, TestCompilation>()

    /**
     * Produces a single [TestRun] per testData file.
     *
     * If testData file contains multiple functions annotated with [kotlin.test.Test], then all these functions will be executed
     * in one shot. If either function will fail, the whole JUnit test will be considered as failed.
     *
     * Example:
     *   ///// testData file (bar.kt): /////
     *   @kotlin.test.Test
     *   fun one() { /* ... */ }
     *
     *   @kotlin.test.Test
     *   fun two() { /* ... */ }
     *
     *   ///// generated JUnit test suite: /////
     *   public class MyTest {
     *       @org.junit.jupiter.api.Test
     *       @org.jetbrains.kotlin.test.TestMetadata("bar.kt")
     *       public void testBar() {
     *           // Compiles bar.kt with test-runner, probably together with other testData files (baz.kt, qux.kt, ...).
     *           // Then executes BarKt.one() and BarKt.two() test functions in one shot.
     *           // If either of test functions fails, the whole "testBar()" JUnit test is marked as failed.
     *       }
     *   }
     */
    fun getSingleTestRun(testDataFile: File): TestRun {
        environment.assertNotDisposed()

        return withTestExecutable(testDataFile) { testCase, executable, _ ->
            val runParameters = getRunParameters(testCase, testFunction = null)
            TestRun(visibleName = null, executable, runParameters, testCase.origin)
        }
    }

    /**
     * Produces at least one [TestRun] per testData file.
     *
     * If testData file contains multiple functions annotated with [kotlin.test.Test], then a separate [TestRun] will be produced
     * for each function.
     *
     * This allows to have a better granularity in tests. So that every individual test method inside testData file will be considered
     * as an individual JUnit test, and will be included as a separate row in JUnit test report.
     *
     * Example:
     *   ///// testData file (bar.kt): /////
     *   @kotlin.test.Test
     *   fun one() { /* ... */ }
     *
     *   @kotlin.test.Test
     *   fun two() { /* ... */ }
     *
     *   ///// generated JUnit test suite: /////
     *   public class MyTest {
     *       @org.junit.jupiter.api.TestFactory
     *       @org.jetbrains.kotlin.test.TestMetadata("bar.kt")
     *       public List<org.junit.jupiter.api.DynamicTest> testBar() {
     *           // Compiles bar.kt with test-runner, probably together with other testData files (baz.kt, qux.kt, ...).
     *           // Then produces two instances of DynamicTest for BarKt.one() and BarKt.two() functions.
     *           // Each DynamicTest is executed and reported as a separate JUnit test.
     *           // So if BarKt.one() fails and BarKt.two() succeeds, then "testBar.one" JUnit test will be reported as failed,
     *           // and "testBar.two" will be reported as passed.
     *       }
     *   }
     */
    fun getTestRuns(testDataFile: File): List<TestRun> {
        environment.assertNotDisposed()

        return withTestExecutable(testDataFile) { testCase, executable, _ ->
            when (testCase.kind) {
                TestKind.STANDALONE_NO_TR -> {
                    val testRunName = testCase.extras.asStandaloneNoTestRunner().entryPoint.substringAfterLast('.')
                    val runParameters = getRunParameters(testCase, testFunction = null)
                    val testRun = TestRun(testRunName, executable, runParameters, testCase.origin)

                    listOf(testRun)
                }
                TestKind.REGULAR, TestKind.STANDALONE -> {
                    val testFunctionsInTestCase: List<TestFunction> = testCase.extras.asWithTestRunner().testFunctions
                        .flatMap { (packageFQN, functionNames) -> functionNames.map { TestFunction(packageFQN, it) } }

                    testFunctionsInTestCase.map { testFunction ->
                        val testRunName = testFunction.functionName
                        val runParameters = getRunParameters(testCase, testFunction = testFunction)

                        TestRun(testRunName, executable, runParameters, testCase.origin)
                    }
                }
            }
        }
    }

    private fun <T> withTestExecutable(
        testDataFile: File,
        action: (TestCase, TestExecutable, TestCompilationCacheKey) -> T
    ): T {
        val testDataDir = testDataFile.parentFile
        val testDataFileName = testDataFile.name

        val testCaseGroup = testCaseGroupProvider.getTestCaseGroup(testDataDir) ?: fail { "No test case for $testDataFile" }
        assumeTrue(testCaseGroup.isEnabled(testDataFileName), "Test case is disabled")

        val testCase = testCaseGroup.getByName(testDataFileName) ?: fail { "No test case for $testDataFile" }

        val cacheKey = when (testCase.kind) {
            TestKind.STANDALONE, TestKind.STANDALONE_NO_TR -> TestCompilationCacheKey.Standalone(testDataFile)
            TestKind.REGULAR -> TestCompilationCacheKey.Grouped(testDataDir, testCase.freeCompilerArgs)
        }

        val testCompilation = cachedCompilations.computeIfAbsent(cacheKey) {
            when (testCase.kind) {
                TestKind.STANDALONE, TestKind.STANDALONE_NO_TR -> {
                    // Create a separate compilation for each standalone test case.
                    compilationFactory.testCasesToExecutable(listOf(testCase))
                }
                TestKind.REGULAR -> {
                    // Group regular test cases by compiler arguments.
                    val testCases = testCaseGroup.getRegularOnlyByCompilerArgs(testCase.freeCompilerArgs)
                    assertTrue(testCases.isNotEmpty())
                    compilationFactory.testCasesToExecutable(testCases)
                }
            }
        }

        val (executableFile, loggedCompilerCall) = testCompilation.result.assertSuccess() // <-- Compilation happens here.
        val executable = TestExecutable(executableFile, loggedCompilerCall)

        return action(testCase, executable, cacheKey)
    }

    private fun getRunParameters(testCase: TestCase, testFunction: TestFunction?): List<TestRunParameter> = with(testCase) {
        when (kind) {
            TestKind.STANDALONE_NO_TR -> {
                assertTrue(testFunction == null)

                listOfNotNull(
                    extras.asStandaloneNoTestRunner().inputDataFile?.let(TestRunParameter::WithInputData),
                    expectedOutputDataFile?.let(TestRunParameter::WithExpectedOutputData)
                )
            }
            TestKind.STANDALONE -> listOfNotNull(
                TestRunParameter.WithGTestLogger,
                testFunction?.let { (packageFQN, functionName) -> TestRunParameter.WithFunctionFilter(packageFQN, functionName) },
                expectedOutputDataFile?.let(TestRunParameter::WithExpectedOutputData)
            )
            TestKind.REGULAR -> listOfNotNull(
                TestRunParameter.WithGTestLogger,
                testFunction?.let { (packageFQN, functionName) -> TestRunParameter.WithFunctionFilter(packageFQN, functionName) }
                    ?: TestRunParameter.WithPackageFilter(testCase.nominalPackageName),
                expectedOutputDataFile?.let(TestRunParameter::WithExpectedOutputData)
            )
        }
    }

    // Currently, only local test runner is supported.
    fun createRunner(testRun: TestRun): AbstractRunner<*> = when (val target = environment.globalEnvironment.target) {
        environment.globalEnvironment.hostTarget -> LocalTestRunner(testRun)
        else -> fail {
            """
                Running at non-host target is not supported yet.
                Compilation target: $target
                Host target: ${environment.globalEnvironment.hostTarget}
            """.trimIndent()
        }
    }

    override fun close() {
        Disposer.dispose(environment)
    }

    private sealed class TestCompilationCacheKey {
        data class Standalone(val testDataFile: File) : TestCompilationCacheKey()
        data class Grouped(val testDataDir: File, val freeCompilerArgs: TestCompilerArgs) : TestCompilationCacheKey()
    }

    private data class TestFunction(val packageFQN: PackageFQN, val functionName: String)
}
