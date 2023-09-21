package `in`.specmatic.core.pattern

import `in`.specmatic.core.OMIT
import `in`.specmatic.core.References
import `in`.specmatic.core.jsonObjectToValues
import `in`.specmatic.core.pattern.RowType.*
import `in`.specmatic.core.value.JSONObjectValue
import io.cucumber.messages.internal.com.fasterxml.jackson.databind.ObjectMapper

const val DEREFERENCE_PREFIX = "$"
const val FILENAME_PREFIX = "@"

enum class RowType {
    NoExamples, Example
}

data class Row(
    val columnNames: List<String> = emptyList(),
    val values: List<String> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
    val references: Map<String, References> = emptyMap(),
    val name: String = "",
    val rowType: RowType = NoExamples
) {
    constructor(columnNames: List<String>, values: List<String>) : this(columnNames, values, rowType = Example)
    constructor(requestExamples: Map<String, Any>, exampleName: String) : this(
        requestExamples.keys.toList().map { keyName: String -> keyName },
        requestExamples.values.toList().map { value: Any? -> value?.toString() ?: "" }
            .map { valueString: String ->
                if (valueString.contains("externalValue")) {
                    ObjectMapper().readValue(valueString, Map::class.java).values.first()
                        .toString()
                } else valueString
            },
        name = exampleName,
        rowType = Example
    )

    private val cells = columnNames.zip(values.map { it }).toMap().toMutableMap()

    fun flattenRequestBodyIntoRow(): Row {
        val jsonValue = parsedJSON(this.getField("(REQUEST-BODY)"))
        if (jsonValue !is JSONObjectValue)
            throw ContractException("Only JSON objects are supported as request body examples")

        val values: List<Pair<String, String>> = jsonObjectToValues(jsonValue)

        return Row(columnNames = values.map { it.first }, values = values.map { it.second }, name = name)
    }

    fun stringForOpenAPIError(): String {
        return columnNames.zip(values).joinToString(", ") { (key, value) ->
            "$key=$value"
        }
    }

    fun getField(columnName: String): String {
        return getValue(columnName).fetch()
    }

    private fun getValue(columnName: String): RowValue {
        val value = cells.getValue(columnName)

        return when {
            isContextValue(value) && isReferenceValue(value) -> ReferenceValue(ValueReference(value), references)
            isContextValue(value) -> VariableValue(ValueReference(value), variables)
            isFileValue(value) -> FileValue(withoutPatternDelimiters(value).removePrefix("@"))
            else -> SimpleValue(value)
        }
    }

    private fun isFileValue(value: String): Boolean {
        return isPatternToken(value) && withoutPatternDelimiters(value).startsWith(FILENAME_PREFIX)
    }

    private fun isReferenceValue(value: String): Boolean = value.contains(".")

    private fun isContextValue(value: String): Boolean {
        return isPatternToken(value) && withoutPatternDelimiters(value).trim().startsWith(DEREFERENCE_PREFIX)
    }

    fun containsField(key: String): Boolean = cells.containsKey(key)

    fun withoutOmittedKeys(keys: Map<String, Pattern>) = keys.filter {
        !this.containsField(withoutOptionality(it.key)) || this.getField(withoutOptionality(it.key)) !in OMIT
    }
}
