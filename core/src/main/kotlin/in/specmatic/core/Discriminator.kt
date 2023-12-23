package `in`.specmatic.core

import `in`.specmatic.core.pattern.Pattern
import `in`.specmatic.core.value.JSONObjectValue

interface Discriminator {
    fun matches(sampleData: JSONObjectValue): Result
    fun discriminate(pattern: Map<String, Pattern>): Map<String, Pattern>
}