package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.model.ApiError
import xyz.nikitacartes.mappinglens.service.ReferenceService
import xyz.nikitacartes.mappinglens.service.VersionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private const val MAX_TARGETS = 25
private const val MAX_VERSIONS = 25

fun Route.referenceRoutes(service: ReferenceService, versionService: VersionService) {
    get("/api/v1/references/{version}") {
        val version = call.parameters["version"]!!
        // Repeat `q` to ask several targets of one version, and add `to` to ask them of a range of
        // versions. A mixin is checked target by target across the versions it has to hold on, and
        // one call per pair made that unusable.
        val targets = call.request.queryParameters.getAll("q").orEmpty().filter { it.isNotBlank() }
        if (targets.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing 'q' (class or class:name:descriptor)", 400)); return@get
        }
        if (targets.size > MAX_TARGETS) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "At most $MAX_TARGETS 'q' parameters per request", 400)); return@get
        }
        val ns = call.request.queryParameters["namespace"] ?: "mojmap"
        if (!call.ensureOneOf("namespace", ns, setOf("yarn", "mojmap"))) return@get
        val includeVariants = call.booleanQuery("includeVariants", false) ?: return@get

        val to = call.request.queryParameters["to"]?.takeIf { it.isNotBlank() }
        val versions = if (to == null) listOf(version) else versionService.versionRange(version, to, includeVariants)
            ?: run { call.respond(HttpStatusCode.NotFound, ApiError("version_not_found", "Unknown version in the range", 404)); return@get }
        if (versions.size > MAX_VERSIONS) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "The range covers ${versions.size} versions; at most $MAX_VERSIONS", 400)); return@get
        }

        val r = service.references(versions, targets, ns)
        if (r == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No named jar for the requested versions ($ns)", 404))
        else call.respond(r)
    }
}
