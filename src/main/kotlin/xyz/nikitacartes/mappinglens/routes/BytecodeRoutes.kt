package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.model.ApiError
import xyz.nikitacartes.mappinglens.service.BytecodeService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.bytecodeRoutes(service: BytecodeService) {
    get("/api/v1/bytecode/{version}/{className...}") {
        val version = call.parameters["version"]!!
        val name = call.classNameParam() ?: return@get
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
        val name = call.classNameParam() ?: return@get
        val explicit = call.request.queryParameters["namespace"]
        val ns = explicit ?: "mojmap"
        val format = call.request.queryParameters["format"] ?: "json"
        if (!call.ensureOneOf("namespace", ns, setOf("yarn", "mojmap"))) return@get
        if (!call.ensureOneOf("format", format, setOf("text", "json"))) return@get
        // No namespace given: prefer mojmap, fall back to yarn for versions that lack mojmap.
        val r = service.source(version, name, ns)
            ?: if (explicit == null) service.source(version, name, "yarn") else null
        when {
            r == null -> {
                val candidates = service.classCandidates(version, name, ns)
                    .ifEmpty { if (explicit == null) service.classCandidates(version, name, "yarn") else emptyList() }
                val hint = if (candidates.isEmpty()) "" else ". Did you mean: ${candidates.joinToString()}"
                call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Source not found$hint", 404))
            }
            format == "text" -> call.respondText(r.source, ContentType.Text.Plain)
            else -> call.respond(r)
        }
    }
    get("/api/v1/blame/{version}/{className...}") {
        val version = call.parameters["version"]!!
        val name = call.classNameParam() ?: return@get
        val ns = call.request.queryParameters["namespace"] ?: "mojmap"
        if (!call.ensureOneOf("namespace", ns, setOf("yarn", "mojmap"))) return@get
        val r = service.blame(version, name, ns)
        if (r == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No $ns source history for this class", 404))
        } else {
            call.respond(r)
        }
    }
}
