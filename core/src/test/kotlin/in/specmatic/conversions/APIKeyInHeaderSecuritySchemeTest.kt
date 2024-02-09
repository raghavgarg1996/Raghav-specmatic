package `in`.specmatic.conversions

import `in`.specmatic.core.*
import `in`.specmatic.core.pattern.Row
import `in`.specmatic.core.value.StringValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class APIKeyInHeaderSecuritySchemeTest {

    private val apiKey = "apiKey"
    private val name = "name"
    private val securityScheme = APIKeyInHeaderSecurityScheme(name, apiKey)

    @Test
    fun `matches returns success when header contains API key`() {
        val httpRequest = HttpRequest("GET", "/", mapOf(name to apiKey), StringValue())
        val result = securityScheme.matches(httpRequest)
        assertTrue(result is Result.Success)
    }

    @Test
    fun `matches is case-insensitive`() {
        val httpRequest = HttpRequest("GET", "/", mapOf(name to apiKey), StringValue())
        val result = securityScheme.matches(httpRequest)
        assertTrue(result is Result.Success)
    }

    @Test
    fun `matches returns failure when header does not contain API key`() {
        val httpRequest = HttpRequest("GET", "/", emptyMap(), StringValue())
        val result = securityScheme.matches(httpRequest)
        assertTrue(result is Result.Failure)
    }

    @Test
    fun `removeParam removes API key from header`() {
        val httpRequest = HttpRequest("GET", "/", mapOf(name to apiKey), StringValue())
        val result = securityScheme.removeParam(httpRequest)
        assertFalse(result.headers.containsKey(name))
    }

    @Test
    fun `addTo adds API key to header`() {
        val httpRequest = HttpRequest("GET", "/", emptyMap(), StringValue())
        val result = securityScheme.addTo(httpRequest)
        assertTrue(result.headers.containsKey(name))
    }

    @Test
    fun `addTo adds API key to HttpRequestPattern`() {
        val row = Row(mapOf(name to apiKey))
        val requestPattern = HttpRequestPattern(method = "GET")
        val result = securityScheme.addTo(requestPattern, row)
        assertTrue(result.headersPattern.pattern.containsKey(name))
    }

    @Test
    fun `isInRow returns true when row contains API key`() {
        val row = Row(mapOf(name to "value"))
        val result = securityScheme.isInRow(row)
        assertTrue(result)
    }

    @Test
    fun `isInRow returns false when row does not contain API key`() {
        val row = Row(emptyMap())
        val result = securityScheme.isInRow(row)
        assertFalse(result)
    }
}