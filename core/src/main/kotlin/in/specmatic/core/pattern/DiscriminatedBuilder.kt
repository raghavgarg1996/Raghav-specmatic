package `in`.specmatic.core.pattern

import `in`.specmatic.core.DiscriminatorKeyValuePair
import `in`.specmatic.core.Resolver
import `in`.specmatic.core.Result
import `in`.specmatic.core.value.EmptyString
import `in`.specmatic.core.value.JSONObjectValue
import `in`.specmatic.core.value.Value

data class DiscriminatedBuilder(val discriminatorKey: String) : Discriminator {
    override fun resolvePattern(
        pattern: Pattern,
        resolver: Resolver
    ): Pattern {
        val discriminatorValue = withoutPatternDelimiters(
            pattern.typeAlias
                ?: throw ContractException("Discriminator key $discriminatorKey is set, but type alias is not set for pattern $pattern")
        )

        val discriminatedResolver = resolver.copy(
            discrimination = DiscriminatorKeyValuePair(discriminatorKey, discriminatorValue)
        )

        return resolvedHop(pattern, discriminatedResolver)
    }

    override fun discriminatedResolver(typeAlias: String?, resolver: Resolver): Resolver {
        return typeAlias?.let {
            resolver.copy(discrimination = DiscriminatorKeyValuePair(discriminatorKey, it))
        } ?: resolver
    }

    override fun matchPatternKeys(
        anyPattern: AnyPattern,
        resolver: Resolver,
        sampleData: JSONObjectValue
    ): List<Pair<Result, List<String>>> {
        return anyPattern.pattern.map {
            val discriminatorValue = withoutPatternDelimiters(
                it.typeAlias
                    ?: throw ContractException("Discriminator key $discriminatorKey is set, but type alias is not set for pattern $it")
            )

            val discriminatedResolver = resolver.copy(
                discrimination = DiscriminatorKeyValuePair(discriminatorKey, discriminatorValue)
            )

            it.matchPatternKeys(sampleData, discriminatedResolver)
        }
    }

    override fun patternMatchResults(
        anyPattern: AnyPattern,
        key: String?,
        resolver: Resolver,
        sampleData: Value?
    ): List<PatternMatchResult> = anyPattern.pattern.map {
        val discriminatorValue = withoutPatternDelimiters(
            it.typeAlias
                ?: throw ContractException("Discriminator key $discriminatorKey is set, but type alias is not set for pattern $it")
        )

        val discriminatedResolver = resolver.copy(
            discrimination = DiscriminatorKeyValuePair(discriminatorKey, discriminatorValue)
        )

        PatternMatchResult(it, discriminatedResolver.matchesPattern(key, it, sampleData ?: EmptyString))
    }

}