package run.qontract.core

import org.junit.jupiter.api.Test

internal class TestBackwardCompatibilityKtTest {
    @Test
    fun `contract backward compatibility should break when one has an optional key and the other does not` () {
        val gherkin1 = """
Feature: Older contract API

Scenario:
Given json Value
| value     | (number) |
| optional? | (number) |
When POST /value
And request-body (Value)
Then status 200
    """.trim()

        val gherkin2 = """
Feature: Older contract API

Scenario:
Given json Value
| value    | (number) |
| optional | (number) |
When POST /value
And request-body (Value)
Then status 200
    """.trim()

        val olderContract = ContractBehaviour(gherkin1)
        val newerContract = ContractBehaviour(gherkin2)

        val result: ExecutionInfo = testBackwardCompatibility(olderContract, newerContract)

        kotlin.test.assertEquals(1, result.failureCount)
        kotlin.test.assertEquals(1, result.successCount)
    }

    @Test
    fun `contract backward compatibility should not break when both have an optional keys` () {
        val gherkin1 = """
Feature: Older contract API

Scenario:
Given json Value
| value     | (number) |
| optional? | (number) |
And fact id 10
When POST /value/(id:number)
And request-body (Value)
Then status 200
    """.trim()

        val gherkin2 = """
Feature: Older contract API

Scenario:
Given json Value
| value    | (number) |
| optional? | (number) |
And fact id 10
When POST /value/(id:number)
And request-body (Value)
Then status 200
    """.trim()

        val olderContract = ContractBehaviour(gherkin1)
        val newerContract = ContractBehaviour(gherkin2)

        val result: ExecutionInfo = testBackwardCompatibility(olderContract, newerContract)

        kotlin.test.assertEquals(2, result.successCount)
        kotlin.test.assertEquals(0, result.failureCount)
    }

    @Test
    fun `contract backward compatibility should break when a new fact is added` () {
        val gherkin1 = """
Feature: Older contract API

Scenario:
Given json Value
| value     | (number) |
| optional? | (number) |
When POST /value/(id:number)
And request-body (Value)
Then status 200
    """.trim()

        val gherkin2 = """
Feature: Older contract API

Scenario:
Given json Value
| value    | (number) |
| optional? | (number) |
And fact id 10
When POST /value/(id:number)
And request-body (Value)
Then status 200
    """.trim()

        val olderContract = ContractBehaviour(gherkin1)
        val newerContract = ContractBehaviour(gherkin2)

        val result: ExecutionInfo = testBackwardCompatibility(olderContract, newerContract)

        kotlin.test.assertEquals(0, result.successCount)
        kotlin.test.assertEquals(2, result.failureCount)
    }

    @Test
    fun `contract should test successfully against itself when fact name is specified without a value in the URL path`() {
        val gherkin = """
Feature: Contract API

Scenario:
Given fact id
When POST /value/(id:number)
Then status 200
    """.trim()

        val contract = ContractBehaviour(gherkin)

        val executionInfo: ExecutionInfo = testBackwardCompatibility(contract, contract)

        if(executionInfo.failureCount > 0)
            executionInfo.print()

        kotlin.test.assertEquals(1, executionInfo.successCount)
        kotlin.test.assertEquals(0, executionInfo.failureCount)
    }

    @Test
    fun `contract should test successfully against itself when fact name is specified without a value in the query`() {
        val gherkin = """
Feature: Contract API

Scenario:
Given fact id
When GET /value?id=(number)
Then status 200
    """.trim()

        val contract = ContractBehaviour(gherkin)

        val executionInfo: ExecutionInfo = testBackwardCompatibility(contract, contract)

        if(executionInfo.failureCount > 0)
            executionInfo.print()

        kotlin.test.assertEquals(1, executionInfo.successCount)
        kotlin.test.assertEquals(0, executionInfo.failureCount)
    }

    @Test
    fun `should be able to validate new contract compatibility with optional request body`() {
        val gherkin = """
Feature: Contract API

Scenario:
When POST /number
And request-body (number?)
Then status 200
    """.trim()

        val contract = ContractBehaviour(gherkin)

        val executionInfo: ExecutionInfo = testBackwardCompatibility(contract, contract)

        if(executionInfo.failureCount > 0)
            executionInfo.print()

        kotlin.test.assertEquals(2, executionInfo.successCount)
        kotlin.test.assertEquals(0, executionInfo.failureCount)
    }

    @Test
    fun `should be able to validate new contract compatibility with optional key in request body`() {
        val gherkin = """
Feature: Contract API

Scenario:
Given json Number
| number | (number?) |
When POST /number
And request-body (Number)
Then status 200
    """.trim()

        val contract = ContractBehaviour(gherkin)

        val executionInfo: ExecutionInfo = testBackwardCompatibility(contract, contract)

        if(executionInfo.failureCount > 0)
            executionInfo.print()

        kotlin.test.assertEquals(2, executionInfo.successCount)
        kotlin.test.assertEquals(0, executionInfo.failureCount)
    }

    @Test
    fun `should be able to validate new contract compatibility with optional response body`() {
        val gherkin = """
Feature: Contract API

Scenario:
When POST /number
Then status 200
And response-body (number?)
    """.trim()

        val contract = ContractBehaviour(gherkin)

        val executionInfo: ExecutionInfo = testBackwardCompatibility(contract, contract)

        if(executionInfo.failureCount > 0)
            executionInfo.print()

        kotlin.test.assertEquals(1, executionInfo.successCount)
        kotlin.test.assertEquals(0, executionInfo.failureCount)
    }

    @Test
    fun `should be able to validate new contract compatibility with optional key in response body`() {
        val gherkin = """
Feature: Contract API

Scenario:
Given json Number
| number | (number?) |
When POST /number
Then status 200
And response-body (Number)
    """.trim()

        val contract = ContractBehaviour(gherkin)

        val executionInfo: ExecutionInfo = testBackwardCompatibility(contract, contract)

        if(executionInfo.failureCount > 0)
            executionInfo.print()

        kotlin.test.assertEquals(1, executionInfo.successCount)
        kotlin.test.assertEquals(0, executionInfo.failureCount)
    }
}
