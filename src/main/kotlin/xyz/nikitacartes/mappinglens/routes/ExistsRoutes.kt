package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.model.ApiError
import xyz.nikitacartes.mappinglens.model.ExistsRequest
import xyz.nikitacartes.mappinglens.service.ExistsService
import xyz.nikitacartes.mappinglens.service.VersionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.existsRoutes(existsService: ExistsService, versionService: VersionService) {
    post("/api/v1/exists/{version}") {
        val version = call.parameters["version"]
        if (version.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing version", 400)); return@post
        }
        val body = runCatching { call.receive<ExistsRequest>() }.getOrNull()
            ?: run { call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Expected {namespace, members[]}", 400)); return@post }
        if (!call.ensureOneOf("namespace", body.namespace, setOf("yarn", "mojmap"))) return@post
        if (body.members.size > 2000) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "At most 2000 members per request", 400)); return@post
        }
        body.members.forEachIndexed { i, m ->
            if (m.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "members[$i] must not be blank", 400)); return@post
            }
        }
        val to = call.request.queryParameters["to"]?.takeIf { it.isNotBlank() }
        if (to == null) {
            val response = existsService.exists(version, body.namespace, body.members)
                ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No named jar for version '$version' (${body.namespace})", 404)); return@post }
            call.respond(response)
            return@post
        }
        val releasesOnly = call.booleanQuery("releasesOnly", false) ?: return@post
        val includeVariants = call.booleanQuery("includeVariants", false) ?: return@post
        val versions = versionService.versionRange(version, to, includeVariants, releasesOnly)
            ?: run { call.respond(HttpStatusCode.NotFound, ApiError("version_not_found", "Unknown version in 'from'/'to'", 404)); return@post }
        val responses = versions.map { v ->
            existsService.exists(v, body.namespace, body.members)
                ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No named jar for version '$v' (${body.namespace})", 404)); return@post }
        }
        call.respond(responses)
    }
}
