package io.specmatic.stub.stateful

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.*
import io.ktor.server.plugins.doublereceive.*
import io.specmatic.core.Feature
import io.specmatic.core.HttpRequest
import io.specmatic.core.HttpRequestPattern
import io.specmatic.core.HttpResponse
import io.specmatic.core.Resolver
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.loadSpecmaticConfig
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.log.logger
import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.JSONObjectPattern
import io.specmatic.core.pattern.Pattern
import io.specmatic.core.pattern.resolvedHop
import io.specmatic.core.route.modules.HealthCheckModule.Companion.configureHealthCheckModule
import io.specmatic.core.route.modules.HealthCheckModule.Companion.isHealthCheckRequest
import io.specmatic.core.utilities.exceptionCauseMessage
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.JSONObjectValue
import io.specmatic.core.value.Value
import io.specmatic.mock.ScenarioStub
import io.specmatic.stub.ContractStub
import io.specmatic.stub.CouldNotParseRequest
import io.specmatic.stub.FoundStubbedResponse
import io.specmatic.stub.HttpStubResponse
import io.specmatic.stub.NotStubbed
import io.specmatic.stub.ResponseDetails
import io.specmatic.stub.StubbedResponseResult
import io.specmatic.stub.badRequest
import io.specmatic.stub.endPointFromHostAndPort
import io.specmatic.stub.fakeHttpResponse
import io.specmatic.stub.generateHttpResponseFrom
import io.specmatic.stub.internalServerError
import io.specmatic.stub.ktorHttpRequestToHttpRequest
import io.specmatic.stub.respondToKtorHttpResponse
import io.specmatic.stub.responseDetailsFrom
import io.specmatic.stub.successResponse
import io.specmatic.test.HttpClient
import java.io.File

