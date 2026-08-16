package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.model.ApiError
import xyz.nikitacartes.mappinglens.service.HierarchyService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.hierarchyRoutes(service: HierarchyService) {
    get("/api/v1/hierarchy/{version}/{className...}") {
        val version = call.parameters["version"]!!
        val name = call.classNameParam() ?: return@get
        val ns = call.request.queryParameters["namespace"] ?: "mojmap"
        if (!call.ensureOneOf("namespace", ns, setOf("yarn", "mojmap"))) return@get
        val r = service.hierarchy(version, name, ns)
        if (r == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Class or jar not found", 404))
        else call.respond(r)
    }
}
