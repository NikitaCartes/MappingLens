package dev.mappinglens.routes

import dev.mappinglens.service.DiffService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.diffRoutes(diffService: DiffService) {
    get("/api/v1/diff") {
        val (from, to) = call.versionPair() ?: return@get
        val namespace = call.request.queryParameters["namespace"] ?: "mojmap"
        val type = call.request.queryParameters["type"] ?: "all"
        val pkg = call.request.queryParameters["package"]
        val klass = call.request.queryParameters["class"]
        val changeType = call.request.queryParameters["changeType"] ?: "all"
        if (!call.ensureOneOf("namespace", namespace, setOf("yarn", "mojmap", "intermediary"))) return@get
        if (!call.ensureOneOf("type", type, setOf("class", "method", "field", "all"))) return@get
        if (!call.ensureOneOf("changeType", changeType, setOf("added", "removed", "renamed", "all"))) return@get
        val limit = call.intQuery("limit", 100, 1, 5000) ?: return@get
        // `class=` targets a single class (member-precise, summary matches /diff/files); `package=`
        // is a package-path prefix over the whole diff. class= wins when both are given.
        if (!klass.isNullOrBlank()) {
            call.respond(diffService.diffClass(from, to, namespace, klass, type, changeType, limit)); return@get
        }
        call.respond(diffService.diff(from, to, namespace, type, pkg, changeType, limit))
    }
    get("/api/v1/diff/files") {
        val (from, to) = call.versionPair() ?: return@get
        val namespace = call.request.queryParameters["namespace"] ?: "mojmap"
        if (!call.ensureOneOf("namespace", namespace, setOf("yarn", "mojmap"))) return@get
        val path = call.request.queryParameters["file"] ?: call.request.queryParameters["path"]
        val format = call.request.queryParameters["format"] ?: "json"
        if (!call.ensureOneOf("format", format, setOf("json", "patch", "git"))) return@get
        if (format == "patch" || format == "git") {
            val context = call.intQuery("context", 3, 0, 20) ?: return@get
            val limit = call.intQuery("limit", 5000, 1, 50000) ?: return@get
            val function = call.request.queryParameters["function"]
            val ignoreWhitespace = call.booleanQuery("ignoreWhitespace", false) ?: return@get
            val patch = diffService.diffPatch(from, to, namespace, path, function, context, limit, ignoreWhitespace)
            call.respondText(patch.patch, ContentType.parse("text/x-diff"))
            return@get
        }
        call.respond(diffService.diffFiles(from, to, namespace, path))
    }
    get("/api/v1/diff/patch") {
        val (from, to) = call.versionPair() ?: return@get
        val namespace = call.request.queryParameters["namespace"] ?: "mojmap"
        if (!call.ensureOneOf("namespace", namespace, setOf("yarn", "mojmap"))) return@get
        val path = call.request.queryParameters["file"] ?: call.request.queryParameters["path"]
        val function = call.request.queryParameters["function"]
        val context = call.intQuery("context", 3, 0, 20) ?: return@get
        val limit = call.intQuery("limit", 5000, 1, 50000) ?: return@get
        val ignoreWhitespace = call.booleanQuery("ignoreWhitespace", false) ?: return@get
        val format = call.request.queryParameters["format"] ?: "patch"
        if (!call.ensureOneOf("format", format, setOf("json", "patch", "git"))) return@get
        val patch = diffService.diffPatch(from, to, namespace, path, function, context, limit, ignoreWhitespace)
        if (format == "json") {
            call.respond(patch)
        } else {
            call.respondText(patch.patch, ContentType.parse("text/x-diff"))
        }
    }
}
