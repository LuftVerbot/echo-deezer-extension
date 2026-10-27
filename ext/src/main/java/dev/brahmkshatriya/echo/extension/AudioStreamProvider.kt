package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Streamable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque

object AudioStreamProvider {

    private const val BLOCK_SIZE = 2048
    private const val BUFFER_POOL_LIMIT = 10

    private val bufferPool = ArrayDeque<ByteArray>()

    private val poolLock = Any()

    private fun getBuffer(): ByteArray =
        synchronized(poolLock) {
            bufferPool.pollFirst()
        } ?: ByteArray(BLOCK_SIZE)

    private fun returnBuffer(buffer: ByteArray) {
        if (buffer.size != BLOCK_SIZE) return

        synchronized(poolLock) {
            if (bufferPool.size < BUFFER_POOL_LIMIT) {
                bufferPool.addLast(buffer)
            }
        }
    }

    suspend fun openStream(
        streamable: Streamable,
        client: OkHttpClient,
        startByte: Long = 0,
        endByte: Long = -1
    ): InputStream = withContext(Dispatchers.IO) {

        require(startByte >= 0) {
            "startByte must not be negative"
        }

        require(endByte == -1L || endByte >= startByte) {
            "endByte must be -1 or greater than or equal to startByte"
        }

        val url = streamable.id
        val key = streamable.extras["key"]
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException(
                "Missing Blowfish key in streamable extras"
            )

        val alignedStart =
            startByte - (startByte % BLOCK_SIZE)

        val fetchEnd = if (endByte < 0) {
            -1L
        } else {
            val lastStripeStart =
                endByte - (endByte % BLOCK_SIZE)

            if (lastStripeStart > Long.MAX_VALUE - BLOCK_SIZE) {
                Long.MAX_VALUE
            } else {
                lastStripeStart + BLOCK_SIZE - 1
            }
        }

        val rangeHeader = if (fetchEnd < 0) {
            "bytes=$alignedStart-"
        } else {
            "bytes=$alignedStart-$fetchEnd"
        }

        val request = Request.Builder()
            .url(url)
            .header("Range", rangeHeader)
            .header("Cache-Control", "no-store")
            .build()

        val response = client.newCall(request).execute()

        try {
            if (!response.isSuccessful) {
                throw IOException(
                    "Failed to fetch audio: HTTP ${response.code}"
                )
            }

            validateRangeResponse(response, alignedStart)

            val body = response.body

            val decrypted = StripeDecryptingInputStream(
                source = body.byteStream(),
                key = key,
                initialChunkIndex = alignedStart / BLOCK_SIZE,
                onClose = { response.close() },
                acquireBuffer = ::getBuffer,
                releaseBuffer = ::returnBuffer
            )

            val length = if (endByte < 0) {
                -1L
            } else {
                endByte - startByte + 1
            }

            return@withContext RangeSlicingInputStream(
                source = decrypted,
                skipBytes = startByte - alignedStart,
                outputLength = length
            )
        } catch (e: Exception) {
            response.close()
            throw e
        }
    }

    private fun validateRangeResponse(
        response: Response,
        alignedStart: Long
    ) {
        if (response.code == 206) {
            val contentRange =
                response.header("Content-Range")
                    ?: throw IOException(
                        "HTTP 206 response is missing Content-Range"
                    )

            val expectedPrefix = "bytes $alignedStart-"

            if (!contentRange.startsWith(expectedPrefix)) {
                throw IOException(
                    "Unexpected Content-Range: $contentRange; " +
                            "expected start at $alignedStart"
                )
            }
        } else if (alignedStart != 0L) {
            throw IOException(
                "Server ignored the byte-range request: " +
                        "HTTP ${response.code}, start=$alignedStart"
            )
        }
    }

