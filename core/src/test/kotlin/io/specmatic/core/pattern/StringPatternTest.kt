package io.specmatic.core.pattern

import io.specmatic.GENERATION
import io.specmatic.core.Resolver
import io.specmatic.core.Result
import io.specmatic.core.UseDefaultExample
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import io.specmatic.shouldNotMatch
import org.apache.commons.lang3.RandomStringUtils
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class StringPatternTest {
    @Test
    fun `should fail to match null values gracefully`() {
        NullValue shouldNotMatch StringPattern()
    }

    @Test
    fun `should not allow maxLength less than minLength`() {
        val exception = assertThrows<IllegalArgumentException> { StringPattern(minLength = 6, maxLength = 4) }
        assertThat(exception.message).isEqualTo("maxLength cannot be less than minLength")
    }

    @Test
    fun `should allow maxLength equal to minLength`() {
        StringPattern(minLength = 4, maxLength = 4)
    }

    @Test
    fun `should generate 5 character long random string when min and max length are not specified`() {
        assertThat(StringPattern().generate(Resolver()).toStringLiteral().length).isEqualTo(5)
    }

    @Test
    fun `should generate random string based on minLength`() {
        assertThat(StringPattern(minLength = 8).generate(Resolver()).toStringLiteral().length).isEqualTo(8)
    }

    @Test
    fun `should match empty string when min and max are not specified`() {
        assertThat(StringPattern().matches(StringValue(""), Resolver()).isSuccess()).isTrue
    }

    @Test
    fun `should match string of any length when min and max are not specified`() {
        val randomString = RandomStringUtils.randomAlphabetic((0..99).random())
        assertThat(StringPattern().matches(StringValue(randomString), Resolver()).isSuccess()).isTrue
    }

    @Test
    fun `should not match when string is shorter than minLength`() {
        val result = StringPattern(minLength = 4).matches(StringValue("abc"), Resolver())
        assertThat(result.isSuccess()).isFalse
        assertThat(result.reportString()).isEqualTo("""Expected string with minLength 4, actual was "abc"""")
    }

    @Test
    fun `should not match when string is longer than maxLength`() {
        val result = StringPattern(maxLength = 3).matches(StringValue("test"), Resolver())
        assertThat(result.isSuccess()).isFalse
        assertThat(result.reportString()).isEqualTo("""Expected string with maxLength 3, actual was "test"""")
    }

    @ParameterizedTest
    @CsvSource(
        "null, 10, 5",
        "null, 4, 1",
        "1, 10, 1",
        "5, 10, 5",
        "6, null, 6",
        "null, null, 5",
    )
    fun `generate string value as per minLength and maxLength`(min: String?, max: String?, expectedLength: Int) {
        val minLength = if (min == "null") null else min?.toInt()
        val maxLength = if (max == "null") null else max?.toInt()

        val result = StringPattern(minLength = minLength, maxLength = maxLength).generate(Resolver()) as StringValue
        val generatedLength = result.string.length

        assertThat(generatedLength).isGreaterThanOrEqualTo(expectedLength)
        maxLength?.let { assertThat(generatedLength).isLessThanOrEqualTo(it) }
    }

    @ParameterizedTest
    @CsvSource(
        "'^\\w+(-\\w+)*$',null,10,1",
        "'^[a-zA-Z0-9]+(-[a-zA-Z0-9]+)*$',1,10,1",
        "'^[a-z]*$', null, null, 5",
        "'^[a-z0-9]{6,10}',6,10,6",
        "null, 1, 10, 1"
    )
    fun `generate string value as per regex in conjunction with minLength and maxLength`(
        regex: String?, min: String?, max: String?, expectedLength: Int
    ) {
        repeat(10) {
            val minLength = min?.toIntOrNull()
            val maxLength = max?.toIntOrNull()
            val patternRegex = if (regex == "null") null else regex

            val result = StringPattern(
                minLength = minLength,
                maxLength = maxLength,
                regex = patternRegex
            ).generate(Resolver()) as StringValue
            val generatedString = result.string
            val generatedLength = generatedString.length

            assertThat(generatedLength).isGreaterThanOrEqualTo(expectedLength)
            maxLength?.let { assertThat(generatedLength).isLessThanOrEqualTo(it) }
            patternRegex?.let { assertThat(generatedString).matches(patternRegex) }
        }
    }

    @Test
    fun `string should encompass enum of string`() {
        val result: Result = StringPattern().encompasses(
            AnyPattern(
                listOf(
                    ExactValuePattern(StringValue("01")),
                    ExactValuePattern(StringValue("02"))
                )
            ), Resolver(), Resolver()
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `enum should not encompass string`() {
        val result: Result = AnyPattern(
            listOf(
                ExactValuePattern(StringValue("01")),
                ExactValuePattern(StringValue("02"))
            )
        ).encompasses(
            StringPattern(), Resolver(), Resolver()
        )

        println(result.reportString())
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }

    @Test
    fun `it should use the example if provided when generating`() {
        val generated = StringPattern(example = "sample data").generate(Resolver(defaultExampleResolver = UseDefaultExample))
        assertThat(generated).isEqualTo(StringValue("sample data"))
    }

    @Test
    @Tag(GENERATION)
    fun `generates other data types and null for negative inputs`() {
        val result = StringPattern().negativeBasedOn(Row(), Resolver()).map { it.value }.toList()
        assertThat(result.map { it.typeName }).containsExactlyInAnyOrder(
            "null",
            "number",
            "boolean",
        )
    }

    @Test
    @Tag(GENERATION)
    fun `positive values for lengths should be generated when lengths are provided`() {
        val minLength = 10
        val maxLength = 20

        val result = StringPattern(minLength = minLength, maxLength = maxLength).newBasedOn(Row(), Resolver()).toList()

        val randomlyGeneratedStrings = result.map { it.value } .filterIsInstance<ExactValuePattern>().map { it.pattern.toString() }

        assertThat(randomlyGeneratedStrings.filter { it.length == minLength}).hasSize(1)
        assertThat(randomlyGeneratedStrings.filter { it.length == maxLength}).hasSize(1)
    }

    @Test
    @Tag(GENERATION)
    fun `negative values for lengths should be generated when lengths are provided`() {
        val minLength = 10
        val maxLength = 20

        val result = StringPattern(minLength = minLength, maxLength = maxLength).negativeBasedOn(Row(), Resolver()).map { it.value }.toList()

        assertThat(
            result.filterIsInstance<StringPattern>().filter {
                it.minLength == minLength-1 && it.maxLength == minLength-1 && it.regex == null
            }
        ).hasSize(1)

        assertThat(
            result.filterIsInstance<StringPattern>().filter {
                it.minLength == maxLength+1 && it.maxLength == maxLength+1 && it.regex == null
            }
        ).hasSize(1)
    }

    @Test
    @Tag(GENERATION)
    fun `negative value for regex should be generated when regex is provided`() {
        val minLength = 10
        val maxLength = 20

        val result = StringPattern(
            minLength = minLength,
            maxLength = maxLength,
            regex = "^[^0-9]{15}$"
        ).negativeBasedOn(Row(), Resolver()).map { it.value }.toList()

        assertThat(
            result.filterIsInstance<StringPattern>().filter {
                it.regex == "^[^0-9]{15}\$_"
            }
        ).hasSize(1)
    }

    @Test
    @Tag(GENERATION)
    fun `should exclude data type based negatives when withDataTypeNegatives config is false`() {
        val minLength = 10
        val maxLength = 20

        val result = StringPattern(
            minLength = minLength,
            maxLength = maxLength,
            regex = "^[^0-9]{15}$"
        ).negativeBasedOn(
            Row(),
            Resolver(),
            NegativePatternConfiguration(withDataTypeNegatives = false)
        ).map { it.value }.toList()


        assertThat(
            result.filterIsInstance<NullPattern>()
                    + result.filterIsInstance<NumberPattern>()
                    + result.filterIsInstance<BooleanPattern>()
        ).hasSize(0)

        assertThat(
            result.filterIsInstance<StringPattern>().filter { it.regex == "^[^0-9]{15}\$_" }
        ).hasSize(1)

        assertThat(
            result.filterIsInstance<StringPattern>().filter {
                it.minLength == minLength-1 && it.maxLength == minLength-1 && it.regex == null
            }
        ).hasSize(1)

        assertThat(
            result.filterIsInstance<StringPattern>().filter {
                it.minLength == maxLength+1 && it.maxLength == maxLength+1 && it.regex == null
            }
        ).hasSize(1)
    }

    @Test
    fun `string pattern encompasses email`() {
        assertThat(StringPattern().encompasses(EmailPattern(), Resolver(), Resolver())).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun `should fail to generate string when maxLength is less than minLength`() {
        val exception = assertThrows<IllegalArgumentException> {
            StringPattern(minLength = 6, maxLength = 4)
        }
        assertThat(exception.message).isEqualTo("maxLength cannot be less than minLength")
    }

    @Test
    fun `should not allow construction of string with minLength is greater that what is possible with regex`() {
        val tenOccurrencesOfAlphabetA = "^a{10}\$"
        assertThrows<Exception> { StringPattern(minLength = 15, maxLength = 20, regex = tenOccurrencesOfAlphabetA) }
            .also { assertThat(it.message).isEqualTo("Invalid String Constraints - minLength cannot be greater than length of shortest possible string that matches regex") }
    }

    @Test
    fun `should not allow construction of string with maxLength is lesser that what is possible with regex`() {
        val tenOccurrencesOfAlphabetA = "^a{10}\$"
        assertThrows<Exception> { StringPattern(minLength = 5, maxLength = 8, regex = tenOccurrencesOfAlphabetA) }
            .also { assertThat(it.message).isEqualTo("Invalid String Constraints - maxLength cannot be less than length of shortest possible string that matches regex") }
    }

    @Test
    fun `should not allow construction of string with maxLength is greater that what is possible with regex`() {
        val fiveToElevenOccurrencesOfAlphabetA = "^a{5,11}\$"
        assertThrows<Exception> { StringPattern(minLength = 5, maxLength = 10, regex = fiveToElevenOccurrencesOfAlphabetA) }
            .also { assertThat(it.message).isEqualTo("Invalid String Constraints - regex cannot generate / match string greater than maxLength") }
    }

    @Test
    fun `should throw an exception with the regex parse failure from the regex library` () {
        assertThatThrownBy { StringPattern(regex = "yes|no|") }.hasMessageContaining("unexpected end-of-string")
    }
}
