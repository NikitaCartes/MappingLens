package dev.mappinglens.routes

import dev.mappinglens.model.ApiError
import dev.mappinglens.service.BytecodeService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.bytecodeRoutes(service: BytecodeService) {
    get("/api/v1/bytecode/{version}/{className...}") {
        val version = call.parameters["version"]!!
        val name = call.parameters.getAll("className")?.joinToString("/").orEmpty()
        if (name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing class name", 400)); return@get
        }
        val ns = call.request.queryParameters["namespace"] ?: "yarn"
        val format = call.request.queryParameters["format"] ?: "text"
        if (!call.ensureOneOf("namespace", ns, setOf("yarn", "mojmap", "intermediary", "obfuscated", "obf"))) return@get
        if (!call.ensureOneOf("format", format, setOf("text", "json"))) return@get
        val r = service.bytecode(version, name, ns)
        if (r == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Class or jar not found", 404))
        else call.respond(r)
    }
    get("/api/v1/source/{version}/{className...}") {
        val version = call.parameters["version"]!!
        val name = call.parameters.getAll("className")?.joinToString("/").orEmpty()
        if (name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing class name", 400)); return@get
        }
        val ns = call.request.queryParameters["namespace"] ?: "yarn"
        if (!call.ensureOneOf("namespace", ns, setOf("yarn", "mojmap"))) return@get
        val r = service.source(version, name, ns)
        if (r == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Source not found", 404))
        else call.respond(r)
    }
}
