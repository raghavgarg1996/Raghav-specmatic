package io.specmatic.core.pattern

import io.specmatic.core.*
import io.specmatic.core.pattern.config.NegativePatternConfiguration
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.ListValue
import io.specmatic.core.value.Value

data class ListPattern(override val pattern: Pattern, override val typeAlias: String? = null, override val example: List<String?>? = null) : Pattern, SequenceType, HasDefaultExample {
    override val memberList: MemberList
        get() = MemberList(emptyList(), pattern)

    override fun addTypeAliasesToConcretePattern(concretePattern: Pattern, resolver: Resolver, typeAlias: String?): Pattern {
        if(concretePattern !is JSONArrayPattern)
            return concretePattern

        return concretePattern.copy(
            typeAlias = typeAlias ?: this.typeAlias,
            pattern = concretePattern.pattern.map { concreteItemPattern ->
                pattern.addTypeAliasesToConcretePattern(concreteItemPattern, resolver)
            }
        )
    }

    override fun fillInTheBlanks(value: Value, resolver: Resolver): ReturnValue<Value> {
        val listValue = value as? JSONArrayValue ?: return HasFailure("Cannot generate a list from partial of type ${value.displayableType()}")
        val newList = listValue.list.map { pattern.fillInTheBlanks(it, resolver.plusDictionaryLookupDetails(null, "[*]")) }.listFold()

        return newList.ifValue { listValue.copy(list = it) }
    }

    override fun resolveSubstitutions(
        substitution: Substitution,
        value: Value,
        resolver: Resolver,
        key: String?
    ): ReturnValue<Value> {
        if(value !is JSONArrayValue)
            return HasFailure(Result.Failure("Cannot resolve substitutions, expected list but got ${value.displayableType()}"))

        val updatedList = value.list.mapIndexed { index, listItem ->
            pattern.resolveSubstitutions(substitution, listItem, resolver).breadCrumb("[$index]")
        }.listFold()

        return updatedList.ifValue { value.copy(list = it) }
    }

    override fun getTemplateTypes(key: String, value: Value, resolver: Resolver): ReturnValue<Map<String, Pattern>> {
        if(value !is JSONArrayValue)
            return HasFailure(Result.Failure("Cannot resolve data substitutions, expected list but got ${value.displayableType()}"))

        val initialValue: ReturnValue<Map<String, Pattern>> = HasValue(emptyMap<String, Pattern>())
        return value.list.fold(initialValue) { acc, valuePattern ->
            val patterns = pattern.getTemplateTypes("", valuePattern, resolver)

            acc.assimilate(patterns) { data, additional -> data + additional }
        }
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if(sampleData !is ListValue)
            return when {
                resolvedHop(pattern, resolver) is XMLPattern -> mismatchResult("xml nodes", sampleData, resolver.mismatchMessages)
                else -> mismatchResult(this, sampleData, resolver.mismatchMessages)
            }

        val resolverWithEmptyType = withEmptyType(pattern, resolver)

        val failures: List<Result.Failure> = sampleData.list.map {
            resolverWithEmptyType.matchesPattern(null, pattern, it)
        }.mapIndexed { index, result ->
            ResultWithIndex(index, result)
        }.filter {
            it.result is Result.Failure
        }.map {
            it.result.breadCrumb("[${it.index}]") as Result.Failure
        }

        return if(failures.isEmpty())
            Result.Success()
        else
            Result.Failure.fromFailures(failures)
    }

    override fun generate(resolver: Resolver): Value {
        val resolverWithEmptyType = withEmptyType(pattern, resolver)

        return resolver.resolveExample(example, pattern) ?: dictionaryLookup(resolverWithEmptyType) ?: generateRandomValue(resolverWithEmptyType)
    }

    private fun dictionaryLookup(resolver: Resolver): Value {
        return resolver.generateList(pattern)
    }

    private fun generateRandomValue(resolver: Resolver): Value {
        return pattern.listOf(0.until(randomNumber(3)).mapIndexed{ index, _ ->
            attempt(breadCrumb = "[$index (random)]") { pattern.generate(resolver) }
        }, resolver)
    }

