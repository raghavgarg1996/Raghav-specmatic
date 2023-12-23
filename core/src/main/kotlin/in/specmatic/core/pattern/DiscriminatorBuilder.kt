package `in`.specmatic.core.pattern

import `in`.specmatic.core.Resolver
import `in`.specmatic.core.Result
import `in`.specmatic.core.value.JSONObjectValue
import `in`.specmatic.core.value.Value

interface DiscriminatorBuilder {
    fun matchPatternKeys(
        anyPattern: AnyPattern,
        resolver: Resolver,
        sampleData: JSONObjectValue
    ): List<Pair<Result, List<String>>>

    fun patternMatchResults(
        anyPattern: AnyPattern,
        key: String?,
        resolver: Resolver,
        sampleData: Value?
    ): List<PatternMatchResult>

    fun discriminatedResolver(typeAlias: String?, resolver: Resolver): Resolver

    fun resolvePattern(
        pattern: Pattern,
        resolver: Resolver
    ): Pattern
}