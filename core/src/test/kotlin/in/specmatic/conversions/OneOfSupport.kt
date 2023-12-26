package `in`.specmatic.conversions

import `in`.specmatic.core.Feature
import `in`.specmatic.core.HttpRequest
import `in`.specmatic.core.HttpResponse
import `in`.specmatic.core.Result
import `in`.specmatic.core.pattern.parsedJSONObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OneOfSupport {
    @Test
    fun `oneOf two objects within a schema used in the request should be handled correctly`() {
        val specification = OpenApiSpecification.fromYAML("""
            openapi: 3.0.0
            info:
              title: Test
              version: 1.0.0
            paths:
              /test:
                post:
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${"$"}ref: '#/components/schemas/Request'
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
            components:
              schemas:
                Request:
                  oneOf:
                    - type: object
                      required:
                        - name
                        - address
                      properties:
                        name:
                          type: string
                        address:
                          type: string
                    - type: object
                      required:
                        - first_name
                        - last_name
                        - address
                      properties:
                        first_name:
                          type: string
                        last_name:
                          type: string
                        address:
                          type: object
                          required:
                            - street
                            - city
                          properties:
                            street:
                              type: string
                            city:
                              type: string
            """.trimIndent(), "").toFeature()

        val tests = specification.generateContractTestScenarios(emptyList())

        val requestBodies = listOf(
            parsedJSONObject("""{"name": "John", "address": "1st Street"}"""),
            parsedJSONObject("""{"first_name": "John", "last_name": "Doe", "address": {"street": "1st Street", "city": "New York"}}""")
        )

        assertThat(tests.zip(requestBodies)).allSatisfy { (test, requestBody) ->
            val doesRequestObjectMatchRequestPattern = test.httpRequestPattern.body.matches(requestBody, test.resolver)

            assertThat(doesRequestObjectMatchRequestPattern).isInstanceOf(Result.Success::class.java)
        }
    }

    @Nested
    inner class OneOfWithinAllOfTest {
        val feature: Feature = OpenApiSpecification.fromYAML("""
---
openapi: "3.0.1"
info:
  title: "Person API"
  version: "1.0"
paths:
  /person/{id}:
    get:
      summary: "Get a person's record"
      parameters:
        - name: id
          in: path
          schema:
            type: string
          examples:
            200_OK:
              value: 10
      responses:
        200:
          description: "A person's details"
          content:
            application/json:
              schema:
                ${"$"}ref: "#/components/schemas/PersonRecord"
              examples:
                200_OK:
                  value:
components:
  schemas:
    Id:
      type: object
      properties:
        id:
          type: integer
      required:
        - id
    PersonDetails:
      oneOf:
        - ${"$"}ref: '#/components/schemas/SimpleName'
        - ${"$"}ref: '#/components/schemas/DestructuredName'
    SimpleName:
      type: object
      properties:
        name:
          type: string
      required:
        - name
    DestructuredName:
      type: object
      properties:
        first_name:
          type: string
        last_name:
          type: string
      required:
        - first_name
        - last_name
    PersonRecord:
      allOf:
        - ${"$"}ref: '#/components/schemas/Id'
        - ${"$"}ref: '#/components/schemas/PersonDetails'
        """.trimIndent(), "").toFeature()

        @Test
        fun `matching stub`() {
            val result = feature.scenarios.first().matchesMock(
                HttpRequest(path = "/person/10", method = "GET"),
                HttpResponse.OK(parsedJSONObject("""{"id": 10, "name": "Sherlock Holmes"}""")),
            )

            println(result.reportString())

            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `also matching stub`() {
            val result = feature.scenarios.first().matchesMock(
                HttpRequest(path = "/person/10", method = "GET"),
                HttpResponse.OK(parsedJSONObject("""{"id": 10, "first_name": "Sherlock", "last_name": "Holmes"}"""))
            )

            println(result.reportString())

            assertThat(result).isInstanceOf(Result.Success::class.java)
        }

        @Test
        fun `non matching stub`() {
            val result = feature.scenarios.first().matchesMock(
                HttpRequest(path = "/person/10", method = "GET"),
                HttpResponse.OK(parsedJSONObject("""{"id": 10, "full_name": "Sherlock Holmes"}"""))
            )

            println(result.reportString())

            assertThat(result).isInstanceOf(Result.Failure::class.java)
        }
    }
}