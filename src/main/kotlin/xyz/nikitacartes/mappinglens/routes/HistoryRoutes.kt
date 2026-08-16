package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.model.ApiError
import xyz.nikitacartes.mappinglens.service.HistoryService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.historyRoutes(service: HistoryService) {
    get("/api/v1/history") {
        // Repeat `q` to follow several keys at once: one pass over the version list serves all of
        // them, which is what multi-version work needs (few keys, many versions).
        val queries = call.request.queryParameters.getAll("q").orEmpty().filter { it.isNotBlank() }
        if (queries.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing 'q' (class or owner:name)", 400)); return@get
        }
        if (queries.size > 50) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "At most 50 'q' parameters per request", 400)); return@get
        }
        val ns = call.request.queryParameters["namespace"] ?: "mojmap"
        if (!call.ensureOneOf("namespace", ns, setOf("yarn", "mojmap", "intermediary"))) return@get
        val r = service.history(queries, ns, call.request.queryParameters["from"], call.request.queryParameters["to"])
        if (r == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Unknown version in 'from'/'to'", 404))
        else call.respond(r)
    }
}
