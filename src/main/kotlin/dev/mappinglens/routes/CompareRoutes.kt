package dev.mappinglens.routes

import dev.mappinglens.model.ApiError
import dev.mappinglens.service.CompareService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.compareRoutes(compareService: CompareService) {
    get("/api/v1/compare/{version}/{className...}") {
        val version = call.parameters["version"]
        if (version.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "version is required", 400)); return@get
        }
        val className = (call.parameters.getAll("className") ?: emptyList()).joinToString("/")
        if (className.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "class name is required", 400)); return@get
        }
        val from = call.request.queryParameters["from"] ?: "yarn"
        val to = call.request.queryParameters["to"] ?: "mojmap"
        val namespaces = setOf("yarn", "mojmap", "intermediary", "obfuscated", "obf")
        if (!call.ensureOneOf("from", from, namespaces)) return@get
        if (!call.ensureOneOf("to", to, namespaces)) return@get

        when (val result = compareService.compare(version, className, from, to)) {
            is CompareService.Result.Ok -> call.respond(result.response)
            is CompareService.Result.VersionNotFound ->
                call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Unknown version '$version'", 404))
            is CompareService.Result.ClassNotFound ->
                call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Class '$className' not found in $from for $version", 404))
            is CompareService.Result.NamespaceUnavailable ->
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    ApiError("namespace_unavailable", "Version ${result.version} has no ${result.namespace} mappings", 422),
                )
        }
    }
}
