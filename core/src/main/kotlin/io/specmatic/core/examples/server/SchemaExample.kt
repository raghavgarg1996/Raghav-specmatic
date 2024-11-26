package io.specmatic.core.examples.server

import io.specmatic.core.pattern.ContractException
import io.specmatic.core.pattern.attempt
import io.specmatic.core.pattern.parsedValue
import io.specmatic.core.value.NullValue
import io.specmatic.core.value.StringValue
import io.specmatic.core.value.Value
import java.io.File

data class SchemaExample(val json: Value, val file: File) {
    companion object {
        val SCHEMA_IDENTIFIER_REGEX = Regex("^resource(?:\\.(\\w+))?\\.(\\w+)\\.example\\.json\$")
        const val SCHEMA_BASED = "SCHEMA_BASED"
        const val NOT_SCHEMA_BASED = "NOT_SCHEMA_BASED"

        fun toSchemaExampleFileName(parentPattern: String, patternName: String): String {
            if (patternName.isBlank()) return "resource.$parentPattern.example.json"
            return "resource.$parentPattern.$patternName.example.json"
        }

        fun matchesFilePattern(file: File): Boolean {
            return SCHEMA_IDENTIFIER_REGEX.matches(file.name)
        }
    }

    constructor(file: File) : this(json = attempt("Error reading example file ${file.canonicalPath}") { parsedValue(file.readText()) }, file = file)

    init {
        if (!matchesFilePattern(file)) {
            throw ContractException(breadCrumb = NOT_SCHEMA_BASED, errorMessage = "Skipping file ${file.canonicalPath}, because didn't match pattern ${SCHEMA_IDENTIFIER_REGEX.pattern}")
        }
    }

    val discriminatorBasedOn = SCHEMA_IDENTIFIER_REGEX.find(file.name)?.groupValues?.getOrNull(1).takeUnless { it.isNullOrBlank() }

    val schemaBasedOn = attempt(breadCrumb = "Error parsing schema from example name ${file.name}") {
        SCHEMA_IDENTIFIER_REGEX.find(file.name)?.groupValues?.let { match -> match[2].ifEmpty { match[1] } }
            ?: throw ContractException("File name didn't match pattern ${SCHEMA_IDENTIFIER_REGEX.pattern}")
    }

    val value = when {
        json is StringValue && json.string == "null" -> NullValue
        else -> json
    }
}