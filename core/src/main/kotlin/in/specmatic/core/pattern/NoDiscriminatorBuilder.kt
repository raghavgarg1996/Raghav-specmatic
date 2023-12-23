package `in`.specmatic.core.pattern

import `in`.specmatic.core.Resolver
import `in`.specmatic.core.Result
import `in`.specmatic.core.value.EmptyString
import `in`.specmatic.core.value.JSONObjectValue
import `in`.specmatic.core.value.Value

class NoDiscriminatorBuilder : DiscriminatorBuilder {
    override fun discriminatedResolver(typeAlias: String?, resolver: Resolver): Resolver {
        return resolver
    }

    override fun resolvePattern(pattern: Pattern, resolver: Resolver): Pattern {
        return resolvedHop(pattern, resolver)
    }

    override fun matchPatternKeys(
        anyPattern: AnyPattern,
        resolver: Resolver,
        sampleData: JSONObjectValue
    ): List<Pair<Result, List<String>>> {
        return anyPattern.pattern.map {
            it.matchPatternKeys(sampleData, resolver)
        }
    }

    override fun patternMatchResults(anyPattern: AnyPattern, key: String?, resolver: Resolver, sampleData: Value?): List<PatternMatchResult> {
        return anyPattern.pattern.map {
            PatternMatchResult(it, resolver.matchesPattern(key, it, sampleData ?: EmptyString))
        }
    }
}