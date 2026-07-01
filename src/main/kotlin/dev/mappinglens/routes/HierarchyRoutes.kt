package dev.mappinglens.routes

import dev.mappinglens.model.ApiError
import dev.mappinglens.service.HierarchyService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.hierarchyRoutes(service: HierarchyService) {
    get("/api/v1/hierarchy/{version}/{className...}") {
        val version = call.parameters["version"]!!
        val name = normalizeClassName(call.parameters.getAll("className")?.joinToString("/").orEmpty())
        if (name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing class name", 400)); return@get
        }
        val ns = call.request.queryParameters["namespace"] ?: "mojmap"
        if (!call.ensureOneOf("namespace", ns, setOf("yarn", "mojmap"))) return@get
        val r = service.hierarchy(version, name, ns)
        if (r == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Class or jar not found", 404))
        else call.respond(r)
    }
}
