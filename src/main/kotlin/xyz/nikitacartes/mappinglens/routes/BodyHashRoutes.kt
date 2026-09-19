package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.model.ApiError
import xyz.nikitacartes.mappinglens.model.BodyHashRequest
import xyz.nikitacartes.mappinglens.service.BodyHashService
import xyz.nikitacartes.mappinglens.service.VersionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Each version costs a jar open and one entry read, so the range is capped rather than left open. */
private const val MAX_VERSIONS = 60
private const val MAX_QUERIES_QUERY = 50
private const val MAX_QUERIES_BODY = 2000

fun Route.bodyHashRoutes(service: BodyHashService, versionService: VersionService) {
    get("/api/v1/bodyhash") {
        val params = call.request.queryParameters
        val queries = params.getAll("q").orEmpty().filter { it.isNotBlank() }
        if (queries.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing 'q' (owner:name or owner:name:descriptor)", 400)); return@get
        }
        if (queries.size > MAX_QUERIES_QUERY) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "At most $MAX_QUERIES_QUERY 'q' parameters per request", 400)); return@get
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

        call.respondBodyHashes(service, versionService, queries, ns, normalize, from, to, releasesOnly, includeVariants)
    }

    // The same call with the queries in a body. QUERY (RFC 10008) is the safe, idempotent spelling
    // of it, and both the Netty parser and the router already pass the method through, so the two
    // share one handler. Neither gets a Cache-Control header: a URL-keyed shared cache cannot see
    // the body, and would answer one batch with another batch's results.
    for (method in listOf(HttpMethod.Post, HttpMethod("QUERY"))) {
        route("/api/v1/bodyhash", method) {
            handle {
                val request = runCatching { call.receive<BodyHashRequest>() }.getOrNull() ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_body", "Expected {namespace, normalize, queries[], from, to}", 400),
                    )
                    return@handle
                }
                val params = call.request.queryParameters
                val queries = if (request.queries.isNotEmpty()) request.queries else request.targets
                if (queries.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "At least one query is required ('queries' in a body)", 400))
                    return@handle
                }
                if (queries.size > MAX_QUERIES_BODY) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "At most $MAX_QUERIES_BODY queries per request", 400))
                    return@handle
                }
                queries.forEachIndexed { i, q ->
                    if (q.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("invalid_body", "queries[$i] must not be blank", 400))
                        return@handle
                    }
                }
                if (!call.ensureOneOf("namespace", request.namespace, setOf("yarn", "mojmap"))) return@handle
                if (!call.ensureOneOf("normalize", request.normalize, setOf("named", "intermediary"))) return@handle
                val from = request.from?.takeIf { it.isNotBlank() } ?: params["from"]
                val to = request.to?.takeIf { it.isNotBlank() } ?: params["to"]
                if (from == null || to == null) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Both 'from' and 'to' are required", 400))
                    return@handle
                }
                val releasesOnly = params["releasesOnly"]?.toBooleanStrictOrNull() ?: request.releasesOnly
                val includeVariants = params["includeVariants"]?.toBooleanStrictOrNull() ?: request.includeVariants
                if (params["releasesOnly"] != null && params["releasesOnly"]!!.toBooleanStrictOrNull() == null) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Invalid boolean parameter 'releasesOnly': '${params["releasesOnly"]}'", 400))
                    return@handle
                }
                if (params["includeVariants"] != null && params["includeVariants"]!!.toBooleanStrictOrNull() == null) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Invalid boolean parameter 'includeVariants': '${params["includeVariants"]}'", 400))
                    return@handle
                }

                call.respondBodyHashes(service, versionService, queries, request.namespace, request.normalize, from, to, releasesOnly, includeVariants)
            }
        }
    }
}

private suspend fun ApplicationCall.respondBodyHashes(
    service: BodyHashService,
    versionService: VersionService,
    queries: List<String>,
    namespace: String,
    normalize: String,
    from: String,
    to: String,
    releasesOnly: Boolean,
    includeVariants: Boolean,
) {
    val bad = queries.indexOfFirst { it.substringAfter(':', "").isEmpty() }
    if (bad >= 0) {
        respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "q[$bad] must name a method: owner:name[:descriptor]", 400)); return
    }
    val versions = versionService.versionRange(from, to, includeVariants, releasesOnly)
    if (versions == null) {
        respond(HttpStatusCode.NotFound, ApiError("not_found", "Unknown version in 'from'/'to'", 404)); return
    }
    // Counted after the filters, so releasesOnly=true is the way to widen the range.
    if (versions.size > MAX_VERSIONS) {
        respond(
            HttpStatusCode.BadRequest,
            ApiError("invalid_query", "Range covers ${versions.size} versions; at most $MAX_VERSIONS. Narrow it, or pass releasesOnly=true", 400),
        ); return
    }
    respond(service.hashes(queries, namespace, versions, normalize))
}
