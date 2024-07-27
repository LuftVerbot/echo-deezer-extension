package dev.brahmkshatriya.echo.extension

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.BlockingQueue


class StreamingInputStream(
    private val audioQueue: BlockingQueue<ByteArray>,
    private val startBytes: Long,
    private val totalSize: Long
) : InputStream() {
    private var currentStream: ByteArrayInputStream? = null
    private var bytesSkipped: Long = 0
    private var bytesRead: Long = 0

    init {
        // Skip to the requested range
        while (bytesSkipped < startBytes) {
            val chunk = audioQueue.take()
            if (chunk.size + bytesSkipped > startBytes) {
                currentStream = ByteArrayInputStream(chunk)
                currentStream!!.skip(startBytes - bytesSkipped)
                bytesSkipped = startBytes
            } else {
                bytesSkipped += chunk.size
            }
        }
    }

    override fun read(): Int {
        if (currentStream == null || currentStream!!.available() <= 0) {
            val chunk = audioQueue.take()
            currentStream = ByteArrayInputStream(chunk)
        }
        return currentStream!!.read().also { bytesRead++ }
    }

    override fun read(b: ByteArray?, off: Int, len: Int): Int {
        if (currentStream == null || currentStream!!.available() <= 0) {
            val chunk = audioQueue.take()
            currentStream = ByteArrayInputStream(chunk)
        }
        return currentStream!!.read(b, off, len).also { bytesRead += it }
    }

    override fun available(): Int {
        return (totalSize - bytesSkipped).toInt()
    }
}