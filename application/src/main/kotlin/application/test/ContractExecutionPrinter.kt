package application.test

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestIdentifier

interface ContractExecutionPrinter {
    fun printFinalSummary(testSummary: TestSummary)
    fun printTestSummary(testIdentifier: TestIdentifier?, testExecutionResult: TestExecutionResult?)
    fun printFailureTitle(failures: String)
}

fun testStatusMessage(
    testIdentifier: TestIdentifier?,
    testExecutionResult: TestExecutionResult?
): String {
    val status: TestExecutionResult.Status? = testExecutionResult?.status

    val statusToMessage = mapOf(
        TestExecutionResult.Status.SUCCESSFUL to "has SUCCEEDED",
        TestExecutionResult.Status.FAILED to "has FAILED",
        TestExecutionResult.Status.ABORTED to "was ABORTED"
    )

    val displayStatus: String = statusToMessage[status] ?: status?.name ?: "is UNKNOWN"

    val prefix = if(status != TestExecutionResult.Status.SUCCESSFUL) "\n--------------------\n" else ""
    return "$prefix${testIdentifier?.displayName} $displayStatus"
}
