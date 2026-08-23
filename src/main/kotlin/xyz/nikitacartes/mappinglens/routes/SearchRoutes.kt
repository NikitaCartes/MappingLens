package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.config.AppConfig
import xyz.nikitacartes.mappinglens.model.ApiError
import xyz.nikitacartes.mappinglens.service.SearchService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.searchRoutes(searchService: SearchService, config: AppConfig) {
    get("/api/v1/search") {
        val q = call.request.queryParameters.required("q")
        if (q.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing required parameter 'q'", 400))
            return@get
        }
        val version = call.request.queryParameters["version"]
        val type = call.request.queryParameters["type"] ?: "all"
        val namespace = call.request.queryParameters["namespace"] ?: "all"
        if (!call.ensureOneOf("type", type, setOf("class", "method", "field", "all"))) return@get
        if (!call.ensureOneOf("namespace", namespace, setOf("yarn", "mojmap", "intermediary", "all"))) return@get
        val limit = call.intQuery("limit", config.search.defaultResults, 1, config.search.maxResults) ?: return@get
        val offset = call.intQuery("offset", 0, 0, Int.MAX_VALUE) ?: return@get
        val exact = call.booleanQuery("exact", false) ?: return@get
        val includeSynthetic = call.booleanQuery("includeSynthetic", false) ?: return@get

        val response = searchService.search(q, version, type, namespace, limit, offset, exact, includeSynthetic)
        call.respond(response)
    }
}
