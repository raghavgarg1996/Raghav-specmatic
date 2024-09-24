package application

import io.specmatic.core.Dictionary
import io.specmatic.core.Result
import io.specmatic.core.Results
import io.specmatic.core.examples.server.ExamplesInteractiveServer
import io.specmatic.core.log.*
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.utilities.exitWithMessage
import io.specmatic.mock.NoMatchingScenario
import io.specmatic.mock.loadDictionary
import picocli.CommandLine.*
import java.io.File
import java.lang.Thread.sleep
import java.util.concurrent.Callable
import kotlin.system.exitProcess

@Command(
    name = "examples",
    mixinStandardHelpOptions = true,
    description = ["Generate externalised JSON example files with API requests and responses"],
    subcommands = [ExamplesCommand.Validate::class, ExamplesCommand.Interactive::class]
)
class ExamplesCommand : Callable<Unit> {
    @Option(
        names = ["--filter-name"],
        description = ["Use only APIs with this value in their name"],
        defaultValue = "\${env:SPECMATIC_FILTER_NAME}"
    )
    var filterName: String = ""

    @Option(
        names = ["--filter-not-name"],
        description = ["Use only APIs which do not have this value in their name"],
        defaultValue = "\${env:SPECMATIC_FILTER_NOT_NAME}"
    )
    var filterNotName: String = ""

    @Option(
        names = ["--extensive"],
        description = ["Generate all examples (by default, generates one example per 2xx API)"],
        defaultValue = "false"
    )
    var extensive: Boolean = false

    @Parameters(index = "0", description = ["Contract file path"], arity = "0..1")
    var contractFile: File? = null

    @Option(names = ["--debug"], description = ["Debug logs"])
    var verbose = false

    @Option(names = ["--dictionary"], description = ["External Dictionary File Path, defaults to dictionary.json"])
    var dictFile: File? = null

    override fun call() {
        if (contractFile == null) {
            println("No contract file provided. Use a subcommand or provide a contract file. Use --help for more details.")
            return
        }
        if (!contractFile!!.exists())
            exitWithMessage("Could not find file ${contractFile!!.path}")

        configureLogger(this.verbose)

        val externalDictionary = getDictionaryPath(dictFile, contractFile)?.let {
            Dictionary(loadDictionary(it))
        } ?: Dictionary(emptyMap())

        try {
            ExamplesInteractiveServer.generate(
                contractFile!!,
                ExamplesInteractiveServer.ScenarioFilter(filterName, filterNotName),
                extensive,
                externalDictionary
            )
        } catch (e: Throwable) {
            logger.log(e)
            exitProcess(1)
        }
    }

