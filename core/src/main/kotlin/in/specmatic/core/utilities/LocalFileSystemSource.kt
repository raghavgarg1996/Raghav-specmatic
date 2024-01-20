package `in`.specmatic.core.utilities

import `in`.specmatic.core.git.SystemGit
import `in`.specmatic.core.log.logger
import java.io.File

class LocalFileSystemSource(
    val directory: String,
    override val testContracts: List<String>,
    override val stubContracts: List<String>
) : ContractSource {
    override val type = "filesystem"

    override fun pathDescriptor(path: String): String = path

    override fun install(workingDirectory: File) {
        logger.log("No installation needed as this source is a directory on the local file system")
    }

    override fun directoryRelativeTo(workingDirectory: File): File {
        if(File(directory).isAbsolute)
            return File(directory)

        return workingDirectory.resolve(directory)
    }

    override fun getLatest(sourceGit: SystemGit) {
        logger.log("No need to get latest as this source is a directory on the local file system")
    }

    override fun pushUpdates(sourceGit: SystemGit) {
        logger.log("No need to push updates as this source is a directory on the local file system")
    }

    override fun loadContracts(
        selector: ContractsSelectorPredicate,
        workingDirectory: String,
        configFilePath: String
    ): List<ContractPathData> {
        return selector.select(this).map {
            val resolvedPath = File(directory).resolve(it)

            ContractPathData(
                directory,
                resolvedPath.path,
                provider = type,
                specificationPath = resolvedPath.canonicalPath,
            )
        }
    }
}