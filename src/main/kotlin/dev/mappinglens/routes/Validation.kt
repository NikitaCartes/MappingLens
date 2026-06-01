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
