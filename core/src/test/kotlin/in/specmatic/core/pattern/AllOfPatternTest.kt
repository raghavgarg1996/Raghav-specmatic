package `in`.specmatic.core.pattern

import `in`.specmatic.core.Resolver
import `in`.specmatic.core.Result
import `in`.specmatic.core.value.StringValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class AllOfPatternTest {
    companion object {
        @JvmStatic
        fun stringTestCases(): Stream<Arguments> {
            return Stream.of(
                Arguments.of("12345", true),
                Arguments.of("12346", true),
                Arguments.of("1234567890", true),
                Arguments.of("12345678901", false),
                Arguments.of("1234", false),
                Arguments.of("", false),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("stringTestCases")
    fun `it matches a string within the range`(value: String, success: Boolean) {
        val allOf = AllOfPattern(listOf(StringPattern(minLength = 5), StringPattern(maxLength = 10)))

        val result = allOf.matches(StringValue(value), Resolver())
        assertThat(result.isSuccess()).isEqualTo(success)
    }

    @Test
    fun `it matches a series of JSONObjects`() {
        val allOf = AllOfPattern(
            listOf(
                JSONObjectPattern(mapOf("id" to NumberPattern())),
                JSONObjectPattern(mapOf("name" to StringPattern()))))

        val sampleData =
            parsedJSONObject("""
                {
                    "id": 1,
                    "name": "name"
                }
            """)

        val result = allOf.matches(sampleData, Resolver())

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `it catches keys unexpected across all objects`() {
        val allOf = AllOfPattern(
            listOf(
                JSONObjectPattern(mapOf("id" to NumberPattern())),
                JSONObjectPattern(mapOf("name" to StringPattern()))))

        val sampleData =
            parsedJSONObject("""
                {
                    "id": 1,
                    "name": "name",
                    "this-key-is-not-in-any-type": 20
                }
            """)
        val result = allOf.matches(sampleData, Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).contains("this-key-is-not-in-any-type")
    }

    @Test
    fun `it matches a series of JSONObjects with the same key having differing optionality`() {
        val allOf = AllOfPattern(
            listOf(
                JSONObjectPattern(mapOf("id?" to NumberPattern())),
                JSONObjectPattern(mapOf("id" to NumberPattern()))))

        val sampleData =
            parsedJSONObject("""
                {
                    "id": 1
                }
            """)

        val result = allOf.matches(sampleData, Resolver())

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `all the options in allOf must be of the same type`() {
        val allOf = AllOfPattern(
            listOf(
                StringPattern(),
                NumberPattern()))

        val sampleData = StringValue("abc")
        val result = allOf.matches(sampleData, Resolver())

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.reportString()).contains("Expected number")
    }

    @Test
    fun `it generates an object matching all types`() {
        val allOf = AllOfPattern(
            listOf(
                JSONObjectPattern(mapOf("id" to NumberPattern())),
                JSONObjectPattern(mapOf("name" to StringPattern())))
        )

        val value = allOf.generate(Resolver())

        assertThat(allOf.matches(value, Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `it generates tests based on an example having all fields`() {
        val allOf = AllOfPattern(
            listOf(
                JSONObjectPattern(mapOf("id" to NumberPattern())),
                JSONObjectPattern(mapOf("name" to StringPattern())))
        )

        val generatedTypes = allOf.newBasedOn(Row(columnNames = listOf("id", "name"), values = listOf("10", "Jeremy")), Resolver())
        val value = generatedTypes.first().generate(Resolver())

        assertThat(value).isEqualTo(parsedJSONObject("""{"id": 10, "name": "Jeremy"}"""))
    }

    @Test
    fun `the backward compatibility test should pass for a backward compatible change to a json object`() {
        val older = AllOfPattern(
            listOf(
                JSONObjectPattern(mapOf("id" to NumberPattern())),
                JSONObjectPattern(mapOf("name" to StringPattern())))
        )

        val newer = AllOfPattern(
            listOf(
                JSONObjectPattern(mapOf("id" to NumberPattern())),
                JSONObjectPattern(mapOf(
                    "name" to StringPattern(),
                    "description?" to StringPattern())))
        )

        val backwardCompatibleResult = newer.encompasses(older, Resolver(), Resolver())
        assertThat(backwardCompatibleResult).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `the backward compatibility test should pass when comparing allOf string to string`() {
        val older = AllOfPattern(listOf(StringPattern()))
        val newer = StringPattern()

        val backwardCompatibleResult = newer.encompasses(older, Resolver(), Resolver())

        println(backwardCompatibleResult.reportString())

        assertThat(backwardCompatibleResult).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `the backward compatibility test should fail for a backward incompatible change from number to string`() {
        val older = AllOfPattern(
            listOf(
                JSONObjectPattern(mapOf("id" to NumberPattern())),
                JSONObjectPattern(mapOf("name" to StringPattern())))
        )

        val newer = AllOfPattern(
            listOf(
                JSONObjectPattern(mapOf("id" to StringPattern())),
                JSONObjectPattern(mapOf(
                    "name" to StringPattern())))
        )

        val backwardCompatibleResult = newer.encompasses(older, Resolver(), Resolver())
        assertThat(backwardCompatibleResult).isInstanceOf(Result.Failure::class.java)
    }
}