package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.model.ApiError
import xyz.nikitacartes.mappinglens.service.BodyHashService
import xyz.nikitacartes.mappinglens.service.VersionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Each version costs a jar open and one entry read, so the range is capped rather than left open. */
private const val MAX_VERSIONS = 60

fun Route.bodyHashRoutes(service: BodyHashService, versionService: VersionService) {
    get("/api/v1/bodyhash") {
        val params = call.request.queryParameters
        val queries = params.getAll("q").orEmpty().filter { it.isNotBlank() }
        if (queries.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing 'q' (owner:name or owner:name:descriptor)", 400)); return@get
        }
        if (queries.size > 50) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "At most 50 'q' parameters per request", 400)); return@get
        }
        if (queries.any { it.substringAfter(':', "").isEmpty() }) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Each 'q' must name a method: owner:name[:descriptor]", 400)); return@get
        }
        // The named jars are what the hash is read from, and only yarn and mojmap have one.
        val ns = params["namespace"] ?: "mojmap"
        if (!call.ensureOneOf("namespace", ns, setOf("yarn", "mojmap"))) return@get
        val normalize = params["normalize"] ?: "named"
        if (!call.ensureOneOf("normalize", normalize, setOf("named", "intermediary"))) return@get
        val from = params["from"]
        val to = params["to"]
        if (from == null || to == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Both 'from' and 'to' are required", 400)); return@get
        }
        val releasesOnly = call.booleanQuery("releasesOnly", false) ?: return@get
        val includeVariants = call.booleanQuery("includeVariants", false) ?: return@get

        val versions = versionService.versionRange(from, to, includeVariants, releasesOnly)
        if (versions == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Unknown version in 'from'/'to'", 404)); return@get
        }
        // Counted after the filters, so releasesOnly=true is the way to widen the range.
        if (versions.size > MAX_VERSIONS) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("invalid_query", "Range covers ${versions.size} versions; at most $MAX_VERSIONS. Narrow it, or pass releasesOnly=true", 400),
            ); return@get
        }
        call.respond(service.hashes(queries, ns, versions, normalize))
    }
}
