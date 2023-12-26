package `in`.specmatic.conversions

import `in`.specmatic.core.HttpRequest
import `in`.specmatic.core.HttpResponse
import `in`.specmatic.core.value.JSONObjectValue
import `in`.specmatic.core.value.Value
import `in`.specmatic.test.TestExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest

class AllOfSupport {
    val feature = OpenApiSpecification.fromYAML("""
            openapi: 3.0.0
            info:
              title: Test
              version: 1.0.0
            paths:
              /data:
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
                  allOf:
                    - ${"$"}ref: '#/components/schemas/Name'
                    - ${"$"}ref: '#/components/schemas/Address'
                Name:
                  type: object
                  minProperties: 1
                  maxProperties: 1
                  required:
                    - full_name
                    - last_name
                  properties:
                    full_name:
                      type: string
                    last_name:
                      type: string
                Address:
                  type: object
                  required:
                    - address
                  properties:
                    address:
                      type: string
        """.trimIndent(), "").toFeature()

//    @RepeatedTest(10)
//    fun `minProperties and maxProperties within an allOf should be honored`() {
//        val results =feature.executeTests(object : TestExecutor {
//            override fun execute(request: HttpRequest): HttpResponse {
//                println(request.body.toStringLiteral())
//
//                val requestBody = request.body as JSONObjectValue
//
//                assertThat(requestBody).satisfiesAnyOf(
//                    { assertThat(it.jsonObject).containsKey("full_name") },
//                    { assertThat(it.jsonObject).containsKey("last_name") }
//                )
//
//                assertThat(requestBody.jsonObject).hasSize(2)
//
//                return HttpResponse.OK
//            }
//
//            override fun setServerState(serverState: Map<String, Value>) {
//
//            }
//        })
//
//        assertThat(results.success()).isTrue
//    }
}