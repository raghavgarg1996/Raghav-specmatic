package io.specmatic.core

import io.specmatic.stub.HttpStub
import io.specmatic.test.HttpClient

data class Contract(val feature: Feature) {
    companion object {
        fun fromGherkin(contractGherkin: String): Contract {
            return Contract(parseGherkinStringToFeature(contractGherkin))
        }
    }

    fun samples(fake: HttpStub) = samples(fake.endPoint)

    private fun samples(endPoint: String) {
        val httpClient = HttpClient(endPoint)

        feature.generateContractTests(emptyList()).fold(Results()) { results, contractTest ->
            Results(results = results.results.plus(contractTest.runTest(httpClient).first))
        }
    }
}
