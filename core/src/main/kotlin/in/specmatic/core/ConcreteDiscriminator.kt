package `in`.specmatic.core

import `in`.specmatic.core.pattern.Pattern
import `in`.specmatic.core.value.JSONObjectValue

interface ConcreteDiscriminator {
    fun matches(sampleData: JSONObjectValue): Result
    fun apply(pattern: Map<String, Pattern>): Map<String, Pattern>
}