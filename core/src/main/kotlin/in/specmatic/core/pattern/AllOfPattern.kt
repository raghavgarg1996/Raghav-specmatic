package `in`.specmatic.core.pattern

import `in`.specmatic.core.*
import `in`.specmatic.core.value.*

data class AllOfPattern(
    override val pattern: List<Pattern>,
    val key: String? = null,
    override val typeAlias: String? = null
) : Pattern {
    override fun equals(other: Any?): Boolean = other is AllOfPattern && other.pattern == this.pattern

    override fun hashCode(): Int = pattern.hashCode()

    override fun matchPatternKeys(sampleData: JSONObjectValue, resolver: Resolver): Pair<Result, List<String>> {
        val (keysMatched, results) = pattern.foldRight(Pair(emptySet<String>(), emptyList<Result>())) { type, acc ->
            val (keysMatchedSoFar, resultsSoFar) = acc
            val (result, keysMatched) = type.matchPatternKeys(sampleData, resolver)
            Pair(keysMatchedSoFar.plus(keysMatched), resultsSoFar.plus(result))
        }

        val failures = results.filterIsInstance<Result.Failure>()

        if(failures.isNotEmpty())
            return Pair(Result.fromFailures(failures), keysMatched.toList())

        return Pair(Result.Success(), keysMatched.toList())
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if(pattern.isEmpty())
            return Result.Failure("allOf specified with no schemas")

        return if(pattern.first().isJSONType(resolver)) {
            if(sampleData !is JSONObjectValue) {
                throw ContractException("allOf specified with JSON types, but sample data is not a JSON object")
            }

            val (result, keysMatched) = matchPatternKeys(
                sampleData,
                resolver.copy(findKeyErrorCheck = DefaultKeyCheck.ignoreUnexpectedKeys())
            )

            val unmatchedKeys = sampleData.jsonObject.keys.filter { it !in keysMatched }

            if(unmatchedKeys.isNotEmpty())
                Result.Failure("Unmatched keys: ${unmatchedKeys.joinToString(", ")}")
            else
                result
        } else {
            val matchResults = pattern.map {
                PatternMatchResult(it, resolver.matchesPattern(key, it, sampleData ?: EmptyString))
            }

            val matchFailures = matchResults.map { it.result }.filterIsInstance<Result.Failure>()

            if(matchFailures.isNotEmpty())
                Result.fromFailures(matchFailures)
            else
                Result.Success()
        }
    }

    override fun generate(resolver: Resolver): Value {
        return mergedPattern(resolver).generate(resolver)
    }

    fun mergedPattern(resolver: Resolver): Pattern {
        return pattern.reduce { acc, pattern ->
            resolver.withCyclePrevention(pattern, false) { cyclePreventedResolver ->
                acc.merge(pattern, cyclePreventedResolver)
            } ?: acc
        }
    }

    override fun newBasedOn(row: Row, resolver: Resolver): List<Pattern> {
        return mergedPattern(resolver).newBasedOn(row, resolver)
    }

    override fun newBasedOn(resolver: Resolver): List<Pattern> {
        return mergedPattern(resolver).newBasedOn(resolver)
    }

    override fun negativeBasedOn(row: Row, resolver: Resolver): List<Pattern> {
        return mergedPattern(resolver).negativeBasedOn(row, resolver)
    }

    override fun parse(value: String, resolver: Resolver): Value {
        return mergedPattern(resolver).parse(value, resolver)
    }

    override fun patternSet(resolver: Resolver): List<Pattern> =
        mergedPattern(resolver).patternSet(resolver)

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        return mergedPattern(thisResolver).encompasses(otherPattern, thisResolver, otherResolver, typeStack)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        if (pattern.isEmpty())
            throw ContractException("allOf doesn't have any types, so can't infer which type of list to wrap the given value in")

        return pattern.first().listOf(valueList, resolver)
    }

    override fun merge(pattern: Pattern, resolver: Resolver): Pattern {
        return mergedPattern(resolver).merge(pattern, resolver)
    }

    override val typeName: String
        get() {
            return "( ${pattern.joinToString(" and ") { it.typeName }} )"
        }
}