    private class StripeDecryptingInputStream(
        private val source: InputStream,
        private val key: String,
        initialChunkIndex: Long,
        private val onClose: () -> Unit,
        private val acquireBuffer: () -> ByteArray,
        private val releaseBuffer: (ByteArray) -> Unit
    ) : InputStream() {

        private var chunkIndex = initialChunkIndex

        private val inputBuffer = acquireBuffer()

        private var outputBuffer: ByteArray? = null
        private var outputPosition = 0
        private var outputLimit = 0

        private var eof = false
        private var closed = false
        private var bufferReturned = false

        private fun checkOpen() {
            if (closed) throw IOException("Stream is closed")
        }

        private fun loadNextStripe(): Boolean {
            var total = 0

            while (total < BLOCK_SIZE) {
                val count = source.read(
                    inputBuffer,
                    total,
                    BLOCK_SIZE - total
                )

                if (count < 0) break

                if (count == 0) {
                    val single = source.read()
                    if (single < 0) break

                    inputBuffer[total++] = single.toByte()
                } else {
                    total += count
                }
            }

            if (total == 0) {
                eof = true
                return false
            }

            outputPosition = 0
            outputLimit = total

            if (total == BLOCK_SIZE) {
                outputBuffer = if (chunkIndex % 3L == 0L) {
                    Utils.decryptBlowfish(inputBuffer, key)
                } else {
                    inputBuffer
                }

                chunkIndex++
            } else {

                outputBuffer = inputBuffer
                eof = true
            }

            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)

            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int
        ): Int {
            checkOpen()

            if (offset < 0 || length < 0 ||
                offset > buffer.size - length
            ) {
                throw IndexOutOfBoundsException()
            }

            if (length == 0) return 0

            if (outputPosition >= outputLimit) {
                if (eof || !loadNextStripe()) return -1
            }

            val current = outputBuffer
                ?: throw IOException("Missing stripe output")

            val count = minOf(
                length,
                outputLimit - outputPosition
            )

            System.arraycopy(
                current,
                outputPosition,
                buffer,
                offset,
                count
            )

            outputPosition += count
            return count
        }

        override fun close() {
            if (closed) return
            closed = true

            try {
                source.close()
            } finally {
                try {
                    onClose()
                } finally {
                    if (!bufferReturned) {
                        bufferReturned = true
                        releaseBuffer(inputBuffer)
                    }
                }
            }
        }
    }

    private class RangeSlicingInputStream(
        private val source: InputStream,
        private var skipBytes: Long,
        private var outputLength: Long
    ) : InputStream() {

        private var closed = false
        private val discardBuffer = ByteArray(4096)

        private fun checkOpen() {
            if (closed) throw IOException("Stream is closed")
        }

        private fun discardPrefix() {
            while (skipBytes > 0) {
                val count = source.read(
                    discardBuffer,
                    0,
                    minOf(skipBytes, discardBuffer.size.toLong()).toInt()
                )

                if (count < 0) {
                    throw IOException(
                        "Unexpected EOF while seeking to requested byte"
                    )
                }

                if (count == 0) continue
                skipBytes -= count
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)

            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int
        ): Int {
            checkOpen()

            if (offset < 0 || length < 0 ||
                offset > buffer.size - length
            ) {
                throw IndexOutOfBoundsException()
            }

            if (length == 0) return 0
            if (outputLength == 0L) return -1

            discardPrefix()

            val allowed = if (outputLength < 0) {
                length
            } else {
                minOf(length, outputLength.toIntSafely())
            }

            val count = source.read(buffer, offset, allowed)

            if (count < 0) {
                if (outputLength > 0) {
                    throw IOException(
                        "Unexpected EOF: requested audio range " +
                                "was not fully received"
                    )
                }
                return -1
            }

            if (outputLength > 0) {
                outputLength -= count
            }

            return count
        }

        override fun close() {
            if (closed) return
            closed = true
            source.close()
        }

        private fun Long.toIntSafely(): Int =
            minOf(this, Int.MAX_VALUE.toLong()).toInt()
    }
}