    override fun newBasedOn(row: Row, resolver: Resolver): Sequence<ReturnValue<Pattern>> {
        val resolverWithEmptyType = withEmptyType(pattern, resolver)
        return attempt(breadCrumb = "[]") {
            resolverWithEmptyType.withCyclePrevention(pattern, true) { cyclePreventedResolver ->
                val patterns = pattern.newBasedOn(row.dropDownIntoList(), cyclePreventedResolver)
                try {
                    patterns.firstOrNull()?.value
                    patterns.map {
                        it.ifValue { ListPattern(it) }
                    }
                } catch(e: ContractException) {
                    if(e.isCycle)
                        null
                    else
                        throw e
                }
            } ?: sequenceOf(HasValue(ExactValuePattern(JSONArrayValue(emptyList()))))
        }
    }

    override fun newBasedOn(resolver: Resolver): Sequence<Pattern> {
        val resolverWithEmptyType = withEmptyType(pattern, resolver)
        return attempt(breadCrumb = "[]") {
            resolverWithEmptyType.withCyclePrevention(pattern) { cyclePreventedResolver ->
                pattern.newBasedOn(cyclePreventedResolver).map { ListPattern(it) }
            }
        }
    }

    override fun negativeBasedOn(row: Row, resolver: Resolver, config: NegativePatternConfiguration): Sequence<ReturnValue<Pattern>> = sequenceOf(HasValue(NullPattern))

    override fun parse(value: String, resolver: Resolver): Value = parsedJSONArray(value, resolver.mismatchMessages)

    override fun patternSet(resolver: Resolver): List<Pattern> {
        val resolverWithEmptyType = withEmptyType(pattern, resolver)
        return pattern.patternSet(resolverWithEmptyType)
    }

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        val thisResolverWithEmptyType = withEmptyType(pattern, thisResolver)
        val otherResolverWithEmptyType = withEmptyType(pattern, otherResolver)

        return when (otherPattern) {
            is ExactValuePattern -> otherPattern.fitsWithin(listOf(this), otherResolverWithEmptyType, thisResolverWithEmptyType, typeStack)
            is ListPattern -> biggerEncompassesSmaller(pattern, otherPattern.pattern, thisResolverWithEmptyType, otherResolverWithEmptyType, typeStack)
            is SequenceType -> {
                val results = otherPattern.memberList.getEncompassables(otherResolverWithEmptyType).asSequence().mapIndexed { index, otherPatternEntry ->
                    Pair(index, biggerEncompassesSmaller(pattern, otherPatternEntry, thisResolverWithEmptyType, otherResolverWithEmptyType, typeStack))
                }

                results.find { it.second is Result.Failure }?.let { result -> result.second.breadCrumb("[${result.first}]") } ?: Result.Success()
            }
            else -> Result.Failure("Expected array or list type, got ${otherPattern.typeName}")
        }
    }

    override fun encompasses(others: List<Pattern>, thisResolver: Resolver, otherResolver: Resolver, lengthError: String, typeStack: TypeStack): ConsumeResult<Pattern, Pattern> {
        val thisResolverWithEmptyType = withEmptyType(pattern, thisResolver)
        val otherResolverWithEmptyType = withEmptyType(pattern, otherResolver)

        val results = others.asSequence().mapIndexed { index, otherPattern ->
            when (otherPattern) {
                is ExactValuePattern ->
                    otherPattern.fitsWithin(listOf(this.pattern), otherResolverWithEmptyType, thisResolverWithEmptyType, typeStack)
                is SequenceType ->
                    biggerEncompassesSmaller(pattern, resolvedHop(otherPattern, otherResolverWithEmptyType), thisResolverWithEmptyType, otherResolverWithEmptyType, typeStack)
                else -> Result.Failure("Expected array or list type, got ${otherPattern.typeName}")
            }.breadCrumb("[$index]")
        }

        val result = results.find { it is Result.Failure } ?: Result.Success()

        return ConsumeResult(result, emptyList())
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        val resolverWithEmptyType = withEmptyType(pattern, resolver)
        return resolverWithEmptyType.withCyclePrevention(pattern) { pattern.listOf(valueList, it) }
    }

    override val typeName: String = "list of ${pattern.typeName}"
}

private fun withEmptyType(pattern: Pattern, resolver: Resolver): Resolver {
    val patternSet = pattern.patternSet(resolver)

    val hasXML = patternSet.any { resolvedHop(it, resolver) is XMLPattern }

    val emptyType = if(hasXML) EmptyStringPattern else NullPattern

    return resolver.copy(newPatterns = resolver.newPatterns.plus("(empty)" to emptyType))
}
