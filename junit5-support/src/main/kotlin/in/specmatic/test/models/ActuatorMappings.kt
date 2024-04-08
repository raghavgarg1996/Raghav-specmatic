package `in`.specmatic.test.models

import `in`.specmatic.test.API

data class ActuatorMappings(
    val contexts: Contexts
) {

    fun getAPIs(): List<API> {
        return this.contexts.application.mappings.dispatcherServlets.dispatcherServlet
            .filter { servlet -> servlet.isValidHandlerMethod() }
            .map { servlet -> servlet.details?.requestMappingConditions }
            .flatMap { requestMappings ->
                requestMappings?.run {
                    // Understand this logic
                    methods.flatMap { method ->
                        patterns.map { pattern -> API(method, pattern) }
                    }
                } ?: emptyList()
            }
    }

    data class Contexts(
        val application: Application
    )

    data class Application(
        val mappings: Mappings
    )

    data class Mappings(
        val dispatcherServlets: DispatcherServlets
    )

    data class DispatcherServlets(
        val dispatcherServlet: List<DispatcherServlet>
    )

    data class DispatcherServlet(
        val details: DispatcherServletDetails?
    ) {
        // isCustomHandlerMethod? isNonSpringHandlerMethod?
        fun isValidHandlerMethod(): Boolean {
            return this.details?.handlerMethod?.className?.contains("springframework") != true
        }
    }

    data class DispatcherServletDetails(
        val handlerMethod: HandlerMethod,
        val requestMappingConditions: RequestMappingConditions
    ) {
        
        data class HandlerMethod(
            val className: String
        )

        data class RequestMappingConditions(
            val methods: List<String>,
            val patterns: List<String>
        )
    }
}