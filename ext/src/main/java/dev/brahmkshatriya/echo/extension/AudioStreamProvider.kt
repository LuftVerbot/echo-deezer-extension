package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Streamable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.closeQuietly
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream

object AudioStreamProvider {

    suspend fun openStream(
        streamable: Streamable,
        client: OkHttpClient,
        startByte: Long = 0,
        endByte: Long = -1
    ): InputStream = withContext(Dispatchers.IO) {
        val blockSize = 2048L
        val alignedStart = startByte - (startByte % blockSize)
        val dropBytes = (startByte % blockSize).toInt()
        val rangeHeader = if (endByte < 0) {
            "bytes=$alignedStart-"
        } else {
            "bytes=$alignedStart-$endByte"
        }

        val url = streamable.id
        val key = streamable.extras["key"] ?: ""

        val request = Request.Builder()
            .url(url)
            .header("Range", rangeHeader)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.closeQuietly()
            throw IllegalStateException("Failed to fetch audio: HTTP ${response.code}")
        }

        val rawStream = response.body.byteStream()

        return@withContext BufferedInputStream(object : FilterInputStream(rawStream) {
            private var blockCounter = alignedStart / blockSize
            private var toDrop = dropBytes

            override fun read(buf: ByteArray, off: Int, len: Int): Int {
                val chunkSize = blockSize.toInt().coerceAtMost(len)
                val temp = ByteArray(chunkSize)
                var totalRead = 0

                while (totalRead < chunkSize) {
                    val r = `in`.read(temp, totalRead, chunkSize - totalRead)
                    if (r == -1) break
                    totalRead += r
                }
                if (totalRead == 0) return -1

                val processed = if (totalRead == chunkSize && blockCounter % 3 == 0L) {
                    Utils.decryptBlowfish(temp, key)
                } else {
                    temp
                }
                blockCounter++

                val startIdx = if (toDrop > 0) {
                    val d = toDrop
                    toDrop = 0
                    d
                } else 0

                val toCopy = (totalRead - startIdx).coerceAtLeast(0)
                System.arraycopy(processed, startIdx, buf, off, toCopy)
                return toCopy
            }
        }, blockSize.toInt())
    }
}
