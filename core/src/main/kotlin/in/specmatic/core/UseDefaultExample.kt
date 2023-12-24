package `in`.specmatic.core

import `in`.specmatic.core.pattern.ContractException
import `in`.specmatic.core.pattern.Discriminator
import `in`.specmatic.core.pattern.Pattern
import `in`.specmatic.core.pattern.attempt
import `in`.specmatic.core.utilities.exceptionCauseMessage
import `in`.specmatic.core.value.JSONArrayValue
import `in`.specmatic.core.value.Value

object UseDefaultExample : DefaultExampleResolver {
    override fun resolveExample(example: String?, pattern: Pattern, resolver: Resolver): Value? {
        if(example == null)
            return null

        val value = pattern.parse(example, resolver)
        val exampleMatchResult = pattern.matches(value, Resolver())

        if(exampleMatchResult.isSuccess())
            return value

        throw ContractException("Example \"$example\" does not match ${pattern.typeName} type")
    }

    override fun resolveExample(example: String?, patterns: List<Pattern>, resolver: Resolver, discriminator: Discriminator): Value? {
        if(example == null)
            return null

        val matchResults = patterns.asSequence().map { pattern ->
            try {
                val discriminatedResolver = discriminator.discriminatedResolver(pattern.typeAlias, resolver)
                val value = pattern.parse(example, discriminatedResolver)

                Pair(pattern.matches(value, discriminatedResolver), value)
            } catch(e: Throwable) {
                Pair(Result.Failure(exceptionCauseMessage(e)), null)
            }
        }

        return matchResults.firstOrNull { it.first.isSuccess() }?.second
            ?: throw ContractException(
                "Example \"$example\" does not match:\n${
                    Result.fromResults(matchResults.map { it.first }.toList()).reportString()
                }"
            )
    }

    override fun resolveExample(example: List<String?>?, pattern: Pattern, resolver: Resolver): JSONArrayValue? {
        if(example == null)
            return null

        val items = example.mapIndexed { index, s ->
            attempt(breadCrumb = "[$index (example)]") {
                pattern.parse(s ?: "", resolver)
            }
        }

        return JSONArrayValue(items)
    }
}