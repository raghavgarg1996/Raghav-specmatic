package `in`.specmatic.core

import `in`.specmatic.core.pattern.Pattern
import `in`.specmatic.core.value.JSONObjectValue

class NoDiscriminator : Discriminator {
    override fun matches(sampleData: JSONObjectValue): Result {
        return Result.Success()
    }

    override fun discriminate(pattern: Map<String, Pattern>): Map<String, Pattern> {
        return pattern
    }
}