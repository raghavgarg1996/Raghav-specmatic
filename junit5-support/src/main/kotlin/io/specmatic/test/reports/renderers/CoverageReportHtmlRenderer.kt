package io.specmatic.test.reports.renderers

import io.specmatic.core.ReportFormatterType
import io.specmatic.core.SpecmaticConfig
import io.specmatic.core.log.HttpLogMessage
import io.specmatic.core.log.logger
import io.specmatic.test.SpecmaticJUnitSupport
import io.specmatic.test.TestInteractionsLog.displayName
import io.specmatic.test.TestInteractionsLog.duration
import io.specmatic.test.TestResultRecord
import io.specmatic.test.reports.coverage.console.OpenAPICoverageConsoleReport
import io.specmatic.test.reports.coverage.html.*
import java.util.*

class CoverageReportHtmlRenderer : ReportRenderer<OpenAPICoverageConsoleReport> {

    companion object {
        private val tableConfig = HtmlTableConfig(
            firstGroupName = "Path",
            firstGroupColSpan = 2,
            secondGroupName = "Method",
            secondGroupColSpan = 1,
            thirdGroupName = "Response",
            thirdGroupColSpan = 1
        )
        val actuatorEnabled = SpecmaticJUnitSupport.openApiCoverageReportInput.endpointsAPISet
    }

    override fun render(report: OpenAPICoverageConsoleReport, specmaticConfig: SpecmaticConfig): String {
        logger.log("Generating HTML report...")
        val reportConfiguration = specmaticConfig.report!!
        val htmlReportConfiguration = reportConfiguration.formatters!!.first { it.type == ReportFormatterType.HTML }
        val openApiSuccessCriteria = reportConfiguration.types.apiCoverage.openAPI.successCriteria

        val reportData = HtmlReportData(
            totalCoveragePercentage = report.totalCoveragePercentage, actuatorEnabled = actuatorEnabled,
            tableRows = makeTableRows(report),
            scenarioData = makeScenarioData(report), totalTestDuration = getTotalDuration(report)
        )

        val htmlReportInformation = HtmlReportInformation(
            reportFormat = htmlReportConfiguration, successCriteria = openApiSuccessCriteria,
            specmaticImplementation = "OpenAPI", specmaticVersion = getSpecmaticVersion(),
            tableConfig = tableConfig, reportData = reportData, specmaticConfig = specmaticConfig
        )

        HtmlReport(htmlReportInformation).generate()
        return "Successfully generated HTML report in ${htmlReportConfiguration.outputDirectory}"
    }

    private fun getSpecmaticVersion(): String {
        val props = Properties()
        CoverageReportHtmlRenderer::class.java.classLoader.getResourceAsStream("version.properties").use {
            props.load(it)
        }
        return props.getProperty("version")
    }

    private fun makeTableRows(report: OpenAPICoverageConsoleReport): List<TableRow> {
        return report.getGroupedCoverageRows().flatMap { (_, methodGroup) ->
            methodGroup.flatMap { (_, statusGroup) ->
                statusGroup.flatMap { (_, coverageRows) ->
                    coverageRows.map {
                        TableRow(
                            coveragePercentage = it.coveragePercentage,
                            firstGroupValue = it.path,
                            showFirstGroup = it.showPath,
                            firstGroupRowSpan = methodGroup.values.sumOf { rows -> rows.size },
                            secondGroupValue = it.method,
                            showSecondGroup = it.showMethod,
                            secondGroupRowSpan = statusGroup.values.sumOf { status -> status.size },
                            response = it.responseStatus,
                            exercised = it.count.toInt(),
                            result = it.remarks
                        )
                    }
                }
            }
        }
    }

    private fun getTotalDuration(report: OpenAPICoverageConsoleReport): Long {
        return report.httpLogMessages.sumOf { it.duration() }
    }

    private fun makeScenarioData(report: OpenAPICoverageConsoleReport): Map<String, Map<String, Map<String, List<ScenarioData>>>> {
        val testData: MutableMap<String, MutableMap<String, MutableMap<String, MutableList<ScenarioData>>>> = mutableMapOf()

        for ((path, methodGroup) in report.getGroupedTestResultRecords()) {
            for ((method, statusGroup) in methodGroup) {
                val methodMap = testData.getOrPut(path) { mutableMapOf() }

                for ((status, testResults) in statusGroup) {
                    val statusMap = methodMap.getOrPut(method) { mutableMapOf() }
                    val scenarioDataList = statusMap.getOrPut(status) { mutableListOf() }

                    for (test in testResults) {
                        val matchingLogMessage = report.httpLogMessages.firstOrNull {
                            it.scenario == test.scenarioResult?.scenario
                        }
                        val scenarioName = getTestName(test, matchingLogMessage)
                        val (requestString, requestTime) = getRequestString(matchingLogMessage)
                        val (responseString, responseTime) = getResponseString(matchingLogMessage)

                        scenarioDataList.add(
                            ScenarioData(
                                name = scenarioName,
                                baseUrl = getBaseUrl(matchingLogMessage),
                                duration = matchingLogMessage?.duration() ?: 0,
                                testResult = test.result,
                                valid = test.isValid,
                                wip = test.isWip,
                                request = requestString,
                                requestTime = requestTime,
                                response = responseString,
                                responseTime = responseTime,
                                specFileName = getSpecFileName(test, matchingLogMessage),
                                details = getReportDetail(test)
                            )
                        )
                    }
                }
            }
        }

        return testData
    }

    private fun getTestName(testResult: TestResultRecord, httpLogMessage: HttpLogMessage?): String {
        return httpLogMessage?.displayName() ?: "Scenario: ${testResult.path} -> ${testResult.responseStatus}"
    }

    private fun getBaseUrl(httpLogMessage: HttpLogMessage?): String {
        return httpLogMessage?.targetServer ?: "Unknown baseURL"
    }

    private fun getRequestString(httpLogMessage: HttpLogMessage?): Pair<String, Long> {
        return Pair(
            httpLogMessage?.request?.toLogString() ?: "No Request",
            httpLogMessage?.requestTime?.toEpochMillis() ?: 0
        )
    }

    private fun getResponseString(httpLogMessage: HttpLogMessage?): Pair<String, Long> {
        return Pair(
            httpLogMessage?.response?.toLogString() ?: "No Response",
            httpLogMessage?.responseTime?.toEpochMillis() ?: 0
        )
    }

    private fun getSpecFileName(testResult: TestResultRecord, httpLogMessage: HttpLogMessage?): String {
        return testResult.specification ?: httpLogMessage?.scenario?.specification ?: "Unknown Spec File"
    }

    private fun getReportDetail(testResult: TestResultRecord): String {
        return testResult.scenarioResult?.reportString() ?: ""
    }
}