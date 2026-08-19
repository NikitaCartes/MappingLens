package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.model.ApiError
import xyz.nikitacartes.mappinglens.model.ValidateRequest
import xyz.nikitacartes.mappinglens.service.ValidateService
import xyz.nikitacartes.mappinglens.service.VersionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Each pair costs one class read, so the matrix is capped on both sides rather than left open. */
private const val MAX_VERSIONS = 60
private const val MAX_TARGETS = 50

/** Injection points that name one instruction. The rest need a different check than this one. */
private val AT_VALUES = setOf("INVOKE", "FIELD")

fun Route.validateRoutes(service: ValidateService, versionService: VersionService) {
    post("/api/v1/validate") {
        val body = runCatching { call.receive<ValidateRequest>() }.getOrNull()
            ?: run {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Expected {namespace, targets[]}", 400)); return@post
            }
        // The named jars are what a target is checked against, and only yarn and mojmap have one.
        if (!call.ensureOneOf("namespace", body.namespace, setOf("yarn", "mojmap"))) return@post
        if (body.targets.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "At least one target is required", 400)); return@post
        }
        if (body.targets.size > MAX_TARGETS) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "At most $MAX_TARGETS targets per request", 400)); return@post
        }
        if (body.targets.any { it.id.isBlank() || it.owner.isBlank() || it.method.isBlank() }) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "Each target needs 'id', 'owner' and 'method'", 400)); return@post
        }
        body.targets.mapNotNull { it.at }.forEach { at ->
            if (!call.ensureOneOf("at.value", at.value, AT_VALUES)) return@post
            if (at.target.count { it == ':' } != 2) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_body", "'at.target' must be owner:name:descriptor, got '${at.target}'", 400),
                ); return@post
            }
        }

        val params = call.request.queryParameters
        val from = params["from"]
        val to = params["to"]
        if (from == null || to == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Both 'from' and 'to' are required", 400)); return@post
        }
        val releasesOnly = call.booleanQuery("releasesOnly", false) ?: return@post
        val includeVariants = call.booleanQuery("includeVariants", false) ?: return@post

        val versions = versionService.versionRange(from, to, includeVariants, releasesOnly)
            ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Unknown version in 'from'/'to'", 404)); return@post }
        // Counted after the filters, so releasesOnly=true is the way to widen the range.
        if (versions.size > MAX_VERSIONS) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("invalid_query", "Range covers ${versions.size} versions; at most $MAX_VERSIONS. Narrow it, or pass releasesOnly=true", 400),
            ); return@post
        }
        call.respond(service.validate(body, versions))
    }
}
