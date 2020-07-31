/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir

import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.utils.addToStdlib.sumByLong
import java.io.*
import java.nio.charset.StandardCharsets
import javax.tools.ToolProvider


class JavacModularizedTest : AbstractModularizedTest() {
    private val measure = FirResolveBench.Measure()
    private var totalLines = 0

    override fun beforePass() {
        measure.clear()
        totalLines = 0
    }

    override fun afterPass(pass: Int) {
        val statistics = FirResolveBench.TotalStatistics(0, 0, 0, 0, 0, 0, measure.files, totalLines, emptyMap(), mapOf("Javac" to measure))
        statistics.report(System.out, "Pass $pass")
    }

    override fun processModule(moduleData: ModuleData): ProcessorAction {
        val classpath = moduleData.classpath.joinToString(separator = ":")
        val sources = moduleData.sources.map { it.absolutePath }.toTypedArray()
        val outputDir = moduleData.outputDir.absolutePath
        val additionalOptions = "-source 1.8 -target 1.8 -proc:none -XDcompilePolicy=check -verbose".split(" ").toTypedArray()

        val output = ByteArrayOutputStream().use { byteArrayStream ->
            PrintStream(byteArrayStream, true, StandardCharsets.UTF_8.name()).use { printStream ->
                val before = vmStateSnapshot()
                val javac = ToolProvider.getSystemJavaCompiler()
                val result = javac.run(null, printStream, printStream, *additionalOptions, "-d", outputDir, "-cp", classpath, *sources)
                val after = vmStateSnapshot()
                val diff = after - before
                if (result != 0) return ProcessorAction.STOP

                measure.user += diff.userTime
                measure.cpu += diff.cpuTime
                measure.gcCollections += diff.gcInfo.values.sumBy { it.collections.toInt() }
                measure.gcTime += diff.gcInfo.values.sumByLong { it.gcTime }

                byteArrayStream.toString(StandardCharsets.UTF_8.name())
            }
        }

        val timeLime = output.split("\n").single { it.startsWith("[total") }
        val timeResult = timeLime.removePrefix("[total ").removeSuffix("ms]")
        measure.time += timeResult.toLong() * 1_000_000L
        measure.files += sources.size
        totalLines += sources.map { File(it).readLines().size }.sum()
        return ProcessorAction.NEXT
    }

    fun testJavac() {
        for (i in 0 until PASSES) {
            println("Pass $i")

            runTestOnce(i)
        }
        afterAllPasses()
    }
}
