/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.konan.blackboxtest.TestCompilationResult.Companion.assertSuccess
import org.jetbrains.kotlin.konan.blackboxtest.group.TestCaseGroupProvider
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

    private val cachedTestMethodNames = ThreadSafeCache<TestCompilationCacheKey, Map<PackageName, List<String>>?>()

    fun getSingleTestRun(testDataFile: File): TestRun {
        environment.assertNotDisposed()

        return withTestExecutable(testDataFile) { testCase, _, executable, _ ->
            val runParameters = getRunParameters(testCase, testMethodName = null)
            TestRun(executable, runParameters, testCase.origin)
        }
    }

    fun getTestRuns(testDataFile: File): List<TestRun> {
        environment.assertNotDisposed()

        return withTestExecutable(testDataFile) { testCase, testCaseGroup, executable, cacheKey ->
            val testMethodNamesInExecutable = cachedTestMethodNames.computeIfAbsent(cacheKey) {
                when (testCase.kind) {
                    TestKind.STANDALONE_NO_TR -> null
                    TestKind.STANDALONE, TestKind.REGULAR -> {
                        testCaseGroup.

                        executable.extractTestMethodNames()
                    }
                }
            }.orEmpty()

            val testMethodNamesInTestCase = testMethodNamesInExecutable[testCase.nominalPackageName]?.takeIf(Collection<*>::isNotEmpty)

            val runParametersForEveryRun = testMethodNamesInTestCase?.map { getRunParameters(testCase, testMethodName = it) }
                ?: listOf(getRunParameters(testCase, testMethodName = null))

            runParametersForEveryRun.map { runParameters -> TestRun(executable, runParameters, testCase.origin) }
        }
    }

    private fun <T> withTestExecutable(
        testDataFile: File,
        action: (TestCase, TestCaseGroup, TestExecutable, TestCompilationCacheKey) -> T
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

        return action(testCase, testCaseGroup, executable, cacheKey)
    }

    private fun getTestExecutable(testDataFile: File): Triple<TestCase, TestExecutable, TestCompilationCacheKey> {
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

        return Triple(testCase, executable, cacheKey)
    }

    private fun getRunParameters(testCase: TestCase, testMethodName: String?): List<TestRunParameter> =
        when (testCase.kind) {
            TestKind.STANDALONE_NO_TR -> {
                assertTrue(testMethodName == null)

                listOfNotNull(
                    testCase.extras!!.inputDataFile?.let(TestRunParameter::WithInputData),
                    testCase.expectedOutputDataFile?.let(TestRunParameter::WithExpectedOutputData)
                )
            }
            TestKind.STANDALONE -> listOfNotNull(
                TestRunParameter.WithGTestLogger,
                testMethodName?.let(TestRunParameter::WithMethodFilter),
                testCase.expectedOutputDataFile?.let(TestRunParameter::WithExpectedOutputData)
            )
            TestKind.REGULAR -> listOfNotNull(
                TestRunParameter.WithGTestLogger,
                testMethodName?.let(TestRunParameter::WithMethodFilter) ?: TestRunParameter.WithPackageFilter(testCase.nominalPackageName),
                testCase.expectedOutputDataFile?.let(TestRunParameter::WithExpectedOutputData)
            )
        }

    override fun close() {
        Disposer.dispose(environment)
    }

    private sealed class TestCompilationCacheKey {
        data class Standalone(val testDataFile: File) : TestCompilationCacheKey()
        data class Grouped(val testDataDir: File, val freeCompilerArgs: TestCompilerArgs) : TestCompilationCacheKey()
    }
}
