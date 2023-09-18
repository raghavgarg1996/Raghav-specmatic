package `in`.specmatic.stub

data class StubRequestLog(
    val path: String?, val method: String?, val responseCode: Int, val sourceProvider: String? = null,
    val sourceRepository: String? = null,
    val sourceRepositoryBranch: String? = null,
    val specification: String? = null,
    val serviceType: String? = null
)

