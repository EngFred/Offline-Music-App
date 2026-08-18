package com.engfred.musicplayer.core.data.server

import android.content.ContentResolver
import android.net.Uri
import com.engfred.musicplayer.core.util.LanAddressUtil
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight embedded HTTP server that streams local Android media content URIs
 * over the local area network (LAN) to Cast receiver devices (e.g., Chromecast/Google TV).
 *
 * Implements HTTP Range requests (206 Partial Content) for remote seeking and requires
 * a cryptographically random per-session token on all requests to prevent unauthorized LAN access.
 *
 * Encapsulates NanoHTTPD internally so consumer modules do not leak or require NanoHTTPD on their classpath.
 */
@Singleton
class LocalMediaHttpServer @Inject constructor(
    private val contentResolver: ContentResolver
) {

    data class MediaEntry(val uri: Uri, val mimeType: String)

    private val mediaRegistry = ConcurrentHashMap<String, MediaEntry>()
    private val artRegistry = ConcurrentHashMap<String, Uri>()

    @Volatile
    private var sessionToken: String = UUID.randomUUID().toString()

    @Volatile
    private var serverIp: String? = null

    private var internalServer: InternalHttpServer? = null

    val currentPort: Int
        get() = internalServer?.listeningPort ?: 0

    val currentToken: String
        get() = sessionToken

    val isRunning: Boolean
        get() = internalServer?.isAlive == true

    private inner class InternalHttpServer : NanoHTTPD(0) {
        override fun serve(session: IHTTPSession): Response {
            return handleServe(session)
        }
    }

    /**
     * Starts the server on an available port, regenerates the session token,
     * and discovers the device's local IP address.
     */
    fun startServer(): Boolean {
        return try {
            stopServer()
            sessionToken = UUID.randomUUID().toString()
            serverIp = LanAddressUtil.getLocalIpAddress() ?: "127.0.0.1"
            val server = InternalHttpServer()
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            internalServer = server
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Stops the server and clears all registered media URIs.
     */
    fun stopServer() {
        try {
            internalServer?.stop()
        } catch (_: Exception) {
            // Ignore stop errors
        } finally {
            internalServer = null
            mediaRegistry.clear()
            artRegistry.clear()
        }
    }

    /**
     * Registers a media content URI and returns the full LAN HTTP streaming URL.
     */
    fun registerMedia(id: String, contentUri: Uri, mimeType: String): String {
        mediaRegistry[id] = MediaEntry(contentUri, mimeType)
        val ip = serverIp ?: LanAddressUtil.getLocalIpAddress() ?: "127.0.0.1"
        return "http://$ip:$currentPort/media/$id?token=$sessionToken"
    }

    /**
     * Registers an album art content URI and returns the full LAN HTTP URL.
     */
    fun registerArt(id: String, contentUri: Uri): String {
        artRegistry[id] = contentUri
        val ip = serverIp ?: LanAddressUtil.getLocalIpAddress() ?: "127.0.0.1"
        return "http://$ip:$currentPort/art/$id?token=$sessionToken"
    }

    /**
     * Clears all registered media entries.
     */
    fun clearRegistry() {
        mediaRegistry.clear()
        artRegistry.clear()
    }

    private fun handleServe(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri
        val params = session.parms

        // 1. Session Token Authentication
        val requestToken = params["token"]
        if (requestToken == null || requestToken != sessionToken) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.UNAUTHORIZED,
                NanoHTTPD.MIME_PLAINTEXT,
                "Unauthorized: Invalid or missing session token"
            )
        }

        // 2. Route Dispatch
        return when {
            uri.startsWith("/media/") -> {
                val mediaId = uri.removePrefix("/media/")
                serveMedia(mediaId, session)
            }
            uri.startsWith("/art/") -> {
                val artId = uri.removePrefix("/art/")
                serveArt(artId)
            }
            uri == "/ping" -> {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "pong")
            }
            else -> {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not Found")
            }
        }
    }

    private fun serveMedia(mediaId: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val entry = mediaRegistry[mediaId] ?: return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.NOT_FOUND,
            NanoHTTPD.MIME_PLAINTEXT,
            "Media not registered or expired"
        )

        return try {
            val pfd = contentResolver.openFileDescriptor(entry.uri, "r")
                ?: return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Unable to open media descriptor"
                )

            val totalLength = pfd.statSize
            val rangeHeader = session.headers["range"]

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                servePartialContent(entry.uri, entry.mimeType, rangeHeader, totalLength)
            } else {
                serveFullContent(entry.uri, entry.mimeType, totalLength)
            }
        } catch (_: Exception) {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                NanoHTTPD.MIME_PLAINTEXT,
                "Error streaming media content"
            )
        }
    }

    private fun servePartialContent(
        contentUri: Uri,
        mimeType: String,
        rangeHeader: String,
        totalLength: Long
    ): NanoHTTPD.Response {
        val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
        val parts = rangeSpec.split("-")
        var startFrom: Long = 0
        var endAt: Long = if (totalLength > 0) totalLength - 1 else Long.MAX_VALUE

        try {
            if (parts[0].isNotEmpty()) {
                startFrom = parts[0].toLong()
            }
            if (parts.size > 1 && parts[1].isNotEmpty()) {
                endAt = parts[1].toLong()
            }
        } catch (_: NumberFormatException) {
            // Fallback to default range
        }

        if (totalLength > 0 && endAt >= totalLength) {
            endAt = totalLength - 1
        }

        val sendLength = if (totalLength > 0) {
            endAt - startFrom + 1
        } else {
            -1L
        }

        val inputStream: InputStream = contentResolver.openInputStream(contentUri)
            ?: return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "Stream unavailable"
            )

        if (startFrom > 0) {
            var skipped: Long = 0
            while (skipped < startFrom) {
                val s = inputStream.skip(startFrom - skipped)
                if (s <= 0) break
                skipped += s
            }
        }

        val response = if (sendLength > 0) {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.PARTIAL_CONTENT, mimeType, inputStream, sendLength)
        } else {
            NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.PARTIAL_CONTENT, mimeType, inputStream)
        }

        response.addHeader("Accept-Ranges", "bytes")
        val contentRange = if (totalLength > 0) {
            "bytes $startFrom-$endAt/$totalLength"
        } else {
            "bytes $startFrom-$endAt/*"
        }
        response.addHeader("Content-Range", contentRange)
        return response
    }

    private fun serveFullContent(contentUri: Uri, mimeType: String, totalLength: Long): NanoHTTPD.Response {
        val inputStream: InputStream = contentResolver.openInputStream(contentUri)
            ?: return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "Stream unavailable"
            )

        val response = if (totalLength > 0) {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mimeType, inputStream, totalLength)
        } else {
            NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, mimeType, inputStream)
        }

        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    private fun serveArt(artId: String): NanoHTTPD.Response {
        val uri = artRegistry[artId] ?: return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.NOT_FOUND,
            NanoHTTPD.MIME_PLAINTEXT,
            "Artwork not registered"
        )

        return try {
            val inputStream = contentResolver.openInputStream(uri)
                ?: return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Art stream unavailable"
                )

            val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            val length = pfd?.statSize ?: -1L

            if (length > 0) {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mimeType, inputStream, length)
            } else {
                NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, mimeType, inputStream)
            }
        } catch (_: Exception) {
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "Failed to load artwork"
            )
        }
    }
}