    @Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = ["Validate the examples"]
    )
    class Validate : Callable<Unit> {
        @Option(names = ["--contract-file"], description = ["Contract file path"], required = true)
        lateinit var contractFile: File

        @Option(names = ["--example-file"], description = ["Example file path"], required = false)
        val exampleFile: File? = null

        @Option(names = ["--debug"], description = ["Debug logs"])
        var verbose = false

        @Option(
            names = ["--filter-name"],
            description = ["Validate examples of only APIs with this value in their name"],
            defaultValue = "\${env:SPECMATIC_FILTER_NAME}"
        )
        var filterName: String = ""

        @Option(
            names = ["--filter-not-name"],
            description = ["Validate examples of only APIs which do not have this value in their name"],
            defaultValue = "\${env:SPECMATIC_FILTER_NOT_NAME}"
        )
        var filterNotName: String = ""

        override fun call() {
            if (!contractFile.exists())
                exitWithMessage("Could not find file ${contractFile.path}")

            configureLogger(this.verbose)

            if (exampleFile != null) {
                try {
                    ExamplesInteractiveServer.validate(contractFile, exampleFile)
                    logger.log("The provided example ${exampleFile.name} is valid.")
                } catch (e: NoMatchingScenario) {
                    logger.log("The provided example ${exampleFile.name} is invalid. Reason:\n")
                    logger.log(e.msg ?: e.message ?: "")
                    exitProcess(1)
                }
            } else {
                val (internalResult, externalResults) = ExamplesInteractiveServer.validateAll(contractFile,
                    ExamplesInteractiveServer.ScenarioFilter(filterName, filterNotName)
                )

                val hasFailures = internalResult is Result.Failure || externalResults?.any { it.value is Result.Failure } == true

                if(hasFailures) {
                    logger.log("=============== Validation Results ===============")

                    if(internalResult != null) {
                        logger.log("      " + internalResult.reportString())
                        logger.log(System.lineSeparator() + "Inline example(s) had errors" + System.lineSeparator())
                    }

                    if(externalResults != null) {
                        externalResults.forEach { (exampleFileName, result) ->
                            if (!result.isSuccess()) {
                                logger.log(System.lineSeparator() + "Example File $exampleFileName has following validation error(s):")
                                logger.log(result.reportString())
                            }
                        }

                        logger.log("=============== Validation Summary ===============")
                        logger.log(Results(externalResults.values.toList()).summary())
                        logger.log("=======================================")
                    }
                }


                if (hasFailures) {
                    exitProcess(1)
                }
            }
        }
    }

    @Command(
        name = "interactive",
        mixinStandardHelpOptions = true,
        description = ["Run the example generation interactively"]
    )
    class Interactive : Callable<Unit> {
        @Option(names = ["--contract-file"], description = ["Contract file path"], required = false)
        var contractFile: File? = null

        @Option(
            names = ["--filter-name"],
            description = ["Use only APIs with this value in their name"],
            defaultValue = "\${env:SPECMATIC_FILTER_NAME}"
        )
        var filterName: String = ""

        @Option(
            names = ["--filter-not-name"],
            description = ["Use only APIs which do not have this value in their name"],
            defaultValue = "\${env:SPECMATIC_FILTER_NOT_NAME}"
        )
        var filterNotName: String = ""

        @Option(names = ["--debug"], description = ["Debug logs"])
        var verbose = false

        @Option(names = ["--dictionary"], description = ["External Dictionary File Path"])
        var dictFile: File? = null

        var server: ExamplesInteractiveServer? = null

        override fun call() {
            configureLogger(verbose)

            try {
                if (contractFile != null && !contractFile!!.exists())
                    exitWithMessage("Could not find file ${contractFile!!.path}")

                val externalDictionary = getDictionaryPath(dictFile, contractFile)?.let {
                    Dictionary(loadDictionary(it))
                } ?: Dictionary(emptyMap())

                println(getDictionaryPath(dictFile, contractFile))
                server = ExamplesInteractiveServer("0.0.0.0", 9001, contractFile, filterName, filterNotName, externalDictionary)
                addShutdownHook()

                consoleLog(StringLog("Examples Interactive server is running on http://0.0.0.0:9001/_specmatic/examples. Ctrl + C to stop."))
                while (true) sleep(10000)
            } catch (e: Exception) {
                logger.log(exceptionCauseMessage(e))
                exitWithMessage(e.message.orEmpty())
            }
        }

        private fun addShutdownHook() {
            Runtime.getRuntime().addShutdownHook(object : Thread() {
                override fun run() {
                    try {
                        println("Shutting down examples interactive server...")
                        server?.close()
                    } catch (e: InterruptedException) {
                        currentThread().interrupt()
                    } catch (e: Throwable) {
                        logger.log(e)
                    }
                }
            })
        }
    }
}

private fun configureLogger(verbose: Boolean) {
    val logPrinters = listOf(ConsolePrinter)

    logger = if (verbose)
        Verbose(CompositePrinter(logPrinters))
    else
        NonVerbose(CompositePrinter(logPrinters))
}

private fun getDictionaryPath(dictFile: File?, contractFile: File?): String? {
    return when {
        dictFile != null -> dictFile.path

        contractFile?.parentFile?.resolve("dictionary.json")?.exists() == true -> {
            contractFile.parentFile.resolve("dictionary.json").path
        }

        else -> {
            val currentDir = File(System.getProperty("user.dir"))
            currentDir.resolve("dictionary.json").takeIf { it.exists() }?.path
        }
    }
}