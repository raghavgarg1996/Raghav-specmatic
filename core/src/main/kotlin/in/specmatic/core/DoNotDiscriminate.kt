package `in`.specmatic.core

import `in`.specmatic.core.pattern.Pattern
import `in`.specmatic.core.value.JSONObjectValue

object DoNotDiscriminate : ConcreteDiscriminator {
    override fun matches(sampleData: JSONObjectValue): Result {
        return Result.Success()
    }

    override fun apply(pattern: Map<String, Pattern>): Map<String, Pattern> {
        return pattern
    }
}