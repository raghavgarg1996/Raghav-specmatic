package `in`.specmatic.conversions

import `in`.specmatic.core.HttpRequest
import `in`.specmatic.core.HttpResponse
import `in`.specmatic.core.pattern.parsedJSONObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OneOfAllOfCombinationsWithDiscriminator {
    @Test
    fun `allOf within oneOf`() {
        val feature = OpenApiSpecification.fromYAML("""
            openapi: 3.0.0
            info:
              title: My API
              version: 1.0.0
            paths:
              /example:
                post:
                  summary: Example endpoint
                  requestBody:
                    content:
                      application/json:
                        schema:
                          oneOf:
                            - ${"$"}ref: '#/components/schemas/Name1'
                            - ${"$"}ref: '#/components/schemas/Name2'
                          discriminator:
                            propertyName: type
                  responses:
                    '200':
                      description: OK
                      content:
                        text/plain:
                          schema:
                            type: string
            components:
              schemas:
                Name:
                  type: object
                  properties:
                    name:
                      type: string
                Type:
                  type: object
                  properties:
                    type:
                      type: string
                Name1:
                  allOf:
                    - ${"$"}ref: '#/components/schemas/Name'
                    - ${"$"}ref: '#/components/schemas/Type'
                Name2:
                  allOf:
                    - ${"$"}ref: '#/components/schemas/Name'
                    - ${"$"}ref: '#/components/schemas/Type'

        """.trimIndent(), "").toFeature()

        val requestForName1 =
            HttpRequest("POST", "/example", body = parsedJSONObject("""{"name": "John Doe", "type": "Name1"}"""))
        assertThat(feature.matches(requestForName1, HttpResponse.OK)).isTrue()

        val requestForName2 =
            HttpRequest("POST", "/example", body = parsedJSONObject("""{"name": "John Doe", "type": "Name2"}"""))
        assertThat(feature.matches(requestForName2, HttpResponse.OK)).isTrue()

        val requestForName3 =
            HttpRequest("POST", "/example", body = parsedJSONObject("""{"name": "John Doe", "type": "Name3"}"""))
        assertThat(feature.matches(requestForName3, HttpResponse.OK)).isFalse()
    }

    @Test
    fun `oneOf within allOf`() {
        val feature = OpenApiSpecification.fromYAML("""
openapi: 3.0.0
info:
  title: My API
  version: 1.0.0
paths:
  /example:
    post:
      summary: Example endpoint
      requestBody:
        content:
          application/json:
            schema:
              allOf:
                - ${"$"}ref: '#/components/schemas/Name'
                - ${"$"}ref: '#/components/schemas/Type'
      responses:
        '200':
          description: OK
          content:
            text/plain:
              schema:
                type: string
components:
  schemas:
    Name:
      type: object
      properties:
        name:
          type: string
    Type:
      oneOf:
        - ${"$"}ref: '#/components/schemas/Type1'
        - ${"$"}ref: '#/components/schemas/Type2'
      discriminator:
        propertyName: type
    Type1:
      type: object
      properties:
        type:
          type: string
    Type2:
      type: object
      properties:
        type:
          type: string
        """.trimIndent(), "").toFeature()

        val requestForName1 =
            HttpRequest("POST", "/example", body = parsedJSONObject("""{"name": "John Doe", "type": "Type1"}"""))
        println(feature.matchingStub(requestForName1, HttpResponse.OK))
        assertThat(feature.matches(requestForName1, HttpResponse.OK)).isTrue()

        val requestForName2 =
            HttpRequest("POST", "/example", body = parsedJSONObject("""{"name": "John Doe", "type": "Type2"}"""))
        assertThat(feature.matches(requestForName2, HttpResponse.OK)).isTrue()

        val requestForName3 =
            HttpRequest("POST", "/example", body = parsedJSONObject("""{"name": "John Doe", "type": "Type3"}"""))
        assertThat(feature.matches(requestForName3, HttpResponse.OK)).isFalse()
    }
}