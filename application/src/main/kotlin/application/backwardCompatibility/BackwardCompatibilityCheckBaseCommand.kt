package application.backwardCompatibility

import io.specmatic.core.IFeature
import io.specmatic.core.Results
import io.specmatic.core.git.GitCommand
import io.specmatic.core.git.SystemGit
import io.specmatic.core.log.logger
import io.specmatic.core.utilities.exitWithMessage
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable
import java.util.regex.Pattern
import kotlin.system.exitProcess

abstract class BackwardCompatibilityCheckBaseCommand : Callable<Unit> {
    private val gitCommand: GitCommand = SystemGit()
    private val newLine = System.lineSeparator()

    @Option(names = ["--base-branch"], description = ["Base branch to compare the changes against"], required = false)
    var baseBranch: String? = null

    @Option(names = ["--target-path"], description = ["Specification file or folder to run the check against"], required = false)
    var targetPath: String = ""

    abstract fun checkBackwardCompatibility(oldFeature: IFeature, newFeature: IFeature): Results
    abstract fun File.isValidSpec(): Boolean
    abstract fun getFeatureFromSpecPath(path: String): IFeature

    abstract fun regexForMatchingReferred(schemaFileName: String): String
    abstract fun getSpecsOfChangedExternalisedExamples(
        filesChangedInCurrentBranch: Set<String>
    ): Set<String>

    open fun areExamplesValid(feature: IFeature, which: String): Boolean = true
    open fun getUnusedExamples(feature: IFeature): Set<String> = emptySet()

    final override fun call() {
        val filteredSpecs = getChangedSpecs(logSpecs = true)
        val result = try {
            runBackwardCompatibilityCheckFor(
                files = filteredSpecs,
                baseBranch = baseBranch()
            )
        } catch(e: Throwable) {
            logger.newLine()
            logger.newLine()
            logger.log(e)
            exitProcess(1)
        }

        println()
        println(result.report)
        exitProcess(result.exitCode)
    }

    fun getChangedSpecs(logSpecs: Boolean = false): Set<String> {
        val filesChangedInCurrentBranch = getChangedSpecsInCurrentBranch().filter {
            it.contains(targetPath)
        }.toSet()
        val filesReferringToChangedSchemaFiles = getSpecsReferringTo(filesChangedInCurrentBranch)
        val specificationsOfChangedExternalisedExamples =
            getSpecsOfChangedExternalisedExamples(filesChangedInCurrentBranch)

        if(logSpecs) {
            logFilesToBeCheckedForBackwardCompatibility(
                filesChangedInCurrentBranch,
                filesReferringToChangedSchemaFiles,
                specificationsOfChangedExternalisedExamples
            )
        }

        return filesChangedInCurrentBranch +
                filesReferringToChangedSchemaFiles +
                specificationsOfChangedExternalisedExamples
    }

    private fun getChangedSpecsInCurrentBranch(): Set<String> {
        return gitCommand.getFilesChangedInCurrentBranch(
            baseBranch()
        ).filter {
            File(it).exists() && File(it).isValidSpec()
        }.toSet().also {
            if(it.isEmpty()) exitWithMessage("${newLine}No specs were changed, skipping the check.$newLine")
        }
    }

    internal fun getSpecsReferringTo(schemaFiles: Set<String>): Set<String> {
        if (schemaFiles.isEmpty()) return emptySet()

        val inputFileNames = schemaFiles.map { File(it).name }
        val result = allSpecFiles().filter {
            it.readText().trim().let { specContent ->
                inputFileNames.any { inputFileName ->
                    val pattern = Pattern.compile("\\b${regexForMatchingReferred(inputFileName)}\\b")
                    val matcher = pattern.matcher(specContent)
                    matcher.find()
                }
            }
        }.map { it.path }.toSet()

        return result.flatMap {
            getSpecsReferringTo(setOf(it)).ifEmpty { setOf(it) }
        }.toSet()
    }

    internal fun allSpecFiles(): List<File> {
        return File(".").walk().toList().filterNot {
            ".git" in it.path
        }.filter { it.isFile && it.isValidSpec() }
    }

