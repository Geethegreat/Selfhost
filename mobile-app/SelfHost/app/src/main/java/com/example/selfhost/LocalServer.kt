package com.example.app

import fi.iki.elonen.NanoHTTPD
import java.io.File


class LocalServer(
    port: Int,
    private val rootDir: File
) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response {

        var uri = session.uri

        if (uri == "/") {
            uri = "/index.html"
        }

        val file = File(rootDir, uri).canonicalFile

        if (!file.path.startsWith(rootDir.canonicalPath)) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "text/plain",
                "403 Forbidden"
            )
        }

        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "404 Not Found"
            )
        }

        val mime = getMimeTypeForFile(file.name)

        return newChunkedResponse(
            Response.Status.OK,
            mime,
            file.inputStream()
        )
    }
}

