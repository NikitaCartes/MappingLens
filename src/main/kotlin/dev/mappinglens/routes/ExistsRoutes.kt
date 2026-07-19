package dev.mappinglens.routes

import dev.mappinglens.model.ApiError
import dev.mappinglens.model.ExistsRequest
import dev.mappinglens.service.ExistsService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.existsRoutes(existsService: ExistsService) {
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
        val response = existsService.exists(version, body.namespace, body.members)
            ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No named jar for version '$version' (${body.namespace})", 404)); return@post }
        call.respond(response)
    }
}
