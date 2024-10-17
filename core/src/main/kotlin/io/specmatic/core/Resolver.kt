package io.specmatic.core

import io.specmatic.core.log.logger
import io.specmatic.core.pattern.*
import io.specmatic.core.value.JSONArrayValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.True
import io.specmatic.core.value.Value

val actualMatch: (resolver: Resolver, factKey: String?, pattern: Pattern, sampleValue: Value) -> Result = { resolver: Resolver, factKey: String?, pattern: Pattern, sampleValue: Value ->
    resolver.actualPatternMatch(factKey, pattern, sampleValue)
}

val matchAnything: (resolver: Resolver, factKey: String?, pattern: Pattern, sampleValue: Value) -> Result = { _: Resolver, _: String?, _: Pattern, _: Value ->
    Result.Success()
}

val actualParse: (resolver: Resolver, pattern: Pattern, rowValue: String) -> Value = { resolver: Resolver, pattern: Pattern, rowValue: String ->
    resolver.actualParse(pattern, rowValue)
}

val alwaysReturnStringValue: (resolver: Resolver, pattern: Pattern, rowValue: String) -> Value = { _: Resolver, _: Pattern, rowValue: String ->
    StringValue(rowValue)
}

data class Resolver(
    val factStore: FactStore = CheckFacts(),
    val mockMode: Boolean = false,
    val newPatterns: Map<String, Pattern> = emptyMap(),
    val findKeyErrorCheck: KeyCheck = DefaultKeyCheck,
    val context: Context = NoContext,
    val mismatchMessages: MismatchMessages = DefaultMismatchMessages,
    val isNegative: Boolean = false,
    val patternMatchStrategy: (resolver: Resolver, factKey: String?, pattern: Pattern, sampleValue: Value) -> Result = actualMatch,
    val parseStrategy: (resolver: Resolver, pattern: Pattern, rowValue: String) -> Value = actualParse,
    val cyclePreventionStack: List<Pattern> = listOf(),
    val defaultExampleResolver: DefaultExampleResolver = DoNotUseDefaultExample,
    val generation: GenerationStrategies = NonGenerativeTests,
    val dictionary: Map<String, Value> = emptyMap(),
    val dictionaryLookupPath: String = ""
) {
    constructor(facts: Map<String, Value> = emptyMap(), mockMode: Boolean = false, newPatterns: Map<String, Pattern> = emptyMap()) : this(CheckFacts(facts), mockMode, newPatterns)
    constructor() : this(emptyMap(), false)

    val patterns: Map<String, Pattern>
        get() {
            return builtInPatterns.plus(newPatterns)
        }

    fun withUnexpectedKeyCheck(unexpectedKeyCheck: UnexpectedKeyCheck): Resolver {
        return this.copy(findKeyErrorCheck = this.findKeyErrorCheck.withUnexpectedKeyCheck(unexpectedKeyCheck))
    }

    fun disableOverrideUnexpectedKeycheck(): Resolver {
        return this.copy(findKeyErrorCheck = this.findKeyErrorCheck.disableOverrideUnexpectedKeycheck())
    }

    fun findKeyError(pattern: Map<String, Any>, actual: Map<String, Any>): KeyError? {
        return findKeyErrorList(pattern, actual).firstOrNull()
    }

    fun findKeyErrorList(pattern: Map<String, Any>, actual: Map<String, Any>): List<KeyError> {
        return findKeyErrorCheck.validateAll(pattern, actual)
    }

    fun matchesPattern(factKey: String?, pattern: Pattern, sampleValue: Value): Result {
        return patternMatchStrategy(this, factKey, pattern, sampleValue)
    }

    fun actualPatternMatch(factKey: String?, pattern: Pattern, sampleValue: Value): Result {
        if (mockMode
                && sampleValue is StringValue
                && isPatternToken(sampleValue.string)
                && pattern.encompasses(getPattern(sampleValue.string).let { if(it is LookupRowPattern) resolvedHop(it.pattern, this) else it }, this, this).isSuccess())
            return Result.Success()

        return pattern.matches(sampleValue, this).ifSuccess {
            if (factKey != null && factStore.has(factKey)) {
                val result = factStore.match(sampleValue, factKey)

                if(result is Result.Failure) {
                    result.reason("Resolver was not able to match fact $factKey with value $sampleValue.")
                }
            }

            Result.Success()
        }
    }

    fun hasPattern(patternValue: String): Boolean = patternValue in patterns

    fun getPattern(patternValue: String): Pattern =
        when {
            isPatternToken(patternValue) -> {
                val resolvedPattern = patterns[patternValue] ?: parsedPattern(patternValue, null)
                when {
                    resolvedPattern is DeferredPattern && resolvedPattern.pattern == patternValue ->
                        throw ContractException("Type $patternValue does not exist")
                    else -> resolvedPattern
                }
            }
            else -> throw ContractException("$patternValue is not a type")
        }

    fun <T> withCyclePrevention(pattern: Pattern, toResult: (r: Resolver) -> T) : T {
        return withCyclePrevention(pattern, false, toResult)!!
    }

    /**
     * Returns non-null if no cycle. If there is a cycle then ContractException(cycle=true) is thrown - unless
     * returnNullOnCycle=true in which case null is returned. Null is never returned if returnNullOnCycle=false.
     */
    fun <T> withCyclePrevention(pattern: Pattern, returnNullOnCycle: Boolean = false, toResult: (r: Resolver) -> T) : T? {
        val count = cyclePreventionStack.filter { it == pattern }.size
        val newCyclePreventionStack = cyclePreventionStack.plus(pattern)

        return try {
            if (count > 1) {
                // Terminate what would otherwise be an infinite cycle.
                val stackAsString = newCyclePreventionStack.mapNotNull { it.typeAlias }.filter { it.isNotBlank() }.joinToString(", ") { withoutPatternDelimiters(it) }
                throw ContractException("Invalid pattern cycle: $stackAsString", isCycle = true)
            }

            toResult(copy(cyclePreventionStack = newCyclePreventionStack))
        } catch (e: ContractException) {
            if (!e.isCycle || !returnNullOnCycle)
                throw e

            // Returns null if (and only if) a cycle has been detected and returnNullOnCycle=true
            null
        }
    }

    fun generate(pattern: Pattern): Value {
        if(dictionaryLookupPath.isBlank())
            return pattern.generate(this)

        val value = dictionary[dictionaryLookupPath] ?: return pattern.generate(this)

        val dictionaryValueMatchResult = pattern.matches(value, this)

        dictionaryValueMatchResult.throwOnFailure()

        return value
    }

    fun generate(typeAlias: String?, rawLookupKey: String, pattern: Pattern): Value {
        val resolvedPattern = resolvedHop(pattern, this)
        if(resolvedPattern is ExactValuePattern && !resolvedPattern.hasPatternToken())
            return pattern.generate(this)

        val lookupKey = withoutOptionality(rawLookupKey)

        if (factStore.has(lookupKey))
            return generate(lookupKey, pattern)

        val lookupPath = if(typeAlias.isNullOrBlank()) {
            if(lookupKey.isBlank())
                ""
            else if(lookupKey == "[*]")
                "$dictionaryLookupPath$lookupKey"
            else
                "$dictionaryLookupPath.$lookupKey"
        } else {
            "${withoutPatternDelimiters(typeAlias)}.$lookupKey"
        }

        val updatedResolver = if(lookupPath.isNotBlank()) {
            this.copy(dictionaryLookupPath = lookupPath)
        } else {
            this
        }

        return updatedResolver.generate(pattern)
    }

    fun generateList(pattern: Pattern): Value {
        val lookupKey = dictionaryLookupPath.trim() + "[*]"

        val value = dictionary[lookupKey] ?: return this.copy(dictionaryLookupPath = lookupKey).generateRandomList(pattern)

        val matchResult = pattern.matches(value, this)

        if(matchResult.isSuccess())
            return JSONArrayValue(listOf(value))

        val errorReport = """
>> $lookupKey

Dictionary value did not match the spec

${matchResult.reportString()}
        """.trimIndent()

        logger.log(errorReport)

        return generateRandomList(pattern)
    }

    private fun generateRandomList(pattern: Pattern): Value {
        return pattern.listOf(0.until(randomNumber(3)).mapIndexed{ index, _ ->
            attempt(breadCrumb = "[$index (random)]") { pattern.generate(this) }
        }, this)
    }

    fun generate(factKey: String, pattern: Pattern): Value {
        if (!factStore.has(factKey))
            return pattern.generate(this)

        return when(val fact = factStore.get(factKey)) {
            is StringValue ->
                try {
                    pattern.parse(fact.string, this)
                } catch (e: Throwable) {
                    throw ContractException("""Value $fact in fact $factKey is not a $pattern""")
                }
            True -> pattern.generate(this)
            else -> {
                when(val matchResult = pattern.matches(fact, this)) {
                    is Result.Failure -> throw ContractException(matchResult.toFailureReport())
                    else -> fact
                }
            }
        }
    }

    fun findKeyErrorListCaseInsensitive(pattern: Map<String, Pattern>, actual: Map<String, StringValue>): List<KeyError> {
        return findKeyErrorCheck.validateAllCaseInsensitive(pattern, actual)
    }

    fun parse(pattern: Pattern, rowValue: String): Value {
        return parseStrategy(this, pattern, rowValue)
    }

    fun actualParse(pattern: Pattern, rowValue: String): Value {
        return pattern.parse(rowValue, this)
    }

    fun invalidRequestResolver(): Resolver {
        return this.copy(patternMatchStrategy = matchAnything, parseStrategy = alwaysReturnStringValue)
    }

    fun generatedPatternsForGenerativeTests(pattern: Pattern, key: String): Sequence<ReturnValue<Pattern>> {
        return generation.generatedPatternsForGenerativeTests(this, pattern, key)
    }

    fun resolveExample(example: String?, pattern: Pattern): Value? {
        return defaultExampleResolver.resolveExample(example, pattern, this)
    }

    fun resolveExample(example: String?, pattern: List<Pattern>): Value? {
        return defaultExampleResolver.resolveExample(example, pattern, this)
    }

    fun resolveExample(example: List<String?>?, pattern: Pattern): JSONArrayValue? {
        return defaultExampleResolver.resolveExample(example, pattern, this)
    }

    fun generateHttpRequestbodies(body: Pattern, row: Row, requestBodyAsIs: Pattern, value: Value): Sequence<ReturnValue<Pattern>> {
        return generation.generateHttpRequestBodies(this, body, row, requestBodyAsIs, value)
    }

    fun generateHttpRequestbodies(body: Pattern, row: Row): Sequence<ReturnValue<Pattern>> {
        return generation.generateHttpRequestBodies(this, body, row)
    }

    fun resolveRow(row: Row): Row {
        return generation.resolveRow(row)
    }

    fun generateKeySubLists(key: String, subList: List<String>): Sequence<List<String>> {
        return generation.generateKeySubLists(key, subList)
    }

    fun plusDictionaryLookupDetails(typeAlias: String?, key: String): Resolver {
        val newDictionaryLookupPath = addToDictionaryLookupPath(typeAlias, key)

        return this.copy(dictionaryLookupPath = newDictionaryLookupPath)
    }

    private fun addToDictionaryLookupPath(typeAlias: String?, key: String): String {
        return when {
            !typeAlias.isNullOrBlank() -> "${withoutPatternDelimiters(typeAlias)}.$key"
            key.startsWith("[") -> "$dictionaryLookupPath$key"
            else -> "$dictionaryLookupPath.$key"
        }
    }

    fun hasDictionaryToken(key: String): Boolean {
        return key in dictionary
    }

    fun getDictionaryToken(key: String): Value {
        return dictionary.getValue(key)
    }
}

private fun ExactValuePattern.hasPatternToken(): Boolean {
    return this.pattern is StringValue && isPatternToken(this.pattern.string)
}

