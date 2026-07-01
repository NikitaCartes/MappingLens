package dev.mappinglens.routes

import dev.mappinglens.model.ApiError
import dev.mappinglens.service.ReferenceService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.referenceRoutes(service: ReferenceService) {
    get("/api/v1/references/{version}") {
        val version = call.parameters["version"]!!
        val target = call.request.queryParameters["q"]?.takeIf { it.isNotBlank() }
        if (target == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing 'q' (class or class:name:descriptor)", 400)); return@get
        }
        val ns = call.request.queryParameters["namespace"] ?: "mojmap"
        if (!call.ensureOneOf("namespace", ns, setOf("yarn", "mojmap"))) return@get
        val r = service.references(version, target, ns)
        if (r == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Jar not found for version/namespace", 404))
        else call.respond(r)
    }
}
