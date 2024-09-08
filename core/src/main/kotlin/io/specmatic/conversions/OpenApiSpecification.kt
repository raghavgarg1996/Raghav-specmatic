package io.specmatic.conversions

import com.fasterxml.jackson.databind.node.ArrayNode
import io.specmatic.core.*
import io.specmatic.core.Result.Failure
import io.specmatic.core.log.LogStrategy
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.*
import io.specmatic.core.utilities.capitalizeFirstChar
import io.specmatic.core.value.*
import io.specmatic.core.wsdl.parser.message.MULTIPLE_ATTRIBUTE_VALUE
import io.specmatic.core.wsdl.parser.message.OCCURS_ATTRIBUTE_NAME
import io.specmatic.core.wsdl.parser.message.OPTIONAL_ATTRIBUTE_VALUE
import io.cucumber.messages.internal.com.fasterxml.jackson.databind.ObjectMapper
import io.cucumber.messages.types.Step
import io.ktor.util.reflect.*
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.examples.Example
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.*
import io.swagger.v3.oas.models.parameters.*
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import io.swagger.v3.parser.core.models.SwaggerParseResult
import java.io.File

private const val BEARER_SECURITY_SCHEME = "bearer"
const val SERVICE_TYPE_HTTP = "HTTP"

const val testDirectoryEnvironmentVariable = "SPECMATIC_TESTS_DIRECTORY"
const val testDirectoryProperty = "specmaticTestsDirectory"

const val NO_SECURITY_SCHEMA_IN_SPECIFICATION = "NO-SECURITY-SCHEME-IN-SPECIFICATION"

var missingRequestExampleErrorMessageForTest: String = "WARNING: Ignoring response example named %s for test or stub data, because no associated request example named %s was found."
var missingResponseExampleErrorMessageForTest: String = "WARNING: Ignoring request example named %s for test or stub data, because no associated response example named %s was found."

internal fun missingRequestExampleErrorMessageForTest(exampleName: String): String =
    missingRequestExampleErrorMessageForTest.format(exampleName, exampleName)

internal fun missingResponseExampleErrorMessageForTest(exampleName: String): String =
    missingResponseExampleErrorMessageForTest.format(exampleName, exampleName)

