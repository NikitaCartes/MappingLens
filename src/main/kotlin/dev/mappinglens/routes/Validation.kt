package dev.mappinglens.routes

import dev.mappinglens.model.ApiError
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

internal suspend fun ApplicationCall.ensureOneOf(parameter: String, value: String, allowed: Set<String>): Boolean {
    if (value in allowed) return true
    respond(
        HttpStatusCode.BadRequest,
        ApiError(
            error = "invalid_query",
            message = "Invalid parameter '$parameter': '$value'. Allowed values: ${allowed.joinToString()}",
            status = 400,
        ),
    )
    return false
}

internal suspend fun ApplicationCall.booleanQuery(name: String, default: Boolean): Boolean? {
    val raw = request.queryParameters[name] ?: return default
    return raw.toBooleanStrictOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Invalid boolean parameter '$name': '$raw'", 400))
        null
    }
}

internal suspend fun ApplicationCall.intQuery(name: String, default: Int, min: Int, max: Int): Int? {
    val raw = request.queryParameters[name] ?: return default
    val parsed = raw.toIntOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Invalid integer parameter '$name': '$raw'", 400))
        return null
    }
    if (parsed !in min..max) {
        respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Parameter '$name' must be between $min and $max", 400))
        return null
    }
    return parsed
}

internal fun Parameters.required(name: String): String? = this[name]?.takeIf { it.isNotBlank() }

/** The `{className...}` tail of a class-scoped route, or null after answering 400. */
internal suspend fun ApplicationCall.classNameParam(): String? {
    val name = normalizeClassName(parameters.getAll("className")?.joinToString("/").orEmpty())
    if (name.isNotBlank()) return name
    respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing class name", 400))
    return null
}

/** The `from`/`to` version pair every diff route needs, or null after answering 400. */
internal suspend fun ApplicationCall.versionPair(): Pair<String, String>? {
    val from = request.queryParameters.required("from")
    val to = request.queryParameters.required("to")
    if (from != null && to != null) return from to to
    respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing 'from' or 'to'", 400))
    return null
}

/** Accept dot-separated FQNs (`net.minecraft.Foo`) alongside slash-separated internal names. */
internal fun normalizeClassName(name: String): String =
    if ('/' in name) name else name.replace('.', '/')
