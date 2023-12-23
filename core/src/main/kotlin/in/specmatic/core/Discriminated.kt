package `in`.specmatic.core

import `in`.specmatic.core.pattern.ExactValuePattern
import `in`.specmatic.core.pattern.Pattern
import `in`.specmatic.core.value.JSONObjectValue
import `in`.specmatic.core.value.StringValue

data class Discriminated(val discriminatorKey: String, val discriminatorValue: String) : Discriminator {
    override fun matches(sampleData: JSONObjectValue): Result {
        if(discriminatorKey !in sampleData.jsonObject)
            return Result.Success()

        if(discriminatorValue == sampleData.jsonObject[discriminatorKey]?.toStringLiteral())
            return Result.Success()

        return Result.Failure("Discriminator value ${sampleData.jsonObject[discriminatorKey]} does not match expected value $discriminatorValue")
    }

    override fun discriminate(pattern: Map<String, Pattern>): Map<String, Pattern> {
        return if (hasDiscriminatorKey(pattern))
            updateWithDiscriminator(pattern)
        else
            pattern
    }

    private fun hasDiscriminatorKey(pattern: Map<String, Pattern>) =
        discriminatorKey in pattern || "$discriminatorKey?" in pattern

    private fun updateWithDiscriminator(
        pattern: Map<String, Pattern>
    ): Map<String, Pattern> {
        return removeDiscriminatorKey(pattern).plus(discriminatorKey to discriminatorValueAsExactValuePattern())
    }

    private fun discriminatorValueAsExactValuePattern() =
        ExactValuePattern(StringValue(discriminatorValue.removeSurrounding("(", ")")))

    private fun removeDiscriminatorKey(pattern: Map<String, Pattern>): Map<String, Pattern> =
        pattern.minus(discriminatorKey).minus("$discriminatorKey?")
}