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

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if(pattern.isEmpty())
            return Result.Failure("allOf specified with no schemas")

        val homogenousTypeCheck = validatePatternTypes(resolver)
        if(homogenousTypeCheck is Result.Failure)
            return homogenousTypeCheck

        val firstType = pattern.firstOrNull() ?: return Result.Failure("allOf specified with no schemas")
        val resolvedFirstType = resolvedHop(firstType, resolver)

        val resolver: Resolver = if(resolvedFirstType is JSONType) {
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
            PatternMatchResult(it, resolver.matchesPattern(key, it, sampleData ?: EmptyString))
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
        val randomPattern = pattern.random()
        val isNullable = pattern.any {it is NullPattern}
        return resolver.withCyclePrevention(randomPattern, isNullable) { cyclePreventedResolver ->
            when (key) {
                null -> randomPattern.generate(cyclePreventedResolver)
                else -> cyclePreventedResolver.generate(key, randomPattern)
            }
        }?: NullValue // Terminates cycle gracefully. Only happens if isNullable=true so that it is contract-valid.
    }

    override fun newBasedOn(row: Row, resolver: Resolver): List<Pattern> {
        val isNullable = pattern.any {it is NullPattern}
        return pattern.sortedBy{ it is NullPattern }.flatMap { innerPattern ->
            resolver.withCyclePrevention(innerPattern, isNullable) { cyclePreventedResolver ->
                innerPattern.newBasedOn(row, cyclePreventedResolver)
            }?: listOf()  // Terminates cycle gracefully. Only happens if isNullable=true so that it is contract-valid.
        }
    }

    override fun newBasedOn(resolver: Resolver): List<Pattern> {
        val isNullable = pattern.any {it is NullPattern}
        return pattern.flatMap { innerPattern ->
            resolver.withCyclePrevention(innerPattern, isNullable) { cyclePreventedResolver ->
                innerPattern.newBasedOn(cyclePreventedResolver)
            }?: listOf()  // Terminates cycle gracefully. Only happens if isNullable=true so that it is contract-valid.
        }
    }

    override fun negativeBasedOn(row: Row, resolver: Resolver): List<Pattern> {
        val nullable = pattern.any { it is NullPattern }

        val negativeTypes = pattern.flatMap {
            it.negativeBasedOn(row, resolver)
        }.let {
            if (nullable)
                it.filterNot { it is NullPattern }
            else
                it
        }

        return if(negativeTypes.all { it is ScalarType })
            negativeTypes.distinct()
        else
            negativeTypes
    }

    override fun parse(value: String, resolver: Resolver): Value {
        val resolvedTypes = pattern.map { resolvedHop(it, resolver) }
        val nonNullTypesFirst = resolvedTypes.filterNot { it is NullPattern }.plus(resolvedTypes.filterIsInstance<NullPattern>())

        return nonNullTypesFirst.asSequence().map {
            try {
                it.parse(value, resolver)
            } catch (e: Throwable) {
                null
            }
        }.find { it != null } ?: throw ContractException(
            "Failed to parse value \"$value\". It should have matched one of ${
                pattern.joinToString(
                    ", "
                ) { it.typeName }
            }."
        )
    }

    override fun patternSet(resolver: Resolver): List<Pattern> =
        this.pattern.flatMap { it.patternSet(resolver) }

    override fun encompasses(
        otherPattern: Pattern,
        thisResolver: Resolver,
        otherResolver: Resolver,
        typeStack: TypeStack
    ): Result {
        val compatibleResult = otherPattern.fitsWithin(patternSet(thisResolver), otherResolver, thisResolver, typeStack)

        return if(compatibleResult is Result.Failure && allValuesAreScalar())
            mismatchResult(this, otherPattern, thisResolver.mismatchMessages)
        else
            compatibleResult
    }

    private fun allValuesAreScalar() = pattern.all { it is ExactValuePattern && it.pattern is ScalarValue }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        if (pattern.isEmpty())
            throw ContractException("AnyPattern doesn't have any types, so can't infer which type of list to wrap the given value in")

        return pattern.first().listOf(valueList, resolver)
    }

    override val typeName: String
        get() {
            return "( ${pattern.joinToString(" and ") { it.typeName }} )"
        }
}
