package `in`.specmatic.conversions

import `in`.specmatic.core.*
import `in`.specmatic.core.pattern.Row
import `in`.specmatic.core.pattern.StringPattern
import org.apache.http.HttpHeaders.AUTHORIZATION

class BearerSecurityScheme(private val token: String? = null) : OpenAPISecurityScheme {
    override fun matches(httpRequest: HttpRequest): Result {
        val headerEntry = httpRequest.headers.entries.find {
            it.key.equals(AUTHORIZATION, ignoreCase = true)
        } ?: return Result.Failure("$AUTHORIZATION header is missing in request")

        val authHeaderValue: String = headerEntry.value

        if (!authHeaderValue.lowercase().startsWith("bearer"))
            return Result.Failure("$AUTHORIZATION header must be prefixed with \"Bearer\"")

        return Result.Success()
    }

    override fun removeParam(httpRequest: HttpRequest): HttpRequest {
        return httpRequest.copy(headers = removeHeader(AUTHORIZATION, httpRequest.headers))
    }

    override fun addTo(httpRequest: HttpRequest): HttpRequest {
        return httpRequest.copy(
            headers = removeHeader(AUTHORIZATION, httpRequest.headers).plus(
                AUTHORIZATION to getAuthorizationHeaderValue()
            )
        )
    }

    override fun addTo(requestPattern: HttpRequestPattern, row: Row): HttpRequestPattern {
        return addToHeaderType(AUTHORIZATION, row, requestPattern)
    }

    override fun isInRow(row: Row): Boolean = row.containsField(AUTHORIZATION)

    private fun getAuthorizationHeaderValue(): String {
        return "Bearer " + (token ?: StringPattern().generate(Resolver()).toStringLiteral())
    }
}
