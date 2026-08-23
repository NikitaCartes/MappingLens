package xyz.nikitacartes.mappinglens.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import xyz.nikitacartes.mappinglens.db.ResourceIndex
import xyz.nikitacartes.mappinglens.model.ApiError
import xyz.nikitacartes.mappinglens.service.ResourceService

/**
 * The resource explorer: what every Minecraft resource held in every version, and how it changed.
 *
 * The server computes no textual diff. `/file` returns the raw bytes, so a client puts a PNG in an
 * img element and an OGG file in an audio element, and passes two versions of a text file to its own
 * diff view.
 */
fun Route.resourceRoutes(resourceService: ResourceService) {
    get("/api/v1/resources/versions") {
        if (!call.resourcesReady(resourceService)) return@get
        val r = resourceService.versions()
        if (r == null) call.disabled() else call.respond(r)
    }

    get("/api/v1/resources/tree") {
        if (!call.resourcesReady(resourceService)) return@get
        val version = call.request.queryParameters.required("version") ?: run {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing 'version'", 400)); return@get
        }
        val branch = call.branch() ?: return@get
        val path = call.request.queryParameters["path"].orEmpty()
        val r = resourceService.tree(version, branch, path)
        if (r == null) call.versionNotFound(version) else call.respond(r)
    }

    get("/api/v1/resources/file") {
        if (!call.resourcesReady(resourceService)) return@get
        val version = call.request.queryParameters.required("version") ?: run {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing 'version'", 400)); return@get
        }
        val branch = call.branch() ?: return@get
        val path = call.request.queryParameters.required("path") ?: run {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing 'path'", 400)); return@get
        }
        val found = resourceService.file(version, branch, path)
        if (found == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No $branch:$path at $version", 404))
            return@get
        }
        val (sha, bytes) = found
        call.response.headers.append(HttpHeaders.ETag, sha)
        call.respondBytes(bytes, ContentType.parse(ResourceService.contentType(path)))
    }

    get("/api/v1/resources/diff") {
        if (!call.resourcesReady(resourceService)) return@get
        val (from, to) = call.versionPair() ?: return@get
        val branch = call.branch() ?: return@get
        val path = call.request.queryParameters["path"].orEmpty()
        val r = resourceService.diff(from, to, branch, path)
        if (r == null) call.versionNotFound("$from or $to") else call.respond(r)
    }

    get("/api/v1/resources/history") {
        if (!call.resourcesReady(resourceService)) return@get
        val branch = call.branch() ?: return@get
        val path = call.request.queryParameters.required("path") ?: run {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing 'path'", 400)); return@get
        }
        val r = resourceService.history(branch, path)
        if (r == null) call.respond(HttpStatusCode.NotFound, ApiError("not_found", "No history for $branch:$path", 404))
        else call.respond(r)
    }

    get("/api/v1/resources/search") {
        if (!call.resourcesReady(resourceService)) return@get
        val q = call.request.queryParameters.required("q") ?: run {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_query", "Missing 'q'", 400)); return@get
        }
        val type = call.request.queryParameters["type"] ?: "content"
        if (!call.ensureOneOf("type", type, setOf("content", "translation"))) return@get
        val limit = call.intQuery("limit", 50, 1, 500) ?: return@get
        val version = call.request.queryParameters["version"]
        val r = resourceService.search(q, type, version, limit)
        if (r == null) call.disabled() else call.respond(r)
    }
}

/** The mcmeta branch to read, or null after answering 400. */
private suspend fun ApplicationCall.branch(): String? {
    val branch = request.queryParameters["branch"] ?: ResourceIndex.BRANCHES.first()
    return if (ensureOneOf("branch", branch, ResourceIndex.BRANCHES.toSet())) branch else null
}

/** False after answering 404: no mcmeta clone is configured, or the resource index is not built. */
private suspend fun ApplicationCall.resourcesReady(service: ResourceService): Boolean {
    if (service.available) return true
    disabled()
    return false
}

private suspend fun ApplicationCall.disabled() = respond(
    HttpStatusCode.NotFound,
    ApiError(
        "resources_disabled",
        "The resource explorer is off. Set mappinglens.resources.repo and run: mappinglens index-resources",
        404,
    ),
)

private suspend fun ApplicationCall.versionNotFound(version: String) = respond(
    HttpStatusCode.NotFound,
    ApiError("version_not_found", "No resource data for version $version", 404),
)
