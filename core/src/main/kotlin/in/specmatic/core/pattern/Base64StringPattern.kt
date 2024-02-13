package `in`.specmatic.core.pattern

import `in`.specmatic.core.Resolver
import `in`.specmatic.core.Result
import `in`.specmatic.core.mismatchResult
import `in`.specmatic.core.value.JSONArrayValue
import `in`.specmatic.core.value.StringValue
import `in`.specmatic.core.value.Value
import org.apache.commons.codec.binary.Base64
import java.util.*

data class Base64StringPattern(override val typeAlias: String? = null) : Pattern, ScalarPattern {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        return when (sampleData) {
            is StringValue -> {
                return if (Base64.isBase64(sampleData.string)) Result.Success() else mismatchResult("string of bytes (base64)", sampleData, resolver.mismatchMessages)
            }

            else -> mismatchResult("string of bytes (base64)", sampleData, resolver.mismatchMessages)
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

    private val randomStringLength: Int = 5

    override fun generate(resolver: Resolver): Value {
        return StringValue(randomBase64String(randomStringLength))
    }

    override fun newBasedOn(row: Row, resolver: Resolver): List<Pattern> = listOf(this)
    override fun newBasedOn(resolver: Resolver): List<Pattern> = listOf(this)
    override fun negativeBasedOn(row: Row, resolver: Resolver): List<Pattern> {
        // TODO ideally StringPattern should be in this list. However need to better understand how to generate
        //      strings that are not valid base64 strings (e.g. send an exact "]" which is not a base64 encoded value)

        return listOf(NullPattern, NumberPattern(), BooleanPattern())
    }

    override fun parse(value: String, resolver: Resolver): Value {
        if(! Base64.isBase64(value))
            throw ContractException("Expected a base64 string but got \"$value\"")

        return StringValue(value)
    }

    override val typeName: String = "string"
    override val pattern: Any = "(string)"
}

fun randomBase64String(length: Int = 5): String {
    val array = ByteArray(length)
    val random = Random()
    for (index in array.indices) {
        array[index] = (random.nextInt(25) + 65).toByte()
    }
    return Base64.encodeBase64String(array)
}