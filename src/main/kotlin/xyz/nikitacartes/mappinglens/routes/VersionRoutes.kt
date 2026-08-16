package xyz.nikitacartes.mappinglens.routes

import xyz.nikitacartes.mappinglens.model.ApiError
import xyz.nikitacartes.mappinglens.service.VersionService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.versionRoutes(versionService: VersionService) {
    route("/api/v1/versions") {
        get {
            call.respond(versionService.listVersions())
        }
        get("{version}") {
            val v = call.parameters["version"]!!
            val info = versionService.getVersion(v)
            if (info == null) call.respond(HttpStatusCode.NotFound, ApiError("version_not_found", "Version $v not found", 404))
            else call.respond(info)
        }
    }
    get("/api/v1/classes/{version}") {
        val v = call.parameters["version"]!!
        val classes = versionService.listClasses(v)
        if (classes == null) call.respond(HttpStatusCode.NotFound, ApiError("version_not_found", "Version $v not found", 404))
        else call.respond(classes)
    }
}
