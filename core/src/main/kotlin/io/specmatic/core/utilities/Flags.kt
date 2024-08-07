package io.specmatic.core.utilities

class Flags {
    companion object {
        const val SPECMATIC_GENERATIVE_TESTS = "SPECMATIC_GENERATIVE_TESTS"
        const val ONLY_POSITIVE = "ONLY_POSITIVE"
        const val VALIDATE_RESPONSE_VALUE = "VALIDATE_RESPONSE_VALUE"
        const val EXTENSIBLE_SCHEMA = "EXTENSIBLE_SCHEMA"
        const val MAX_TEST_REQUEST_COMBINATIONS = "MAX_TEST_REQUEST_COMBINATIONS"
        const val SCHEMA_EXAMPLE_DEFAULT = "SCHEMA_EXAMPLE_DEFAULT"
        const val SPECMATIC_TEST_PARALLELISM = "SPECMATIC_TEST_PARALLELISM"

        const val LOCAL_TESTS_DIRECTORY = "LOCAL_TESTS_DIRECTORY"

        fun getStringValue(flagName: String): String? = System.getenv(flagName) ?: System.getProperty(flagName)

        fun getBooleanValue(flagName: String) = ( getStringValue(flagName) ?: "false").toBoolean()
    }
}