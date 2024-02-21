package `in`.specmatic.core.pattern

import `in`.specmatic.core.Resolver
import `in`.specmatic.core.Result
import `in`.specmatic.core.value.EmptyString
import `in`.specmatic.core.value.Value

data class LookupRowPattern(override val pattern: Pattern, override val key: String? = null) : Pattern, Keyed {
    override fun equals(other: Any?): Boolean = other is LookupRowPattern && resolvedHop(other.pattern, Resolver()) == resolvedHop(pattern, Resolver())
    override fun hashCode(): Int = pattern.hashCode()

    override fun matches(sampleData: Value?, resolver: Resolver): Result =
            resolver.matchesPattern(key, pattern, sampleData ?: EmptyString)

    override fun generate(resolver: Resolver): Value {
        return resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
            when (key) {
                null -> pattern.generate(cyclePreventedResolver)
                else -> cyclePreventedResolver.generate(key, pattern)
            }
        }
    }

    override fun newBasedOn(row: Row, resolver: Resolver): List<Pattern> {
        return when(key) {
            null -> resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
                pattern.newBasedOn(row, cyclePreventedResolver)
            }
            // Cycle prevention handled in helper method
            else -> newBasedOn(row, key, pattern, resolver)
        }
    }

    override fun newBasedOn(resolver: Resolver): List<Pattern> {
        return when(key) {
            null -> resolver.withCyclePrevention(pattern) { cyclePreventedResolver ->
                pattern.newBasedOn(cyclePreventedResolver)
            }
            // Cycle prevention handled in helper method
            else -> newBasedOn(key, pattern, resolver)
        }
    }

    override fun negativeBasedOn(row: Row, resolver: Resolver): List<Pattern> {
        return listOf(this)
    }

    override fun parse(value: String, resolver: Resolver): Value = pattern.parse(value, resolver)

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver, typeStack: TypeStack): Result {
        return pattern.encompasses(resolvedHop(otherPattern, otherResolver), thisResolver, otherResolver, typeStack)
    }

    override fun listOf(valueList: List<Value>, resolver: Resolver): Value {
        return pattern.listOf(valueList, resolver)
    }

    override fun testCount(resolver: Resolver): ULong {
        return pattern.testCount(resolver)
    }

    override val typeAlias: String? = pattern.typeAlias

    override fun patternSet(resolver: Resolver): List<Pattern> = pattern.patternSet(resolver)

    override val typeName: String = pattern.typeName
}