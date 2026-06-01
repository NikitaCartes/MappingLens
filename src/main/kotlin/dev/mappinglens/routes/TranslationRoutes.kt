package dev.mappinglens.routes

import dev.mappinglens.model.ApiError
import dev.mappinglens.service.TranslationService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.translationRoutes(translationService: TranslationService) {
    get("/api/v1/translate") {
        val name = call.request.queryParameters.required("name")
        val from = call.request.queryParameters.required("from")
        val to = call.request.queryParameters.required("to")
        if (name.isNullOrBlank() || from.isNullOrBlank() || to.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "name, from, to are required", 400)); return@get
        }
        val version = call.request.queryParameters["version"]
        val type = call.request.queryParameters["type"] ?: "auto"
        val namespaces = setOf("yarn", "mojmap", "intermediary", "obfuscated", "obf")
        if (!call.ensureOneOf("from", from, namespaces)) return@get
        if (!call.ensureOneOf("to", to, namespaces)) return@get
        if (!call.ensureOneOf("type", type, setOf("class", "method", "field", "auto"))) return@get
        translationService.firstUnavailableNamespace(version, listOf(from, to))?.let { (ver, ns) ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ApiError(
                    "namespace_unavailable",
                    "Version $ver has no $ns mappings",
                    422,
                ),
            ); return@get
        }
        val r = translationService.translate(name, from, to, version, type)
        if (r == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No translation found", 404))
        else call.respond(r)
    }
    get("/api/v1/translate/class/{name...}") {
        val parts = call.parameters.getAll("name") ?: emptyList()
        val name = parts.joinToString("/")
        val from = call.request.queryParameters["from"] ?: "yarn"
        val to = call.request.queryParameters["to"] ?: "mojmap"
        val version = call.request.queryParameters["version"]
        val namespaces = setOf("yarn", "mojmap", "intermediary", "obfuscated", "obf")
        if (!call.ensureOneOf("from", from, namespaces)) return@get
        if (!call.ensureOneOf("to", to, namespaces)) return@get
        translationService.firstUnavailableNamespace(version, listOf(from, to))?.let { (ver, ns) ->
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ApiError(
                    "namespace_unavailable",
                    "Version $ver has no $ns mappings",
                    422,
                ),
            ); return@get
        }
        val r = translationService.translate(name, from, to, version, "class")
        if (r == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No translation found", 404))
        else call.respond(r)
    }
}
