package `in`.specmatic.core.pattern

import `in`.specmatic.core.*
import `in`.specmatic.core.utilities.mapZip
import `in`.specmatic.core.utilities.stringToPatternMap
import `in`.specmatic.core.utilities.withNullPattern
import `in`.specmatic.core.value.JSONArrayValue
import `in`.specmatic.core.value.JSONObjectValue
import `in`.specmatic.core.value.Value
import java.util.Optional

fun toJSONObjectPattern(jsonContent: String, typeAlias: String?): JSONObjectPattern = toJSONObjectPattern(stringToPatternMap(jsonContent), typeAlias)

fun toJSONObjectPattern(map: Map<String, Pattern>, typeAlias: String? = null): JSONObjectPattern {
    val missingKeyStrategy: UnexpectedKeyCheck = when ("...") {
        in map -> IgnoreUnexpectedKeys
        else -> ValidateUnexpectedKeys
    }

    return JSONObjectPattern(map.minus("..."), missingKeyStrategy, typeAlias)
}

data class JSONObjectPattern(override val pattern: Map<String, Pattern> = emptyMap(), private val unexpectedKeyCheck: UnexpectedKeyCheck = ValidateUnexpectedKeys, override val typeAlias: String? = null) : Pattern, JSONType {
    override val keys: List<String>
        get() = pattern.keys.toList().map { withoutOptionality(it) }

    override fun equals(other: Any?): Boolean = when (other) {
        is JSONObjectPattern -> this.pattern == other.pattern
        else -> false
    }

    override fun isJSONType(resolver: Resolver): Boolean {
        return true
    }

    override fun merge(pattern: Pattern, resolver: Resolver): Pattern {
        if (pattern !is JSONObjectPattern)
            throw ContractException("Cannot merge ${this.typeAlias} with ${pattern.typeAlias}")

        return JSONObjectPattern(mergeObjectEntries(this.pattern, pattern.pattern, resolver))
    }

    override fun matchPatternKeys(sampleData: JSONObjectValue, resolver: Resolver): Pair<Result, List<String>> {
        val result = this.matches(sampleData, resolver)

        val patternKeys = pattern.keys.map { withoutOptionality(it) }.toSet()
        val unmatchedPortion: Map<String, Value> = sampleData.jsonObject.filter { withoutOptionality(it.key) in patternKeys }

        return Pair(result, pattern.keys.map { withoutOptionality(it) })
    }

    override fun encompasses(_otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        val otherPattern = resolvedHop(_otherPattern, otherResolver)
        val thisResolverWithNullType = withNullPattern(thisResolver)
        val otherResolverWithNullType = withNullPattern(otherResolver)

        return when (otherPattern) {
            is ExactValuePattern -> otherPattern.fitsWithin(listOf(this), otherResolverWithNullType, thisResolverWithNullType, typeStack)
            is TabularPattern -> mapEncompassesMap(pattern, otherPattern.pattern, thisResolverWithNullType, otherResolverWithNullType, typeStack)
            is JSONObjectPattern -> mapEncompassesMap(pattern, otherPattern.pattern, thisResolverWithNullType, otherResolverWithNullType, typeStack)
            else -> Result.Failure("Expected json type, got ${otherPattern.typeName}")
        }
    }

    override fun generateWithAll(resolver: Resolver): Value {
        return attempt(breadCrumb = "HEADERS") {
            JSONObjectValue(pattern.filterNot { it.key == "..." }.mapKeys {
                attempt(breadCrumb = it.key) {
                    withoutOptionality(it.key)
                }
            }.mapValues {
                it.value.generateWithAll(resolver)
            })
        }
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return JSONArrayValue(valueList)
    }

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        val resolverWithNullType = withNullPattern(resolver)
        if (sampleData !is JSONObjectValue)
            return mismatchResult("JSON object", sampleData, resolver.mismatchMessages)

        val keyErrors: List<Result.Failure> = resolverWithNullType.findKeyErrorList(pattern, sampleData.jsonObject).map {
            it.missingKeyToResult("key", resolver.mismatchMessages).breadCrumb(it.name)
        }

        val results: List<Result.Failure> = mapZip(pattern, sampleData.jsonObject).map { (key, patternValue, sampleValue) ->
            resolverWithNullType.matchesPattern(key, patternValue, sampleValue).breadCrumb(key)
        }.filterIsInstance<Result.Failure>()

        val failures = keyErrors.plus(results)

