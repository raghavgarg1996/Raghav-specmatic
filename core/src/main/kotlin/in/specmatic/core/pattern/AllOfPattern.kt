package `in`.specmatic.core.pattern

import `in`.specmatic.core.*
import `in`.specmatic.core.value.*

data class AllOfPattern(
    override val pattern: List<Pattern>,
    val key: String? = null,
) : Pattern {
    override val typeAlias: String? = null
    override fun equals(other: Any?): Boolean = other is AllOfPattern && other.pattern == this.pattern

    override fun hashCode(): Int = pattern.hashCode()

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if(pattern.isEmpty())
            return Result.Failure("allOf specified with no schemas")

        val homogenousTypeCheck = validatePatternTypes(resolver)
        if(homogenousTypeCheck is Result.Failure)
            return homogenousTypeCheck

        val firstType = pattern.firstOrNull() ?: return Result.Failure("allOf specified with no schemas")
        val resolvedFirstType = resolvedHop(firstType, resolver)

        val resolverBasedOnType: Resolver = if(resolvedFirstType is JSONType) {
            if(sampleData !is JSONObjectValue) {
                throw ContractException("allOf specified with JSON types, but sample data is not a JSON object")
            }

            val recognizedKeys = pattern.map { resolvedHop(it, resolver) }.filterIsInstance<JSONType>().flatMap { it.keys }
            val unrecognizedKeys = sampleData.jsonObject.keys.filterNot { it in recognizedKeys }

            if(unrecognizedKeys.isNotEmpty())
                return Result.fromFailures(unrecognizedKeys.map { MissingKeyError(it) }.map { it.missingKeyToResult("key") })

            resolver.copy(findKeyErrorCheck = DefaultKeyCheck.ignoreUnexpectedKeys())
        } else {
            resolver
        }

        val matchResults = pattern.map {
            PatternMatchResult(it, resolverBasedOnType.matchesPattern(key, it, sampleData ?: EmptyString))
        }

        val matchFailures = matchResults.map { it.result }.filterIsInstance<Result.Failure>()

        if(matchFailures.isNotEmpty())
            return Result.fromFailures(matchFailures)

        return Result.Success()
    }

    private fun validatePatternTypes(resolver: Resolver): Result {
        val resolvedTypes = pattern.map { resolvedHop(it, resolver) }
        val classes = resolvedTypes.map { it::class.java }

        if(classes.sortedBy { it.name }.distinct().size > 1)
            return Result.Failure("allOf can only contain patterns of the same type. Found: ${resolvedTypes.joinToString(", ") { it.typeName }}")

        return Result.Success()
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
