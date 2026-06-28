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
        val name = normalizeClassName(call.parameters.getAll("className")?.joinToString("/").orEmpty())
        if (name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing class name", 400)); return@get
        }
        val ns = call.request.queryParameters["namespace"] ?: "mojmap"
        val format = call.request.queryParameters["format"] ?: "json"
        if (!call.ensureOneOf("namespace", ns, setOf("yarn", "mojmap", "intermediary", "obfuscated", "obf"))) return@get
        if (!call.ensureOneOf("format", format, setOf("text", "json"))) return@get
        val r = service.bytecode(version, name, ns)
        when {
            r == null -> call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Class or jar not found", 404))
            format == "text" -> call.respondText(r.bytecode, ContentType.Text.Plain)
            else -> call.respond(r)
        }
    }
    get("/api/v1/source/{version}/{className...}") {
        val version = call.parameters["version"]!!
        val name = normalizeClassName(call.parameters.getAll("className")?.joinToString("/").orEmpty())
        if (name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing class name", 400)); return@get
        }
        val explicit = call.request.queryParameters["namespace"]
        val ns = explicit ?: "mojmap"
        if (!call.ensureOneOf("namespace", ns, setOf("yarn", "mojmap"))) return@get
        // No namespace given: prefer mojmap, fall back to yarn for versions that lack mojmap.
        val r = service.source(version, name, ns)
            ?: if (explicit == null) service.source(version, name, "yarn") else null
        if (r == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Source not found", 404))
        else call.respond(r)
    }
}
