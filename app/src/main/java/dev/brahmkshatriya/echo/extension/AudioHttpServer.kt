package dev.brahmkshatriya.echo.extension

import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.LinkedBlockingQueue

class AudioHttpServer(port: Int) : NanoHTTPD(port) {
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var totalSize: Long = 0

    @Synchronized
    fun add(chunk: ByteArray) {
        audioQueue.put(chunk)
        totalSize += chunk.size
    }

    @Synchronized
    fun clear() {
        audioQueue.clear()
        totalSize = 0
    }

    override fun start() {
        super.start()
        clear() // Clear previous data when starting
    }

    override fun serve(session: IHTTPSession): Response {
        // Must be only GET
        if (session.method != Method.GET) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Only GET request supported!")
        }

        // Parse range header
        val rangeHeader = session.headers["range"]
        val startBytes = rangeHeader?.substringAfter("bytes=")?.substringBefore("-")?.toLongOrNull() ?: 0L
        val isRanged = rangeHeader != null

        val streamingInputStream = StreamingInputStream(audioQueue, startBytes, totalSize)

        return newFixedLengthResponse(
            if (isRanged) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
            "audio/mpeg",
            streamingInputStream,
            streamingInputStream.available().toLong()
        ).apply {
            if (isRanged) {
                addHeader("Content-Range", "bytes $startBytes-${streamingInputStream.available() - 1}/${totalSize}")
            }
            addHeader("Accept-Ranges", "bytes")
        }
    }
}