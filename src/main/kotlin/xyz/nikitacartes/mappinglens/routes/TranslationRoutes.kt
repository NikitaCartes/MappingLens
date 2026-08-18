package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.model.ApiError
import xyz.nikitacartes.mappinglens.model.BatchTranslateRequest
import xyz.nikitacartes.mappinglens.service.TranslationService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
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
        if (!call.namespacesAvailable(translationService, version, from, to)) return@get
        val r = translationService.translate(name, from, to, version, type)
        if (r == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No translation found", 404))
        else call.respond(r)
    }
    post("/api/v1/translate/{version}") {
        val version = call.parameters["version"]
        if (version.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing version", 400)); return@post
        }
        val body = runCatching { call.receive<BatchTranslateRequest>() }.getOrNull()
            ?: run { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Expected {from, to, keys[]}", 400)); return@post }
        val namespaces = setOf("yarn", "mojmap", "intermediary", "obfuscated", "obf")
        if (!call.ensureOneOf("from", body.from, namespaces)) return@post
        if (!call.ensureOneOf("to", body.to, namespaces)) return@post
        if (body.keys.size > 2000) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "At most 2000 keys per request", 400)); return@post
        }
        if (!call.namespacesAvailable(translationService, version, body.from, body.to)) return@post
        val r = translationService.translateBatch(version, body.from, body.to, body.keys)
        if (r == null) call.respond(HttpStatusCode.NotFound, ApiError("version_not_found", "Version $version not found", 404))
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
        if (!call.namespacesAvailable(translationService, version, from, to)) return@get
        val r = translationService.translate(name, from, to, version, "class")
        if (r == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No translation found", 404))
        else call.respond(r)
    }
}

/** False after answering 422: one of [from]/[to] has no mappings in the requested version. */
private suspend fun ApplicationCall.namespacesAvailable(
    service: TranslationService,
    version: String?,
    from: String,
    to: String,
): Boolean {
    val (ver, ns) = service.firstUnavailableNamespace(version, listOf(from, to)) ?: return true
    respond(HttpStatusCode.UnprocessableEntity, ApiError("namespace_unavailable", "Version $ver has no $ns mappings", 422))
    return false
}
