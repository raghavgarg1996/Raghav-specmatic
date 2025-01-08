package io.specmatic.core.filters

data class ScenarioMetadataFilter(
    val filterGroups: List<FilterGroup> = emptyList()
) {
    fun isSatisfiedBy(metadata: ScenarioMetadata): Boolean {
        if (filterGroups.isEmpty()) return true

        val groupResults = mutableListOf<Boolean>()
        var tempAndResult: Boolean? = null

        for ((index, group) in filterGroups.withIndex()) {
            val currentResult = group.isSatisfiedBy(metadata)

            if (index == 0) {
                tempAndResult = currentResult
            } else if (group.isAndOperation) {
                tempAndResult = tempAndResult?.and(currentResult) ?: currentResult
            } else {
                if (tempAndResult != null) {
                    groupResults.add(tempAndResult)
                    tempAndResult = null
                }

                groupResults.add(currentResult)
            }
        }

        if (tempAndResult != null) {
            groupResults.add(tempAndResult)
        }

        return groupResults.any { it }
    }

    companion object {
        fun from(filterExpression: String): ScenarioMetadataFilter {
            if (filterExpression.isEmpty()) return ScenarioMetadataFilter()
            val parsedFilters = FilterSyntax(filterExpression).parse()
            return ScenarioMetadataFilter(filterGroups = parsedFilters)
        }

        fun <T> filterUsing(
            items: Sequence<T>,
            scenarioMetadataFilter: ScenarioMetadataFilter,
            toScenarioMetadata: (T) -> ScenarioMetadata
        ): Sequence<T> {
            val returnItems = items.filter {
                scenarioMetadataFilter.isSatisfiedBy(toScenarioMetadata(it))
            }
            return returnItems
        }
    }
}







