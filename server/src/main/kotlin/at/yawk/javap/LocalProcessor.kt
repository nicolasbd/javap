/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package at.yawk.javap

import at.yawk.javap.model.ProcessingInput
import at.yawk.javap.model.ProcessingOutput
import com.google.common.annotations.VisibleForTesting
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.stream.Collectors
import java.util.stream.Stream
import javax.inject.Inject
import javax.ws.rs.BadRequestException

/**
 * @author yawkat
 */
@VisibleForTesting
internal const val NO_CLASSES_GENERATED = "No classes generated"

private inline fun <T, R> Stream<T>.use(f: (Stream<T>) -> R): R {
    try {
        return f(this)
    } finally {
        close()
    }
}

fun deleteRecursively(path: Path) {
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

class LocalProcessor @Inject constructor(val sdkProvider: SdkProvider, val firejail: Firejail) : Processor {
    override fun process(input: ProcessingInput): ProcessingOutput {
        val tempDirectory = Files.createTempDirectory(null)
        try {
            val sourceDir = tempDirectory.resolve("source")
            Files.createDirectories(sourceDir)
            val classDir = tempDirectory.resolve("classes")
            Files.createDirectories(classDir)

            val sdk = sdkProvider.sdks.find { it.name == input.compilerName }
                    ?: throw BadRequestException("Unknown compiler name")

            val sourceFile = sourceDir.resolve(when (sdk.language) {
                SdkLanguage.JAVA -> "Main.java"
                SdkLanguage.KOTLIN -> "Main.kt"
                SdkLanguage.SCALA -> "Main.scala"
            })

            Files.write(sourceFile, input.code.toByteArray(Charsets.UTF_8))

            val flags = when (sdk.language) {
                SdkLanguage.JAVA -> listOf(
                        "-encoding", "utf-8",
                        "-g" // debugging info
                )
                SdkLanguage.KOTLIN -> emptyList()
                SdkLanguage.SCALA -> emptyList()
            }

            val command =
                    sdk.compilerCommand +
                            flags +
                            listOf("-d", classDir.toAbsolutePath().toString(), sourceFile.fileName.toString())
            val javacResult = firejail.executeCommand(
                    command,
                    workingDir = sourceDir,
                    whitelist = listOf(tempDirectory),
                    readOnlyWhitelist = (if (sdk.baseDir == null) emptyList() else listOf(sdk.baseDir)) + sdk.hostJdk.path
            )

            var javapOutput: String? = javap(sdk.hostJdk, classDir)
            val procyonOutput: String?
            if (javapOutput == null) {
                if (javacResult.exitValue == 0) {
                    javapOutput = NO_CLASSES_GENERATED
                }
                procyonOutput = null
            } else {
                procyonOutput = decompile(classDir, Decompiler.PROCYON)
            }

            return ProcessingOutput(javacResult.outputUTF8(), javapOutput, procyonOutput)
        } finally {
            deleteRecursively(tempDirectory)
        }
    }

    private fun javap(jdk: Jdk, classDir: Path): String? {
        val classFiles = Files.list(classDir).use { // close stream
            it.sorted().map { it.fileName.toString() }.collect(Collectors.toList<String>())
        }
        return if (!classFiles.isEmpty()) {
            val javapOutput = firejail.executeCommand(
                    listOf(jdk.javap.toAbsolutePath().toString(),
                            "-v",
                            "-private",
                            "-constants",
                            "-XDdetails:stackMaps,localVariables") + classFiles,
                    classDir,
                    runInJail = false
            ).outputUTF8()
            javapOutput
                    .replace("Classfile .*\n  Last modified.*\n  MD5.*\n  ".toRegex(RegexOption.MULTILINE), "")
        } else null
    }

    private fun decompile(classDir: Path, decompiler: Decompiler): String {
        try {
            return decompiler.decompile(classDir)
        } catch(e: Throwable) {
            e.printStackTrace()
            return e.toString()
        }
    }
}