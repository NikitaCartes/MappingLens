package dev.mappinglens.routes

import dev.mappinglens.model.ApiError
import dev.mappinglens.model.SourceToken
import dev.mappinglens.service.TokenService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.tokenRoutes(service: TokenService) {
    get("/api/v1/tokens/{version}/{className...}") {
        val version = call.parameters["version"]!!
        val name = call.classNameParam() ?: return@get
        val ns = call.request.queryParameters["namespace"] ?: "mojmap"
        val format = call.request.queryParameters["format"] ?: "json"
        if (!call.ensureOneOf("namespace", ns, setOf("yarn", "mojmap"))) return@get
        if (!call.ensureOneOf("format", format, setOf("text", "json"))) return@get
        val r = service.tokens(version, name, ns)
        when {
            r == null -> call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Source not found for version/namespace", 404))
            format == "text" -> call.respondText(tokenTsv(r.tokens), ContentType.Text.Plain)
            else -> call.respond(r)
        }
    }
}

/** One token per line, tab separated, so a token dump can be grepped without a JSON parser. */
private fun tokenTsv(tokens: List<SourceToken>): String = buildString {
    append("#startLine\tstartColumn\tendLine\tendColumn\ttype\tclassName\tname\tdescriptor\tdeclaration\n")
    for (t in tokens) {
        append(t.startLine).append('\t').append(t.startColumn).append('\t')
        append(t.endLine).append('\t').append(t.endColumn).append('\t')
        append(t.type).append('\t').append(t.className).append('\t')
        append(t.name.orEmpty()).append('\t').append(t.descriptor.orEmpty()).append('\t')
        append(t.declaration).append('\n')
    }
}