class OpenApiSpecification(
    private val openApiFilePath: String,
    private val parsedOpenApi: OpenAPI,
    private val sourceProvider: String? = null,
    private val sourceRepository: String? = null,
    private val sourceRepositoryBranch: String? = null,
    private val specificationPath: String? = null,
    private val securityConfiguration: SecurityConfiguration? = null,
    private val specmaticConfig: SpecmaticConfig = SpecmaticConfig()
) : IncludedSpecification, ApiSpecification {
    init {
        logger.log(openApiSpecificationInfo(openApiFilePath, parsedOpenApi))
    }

    companion object {

        fun fromFile(openApiFilePath: String, relativeTo: String = ""): OpenApiSpecification {
            val openApiFile = File(openApiFilePath).let { openApiFile ->
                if (openApiFile.isAbsolute) {
                    openApiFile
                } else {
                    File(relativeTo).canonicalFile.parentFile.resolve(openApiFile)
                }
            }

            return fromFile(openApiFile.canonicalPath)
        }

        fun fromFile(openApiFilePath: String): OpenApiSpecification {
            return fromFile(openApiFilePath, SpecmaticConfig())
        }

        fun fromFile(openApiFilePath: String, specmaticConfig: SpecmaticConfig): OpenApiSpecification {
            return OpenApiSpecification(openApiFilePath, getParsedOpenApi(openApiFilePath), specmaticConfig = specmaticConfig)
        }

        fun getParsedOpenApi(openApiFilePath: String): OpenAPI {
            return OpenAPIV3Parser().read(openApiFilePath, null, resolveExternalReferences())
        }

        fun isParsable(openApiFilePath: String): Boolean {
            return OpenAPIV3Parser().read(openApiFilePath, null, resolveExternalReferences()) != null
        }

        fun fromYAML(
            yamlContent: String,
            openApiFilePath: String,
            loggerForErrors: LogStrategy = logger,
            sourceProvider: String? = null,
            sourceRepository: String? = null,
            sourceRepositoryBranch: String? = null,
            specificationPath: String? = null,
            securityConfiguration: SecurityConfiguration? = null,
            specmaticConfig: SpecmaticConfig = SpecmaticConfig()
        ): OpenApiSpecification {
            val parseResult: SwaggerParseResult =
                OpenAPIV3Parser().readContents(yamlContent, null, resolveExternalReferences(), openApiFilePath)
            val parsedOpenApi: OpenAPI? = parseResult.openAPI

            if (parsedOpenApi == null) {
                logger.debug("Failed to parse OpenAPI from file $openApiFilePath\n\n$yamlContent")

                printMessages(parseResult, openApiFilePath, loggerForErrors)

                throw ContractException("Could not parse contract $openApiFilePath, please validate the syntax using https://editor.swagger.io")
            } else if (parseResult.messages?.isNotEmpty() == true) {
                logger.log("The OpenAPI file $openApiFilePath was read successfully but with some issues")

                printMessages(parseResult, openApiFilePath, loggerForErrors)
            }

            return OpenApiSpecification(
                openApiFilePath,
                parsedOpenApi,
                sourceProvider,
                sourceRepository,
                sourceRepositoryBranch,
                specificationPath,
                securityConfiguration,
                specmaticConfig
            )
        }

        private fun printMessages(parseResult: SwaggerParseResult, filePath: String, loggerForErrors: LogStrategy) {
            parseResult.messages.filterNotNull().let {
                if (it.isNotEmpty()) {
                    val parserMessages = parseResult.messages.joinToString(System.lineSeparator())
                    loggerForErrors.log("Error parsing file $filePath")
                    loggerForErrors.log(parserMessages.prependIndent("  "))
                }
            }
        }

        private fun resolveExternalReferences(): ParseOptions = ParseOptions().also { it.isResolve = true }
    }

    val patterns = mutableMapOf<String, Pattern>()

    fun isOpenAPI31(): Boolean {
        return parsedOpenApi.openapi.startsWith("3.1")
    }

    fun toFeature(): Feature {
        val name = File(openApiFilePath).name

        val (scenarioInfos, stubsFromExamples) = toScenarioInfos()

        return Feature(
            scenarioInfos.map { Scenario(it) }, name = name, path = openApiFilePath, sourceProvider = sourceProvider,
            sourceRepository = sourceRepository,
            sourceRepositoryBranch = sourceRepositoryBranch,
            specification = specificationPath,
            serviceType = SERVICE_TYPE_HTTP,
            stubsFromExamples = stubsFromExamples,
            specmaticConfig = specmaticConfig
        )
    }

    override fun toScenarioInfos(): Pair<List<ScenarioInfo>, Map<String, List<Pair<HttpRequest, HttpResponse>>>> {
        val (
            scenarioInfos: List<ScenarioInfo>,
            examplesAsExpectations: Map<String, List<Pair<HttpRequest, HttpResponse>>>
        ) = openApiToScenarioInfos()

        return scenarioInfos.filter { it.httpResponsePattern.status > 0 } to examplesAsExpectations
    }

    override fun matches(
        specmaticScenarioInfo: ScenarioInfo, steps: List<Step>
    ): List<ScenarioInfo> {
        val (openApiScenarioInfos, _) = openApiToScenarioInfos()
        if (openApiScenarioInfos.isEmpty() || steps.isEmpty()) return listOf(specmaticScenarioInfo)
        val result: MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> =
            specmaticScenarioInfo to openApiScenarioInfos to ::matchesPath then ::matchesMethod then ::matchesStatus then ::updateUrlMatcher otherwise ::handleError
        when (result) {
            is MatchFailure -> throw ContractException(result.error.message)
            is MatchSuccess -> return result.value.second
        }
    }

    private fun matchesPath(parameters: Pair<ScenarioInfo, List<ScenarioInfo>>): MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> {
        val (specmaticScenarioInfo, openApiScenarioInfos) = parameters

        // exact + exact   -> values should be equal
        // exact + pattern -> error
        // pattern + exact -> pattern should match exact
        // pattern + pattern -> both generated concrete values should be of same type

        val matchingScenarioInfos = specmaticScenarioInfo.matchesGherkinWrapperPath(openApiScenarioInfos, this)

        return when {
            matchingScenarioInfos.isEmpty() -> MatchFailure(
                Failure(
                    """Scenario: "${specmaticScenarioInfo.scenarioName}" PATH: "${
                        specmaticScenarioInfo.httpRequestPattern.httpPathPattern!!.generate(Resolver())
                    }" is not as per included wsdl / OpenApi spec"""
                )
            )

            else -> MatchSuccess(specmaticScenarioInfo to matchingScenarioInfos)
        }
    }

    override fun patternMatchesExact(
        wrapperURLPart: URLPathSegmentPattern,
        openapiURLPart: URLPathSegmentPattern,
        resolver: Resolver,
    ): Boolean {
        val valueFromWrapper = (wrapperURLPart.pattern as ExactValuePattern).pattern

        val valueToMatch: Value =
            if (valueFromWrapper is StringValue) {
                openapiURLPart.pattern.parse(valueFromWrapper.toStringLiteral(), resolver)
            } else {
                wrapperURLPart.pattern.pattern
            }

        return openapiURLPart.pattern.matches(valueToMatch, resolver) is Result.Success
    }

    override fun exactValuePatternsAreEqual(
        openapiURLPart: URLPathSegmentPattern,
        wrapperURLPart: URLPathSegmentPattern
    ) =
        (openapiURLPart.pattern as ExactValuePattern).pattern.toStringLiteral() == (wrapperURLPart.pattern as ExactValuePattern).pattern.toStringLiteral()

    private fun matchesMethod(parameters: Pair<ScenarioInfo, List<ScenarioInfo>>): MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> {
        val (specmaticScenarioInfo, openApiScenarioInfos) = parameters

        val matchingScenarioInfos =
            openApiScenarioInfos.filter { it.httpRequestPattern.method == specmaticScenarioInfo.httpRequestPattern.method }

        return when {
            matchingScenarioInfos.isEmpty() -> MatchFailure(
                Failure(
                    """Scenario: "${specmaticScenarioInfo.scenarioName}" METHOD: "${
                        specmaticScenarioInfo.httpRequestPattern.method
                    }" is not as per included wsdl / OpenApi spec"""
                )
            )

            else -> MatchSuccess(specmaticScenarioInfo to matchingScenarioInfos)
        }
    }

    private fun matchesStatus(parameters: Pair<ScenarioInfo, List<ScenarioInfo>>): MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> {
        val (specmaticScenarioInfo, openApiScenarioInfos) = parameters

        val matchingScenarioInfos =
            openApiScenarioInfos.filter { it.httpResponsePattern.status == specmaticScenarioInfo.httpResponsePattern.status }

        return when {
            matchingScenarioInfos.isEmpty() -> MatchFailure(
                Failure(
                    """Scenario: "${specmaticScenarioInfo.scenarioName}" RESPONSE STATUS: "${
                        specmaticScenarioInfo.httpResponsePattern.status
                    }" is not as per included wsdl / OpenApi spec"""
                )
            )

            else -> MatchSuccess(specmaticScenarioInfo to matchingScenarioInfos)
        }
    }

    private fun updateUrlMatcher(parameters: Pair<ScenarioInfo, List<ScenarioInfo>>): MatchingResult<Pair<ScenarioInfo, List<ScenarioInfo>>> {
        val (specmaticScenarioInfo, openApiScenarioInfos) = parameters

        return MatchSuccess(specmaticScenarioInfo to openApiScenarioInfos.map { openApiScenario ->
            val queryPattern = openApiScenario.httpRequestPattern.httpQueryParamPattern.queryPatterns
            val zippedPathPatterns =
                (specmaticScenarioInfo.httpRequestPattern.httpPathPattern?.pathSegmentPatterns ?: emptyList()).zip(
                    openApiScenario.httpRequestPattern.httpPathPattern?.pathSegmentPatterns ?: emptyList()
                )

            val pathPatterns = zippedPathPatterns.map { (fromWrapper, fromOpenApi) ->
                if (fromWrapper.pattern is ExactValuePattern)
                    fromWrapper
                else
                    fromOpenApi.copy(key = fromWrapper.key)
            }

            val httpPathPattern =
                HttpPathPattern(pathPatterns, openApiScenario.httpRequestPattern.httpPathPattern?.path ?: "")
            val httpQueryParamPattern = HttpQueryParamPattern(queryPattern)

            val httpRequestPattern = openApiScenario.httpRequestPattern.copy(
                httpPathPattern = httpPathPattern,
                httpQueryParamPattern = httpQueryParamPattern
            )
            openApiScenario.copy(httpRequestPattern = httpRequestPattern)
        })
    }

    data class RequestPatternsData(val requestPattern: HttpRequestPattern, val examples: Map<String, List<HttpRequest>>, val original: Pair<String, MediaType>? = null)

    private fun openApiToScenarioInfos(): Pair<List<ScenarioInfo>, Map<String, List<Pair<HttpRequest, HttpResponse>>>> {
        val data: List<Pair<List<ScenarioInfo>, Map<String, List<Pair<HttpRequest, HttpResponse>>>>> =
            openApiPaths().map { (openApiPath, pathItem) ->
                val scenariosAndExamples = openApiOperations(pathItem).map { (httpMethod, openApiOperation) ->
                    try {
                        openApiOperation.validateParameters()
                    } catch (e: ContractException) {
                        throw ContractException("In $httpMethod $openApiPath: ${e.message}")
                    }

                    val operation = openApiOperation.operation

                    val specmaticPathParam = toSpecmaticPathParam(openApiPath, operation)
                    val specmaticQueryParam = toSpecmaticQueryParam(operation)

                    val httpResponsePatterns: List<ResponsePatternData> =
                        attempt(breadCrumb = "$httpMethod $openApiPath -> RESPONSE") {
                            toHttpResponsePatterns(operation.responses)
                        }

                    val httpResponsePatternsGrouped = httpResponsePatterns.groupBy { it.responsePattern.status }

                    val httpRequestPatterns: List<RequestPatternsData> =
                        attempt("In $httpMethod $openApiPath request") {
                            toHttpRequestPatterns(
                                specmaticPathParam, specmaticQueryParam, httpMethod, operation
                            )
                        }

                    val httpRequestPatternDataGroupedByContentType = httpRequestPatterns.groupBy {
                        it.requestPattern.headersPattern.contentType
                    }

                    val requestMediaTypes = httpRequestPatternDataGroupedByContentType.keys

                    val requestResponsePairs = httpResponsePatternsGrouped.flatMap { (status, responses) ->
                        val responsesGrouped = responses.groupBy {
                            it.responsePattern.headersPattern.contentType
                        }

                        if (responsesGrouped.keys.filterNotNull().toSet() == requestMediaTypes.filterNotNull().toSet()) {
                            responsesGrouped.map { (contentType, responsesData) ->
                                httpRequestPatternDataGroupedByContentType.getValue(contentType)
                                    .single() to responsesData.single()
                            }
                        } else {
                            responses.flatMap { responsePatternData ->
                                httpRequestPatterns.map { requestPatternData ->
                                    requestPatternData to responsePatternData
                                }
                            }
                        }

                    }

                    val scenarioInfos = requestResponsePairs.map { (requestPatternData, responsePatternData) ->
                        val (httpRequestPattern, requestExamples: Map<String, List<HttpRequest>>, openApiRequest) = requestPatternData
                        val (response, responseMediaType: MediaType, httpResponsePattern, responseExamples: Map<String, HttpResponse>) = responsePatternData

                        val specmaticExampleRows: List<Row> =
                            testRowsFromExamples(responseExamples, requestExamples, operation, openApiRequest)
                        val scenarioName = scenarioName(operation, response, httpRequestPattern)

                        val ignoreFailure = operation.tags.orEmpty().map { it.trim() }.contains("WIP")

                        val rowsToBeUsed: List<Row> = specmaticExampleRows

                        ScenarioInfo(
                            scenarioName = scenarioName,
                            patterns = patterns.toMap(),
                            httpRequestPattern = httpRequestPattern,
                            httpResponsePattern = httpResponsePattern,
                            ignoreFailure = ignoreFailure,
                            examples = rowsToExamples(rowsToBeUsed),
                            sourceProvider = sourceProvider,
                            sourceRepository = sourceRepository,
                            sourceRepositoryBranch = sourceRepositoryBranch,
                            specification = specificationPath,
                            serviceType = SERVICE_TYPE_HTTP
                        )
                    }

                    val scenarioInfos2 =
                        httpResponsePatterns.map { (response, responseMediaType: MediaType, httpResponsePattern, responseExamples: Map<String, HttpResponse>) ->

                            httpRequestPatterns.map { (httpRequestPattern, requestExamples: Map<String, List<HttpRequest>>, openApiRequest) ->
                                val specmaticExampleRows: List<Row> =
                                    testRowsFromExamples(responseExamples, requestExamples, operation, openApiRequest)
                                val scenarioName = scenarioName(operation, response, httpRequestPattern)

                                val ignoreFailure = operation.tags.orEmpty().map { it.trim() }.contains("WIP")

                                val rowsToBeUsed: List<Row> = specmaticExampleRows

                                ScenarioInfo(
                                    scenarioName = scenarioName,
                                    patterns = patterns.toMap(),
                                    httpRequestPattern = httpRequestPattern,
                                    httpResponsePattern = httpResponsePattern,
                                    ignoreFailure = ignoreFailure,
                                    examples = rowsToExamples(rowsToBeUsed),
                                    sourceProvider = sourceProvider,
                                    sourceRepository = sourceRepository,
                                    sourceRepositoryBranch = sourceRepositoryBranch,
                                    specification = specificationPath,
                                    serviceType = SERVICE_TYPE_HTTP
                                )
                            }
                        }.flatten()

                    val requestExamples = httpRequestPatterns.map {
                        it.examples
                    }.foldRight(emptyMap<String, List<HttpRequest>>()) { acc, map ->
                        acc.plus(map)
                    }

                    val responseExamplesList = httpResponsePatterns.map { it.examples }

                    val examples =
                        collateExamplesForExpectations(requestExamples, responseExamplesList)

                    val requestExampleNames = requestExamples.keys

                    val usedExamples = examples.keys

                    val unusedRequestExampleNames = requestExampleNames - usedExamples

                    val responseThatReturnsNoValues = httpResponsePatterns.find { responsePatternData ->
                        responsePatternData.let {
                            it.responsePattern.status == 204 && it.responsePattern.headersPattern.isEmpty()
                        }
                    }

                    val (additionalExamples, updatedScenarios) = if(responseThatReturnsNoValues != null && unusedRequestExampleNames.isNotEmpty()) {
                        val empty204Response = HttpResponse(204)
                        val examplesOfResponseThatReturnsNoValues: Map<String, List<Pair<HttpRequest, HttpResponse>>> = requestExamples.filterKeys { it in unusedRequestExampleNames }.mapValues { (key, examples) ->
                            examples.map { it to empty204Response }
                        }

                        val updatedScenarioInfos = scenarioInfos.map { scenarioInfo ->
                            if(scenarioInfo.httpResponsePattern.status == 204) {
                                val unusedRequestExample = requestExamples.filter { it.key in unusedRequestExampleNames }

                                val rows = unusedRequestExample.flatMap { (key, requests) ->
                                    requests.map { request ->
                                        val paramExamples = (request.headers + request.queryParams.asMap()).toList()

                                        val allExamples = if(scenarioInfo.httpRequestPattern.body is NoBodyPattern) {
                                            paramExamples
                                        } else
                                            listOf("(REQUEST-BODY)" to request.body.toStringLiteral()) + paramExamples

                                        Row(
                                            name = key,
                                            columnNames = allExamples.map { it.first },
                                            values = allExamples.map { it.second }
                                        )
                                    }
                                }

                                val updatedExamples: List<Examples> = listOf(Examples(rows.first().columnNames, scenarioInfo.examples.firstOrNull()?.rows.orEmpty() + rows))

                                scenarioInfo.copy(
                                    examples = updatedExamples
                                )
                            } else
                                scenarioInfo
                        }

                        examplesOfResponseThatReturnsNoValues to updatedScenarioInfos
                    } else
                        emptyMap<String, List<Pair<HttpRequest, HttpResponse>>>() to scenarioInfos

                    Triple(updatedScenarios, examples + additionalExamples, requestExampleNames)
                }

                val requestExampleNames = scenariosAndExamples.flatMap { it.third }.toSet()

                val usedExamples = scenariosAndExamples.flatMap { it.second.keys }.toSet()

                val unusedRequestExampleNames = requestExampleNames - usedExamples

                unusedRequestExampleNames.forEach { unusedRequestExampleName ->
                    logger.log(missingResponseExampleErrorMessageForTest(unusedRequestExampleName))
                }

                scenariosAndExamples.map {
                    it.first to it.second
                }
            }.flatten()


        val scenarioInfos = data.map { it.first }.flatten()
        val examples: Map<String, List<Pair<HttpRequest, HttpResponse>>> =
            data.map { it.second }.foldRight(emptyMap()) { acc, map ->
                acc.plus(map)
            }

        logger.newLine()

        return scenarioInfos to examples
    }

    private fun validateParameters(parameters: List<Parameter>?) {
        parameters.orEmpty().forEach { parameter ->
            if(parameter.name == null)
                throw ContractException("A parameter does not have a name.")

            if(parameter.schema == null)
                throw ContractException("A parameter does not have a schema.")

            if(parameter.schema.type == "array" && parameter.schema.items == null)
                throw ContractException("A parameter of type \"array\" has not defined \"items\".")

        }
    }

    private fun collateExamplesForExpectations(
        requestExamples: Map<String, List<HttpRequest>>,
        responseExamplesList: List<Map<String, HttpResponse>>
    ): Map<String, List<Pair<HttpRequest, HttpResponse>>> {
        return responseExamplesList.flatMap { responseExamples ->
            responseExamples.filter { (key, _) ->
                key in requestExamples
            }.map { (key, responseExample) ->
                key to requestExamples.getValue(key).map { it to responseExample }
            }
        }.toMap()
    }

    private fun scenarioName(
        operation: Operation,
        response: ApiResponse,
        httpRequestPattern: HttpRequestPattern
    ): String = operation.summary?.let {
        """${operation.summary}. Response: ${response.description}"""
    } ?: "${httpRequestPattern.testDescription()}. Response: ${response.description}"

    private fun rowsToExamples(specmaticExampleRows: List<Row>): List<Examples> =
        when (specmaticExampleRows) {
            emptyList<Row>() -> emptyList()
            else -> {
                val examples = Examples(
                    specmaticExampleRows.first().columnNames,
                    specmaticExampleRows
                )

                listOf(examples)
            }
        }

    private fun testRowsFromExamples(
        responseExamples: Map<String, HttpResponse>,
        requestExampleAsHttpRequests: Map<String, List<HttpRequest>>,
        operation: Operation,
        openApiRequest: Pair<String, MediaType>?
    ): List<Row> {

        return responseExamples.mapNotNull { (exampleName, responseExample) ->
            val parameterExamples: Map<String, Any> = parameterExamples(operation, exampleName)

            val requestBodyExample: Map<String, Any> =
                requestBodyExample(openApiRequest, exampleName, operation.summary)

            val requestExamples = parameterExamples.plus(requestBodyExample).map { (key, value) ->
                if (value.toString().contains("externalValue")) "${key}_filename" to value
                else key to value
            }.toMap()

            if (requestExamples.isEmpty()) {
                logger.log(missingRequestExampleErrorMessageForTest(exampleName))
                return@mapNotNull null
            }

            val resolvedResponseExample: ResponseExample? =
                when {
                    specmaticConfig.isResponseValueValidationEnabled() ->
                        ResponseValueExample(responseExample)

                    else ->
                        null
                }

            Row(
                requestExamples.keys.toList().map { keyName: String -> keyName },
                requestExamples.values.toList().map { value: Any? -> value?.toString() ?: "" }
                    .map { valueString: String ->
                        if (valueString.contains("externalValue")) {
                            ObjectMapper().readValue(valueString, Map::class.java).values.first()
                                .toString()
                        } else valueString
                    },
                name = exampleName,
                responseExampleForValidation = if(resolvedResponseExample != null && responseExample.isNotEmpty()) resolvedResponseExample else null,
                requestExample = requestExampleAsHttpRequests[exampleName]?.first(),
                responseExample = responseExample
            )
        }
    }

    data class OperationIdentifier(val requestMethod: String, val requestPath: String, val responseStatus: Int, val requestContentType: String?, val responseContentType: String?)

    private fun requestBodyExampleNames(
        openApiRequest: Pair<String, MediaType>?,
    ): Set<String> {
        if(openApiRequest == null)
            return emptySet()

        val (_, requestBodyMediaType) = openApiRequest

        val requestExampleValue =
            requestBodyMediaType.examples.orEmpty().keys

        return requestExampleValue
    }

    private fun requestBodyExample(
        openApiRequest: Pair<String, MediaType>?,
        exampleName: String,
        operationSummary: String?
    ): Map<String, Any> {
        if(openApiRequest == null)
            return emptyMap()

        val (requestBodyContentType, requestBodyMediaType) = openApiRequest

        val requestExampleValue: Any? =
            resolveExample(requestBodyMediaType.examples?.get(exampleName))?.value

        val requestBodyExample: Map<String, Any> = if (requestExampleValue != null) {
            if (requestBodyContentType == "application/x-www-form-urlencoded" || requestBodyContentType == "multipart/form-data") {
                val operationSummaryClause = operationSummary?.let { "for operation \"${operationSummary}\"" } ?: ""
                val jsonExample =
                    attempt("Could not parse example $exampleName$operationSummaryClause") {
                        parsedJSON(requestExampleValue.toString()) as JSONObjectValue
                    }
                jsonExample.jsonObject.map { (key, value) ->
                    key to value.toString()
                }.toMap()
            } else {
                mapOf("(REQUEST-BODY)" to requestExampleValue)
            }
        } else {
            emptyMap()
        }
        return requestBodyExample
    }

    private fun resolveExample(example: Example?): Example? {
        return example?.`$ref`?.let {
            val exampleName = it.substringAfterLast("/")
            parsedOpenApi.components?.examples?.get(exampleName)
        } ?: example
    }

    private fun parameterExamples(
        operation: Operation,
        exampleName: String
    ): Map<String, Any> = operation.parameters.orEmpty()
        .filter { parameter ->
            parameter.examples.orEmpty().any { it.key == exampleName }
        }.associate {
            val exampleValue: Example = it.examples[exampleName]
                ?: throw ContractException("The value of ${it.name} in example $exampleName was unexpectedly found to be null.")

            it.name to (resolveExample(exampleValue)?.value ?: "")
        }

    private fun openApiPaths() = parsedOpenApi.paths.orEmpty()

    private fun isNumber(value: String): Boolean {
        return value.toIntOrNull() != null
    }

    private fun toHttpResponsePatterns(responses: ApiResponses?): List<ResponsePatternData> {
        return responses.orEmpty().map { (status, response) ->
            val headersMap = openAPIHeadersToSpecmatic(response)
            if(!isNumber(status) && status != "default")
                throw ContractException("Response status codes are expected to be numbers, but \"$status\" was found")

            attempt(breadCrumb = status) { openAPIResponseToSpecmatic(response, status, headersMap) }
        }.flatten()
    }

    private fun openAPIHeadersToSpecmatic(response: ApiResponse) =
        response.headers.orEmpty().map { (headerName, header) ->
            toSpecmaticParamName(header.required != true, headerName) to toSpecmaticPattern(
                resolveResponseHeader(header)?.schema ?: throw ContractException(
                    headerComponentMissingError(
                        headerName,
                        response
                    )
                ), emptyList()
            )
        }.toMap()

    data class ResponsePatternData(
        val response: ApiResponse,
        val mediaType: MediaType,
        val responsePattern: HttpResponsePattern,
        val examples: Map<String, HttpResponse>
    )

    private fun headerComponentMissingError(headerName: String, response: ApiResponse): String {
        if (response.description != null) {
            return "Header component not found for header $headerName in response \"${response.description}\""
        }

        return "Header component not found for header $headerName"
    }

    private fun resolveResponseHeader(header: Header): Header? {
        return if (header.`$ref` != null) {
            val headerComponentName = header.`$ref`.substringAfterLast("/")
            parsedOpenApi.components?.headers?.get(headerComponentName)
        } else {
            header
        }
    }

    private fun openAPIResponseToSpecmatic(
        response: ApiResponse,
        status: String,
        headersMap: Map<String, Pattern>
    ): List<ResponsePatternData> {
        if (response.content == null || response.content.isEmpty()) {
            val responsePattern = HttpResponsePattern(
                headersPattern = HttpHeadersPattern(headersMap),
                status = status.toIntOrNull() ?: DEFAULT_RESPONSE_CODE
            )

            return listOf(ResponsePatternData(response, MediaType(), responsePattern, emptyMap()))
        }

        val headerExamples =
            response.headers.orEmpty().entries.fold(emptyMap<String, Map<String, String>>()) { acc, (headerName, header) ->
                extractParameterExamples(header.examples, headerName, acc)
            }

        return response.content.map { (contentType, mediaType) ->
            val responsePattern = HttpResponsePattern(
                headersPattern = HttpHeadersPattern(headersMap, contentType = contentType),
                status = if (status == "default") 1000 else status.toInt(),
                body = when (contentType) {
                    "application/xml" -> toXMLPattern(mediaType)
                    else -> toSpecmaticPattern(mediaType, "response")
                }
            )

            val exampleBodies: Map<String, String?> = mediaType.examples?.mapValues {
                resolveExample(it.value)?.value?.toString() ?: ""
            } ?: emptyMap()

            val examples: Map<String, HttpResponse> =
                when (status.toIntOrNull()) {
                    0, null -> emptyMap()
                    else -> exampleBodies.map {
                        it.key to HttpResponse(
                            status.toInt(),
                            body = it.value ?: "",
                            headers = headerExamples[it.key] ?: emptyMap()
                        )
                    }.toMap()
                }

            ResponsePatternData(response, mediaType, responsePattern, examples)
        }
    }

    private fun toHttpRequestPatterns(
        httpPathPattern: HttpPathPattern,
        httpQueryParamPattern: HttpQueryParamPattern,
        httpMethod: String,
        operation: Operation
    ): List<RequestPatternsData> {

        val securitySchemes: Map<String, OpenAPISecurityScheme> =
            parsedOpenApi.components?.securitySchemes?.mapValues { (schemeName, scheme) ->
                toSecurityScheme(schemeName, scheme)
            } ?: mapOf(NO_SECURITY_SCHEMA_IN_SPECIFICATION to NoSecurityScheme())

        val securitySchemesForRequestPattern: Map<String, OpenAPISecurityScheme> =
            (parsedOpenApi.security.orEmpty() + operation.security.orEmpty())
                .flatMap { it.keys }
                .toSet()
                .map {
                    val securityScheme = securitySchemes[it]
                        ?: throw ContractException("Security scheme used in $httpMethod ${httpPathPattern.path} does not exist in the spec")
                    it to securityScheme
                }
            .toMap().ifEmpty {
                    mapOf(NO_SECURITY_SCHEMA_IN_SPECIFICATION to NoSecurityScheme())
                }

        val parameters = operation.parameters

        val headersMap = parameters.orEmpty().filterIsInstance<HeaderParameter>().associate {
            toSpecmaticParamName(it.required != true, it.name) to toSpecmaticPattern(it.schema, emptyList())
        }

        val headersPattern = HttpHeadersPattern(headersMap)
        val requestPattern = HttpRequestPattern(
            httpPathPattern = httpPathPattern,
            httpQueryParamPattern = httpQueryParamPattern,
            method = httpMethod,
            headersPattern = headersPattern,
            securitySchemes = operationSecuritySchemes(operation, securitySchemesForRequestPattern)
        )

        val exampleQueryParams = namedExampleParams(operation, QueryParameter::class.java)
        val examplePathParams = namedExampleParams(operation, PathParameter::class.java)
        val exampleHeaderParams = namedExampleParams(operation, HeaderParameter::class.java)

        val exampleRequestBuilder = ExampleRequestBuilder(
            examplePathParams,
            exampleHeaderParams,
            exampleQueryParams,
            httpPathPattern,
            httpMethod,
            securitySchemesForRequestPattern
        )

        val requestBody = resolveRequestBody(operation)
            ?: return listOf(
                RequestPatternsData(
                    requestPattern.copy(body = NoBodyPattern),
                    exampleRequestBuilder.examplesBasedOnParameters
                )
            )

        return requestBody.content.map { (contentType, mediaType) ->
            when (contentType.lowercase()) {
                "multipart/form-data" -> {
                    val partSchemas = if (mediaType.schema.`$ref` == null) {
                        mediaType.schema
                    } else {
                        resolveReferenceToSchema(mediaType.schema.`$ref`).second
                    }

                    val parts: List<MultiPartFormDataPattern> =
                        partSchemas.properties.map { (partName, partSchema) ->
                            val partContentType = mediaType.encoding?.get(partName)?.contentType
                            val partNameWithPresence = if (partSchemas.required?.contains(partName) == true)
                                partName
                            else
                                "$partName?"

                            if (partSchema is BinarySchema) {
                                MultiPartFilePattern(
                                    partNameWithPresence,
                                    toSpecmaticPattern(partSchema, emptyList()),
                                    partContentType
                                )
                            } else {
                                MultiPartContentPattern(
                                    partNameWithPresence,
                                    toSpecmaticPattern(partSchema, emptyList()),
                                    partContentType
                                )
                            }
                        }

                    Pair(
                        requestPattern.copy(
                            multiPartFormDataPattern = parts,
                            headersPattern = headersPatternWithContentType(requestPattern, contentType)
                        ), emptyMap()
                    )
                }

                "application/x-www-form-urlencoded" -> Pair(
                    requestPattern.copy(
                        formFieldsPattern = toFormFields(mediaType),
                        headersPattern = headersPatternWithContentType(requestPattern, contentType)
                    ), emptyMap()
                )

                "application/xml" -> Pair(
                    requestPattern.copy(
                        body = toXMLPattern(mediaType),
                        headersPattern = headersPatternWithContentType(requestPattern, contentType)
                    ), emptyMap()
                )

                else -> {
                    val examplesFromMediaType = mediaType.examples ?: emptyMap()

                    val exampleBodies: Map<String, String?> = examplesFromMediaType.mapValues {
                        resolveExample(it.value)?.value?.toString() ?: ""
                    }

                    val allExamples = exampleRequestBuilder.examplesWithRequestBodies(exampleBodies)

                    val bodyIsRequired: Boolean = requestBody.required ?: true

                    val body = toSpecmaticPattern(mediaType, "request").let {
                        if (bodyIsRequired)
                            it
                        else
                            OptionalBodyPattern.fromPattern(it)
                    }

                    Pair(
                        requestPattern.copy(
                            body = body,
                            headersPattern = headersPatternWithContentType(requestPattern, contentType)
                        ), allExamples
                    )
                }
            }.let { RequestPatternsData(it.first, it.second, Pair(contentType, mediaType)) }
        }
    }

    private fun headersPatternWithContentType(
        requestPattern: HttpRequestPattern,
        contentType: String
    ) = requestPattern.headersPattern.copy(
        contentType = contentType
    )

    private fun <T : Parameter> namedExampleParams(
        operation: Operation,
        parameterType: Class<T>
    ): Map<String, Map<String, String>> = operation.parameters.orEmpty()
        .filterIsInstance(parameterType)
        .fold(emptyMap()) { acc, parameter ->
            extractParameterExamples(parameter.examples, parameter.name, acc)
        }

    private fun extractParameterExamples(
        examplesToAdd: Map<String, Example>?,
        parameterName: String,
        examplesAccumulatedSoFar: Map<String, Map<String, String>>
    ): Map<String, Map<String, String>> {
        return examplesToAdd.orEmpty()
            .entries.filter { it.value.value?.toString().orEmpty() !in OMIT }
            .fold(examplesAccumulatedSoFar) { acc, (exampleName, example) ->
                val exampleValue = resolveExample(example)?.value?.toString() ?: ""
                val exampleMap = acc[exampleName] ?: emptyMap()
                acc.plus(exampleName to exampleMap.plus(parameterName to exampleValue))
            }
    }

    private fun resolveRequestBody(operation: Operation): RequestBody? =
        operation.requestBody?.`$ref`?.let {
            resolveReferenceToRequestBody(it).second
        } ?: operation.requestBody

    private fun operationSecuritySchemes(
        operation: Operation,
        contractSecuritySchemes: Map<String, OpenAPISecurityScheme>
    ): List<OpenAPISecurityScheme> {
        val globalSecurityRequirements: List<String> =
            parsedOpenApi.security?.map { it.keys.toList() }?.flatten() ?: emptyList()
        val operationSecurityRequirements: List<String> =
            operation.security?.map { it.keys.toList() }?.flatten() ?: emptyList()
        val operationSecurityRequirementsSuperSet: List<String> =
            globalSecurityRequirements.plus(operationSecurityRequirements).distinct()
        val operationSecuritySchemes: List<OpenAPISecurityScheme> =
            contractSecuritySchemes.filter { (name, _: OpenAPISecurityScheme) -> name in operationSecurityRequirementsSuperSet }.values.toList()
        return operationSecuritySchemes.ifEmpty { listOf(NoSecurityScheme()) }
    }

    private fun toSecurityScheme(schemeName: String, securityScheme: SecurityScheme): OpenAPISecurityScheme {
        val securitySchemeConfiguration = securityConfiguration?.OpenAPI?.securitySchemes?.get(schemeName)
        if (securityScheme.scheme == BEARER_SECURITY_SCHEME) {
            return toBearerSecurityScheme(securitySchemeConfiguration, schemeName)
        }

        if (securityScheme.type == SecurityScheme.Type.OAUTH2) {
            return toBearerSecurityScheme(securitySchemeConfiguration, schemeName)
        }

        if (securityScheme.type == SecurityScheme.Type.APIKEY) {
            val apiKey = getSecurityTokenForApiKeyScheme(securitySchemeConfiguration, schemeName)
            if (securityScheme.`in` == SecurityScheme.In.HEADER)
                return APIKeyInHeaderSecurityScheme(securityScheme.name, apiKey)

            if (securityScheme.`in` == SecurityScheme.In.QUERY)
                return APIKeyInQueryParamSecurityScheme(securityScheme.name, apiKey)
        }

        if(securityScheme.type == SecurityScheme.Type.HTTP && securityScheme.scheme == "basic")
            return toBasicAuthSecurityScheme(securitySchemeConfiguration, schemeName)

        throw ContractException("Specmatic only supports oauth2, bearer, and api key authentication (header, query) security schemes at the moment")
    }

    private fun toBearerSecurityScheme(
        securitySchemeConfiguration: SecuritySchemeConfiguration?,
        environmentVariable: String,
    ): BearerSecurityScheme {
        val token = getSecurityTokenForBearerScheme(securitySchemeConfiguration, environmentVariable)
        return BearerSecurityScheme(token)
    }

    private fun toBasicAuthSecurityScheme(
        securitySchemeConfiguration: SecuritySchemeConfiguration?,
        environmentVariable: String,
    ): BasicAuthSecurityScheme {
        val token = getSecurityTokenForBasicAuthScheme(securitySchemeConfiguration, environmentVariable)
        return BasicAuthSecurityScheme(token)
    }

    private fun toFormFields(mediaType: MediaType): Map<String, Pattern> {
        val schema = mediaType.schema.`$ref`?.let {
            val (_, resolvedSchema) = resolveReferenceToSchema(mediaType.schema.`$ref`)
            resolvedSchema
        } ?: mediaType.schema

        return schema.properties.map { (formFieldName, formFieldValue) ->
            formFieldName to toSpecmaticPattern(
                formFieldValue, emptyList(), jsonInFormData = isJsonInString(mediaType, formFieldName)
            )
        }.toMap()
    }

    private fun isJsonInString(
        mediaType: MediaType, formFieldName: String?
    ) = if (mediaType.encoding.isNullOrEmpty()) false
    else mediaType.encoding[formFieldName]?.contentType == "application/json"

    private fun toSpecmaticPattern(mediaType: MediaType, section: String, jsonInFormData: Boolean = false): Pattern =
        toSpecmaticPattern(mediaType.schema ?: throw ContractException("${section.capitalizeFirstChar()} body definition is missing"), emptyList(), jsonInFormData = jsonInFormData)

    private fun resolveDeepAllOfs(schema: Schema<Any>): List<Schema<Any>> {
        if (schema.allOf == null)
            return listOf(schema)

        return schema.allOf.flatMap { constituentSchema ->
            if (constituentSchema.`$ref` != null) {
                val (_, referredSchema) = resolveReferenceToSchema(constituentSchema.`$ref`)

                resolveDeepAllOfs(referredSchema)
            } else listOf(constituentSchema)
        }
    }

    private fun toSpecmaticPattern(
        schema: Schema<*>, typeStack: List<String>, patternName: String = "", jsonInFormData: Boolean = false
    ): Pattern {
        val preExistingResult = patterns["($patternName)"]
        val pattern = if (preExistingResult != null && patternName.isNotBlank())
            preExistingResult
        else if (typeStack.filter { it == patternName }.size > 1) {
            DeferredPattern("($patternName)")
        } else when (schema) {
            is StringSchema -> when (schema.enum) {
                null -> StringPattern(
                    minLength = schema.minLength,
                    maxLength = schema.maxLength,
                    example = schema.example?.toString(),
                    regex = schema.pattern
                )

                else -> toEnum(schema, patternName) { enumValue -> StringValue(enumValue.toString()) }.withExample(
                    schema.example?.toString()
                )
            }

            is EmailSchema -> EmailPattern(example = schema.example?.toString())

            is PasswordSchema -> StringPattern(example = schema.example?.toString())

            is IntegerSchema -> when (schema.enum) {
                null -> numberPattern(schema, false)
                else -> toEnum(schema, patternName) { enumValue ->
                    NumberValue(
                        enumValue.toString().toInt()
                    )
                }.withExample(schema.example?.toString())
            }

            is BinarySchema -> BinaryPattern()
            is NumberSchema -> numberPattern(schema, true)
            is UUIDSchema -> UUIDPattern
            is DateTimeSchema -> DateTimePattern
            is DateSchema -> DatePattern
            is BooleanSchema -> BooleanPattern(example = schema.example?.toString())
            is ObjectSchema -> {
                if (schema.additionalProperties is Schema<*>) {
                    toDictionaryPattern(schema, typeStack)
                } else if (noPropertiesDefinedInSchema(schema)) {
                    toFreeFormDictionaryWithStringKeysPattern()
                } else if (schema.xml?.name != null) {
                    toXMLPattern(schema, typeStack = typeStack)
                } else {
                    toJsonObjectPattern(schema, patternName, typeStack)
                }
            }
            is ByteArraySchema -> Base64StringPattern()

            is ArraySchema -> {
                if (schema.xml?.name != null) {
                    toXMLPattern(schema, typeStack = typeStack)
                } else {

                    ListPattern(
                        toSpecmaticPattern(
                            schema.items, typeStack
                        ),
                        example = toListExample(schema.example)
                    )
                }
            }

            is ComposedSchema -> {
                if (schema.allOf != null) {
                    val deepListOfAllOfs = resolveDeepAllOfs(schema)
                    val schemaProperties = deepListOfAllOfs.map { schemaToProcess ->
                        val requiredFields = schemaToProcess.required.orEmpty()
                        toSchemaProperties(schemaToProcess, requiredFields, patternName, typeStack)
                    }.fold(emptyMap<String, Pattern>()) { propertiesAcc, propertiesEntry ->
                        combine(propertiesEntry, propertiesAcc)
                    }

                    val schemasWithOneOf = deepListOfAllOfs.filter {
                        it.oneOf != null
                    }

                    val oneOfs = schemasWithOneOf.map { oneOfTheSchemas ->
                        oneOfTheSchemas.oneOf.map {
                            val (componentName, schemaToProcess) = resolveReferenceToSchema(it.`$ref`)
                            val requiredFields = schemaToProcess.required.orEmpty()
                            componentName to toSchemaProperties(
                                schemaToProcess,
                                requiredFields,
                                componentName,
                                typeStack
                            )
                        }.map { (componentName, properties) ->
                            componentName to combine(schemaProperties, properties)
                        }
                    }.flatten().map { (componentName, properties) ->
                        toJSONObjectPattern(properties, "(${componentName})")
                    }

                    if (oneOfs.size == 1)
                        oneOfs.single()
                    else if (oneOfs.size > 1)
                        AnyPattern(oneOfs)
                    else
                        toJSONObjectPattern(schemaProperties, "(${patternName})")
                } else if (schema.oneOf != null) {
                    val candidatePatterns = schema.oneOf.filterNot { nullableEmptyObject(it) }.map { componentSchema ->
                        val (componentName, schemaToProcess) =
                            if (componentSchema.`$ref` != null)
                                resolveReferenceToSchema(componentSchema.`$ref`)
                            else
                                "" to componentSchema

                        toSpecmaticPattern(schemaToProcess, typeStack.plus(componentName), componentName)
                    }

                    val nullable =
                        if (schema.oneOf.any { nullableEmptyObject(it) }) listOf(NullPattern) else emptyList()

                    AnyPattern(candidatePatterns.plus(nullable))
                } else if (schema.anyOf != null) {
                    throw UnsupportedOperationException("Specmatic does not support anyOf")
                } else {
                    throw UnsupportedOperationException("Unsupported composed schema: $schema")
                }
            }

            else -> {
                if (schema.nullable == true && schema.additionalProperties == null && schema.`$ref` == null) {
                    NullPattern
                } else if (schema.additionalProperties is Schema<*>) {
                    toDictionaryPattern(schema, typeStack)
                } else if (schema.additionalProperties == true) {
                    toFreeFormDictionaryWithStringKeysPattern()
                } else if(schema.properties != null)
                    toJsonObjectPattern(schema, patternName, typeStack)
                else if (schema.`$ref` != null) {
                    val component: String = schema.`$ref`

                    val (componentName, referredSchema) = resolveReferenceToSchema(component)
                    val cyclicReference = typeStack.contains(componentName)
                    if (!cyclicReference) {
                        val componentPattern = toSpecmaticPattern(
                            referredSchema,
                            typeStack.plus(componentName), componentName
                        )
                        cacheComponentPattern(componentName, componentPattern)
                    }
                    DeferredPattern("(${componentName})")
                }
                else {
                    val schemaFragment = if(patternName.isNotBlank()) " in schema $patternName" else " in the schema"

                    if(schema.javaClass.simpleName != "Schema")
                        throw ContractException("${schemaFragment.capitalizeFirstChar()} is not yet supported, please raise an issue on https://github.com/znsio/specmatic/issues")
                    else
                        AnyNonNullJSONValue()
                }
            }
        }.also {
            when {
                it.instanceOf(JSONObjectPattern::class) && jsonInFormData -> {
                    PatternInStringPattern(
                        patterns.getOrDefault("($patternName)", StringPattern()), "($patternName)"
                    )
                }

                else -> it
            }
        }

        return when (schema.nullable) {
            false, null -> pattern
            true -> pattern.toNullable(schema.example?.toString())
        }
    }

    private fun numberPattern(schema: Schema<*>, isDoubleFormat: Boolean) = NumberPattern(
        minimum = schema.minimum ?: NumberPattern.LOWEST_DECIMAL,
        maximum = schema.maximum ?: NumberPattern.HIGHEST_DECIMAL,
        exclusiveMinimum = schema.exclusiveMinimum ?: false,
        exclusiveMaximum = schema.exclusiveMaximum ?: false,
        isDoubleFormat = isDoubleFormat,
        example = schema.example?.toString()
    )

    private fun toListExample(example: Any?): List<String?>? {
        if (example == null)
            return null

        if (example !is ArrayNode)
            return null

        return example.toList().flatMap {
            when {
                it.isNull -> listOf(null)
                it.isNumber -> listOf(it.numberValue().toString())
                it.isBoolean -> listOf(it.booleanValue().toString())
                it.isTextual -> listOf(it.textValue())
                else -> emptyList()
            }
        }
    }

    private fun combine(
        propertiesEntry: Map<String, Pattern>,
        propertiesAcc: Map<String, Pattern>
    ): Map<String, Pattern> {
        val updatedPropertiesAcc: Map<String, Pattern> =
            propertiesEntry.entries.fold(propertiesAcc) { acc, propertyEntry ->
                when (val keyWithoutOptionality = withoutOptionality(propertyEntry.key)) {
                    in acc ->
                        acc

                    propertyEntry.key ->
                        acc.minus("$keyWithoutOptionality?").plus(propertyEntry.key to propertyEntry.value)

                    else ->
                        acc.plus(propertyEntry.key to propertyEntry.value)
                }
            }

        return updatedPropertiesAcc
    }

    private fun <T : Pattern> cacheComponentPattern(componentName: String, pattern: T): T {
        if (componentName.isNotBlank() && pattern !is DeferredPattern) {
            val typeName = "(${componentName})"
            val prev = patterns[typeName]
            if (pattern != prev) {
                if (prev != null) {
                    logger.debug("Replacing cached component pattern. name=$componentName, prev=$prev, new=$pattern")
                }
                patterns[typeName] = pattern
            }
        }
        return pattern
    }

    private fun nullableEmptyObject(schema: Schema<*>): Boolean {
        return schema is ObjectSchema && schema.nullable == true
    }

    private fun toXMLPattern(mediaType: MediaType): Pattern {
        return toXMLPattern(mediaType.schema, typeStack = emptyList())
    }

    private fun toXMLPattern(
        schema: Schema<Any>, nodeNameFromProperty: String? = null, typeStack: List<String>
    ): XMLPattern {
        val name = schema.xml?.name ?: nodeNameFromProperty

        return when (schema) {
            is ObjectSchema -> {
                if(schema.properties == null) {
                    throw ContractException("XML schema named $name does not have properties.")
                }

                val nodeProperties = schema.properties.filter { entry ->
                    entry.value.xml?.attribute != true
                }

                val nodes = nodeProperties.map { (propertyName: String, propertySchema) ->
                    val type = when (propertySchema.type) {
                        in primitiveOpenAPITypes -> {
                            val innerPattern = DeferredPattern(primitiveOpenAPITypes.getValue(propertySchema.type))
                            XMLPattern(XMLTypeData(propertyName, propertyName, emptyMap(), listOf(innerPattern)))
                        }

                        else -> {
                            toXMLPattern(propertySchema, propertyName, typeStack)
                        }
                    }

                    val optionalAttribute = if (propertyName !in (schema.required ?: emptyList<String>())) mapOf(
                        OCCURS_ATTRIBUTE_NAME to ExactValuePattern(StringValue(OPTIONAL_ATTRIBUTE_VALUE))
                    )
                    else emptyMap()

                    type.copy(pattern = type.pattern.copy(attributes = optionalAttribute.plus(type.pattern.attributes)))
                }

                val attributeProperties = schema.properties.filter { entry ->
                    entry.value.xml?.attribute == true
                }

                val attributes: Map<String, Pattern> = attributeProperties.map { (name, schema) ->
                    val attributeName = if(name !in schema.required.orEmpty())
                        "$name.opt"
                    else
                        name

                    attributeName to toSpecmaticPattern(schema, emptyList())
                }.toMap()

                name ?: throw ContractException("Could not determine name for an xml node")

                val namespaceAttributes: Map<String, ExactValuePattern> =
                    if (schema.xml?.namespace != null && schema.xml?.prefix != null) {
                        val attributeName = "xmlns:${schema.xml?.prefix}"
                        val attributeValue = ExactValuePattern(StringValue(schema.xml.namespace))
                        mapOf(attributeName to attributeValue)
                    } else {
                        emptyMap()
                    }

                val xmlTypeData = XMLTypeData(name, realName(schema, name), namespaceAttributes.plus(attributes), nodes)

                XMLPattern(xmlTypeData)
            }

            is ArraySchema -> {
                val repeatingSchema = schema.items as Schema<Any>

                val repeatingType = when (repeatingSchema.type) {
                    in primitiveOpenAPITypes -> {
                        val innerPattern = DeferredPattern(primitiveOpenAPITypes.getValue(repeatingSchema.type))

                        val innerName = repeatingSchema.xml?.name
                            ?: if (schema.xml?.name != null && schema.xml?.wrapped == true) schema.xml.name else nodeNameFromProperty

                        XMLPattern(
                            XMLTypeData(
                                innerName ?: throw ContractException("Could not determine name for an xml node"),
                                innerName,
                                emptyMap(),
                                listOf(innerPattern)
                            )
                        )
                    }

                    else -> {
                        toXMLPattern(repeatingSchema, name, typeStack)
                    }
                }.let { repeatingType ->
                    repeatingType.copy(
                        pattern = repeatingType.pattern.copy(
                            attributes = repeatingType.pattern.attributes.plus(
                                OCCURS_ATTRIBUTE_NAME to ExactValuePattern(StringValue(MULTIPLE_ATTRIBUTE_VALUE))
                            )
                        )
                    )
                }

                if (schema.xml?.wrapped == true) {
                    val wrappedName = schema.xml?.name ?: nodeNameFromProperty
                    val wrapperTypeData = XMLTypeData(
                        wrappedName ?: throw ContractException("Could not determine name for an xml node"),
                        wrappedName,
                        emptyMap(),
                        listOf(repeatingType)
                    )
                    XMLPattern(wrapperTypeData)
                } else repeatingType
            }

            else -> {
                if (schema.`$ref` != null) {
                    val component = schema.`$ref`
                    val (componentName, componentSchema) = resolveReferenceToSchema(component)

                    val typeName = "($componentName)"

                    val nodeName = componentSchema.xml?.name ?: name ?: componentName

                    if (typeName !in typeStack) {
                        val componentPattern = toXMLPattern(componentSchema, componentName, typeStack.plus(typeName))
                        cacheComponentPattern(componentName, componentPattern)
                    }

                    val xmlRefType = XMLTypeData(
                        nodeName, nodeName, mapOf(
                            TYPE_ATTRIBUTE_NAME to ExactValuePattern(
                                StringValue(
                                    componentName
                                )
                            )
                        ), emptyList()
                    )

                    XMLPattern(xmlRefType)
                } else throw ContractException("Node not recognized as XML type: ${schema.type}")
            }
        }
    }

    private fun realName(schema: ObjectSchema, name: String): String = if (schema.xml?.prefix != null) {
        "${schema.xml?.prefix}:${name}"
    } else {
        name
    }

    private val primitiveOpenAPITypes =
        mapOf("string" to "(string)", "number" to "(number)", "integer" to "(number)", "boolean" to "(boolean)")

    private fun toDictionaryPattern(
        schema: Schema<*>, typeStack: List<String>
    ): DictionaryPattern {
        val valueSchema = schema.additionalProperties as Schema<Any>
        val valueSchemaTypeName = valueSchema.`$ref` ?: valueSchema.types?.first() ?: ""
        return DictionaryPattern(
            StringPattern(), toSpecmaticPattern(valueSchema, typeStack, valueSchemaTypeName, false)
        )
    }

    private fun noPropertiesDefinedInSchema(valueSchema: Schema<Any>) = valueSchema.properties == null

    private fun toFreeFormDictionaryWithStringKeysPattern(): DictionaryPattern {
        return DictionaryPattern(
            StringPattern(), AnythingPattern
        )
    }


    private fun toJsonObjectPattern(
        schema: Schema<*>, patternName: String, typeStack: List<String>
    ): JSONObjectPattern {
        val requiredFields = schema.required.orEmpty()
        val schemaProperties = toSchemaProperties(schema, requiredFields, patternName, typeStack)
        val minProperties: Int? = schema.minProperties
        val maxProperties: Int? = schema.maxProperties
        val jsonObjectPattern = toJSONObjectPattern(schemaProperties, "(${patternName})").copy(
            minProperties = minProperties,
            maxProperties = maxProperties
        )
        return cacheComponentPattern(patternName, jsonObjectPattern)
    }

    private fun toSchemaProperties(
        schema: Schema<*>, requiredFields: List<String>, patternName: String, typeStack: List<String>
    ): Map<String, Pattern> = schema.properties.orEmpty().map { (propertyName, propertyType) ->
        if (schema.discriminator?.propertyName == propertyName)
            propertyName to ExactValuePattern(StringValue(patternName))
        else {
            val optional = !requiredFields.contains(propertyName)
            toSpecmaticParamName(optional, propertyName) to attempt(breadCrumb = propertyName) { toSpecmaticPattern(propertyType, typeStack) }
        }
    }.toMap()

    private fun toEnum(schema: Schema<*>, patternName: String, toSpecmaticValue: (Any) -> Value): EnumPattern {
        val specmaticValues = schema.enum.map<Any?, Value> { enumValue ->
            when (enumValue) {
                null -> NullValue
                else -> toSpecmaticValue(enumValue)
            }
        }

        if (schema.nullable != true && NullValue in specmaticValues)
            throw ContractException("Enum values cannot contain null since the schema $patternName is not nullable")

        if (schema.nullable == true && NullValue !in specmaticValues)
            throw ContractException("Enum values must contain null since the schema $patternName is nullable")

        return EnumPattern(specmaticValues, nullable = schema.nullable == true, typeAlias = patternName).also {
            cacheComponentPattern(patternName, it)
        }
    }

    private fun toSpecmaticParamName(optional: Boolean, name: String) = when (optional) {
        true -> "${name}?"
        false -> name
    }

    private fun resolveReferenceToSchema(component: String): Pair<String, Schema<Any>> {
        val componentName = extractComponentName(component)
        val components = parsedOpenApi.components ?: throw ContractException("Could not find components in the specification (trying to dereference $component")
        val schemas = components.schemas ?: throw ContractException("Could not find schemas components in the specification (trying to dereference $component)")

        val schema =
            schemas[componentName] ?: ObjectSchema().also { it.properties = emptyMap() }

        return componentName to schema as Schema<Any>
    }

    private fun resolveReferenceToRequestBody(component: String): Pair<String, RequestBody> {
        val componentName = extractComponentName(component)
        val requestBody = parsedOpenApi.components.requestBodies[componentName] ?: RequestBody()

        return componentName to requestBody
    }

    private fun extractComponentName(component: String): String {
        if(!component.startsWith("#")) {
            val componentPath = component.substringAfterLast("#")
            val filePath = component.substringBeforeLast("#")
            val message = try {
                "Could not dereference $component. Either the file $filePath does not exist, or $componentPath is missing from it."
            } catch (e: Throwable) {
                "Could not dereference $component due an an error (${e.message})."
            }

            throw ContractException(message)
        }

        return componentNameFromReference(component)
    }

    private fun componentNameFromReference(component: String) = component.substringAfterLast("/")

    private fun toSpecmaticQueryParam(operation: Operation): HttpQueryParamPattern {
        val parameters = operation.parameters ?: return HttpQueryParamPattern(emptyMap())

        val queryPattern: Map<String, Pattern> = parameters.filterIsInstance<QueryParameter>().associate {
            val specmaticPattern: Pattern? = if (it.schema.type == "array") {
                QueryParameterArrayPattern(listOf(toSpecmaticPattern(schema = it.schema.items, typeStack = emptyList())), it.name)
            } else if (it.schema.type != "object") {
                QueryParameterScalarPattern(toSpecmaticPattern(schema = it.schema, typeStack = emptyList(), patternName = it.name))
            } else null

            val queryParamKey = if(it.required == true)
                it.name
            else
                "${it.name}?"

            queryParamKey to specmaticPattern
        }.filterValues { it != null }.mapValues { it.value!! }

        val additionalProperties = additionalPropertiesInQueryParam(parameters)

        return HttpQueryParamPattern(queryPattern, additionalProperties)
    }

    private fun additionalPropertiesInQueryParam(parameters: List<Parameter>): Pattern? {
        val additionalProperties = parameters.filterIsInstance<QueryParameter>()
            .find { it.schema.type == "object" && it.schema.additionalProperties != null }?.schema?.additionalProperties

        if(additionalProperties == false)
            return null

        if(additionalProperties == true)
            return AnythingPattern

        if(additionalProperties is Schema<*>)
            return toSpecmaticPattern(additionalProperties, emptyList())

        return null
    }

    private fun toSpecmaticPathParam(openApiPath: String, operation: Operation): HttpPathPattern {
        val parameters = operation.parameters ?: emptyList()

        val pathSegments: List<String> = openApiPath.removePrefix("/").removeSuffix("/").let {
            if (it.isBlank())
                emptyList()
            else it.split("/")
        }
        val pathParamMap: Map<String, PathParameter> =
            parameters.filterIsInstance<PathParameter>().associateBy {
                it.name
            }

        val pathPattern: List<URLPathSegmentPattern> = pathSegments.map { pathSegment ->
            if (isParameter(pathSegment)) {
                val paramName = pathSegment.removeSurrounding("{", "}")

                val param = pathParamMap[paramName]
                    ?: throw ContractException("The path parameter in $openApiPath is not defined in the specification")

                URLPathSegmentPattern(toSpecmaticPattern(param.schema, emptyList()), paramName)
            } else {
                URLPathSegmentPattern(ExactValuePattern(StringValue(pathSegment)))
            }
        }

        val specmaticPath = toSpecmaticFormattedPathString(parameters, openApiPath)

        return HttpPathPattern(pathPattern, specmaticPath)
    }

    private fun isParameter(pathSegment: String) = pathSegment.startsWith("{") && pathSegment.endsWith("}")

    private fun toSpecmaticFormattedPathString(
        parameters: List<Parameter>,
        openApiPath: String
    ): String {
        return parameters.filterIsInstance<PathParameter>().foldRight(openApiPath) { it, specmaticPath ->
            val pattern = if (it.schema.enum != null) StringPattern("") else toSpecmaticPattern(it.schema, emptyList())
            specmaticPath.replace(
                "{${it.name}}", "(${it.name}:${pattern.typeName})"
            )
        }
    }

    private fun openApiOperations(pathItem: PathItem): Map<String, OpenApiOperation> {
        return linkedMapOf<String, Operation?>(
            "POST" to pathItem.post,
            "GET" to pathItem.get,
            "PATCH" to pathItem.patch,
            "PUT" to pathItem.put,
            "DELETE" to pathItem.delete
        ).filter { (_, value) -> value != null }.map { (key, value) -> key to OpenApiOperation(value!!) }.toMap()
    }
}