    private fun logFilesToBeCheckedForBackwardCompatibility(
        changedFiles: Set<String>,
        filesReferringToChangedFiles: Set<String>,
        specificationsOfChangedExternalisedExamples: Set<String>
    ) {

        println("Checking backward compatibility of the following files: $newLine")
        println("${ONE_INDENT}Files that have changed:")
        changedFiles.forEach { println(it.prependIndent(TWO_INDENTS)) }
        println()

        if(filesReferringToChangedFiles.isNotEmpty()) {
            println("${ONE_INDENT}Files referring to the changed files - ")
            filesReferringToChangedFiles.forEach { println(it.prependIndent(TWO_INDENTS)) }
            println()
        }

        if(specificationsOfChangedExternalisedExamples.isNotEmpty()) {
            println("${ONE_INDENT}Specifications whose externalised examples were changed - ")
            filesReferringToChangedFiles.forEach { println(it.prependIndent(TWO_INDENTS)) }
            println()
        }

        println("-".repeat(20))
        println()
    }

    private fun runBackwardCompatibilityCheckFor(files: Set<String>, baseBranch: String): CompatibilityReport {
        val branchWithChanges = gitCommand.currentBranch()
        val treeishWithChanges = if (branchWithChanges == HEAD) gitCommand.detachedHEAD() else branchWithChanges

        try {
            val results = files.mapIndexed { index, specFilePath ->
                var areLocalChangesStashed = false
                try {
                    println("${index.inc()}. Running the check for $specFilePath:")

                    // newer => the file with changes on the branch
                    val newer = getFeatureFromSpecPath(specFilePath)
                    val unusedExamples = getUnusedExamples(newer)

                    val olderFile = gitCommand.getFileInTheBaseBranch(
                        specFilePath,
                        treeishWithChanges,
                        baseBranch
                    )
                    if (olderFile == null) {
                        println("$specFilePath is a new file.$newLine")
                        return@mapIndexed CompatibilityResult.PASSED
                    }

                    areLocalChangesStashed = gitCommand.stash()
                    gitCommand.checkout(baseBranch)
                    // older => the same file on the default (e.g. main) branch
                    val older = getFeatureFromSpecPath(olderFile.path)

                    val backwardCompatibilityResult = checkBackwardCompatibility(older, newer)

                    if (backwardCompatibilityResult.success()) {
                        println(
                            "$newLine The file $specFilePath is backward compatible.$newLine".prependIndent(
                                MARGIN_SPACE
                            )
                        )
                        println()
                        var errorsFound = false

                        if(!areExamplesValid(newer, "newer")) {
                            println(
                                "$newLine *** Examples in $specFilePath are not valid. ***$newLine".prependIndent(
                                    MARGIN_SPACE
                                )
                            )
                            println()
                            errorsFound = true
                        }

                        if(unusedExamples.isNotEmpty()) {
                            println(
                                "$newLine *** Some examples for $specFilePath could not be loaded. ***$newLine".prependIndent(
                                    MARGIN_SPACE
                                )
                            )
                            println()
                            errorsFound = true
                        }

                        if(errorsFound) CompatibilityResult.FAILED
                        else CompatibilityResult.PASSED
                    } else {
                        println("$newLine ${backwardCompatibilityResult.report().prependIndent(
                            MARGIN_SPACE
                        )}")
                        println(
                            "$newLine *** The file $specFilePath is NOT backward compatible. ***$newLine".prependIndent(
                                MARGIN_SPACE
                            )
                        )
                        println()
                        CompatibilityResult.FAILED
                    }
                } finally {
                    gitCommand.checkout(treeishWithChanges)
                    if (areLocalChangesStashed) gitCommand.stashPop()
                }
            }

            return CompatibilityReport(results)
        } finally {
            gitCommand.checkout(treeishWithChanges)
        }
    }

    private fun baseBranch() = baseBranch ?: gitCommand.currentRemoteBranch()

    companion object {
        private const val HEAD = "HEAD"
        private const val MARGIN_SPACE = "  "
        private const val ONE_INDENT = "  "
        private const val TWO_INDENTS = "${ONE_INDENT}${ONE_INDENT}"
    }
}