        return if(failures.isEmpty())
            Result.Success()
        else
            Result.Failure.fromFailures(failures)
    }

    override fun generate(resolver: Resolver): JSONObjectValue =
        JSONObjectValue(generate(pattern, withNullPattern(resolver)))

    override fun newBasedOn(row: Row, resolver: Resolver): List<JSONObjectPattern> =
        allOrNothingCombinationIn(pattern.minus("..."), if(resolver.generativeTestingEnabled) Row() else row) { pattern ->
            newBasedOn(pattern, row, withNullPattern(resolver))
        }.map { toJSONObjectPattern(it.mapKeys { (key, _) ->
            withoutOptionality(key)
        }) }

    override fun newBasedOn(resolver: Resolver): List<JSONObjectPattern> =
        allOrNothingCombinationIn(pattern.minus("...")) { pattern ->
            newBasedOn(pattern, withNullPattern(resolver))
        }.map { toJSONObjectPattern(it) }

    override fun negativeBasedOn(row: Row, resolver: Resolver): List<Pattern> =
        allOrNothingCombinationIn(pattern.minus("...")) { pattern ->
            negativeBasedOn(pattern, row, withNullPattern(resolver))
        }.map { toJSONObjectPattern(it) }

    override fun parse(value: String, resolver: Resolver): Value = parsedJSONObject(value, resolver.mismatchMessages)
    override fun hashCode(): Int = pattern.hashCode()

    override val typeName: String = "json object"
}

fun generate(jsonPattern: Map<String, Pattern>, resolver: Resolver): Map<String, Value> {
    val resolverWithNullType = withNullPattern(resolver)

    val optionalProps = jsonPattern.keys.filter { isOptional(it) }.map { withoutOptionality(it) }

    return jsonPattern
        .mapKeys { entry -> withoutOptionality(entry.key) }
        .mapValues { (key, pattern) ->
            attempt(breadCrumb = key) {
                // Handle cycle (represented by null value) by marking this property as removable
                Optional.ofNullable(resolverWithNullType.withCyclePrevention(pattern, optionalProps.contains(key)) {
                    it.generate(key, pattern)
                })
            }
        }
        .filterValues { it.isPresent }
        .mapValues { (key, opt) -> opt.get()}
}

internal fun mapEncompassesMap(pattern: Map<String, Pattern>, otherPattern: Map<String, Pattern>, thisResolverWithNullType: Resolver, otherResolverWithNullType: Resolver, typeStack: TypeStack = emptySet()): Result {
    val myRequiredKeys = pattern.keys.filter { !isOptional(it) }
    val otherRequiredKeys = otherPattern.keys.filter { !isOptional(it) }

    val missingFixedKeyErrors: List<Result.Failure> = myRequiredKeys.filter { it !in otherRequiredKeys }.map { missingFixedKey ->
        MissingKeyError(missingFixedKey).missingKeyToResult("key", thisResolverWithNullType.mismatchMessages).breadCrumb(withoutOptionality(missingFixedKey))
    }

    val keyErrors = pattern.keys.map { key ->
        val bigger = pattern.getValue(key)
        val smaller = otherPattern[key] ?: otherPattern[withoutOptionality(key)]

        when {
            smaller != null -> biggerEncompassesSmaller(bigger, smaller, thisResolverWithNullType, otherResolverWithNullType, typeStack).breadCrumb(withoutOptionality(key))
            else -> Result.Success()
        }
    }

    return Result.fromResults(missingFixedKeyErrors.plus(keyErrors))
}

fun mergeObjectEntries(pattern1: Map<String, Pattern>, pattern2: Map<String, Pattern>, resolver: Resolver): Map<String, Pattern> {
    return pattern2.entries.fold(pattern1) { acc, entry2 ->
        val mandatoryKey = withoutOptionality(entry2.key)
        val optionalKey = "$mandatoryKey?"

        val keyInAcc = listOf(mandatoryKey, optionalKey).find { it in acc }

        when(Pair(keyInAcc, entry2.key)) {
            Pair(null, optionalKey), Pair(null, mandatoryKey) -> {
                acc.plus(entry2.key to entry2.value)
            }
            Pair(optionalKey, mandatoryKey) -> {
                val accValueType = acc.getValue(optionalKey)
                val mergedType = accValueType.merge(entry2.value, resolver)

                acc.minus(optionalKey).plus(mandatoryKey to mergedType)
            }
            Pair(mandatoryKey, optionalKey) -> {
                val accValueType = acc.getValue(mandatoryKey)
                val mergedType = accValueType.merge(entry2.value, resolver)

                acc.plus(mandatoryKey to mergedType)
            }
            else -> {
                val accValueType = acc.getValue(entry2.key)
                val mergedType = accValueType.merge(entry2.value, resolver)
                acc.plus(entry2.key to mergedType)
            }
        }
    }
}
