package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.model.ApiError
import xyz.nikitacartes.mappinglens.model.ReferenceRequest
import xyz.nikitacartes.mappinglens.service.ReferenceService
import xyz.nikitacartes.mappinglens.service.VersionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// Netty rejects a request line longer than 4096 bytes before it reaches the route, and a target key
// costs ~120 bytes once encoded, so a query string holds about 33 of them. The query cap stays where
// the transport puts it; larger batches travel in a body and get the same cap as /exists.
private const val MAX_TARGETS_QUERY = 25
private const val MAX_TARGETS_BODY = 2000
// Counted against the versions that need a jar scan, not against the range: a version the prebuilt
// index covers is one row read. A release-only walk of the whole index is therefore uncapped, and a
// snapshot walk still cannot ask for hundreds of 0.4-0.8s scans in one request.
private const val MAX_SCANNED_VERSIONS = 25

// Each frame multiplies the chains to report, and the answer stops being readable long before the
// walk stops being cheap. The 200-path cap inside the service is the other half of the same guard.
private const val MAX_DEPTH = 5

fun Route.referenceRoutes(service: ReferenceService, versionService: VersionService) {
    get("/api/v1/references/{version}") {
        // Repeat `q` to ask several targets of one version, and add `to` to ask them of a range of
        // versions. A mixin is checked target by target across the versions it has to hold on, and
        // one call per pair made that unusable.
        val includeVariants = call.booleanQuery("includeVariants", false) ?: return@get
        val releasesOnly = call.booleanQuery("releasesOnly", false) ?: return@get
        val depth = call.intQuery("depth", 1, 1, MAX_DEPTH) ?: return@get
        val params = call.request.queryParameters
        val request = ReferenceRequest(
            namespace = params["namespace"] ?: "mojmap",
            targets = params.getAll("q").orEmpty().filter { it.isNotBlank() },
            to = params["to"]?.takeIf { it.isNotBlank() },
            releasesOnly = releasesOnly,
            includeVariants = includeVariants,
            depth = depth,
        )
        call.respondReferences(service, versionService, request, MAX_TARGETS_QUERY)
    }

    // Grouped with the other diffs by URL, and kept here because it reads the same reverse index.
    get("/api/v1/diff/references") {
        val (from, to) = call.versionPair() ?: return@get
        val query = call.request.queryParameters.required("q") ?: run {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing 'q' (class or class:name:descriptor)", 400)); return@get
        }
        val namespace = call.request.queryParameters["namespace"] ?: "mojmap"
        if (!call.ensureOneOf("namespace", namespace, setOf("yarn", "mojmap"))) return@get
        val diff = service.diffReferences(from, to, query, namespace)
            ?: run { call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No named jar for '$from' or '$to' ($namespace)", 404)); return@get }
        call.respond(diff)
    }

    // The same call with the targets in a body. QUERY (RFC 10008) is the safe, idempotent spelling
    // of it, and both the Netty parser and the router already pass the method through, so the two
    // share one handler. Neither gets a Cache-Control header: a URL-keyed shared cache cannot see
    // the body, and would answer one batch with another batch's results.
    for (method in listOf(HttpMethod.Post, HttpMethod("QUERY"))) {
        route("/api/v1/references/{version}", method) {
            handle {
                val request = runCatching { call.receive<ReferenceRequest>() }.getOrNull() ?: run {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_body", "Expected {namespace, targets[], to, releasesOnly, includeVariants}", 400),
                    )
                    return@handle
                }
                call.respondReferences(service, versionService, request, MAX_TARGETS_BODY)
            }
        }
    }
}

private suspend fun ApplicationCall.respondReferences(
    service: ReferenceService,
    versionService: VersionService,
    request: ReferenceRequest,
    maxTargets: Int,
) {
    val version = parameters["version"]!!
    if (request.targets.isEmpty()) {
        respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing targets ('q' in the query, 'targets' in a body)", 400)); return
    }
    if (request.targets.size > maxTargets) {
        val hint = if (maxTargets == MAX_TARGETS_QUERY) ". POST the same request with a body for up to $MAX_TARGETS_BODY" else ""
        respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "At most $maxTargets targets per request$hint", 400)); return
    }
    if (!ensureOneOf("namespace", request.namespace, setOf("yarn", "mojmap"))) return
    if (request.depth !in 1..MAX_DEPTH) {
        respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Parameter 'depth' must be between 1 and $MAX_DEPTH", 400)); return
    }

    val versions = if (request.to == null) listOf(version) else
        versionService.versionRange(version, request.to, request.includeVariants, request.releasesOnly)
            ?: run { respond(HttpStatusCode.NotFound, ApiError("version_not_found", "Unknown version in the range", 404)); return }
    val scanned = service.scanned(versions, request.namespace)
    if (scanned.size > MAX_SCANNED_VERSIONS) {
        respond(
            HttpStatusCode.BadRequest,
            ApiError(
                "invalid_query",
                "The range covers ${versions.size} versions, ${scanned.size} of which are not in the prebuilt " +
                    "reference index; at most $MAX_SCANNED_VERSIONS of those. Pass releasesOnly=true, or narrow the range",
                400,
            ),
        ); return
    }

    val r = service.references(versions, request.targets, request.namespace, request.depth)
    if (r == null) respond(HttpStatusCode.NotFound, ApiError("not_found", "No named jar for the requested versions (${request.namespace})", 404))
    else respond(r)
}