class StatefulHttpStub(
    host: String = "127.0.0.1",
    port: Int = 9000,
    private val features: List<Feature>,
    private val specmaticConfigPath: String? = null,
    private val scenarioStubs: List<ScenarioStub> = emptyList(),
    private val timeoutMillis: Long = 2000,
): ContractStub {

    private val environment = applicationEngineEnvironment {
        module {
            install(DoubleReceive)

            install(CORS) {
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Patch)

                allowHeaders {
                    true
                }

                allowCredentials = true
                allowNonSimpleContentTypes = true

                anyHost()
            }

            intercept(ApplicationCallPipeline.Call) {
                val httpLogMessage = HttpLogMessage()

                try {
                    val rawHttpRequest = ktorHttpRequestToHttpRequest(call)
                    httpLogMessage.addRequest(rawHttpRequest)

                    if(rawHttpRequest.isHealthCheckRequest()) return@intercept

                    val httpStubResponse: HttpStubResponse = cachedHttpResponse(rawHttpRequest).response

                    respondToKtorHttpResponse(
                        call,
                        httpStubResponse.response,
                        httpStubResponse.delayInMilliSeconds,
                        specmaticConfig
                    )
                    httpLogMessage.addResponse(httpStubResponse)
                } catch (e: ContractException) {
                    val response = badRequest(e.report())
                    httpLogMessage.addResponse(response)
                    respondToKtorHttpResponse(call, response)
                } catch (e: CouldNotParseRequest) {
                    val response = badRequest("Could not parse request")
                    httpLogMessage.addResponse(response)

                    respondToKtorHttpResponse(call, response)
                } catch (e: Throwable) {
                    val response = internalServerError(exceptionCauseMessage(e) + "\n\n" + e.stackTraceToString())
                    httpLogMessage.addResponse(response)

                    respondToKtorHttpResponse(call, response)
                }

                logger.log(httpLogMessage)
            }

            configureHealthCheckModule()

            connector {
                this.host = host
                this.port = port
            }
        }

    }

    private val server: ApplicationEngine = embeddedServer(Netty, environment, configure = {
        this.callGroupSize = 20
    })

    init {
        server.start()
    }

    override val client = HttpClient(endPointFromHostAndPort(host, port, null))

    override fun setExpectation(json: String) {
        return
    }

    override fun close() {
        server.stop(gracePeriodMillis = timeoutMillis, timeoutMillis = timeoutMillis)
    }

    private val specmaticConfig = loadSpecmaticConfig()
    private val stubCache = stubCacheWithExampleData()

    private fun cachedHttpResponse(
        httpRequest: HttpRequest,
    ): StubbedResponseResult {
        if (features.isEmpty())
            return NotStubbed(HttpStubResponse(HttpResponse(400, "No valid API specifications loaded")))

        val responses: List<ResponseDetails> = responseDetailsFrom(features, httpRequest)
        val fakeResponse = responses.successResponse()
            ?: return fakeHttpResponse(features, httpRequest, specmaticConfig)

        val generatedResponse = generateHttpResponseFrom(fakeResponse, httpRequest)
        val updatedResponse = cachedResponse(
            fakeResponse,
            httpRequest,
            specmaticConfig.stub.includeMandatoryAndRequestedKeysInResponse
        ) ?: generatedResponse

        return FoundStubbedResponse(
            HttpStubResponse(
                updatedResponse,
                contractPath = fakeResponse.feature.path,
                feature = fakeResponse.feature,
                scenario = fakeResponse.successResponse?.scenario
            )
        )
    }

    private fun cachedResponse(
        fakeResponse: ResponseDetails,
        httpRequest: HttpRequest,
        includeMandatoryAndRequestedKeysInResponse: Boolean?
    ): HttpResponse? {
        val scenario = fakeResponse.successResponse?.scenario

        val generatedResponse = generateHttpResponseFrom(fakeResponse, httpRequest)
        val method = scenario?.method
        val pathSegments = httpRequest.pathSegments()
        if(isUnsupportedResponseBodyForCaching(generatedResponse, method, pathSegments)) return null

        val (resourcePath, resourceId) = resourcePathAndIdFrom(httpRequest)
        val resourceIdKey = resourceIdKeyFrom(scenario?.httpRequestPattern)
        val attributeSelectionKeys: Set<String> =
            scenario?.getFieldsToBeMadeMandatoryBasedOnAttributeSelection(httpRequest.queryParams).orEmpty()

        if (method == "POST") {
            val responseBody =
                generatePostResponse(generatedResponse, httpRequest)?.includeMandatoryAndRequestedKeys(
                    fakeResponse,
                    httpRequest,
                    includeMandatoryAndRequestedKeysInResponse
                ) ?: return null

            stubCache.addResponse(resourcePath, responseBody)
            return generatedResponse.withUpdated(responseBody, attributeSelectionKeys)
        }

        if(method == "PATCH" && pathSegments.size > 1) {
            val responseBody =
                generatePatchResponse(
                    httpRequest,
                    resourcePath,
                    resourceIdKey,
                    resourceId,
                    fakeResponse
                ) ?: return null

            stubCache.updateResponse(resourcePath, responseBody, resourceIdKey, resourceId)
            return generatedResponse.withUpdated(responseBody, attributeSelectionKeys)
        }

        if(method == "GET" && pathSegments.size == 1) {
            val responseBody = stubCache.findAllResponsesFor(resourcePath, attributeSelectionKeys)
            return generatedResponse.withUpdated(responseBody, attributeSelectionKeys)
        }

        if(method == "GET" && pathSegments.size > 1) {
            val responseBody =
                stubCache.findResponseFor(resourcePath, resourceIdKey, resourceId)?.responseBody
                    ?: return HttpResponse(404, "Resource with resourceId '$resourceId' not found")

            return generatedResponse.withUpdated(responseBody, attributeSelectionKeys)
        }

        if(method == "DELETE" && pathSegments.size > 1) {
            stubCache.deleteResponse(resourcePath, resourceIdKey, resourceId)
            return generatedResponse
        }

        return null
    }

    private fun resourcePathAndIdFrom(httpRequest: HttpRequest): Pair<String, String> {
        val pathSegments = httpRequest.pathSegments()
        val resourcePath = "/${pathSegments.first()}"
        val resourceId = pathSegments.last()
        return Pair(resourcePath, resourceId)
    }

    private fun HttpRequest.pathSegments(): List<String> {
        return this.path?.split("/")?.filter { it.isNotBlank() }.orEmpty()
    }

    private fun isUnsupportedResponseBodyForCaching(
        generatedResponse: HttpResponse,
        method: String?,
        pathSegments: List<String>
    ): Boolean {
        return (generatedResponse.body is JSONObjectValue ||
                (method == "DELETE" && pathSegments.size > 1) ||
                (method == "GET" &&
                        generatedResponse.body is JSONArrayValue &&
                        generatedResponse.body.list.firstOrNull() is JSONObjectValue)).not()
    }

    private fun generatePostResponse(
        generatedResponse: HttpResponse,
        httpRequest: HttpRequest
    ): JSONObjectValue? {
        if (generatedResponse.body !is JSONObjectValue || httpRequest.body !is JSONObjectValue)
            return null

        return generatedResponse.body.copy(
            jsonObject = patchValuesFromRequestIntoResponse(httpRequest.body, generatedResponse.body)
        )
    }

    private fun generatePatchResponse(
        httpRequest: HttpRequest,
        resourcePath: String,
        resourceIdKey: String,
        resourceId: String,
        fakeResponse: ResponseDetails
    ): JSONObjectValue? {
        if (httpRequest.body !is JSONObjectValue) return null

        val responseBodyPattern = responseBodyPatternFrom(fakeResponse) ?: return null
        if(responseBodyPattern !is JSONObjectPattern) return null
        val resolver = fakeResponse.successResponse?.resolver ?: return null

        val cachedResponse = stubCache.findResponseFor(resourcePath, resourceIdKey, resourceId)

        val responseBody = cachedResponse?.responseBody ?: return null

        return responseBody.copy(
            jsonObject = patchAndAppendValuesFromRequestIntoResponse(
                httpRequest.body,
                responseBody,
                responseBodyPattern,
                resolver
            )
        )
    }

    private fun JSONObjectValue.includeMandatoryAndRequestedKeys(
        fakeResponse: ResponseDetails,
        httpRequest: HttpRequest,
        includeMandatoryAndRequestedKeysInResponse: Boolean?
    ): JSONObjectValue {
        val responseBodyPattern = fakeResponse.successResponse?.responseBodyPattern ?: return this
        val resolver = fakeResponse.successResponse.resolver

        val resolvedResponseBodyPattern = responseBodyPatternFrom(fakeResponse)
        if(resolvedResponseBodyPattern !is JSONObjectPattern) return this

        if (includeMandatoryAndRequestedKeysInResponse == true && httpRequest.body is JSONObjectValue) {
            return this.copy(
                jsonObject = patchAndAppendValuesFromRequestIntoResponse(
                    httpRequest.body,
                    responseBodyPattern.eliminateOptionalKey(this, resolver) as JSONObjectValue,
                    resolvedResponseBodyPattern,
                    resolver
                )
            )
        }

        return this
    }

    private fun patchValuesFromRequestIntoResponse(requestBody: JSONObjectValue, responseBody: JSONObjectValue): Map<String, Value> {
        return responseBody.jsonObject.mapValues { (key, value) ->
            val patchValueFromRequest = requestBody.jsonObject.entries.firstOrNull {
                it.key == key
            }?.value ?: return@mapValues value

            if(patchValueFromRequest::class.java == value::class.java) return@mapValues patchValueFromRequest
            value
        }
    }

    private fun patchAndAppendValuesFromRequestIntoResponse(
        requestBody: JSONObjectValue,
        responseBody: JSONObjectValue,
        responseBodyPattern: JSONObjectPattern,
        resolver: Resolver
    ): Map<String, Value> {
        val acceptedKeysInResponseBody = responseBodyPattern.keysInNonOptionalFormat()

        val entriesFromRequestMissingInTheResponse = requestBody.jsonObject.filter {
            it.key in acceptedKeysInResponseBody
                    && responseBodyPattern.patternForKey(it.key)?.matches(it.value, resolver)?.isSuccess() == true
                    && responseBody.jsonObject.containsKey(it.key).not()
        }.map {
            it.key to it.value
        }.toMap()

        return patchValuesFromRequestIntoResponse(
            requestBody,
            responseBody
        ).plus(entriesFromRequestMissingInTheResponse)
    }

    private fun responseBodyPatternFrom(fakeResponse: ResponseDetails): Pattern? {
        val responseBodyPattern = fakeResponse.successResponse?.responseBodyPattern ?: return null
        val resolver = fakeResponse.successResponse.resolver

        return resolver.withCyclePrevention(responseBodyPattern) {
            resolvedHop(responseBodyPattern, it)
        }
    }

    private fun resourceIdKeyFrom(httpRequestPattern: HttpRequestPattern?): String {
        return httpRequestPattern?.getPathSegmentPatterns()?.last()?.key.orEmpty()
    }

    private fun HttpResponse.withUpdated(body: Value, attributeSelectionKeys: Set<String>): HttpResponse {
        if(body !is JSONObjectValue) return this.copy(body = body)
        return this.copy(body = body.removeKeysNotPresentIn(attributeSelectionKeys))
    }

    private fun loadSpecmaticConfig(): SpecmaticConfig {
        return if(specmaticConfigPath != null && File(specmaticConfigPath).exists())
            loadSpecmaticConfig(specmaticConfigPath)
        else
            SpecmaticConfig()
    }

    private fun stubCacheWithExampleData(): StubCache {
        val stubCache = StubCache()

        scenarioStubs.forEach {
            val httpRequest = it.request
            if (httpRequest.method !in setOf("GET", "POST")) return@forEach
            if (isUnsupportedResponseBodyForCaching(
                    generatedResponse = it.response,
                    method = httpRequest.method,
                    pathSegments = httpRequest.pathSegments()
                )
            ) return@forEach

            val (resourcePath, _) = resourcePathAndIdFrom(httpRequest)
            val responseBody = it.response.body
            if (httpRequest.method == "GET" && httpRequest.pathSegments().size == 1) {
                val responseBodies = (it.response.body as JSONArrayValue).list.filterIsInstance<JSONObjectValue>()
                responseBodies.forEach { body ->
                    stubCache.addResponse(resourcePath, body)
                }
            } else {
                if (responseBody !is JSONObjectValue) return@forEach
                if(httpRequest.method == "POST" && httpRequest.body !is JSONObjectValue) return@forEach

                stubCache.addResponse(resourcePath, responseBody)
            }
        }

        return stubCache
    }
}