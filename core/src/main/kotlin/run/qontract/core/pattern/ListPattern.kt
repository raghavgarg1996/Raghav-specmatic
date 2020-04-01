package run.qontract.core.pattern

import run.qontract.core.ContractParseException
import run.qontract.core.Resolver
import run.qontract.core.Result
import run.qontract.core.value.JSONArrayValue
import run.qontract.core.value.Value

data class ListPattern(override val pattern: Pattern) : Pattern {
//    private val cleanPatternSpec = withoutRepeatingToken(patternSpec)

    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if(sampleData !is JSONArrayValue)
            return Result.Failure("Expected: JSONArrayValue. Actual: ${sampleData?.javaClass ?: "null"}")

        val resolverWithNumberType = resolver.copy().also {
            it.addCustomPattern("(number)", NumberTypePattern())
        }

        return sampleData.list.asSequence().map {
            pattern.matches(it, resolverWithNumberType)
//            resolverWithNumberType.matchesPattern(null, cleanPatternSpec, it)
        }.find { it is Result.Failure }.let { result ->
            when(result) {
                is Result.Failure -> result.add("Expected multiple values of type $pattern, but one of the values didn't match in ${sampleData.list}")
                else -> Result.Success()
            }
        }
    }

    override fun generate(resolver: Resolver): JSONArrayValue = JSONArrayValue(generateMultipleValues(pattern, resolver))
//    override fun generate(resolver: Resolver): JSONArrayValue = JSONArrayValue(generateMultipleValues(resolver.getPattern(cleanPatternSpec), resolver))
    override fun newBasedOn(row: Row, resolver: Resolver): List<Pattern> = pattern.newBasedOn(row, resolver)
//    override fun newBasedOn(row: Row, resolver: Resolver): List<Pattern> = resolver.getPattern(patternSpec).newBasedOn(row, resolver)

    override fun parse(value: String, resolver: Resolver): Value = parsedJSON(value) ?: throw ContractParseException("""Parsing as $javaClass but failed. Value: $value""")

//    override val pattern: Any = patternSpec
}
