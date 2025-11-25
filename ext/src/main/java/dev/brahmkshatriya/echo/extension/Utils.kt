package dev.brahmkshatriya.echo.extension

import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Utils {
    // Fuck github
    private const val SECRET = "g4el58wc0zvf9na1"
    private val secretIvSpec = IvParameterSpec(ByteArray(8) { it.toByte() })
    private val keySpecCache = ConcurrentHashMap<String, SecretKeySpec>()

    private val md5Digest: MessageDigest by lazy { MessageDigest.getInstance("MD5") }

    private val charset = Charsets.ISO_8859_1

    private fun bitwiseXor(vararg values: Char): Char {
        return values.fold(0) { acc, char -> acc xor char.code }.toChar()
    }

    fun createBlowfishKey(trackId: String): String {
        val trackMd5Hex = trackId.toMD5()
        return buildString {
            for (i in 0 until 16) {
                append(bitwiseXor(trackMd5Hex[i], trackMd5Hex[i + 16], SECRET[i]))
            }
        }
    }

    private fun getSecretKeySpec(blowfishKey: String): SecretKeySpec {
        return keySpecCache.computeIfAbsent(blowfishKey) {
            SecretKeySpec(blowfishKey.toByteArray(charset), "Blowfish")
        }
    }

    private fun String.toMD5(): String {
        return md5Digest.digest(toByteArray(charset)).joinToString("") { "%02x".format(it) }
    }

    fun decryptBlowfish(chunk: ByteArray, blowfishKey: String): ByteArray {
        val secretKeySpec = getSecretKeySpec(blowfishKey)
        val cipher = Cipher.getInstance("BLOWFISH/CBC/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKeySpec, secretIvSpec)
        }
        return cipher.doFinal(chunk)
    }

    fun getContentLength(url: String, client: OkHttpClient): Long {
        val request = Request.Builder().url(url).head().build()
        client.newCall(request).execute().use { response ->
            return response.header("Content-Length")?.toLong() ?: 0L
        }
    }
}

/**
 * Seems Deezer ditched this way of getting songs.
 * Will leave it for now.
 */
/*
@Suppress("NewApi", "GetInstance")
fun generateTrackUrl(trackId: String, md5Origin: String, mediaVersion: String, quality: Int): String {
    val magicByte = 164
    val aesKey = "jo6aey6haid2Teih".toByteArray()
    val keySpec = SecretKeySpec(aesKey, "AES")

    val step1 = ByteArrayOutputStream().apply {
        write(md5Origin.toByteArray())
        write(magicByte)
        write(quality.toString().toByteArray())
        write(magicByte)
        write(trackId.toByteArray())
        write(magicByte)
        write(mediaVersion.toByteArray())
    }

    val md5Digest = MessageDigest.getInstance("MD5").digest(step1.toByteArray())
    val md5hex = md5Digest.joinToString("") { "%02x".format(it) }

    val step2 = ByteArrayOutputStream().apply {
        write(md5hex.toByteArray())
        write(magicByte)
        write(step1.toByteArray())
        write(magicByte)
    }

    while (step2.size() % 16 != 0) {
        step2.write(46)
    }

    val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, keySpec)
    }

    val encryptedHex = StringBuilder()
    val step2Bytes = step2.toByteArray()
    for (i in step2Bytes.indices step 16) {
        val block = step2Bytes.copyOfRange(i, i + 16)
        encryptedHex.append(cipher.doFinal(block).joinToString("") { "%02x".format(it) })
    }

    return "https://e-cdns-proxy-${md5Origin[0]}.dzcdn.net/mobile/1/$encryptedHex"
}*/