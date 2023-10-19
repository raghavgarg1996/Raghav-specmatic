package `in`.specmatic.core.pattern

import `in`.specmatic.core.Resolver
import `in`.specmatic.core.Result
import `in`.specmatic.core.mismatchResult
import `in`.specmatic.core.value.JSONArrayValue
import `in`.specmatic.core.value.StringValue
import `in`.specmatic.core.value.Value
import java.nio.charset.StandardCharsets
import java.util.*

data class StringPattern(
    override val typeAlias: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null
) : Pattern, ScalarType {
    init {
        require(minLength?.let { maxLength?.let { minLength <= maxLength } }
            ?: true) { """maxLength cannot be less than minLength""" }
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        return when (sampleData) {
            is StringValue -> {
                if (minLength != null && sampleData.toStringLiteral().length < minLength) return mismatchResult(
                    "string with minLength $minLength",
                    sampleData, resolver.mismatchMessages
                )
                if (maxLength != null && sampleData.toStringLiteral().length > maxLength) return mismatchResult(
                    "string with maxLength $maxLength",
                    sampleData, resolver.mismatchMessages
                )
                return Result.Success()
            }
            else -> mismatchResult("string", sampleData, resolver.mismatchMessages)
        }
    }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        return encompasses(this, otherPattern, thisResolver, otherResolver, typeStack)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    private val randomStringLength: Int =
        when {
            minLength != null && 5 < minLength -> minLength
            maxLength != null && 5 > maxLength -> maxLength
            else -> 5
        }
    
    override fun generate(resolver: Resolver): Value = StringValue(randomString(randomStringLength))

    override fun newBasedOn(row: Row, resolver: Resolver): List<Pattern> = listOf(this)
    override fun newBasedOn(resolver: Resolver): List<Pattern> = listOf(this)
    override fun negativeBasedOn(row: Row, resolver: Resolver): List<Pattern> {
        return listOf(NullPattern, NumberPattern(), BooleanPattern)
    }

    override fun parse(value: String, resolver: Resolver): Value = StringValue(value)
    override val typeName: String = "string"

    override val pattern: Any = "(string)"

    override fun merge(pattern: Pattern, resolver: Resolver): Pattern {
        if (pattern !is StringPattern)
            throw ContractException("Cannot merge ${this.typeAlias} with ${pattern.typeAlias}")

        val resolvedMin = listOfNotNull(minLength, pattern.minLength).maxOrNull()
        val resolvedMax = listOfNotNull(maxLength, pattern.maxLength).minOrNull()

        return StringPattern(minLength = resolvedMin, maxLength = resolvedMax)
    }

    override fun toString(): String = "(string minLength=$minLength maxLength=$maxLength)"
}

fun randomString(length: Int = 5): String {
    val array = ByteArray(length)
    val random = Random()
    for (index in array.indices) {
        array[index] = (random.nextInt(25) + 65).toByte()
    }
    return String(array, StandardCharsets.UTF_8)
}
