package org.senda.browser.core.backup

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

/**
 * The age file encryption format, version 1 (https://age-encryption.org/v1), with passphrase (scrypt)
 * recipients: a backup made by Senda can be opened with the official `age` tool or any compatible one,
 * on any system, and Senda can open files made by them.
 *
 * Only the passphrase recipient is implemented. Parsing is strict (canonical base64, LF line endings,
 * one scrypt stanza), as the specification requires, and checked against the official test vectors
 * (C2SP CCTV) in AgeTest. The whole file is decrypted before anything is returned: a damaged or
 * tampered file yields nothing, never a partial backup.
 */
object Age {
    private const val INTRO = "age-encryption.org/v1"
    private const val SCRYPT_LABEL = "age-encryption.org/v1/scrypt"
    private const val CHUNK = 64 * 1024
    private const val TAG = 16

    /**
     * scrypt work factor for new files. age's tool uses 18 (256 MiB of memory); 16 (64 MiB) keeps phones
     * with little memory working and is still costly to brute-force, so the passphrase strength matters most.
     */
    const val DEFAULT_LOG_N = 16

    /**
     * Highest work factor accepted when decrypting: 18 is age's default (256 MiB). More would not fit in the
     * memory a phone gives an app; age itself refuses more than 22.
     */
    const val MAX_LOG_N = 18

    class AgeException(val kind: Kind, message: String) : Exception(message) {
        /** Same categories as the official test vectors. */
        enum class Kind { HEADER, NO_MATCH, HMAC, PAYLOAD, UNSUPPORTED }
    }

    fun encrypt(plain: ByteArray, passphrase: CharArray, logN: Int = DEFAULT_LOG_N, random: SecureRandom = SecureRandom()): ByteArray {
        require(logN in 1..MAX_LOG_N)
        val fileKey = ByteArray(16).also { random.nextBytes(it) }
        try {
            val salt = ByteArray(16).also { random.nextBytes(it) }
            val wrapKey = scrypt(passphrase, salt, logN)
            val body = try { aead(true, wrapKey, ByteArray(12), fileKey) } finally { Arrays.fill(wrapKey, 0) }
            val header = StringBuilder()
                .append(INTRO).append('\n')
                .append("-> scrypt ").append(b64(salt)).append(' ').append(logN).append('\n')
            appendBody(header, body)
            header.append("---")
            val headerBytes = header.toString().toByteArray(Charsets.US_ASCII)
            val mac = headerMac(fileKey, headerBytes)
            val out = ByteArrayOutputStream(plain.size + plain.size / CHUNK * TAG + 256)
            out.write(headerBytes)
            out.write(" ${b64(mac)}\n".toByteArray(Charsets.US_ASCII))
            val nonce = ByteArray(16).also { random.nextBytes(it) }
            out.write(nonce)
            val payloadKey = hkdf(fileKey, nonce, "payload")
            try {
                var offset = 0
                var counter = 0L
                do {
                    val end = minOf(plain.size, offset + CHUNK)
                    val last = end == plain.size
                    out.write(aead(true, payloadKey, chunkNonce(counter, last), plain.copyOfRange(offset, end)))
                    offset = end
                    counter++
                } while (!last)
            } finally {
                Arrays.fill(payloadKey, 0)
            }
            return out.toByteArray()
        } finally {
            Arrays.fill(fileKey, 0)
        }
    }

    fun decrypt(file: ByteArray, passphrase: CharArray, maxLogN: Int = MAX_LOG_N): ByteArray {
        val header = parseHeader(file)
        if (header.stanzas.none { it.type == "scrypt" }) throw AgeException(AgeException.Kind.NO_MATCH, "No passphrase recipient")
        // The specification forbids mixing a passphrase with other recipients
        if (header.stanzas.size != 1) throw AgeException(AgeException.Kind.HEADER, "scrypt stanza must be alone")
        val stanza = header.stanzas.single()
        if (stanza.args.size != 2) throw AgeException(AgeException.Kind.HEADER, "Bad scrypt arguments")
        val salt = canonicalB64(stanza.args[0]) ?: throw AgeException(AgeException.Kind.HEADER, "Bad salt")
        if (salt.size != 16) throw AgeException(AgeException.Kind.HEADER, "Bad salt length")
        if (!Regex("[1-9][0-9]*").matches(stanza.args[1]) || stanza.args[1].length > 2) {
            throw AgeException(AgeException.Kind.HEADER, "Bad work factor")
        }
        val logN = stanza.args[1].toInt()
        // age itself treats a work factor above 22 as a malformed file
        if (logN > 22) throw AgeException(AgeException.Kind.HEADER, "Work factor $logN out of range")
        if (logN > maxLogN) throw AgeException(AgeException.Kind.UNSUPPORTED, "Work factor $logN too high")
        if (stanza.body.size != 32) throw AgeException(AgeException.Kind.HEADER, "Bad scrypt body length")
        val wrapKey = scrypt(passphrase, salt, logN)
        val fileKey = try {
            aead(false, wrapKey, ByteArray(12), stanza.body)
        } catch (e: Exception) {
            throw AgeException(AgeException.Kind.NO_MATCH, "Wrong passphrase")
        } finally {
            Arrays.fill(wrapKey, 0)
        }
        try {
            return decryptWithFileKey(file, header, fileKey)
        } finally {
            Arrays.fill(fileKey, 0)
        }
    }

    // ---- Internals (also used by the tests with the vectors' file key) ----

    internal class Stanza(val type: String, val args: List<String>, val body: ByteArray)
    internal class Header(val stanzas: List<Stanza>, val macInput: ByteArray, val mac: ByteArray, val payloadOffset: Int)

    internal fun decryptWithFileKey(file: ByteArray, header: Header, fileKey: ByteArray): ByteArray {
        if (fileKey.size != 16) throw AgeException(AgeException.Kind.NO_MATCH, "Bad file key")
        if (!MessageDigest.isEqual(headerMac(fileKey, header.macInput), header.mac)) {
            throw AgeException(AgeException.Kind.HMAC, "Header MAC does not match")
        }
        return decryptPayload(file, header.payloadOffset, fileKey)
    }

    internal fun parseHeader(file: ByteArray): Header {
        fun fail(msg: String): Nothing = throw AgeException(AgeException.Kind.HEADER, msg)
        var pos = 0
        fun readLine(): String {
            var i = pos
            while (i < file.size && file[i] != '\n'.code.toByte()) {
                val c = file[i].toInt() and 0xFF
                // Only printable ASCII and spaces in the header; CR is not allowed
                if (c < 0x20 || c > 0x7E) fail("Invalid header byte")
                i++
            }
            if (i >= file.size) fail("Header not terminated")
            val line = String(file, pos, i - pos, Charsets.US_ASCII)
            pos = i + 1
            return line
        }
        if (readLine() != INTRO) fail("Unknown version")
        val stanzas = mutableListOf<Stanza>()
        while (true) {
            val lineStart = pos
            val line = readLine()
            if (line.startsWith("---")) {
                if (!line.startsWith("--- ")) fail("Bad MAC line")
                val mac = canonicalB64(line.substring(4)) ?: fail("Bad MAC encoding")
                if (mac.size != 32) fail("Bad MAC length")
                if (stanzas.isEmpty()) fail("No recipients")
                // The MAC covers the header up to and including "---"
                return Header(stanzas, file.copyOfRange(0, lineStart + 3), mac, pos)
            }
            if (!line.startsWith("-> ")) fail("Expected a stanza")
            val parts = line.substring(3).split(' ')
            if (parts.any { it.isEmpty() }) fail("Empty stanza argument")
            val body = ByteArrayOutputStream()
            while (true) {
                val bodyLine = readLine()
                if (bodyLine.length > 64) fail("Body line too long")
                val decoded = (if (bodyLine.isEmpty()) ByteArray(0) else canonicalB64(bodyLine)) ?: fail("Bad body encoding")
                body.write(decoded)
                if (bodyLine.length < 64) break
            }
            stanzas.add(Stanza(parts[0], parts.drop(1), body.toByteArray()))
        }
    }

    private fun decryptPayload(file: ByteArray, offset: Int, fileKey: ByteArray): ByteArray {
        fun fail(msg: String): Nothing = throw AgeException(AgeException.Kind.PAYLOAD, msg)
        // The test vectors classify a missing or short nonce as part of a malformed header
        if (file.size - offset < 16) throw AgeException(AgeException.Kind.HEADER, "Missing payload nonce")
        val nonce = file.copyOfRange(offset, offset + 16)
        val key = hkdf(fileKey, nonce, "payload")
        val out = ByteArrayOutputStream(maxOf(0, file.size - offset))
        try {
            var pos = offset + 16
            var counter = 0L
            while (true) {
                val remaining = file.size - pos
                if (remaining < TAG) fail("Truncated chunk")
                val size = minOf(remaining, CHUNK + TAG)
                val chunk = file.copyOfRange(pos, pos + size)
                pos += size
                val last = pos == file.size
                val plain = try {
                    aead(false, key, chunkNonce(counter, last), chunk)
                } catch (e: Exception) {
                    fail(if (last) "Bad final chunk" else "Bad chunk")
                }
                // Only an empty file may end with an empty chunk
                if (last && plain.isEmpty() && counter > 0) fail("Empty final chunk")
                out.write(plain)
                if (last) break
                counter++
            }
            return out.toByteArray()
        } catch (e: AgeException) {
            // Nothing from a damaged payload is released
            val partial = out.toByteArray()
            Arrays.fill(partial, 0)
            throw e
        } finally {
            Arrays.fill(key, 0)
        }
    }

    private fun appendBody(sb: StringBuilder, body: ByteArray) {
        val encoded = b64(body)
        var i = 0
        while (true) {
            val end = minOf(encoded.length, i + 64)
            sb.append(encoded, i, end).append('\n')
            // A body line of exactly 64 characters is followed by another (possibly empty) one
            if (end - i < 64) break
            i = end
        }
    }

    private fun chunkNonce(counter: Long, last: Boolean): ByteArray {
        val n = ByteArray(12)
        var c = counter
        for (i in 10 downTo 3) { n[i] = (c and 0xFF).toByte(); c = c ushr 8 }
        n[11] = if (last) 1 else 0
        return n
    }

    private fun scrypt(passphrase: CharArray, salt: ByteArray, logN: Int): ByteArray {
        val pass = String(passphrase).toByteArray(Charsets.UTF_8)
        val fullSalt = SCRYPT_LABEL.toByteArray(Charsets.US_ASCII) + salt
        return try {
            SCrypt.generate(pass, fullSalt, 1 shl logN, 8, 1, 32)
        } catch (e: OutOfMemoryError) {
            throw AgeException(AgeException.Kind.UNSUPPORTED, "Not enough memory for work factor $logN")
        } finally {
            Arrays.fill(pass, 0)
        }
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.US_ASCII)))
        return ByteArray(32).also { gen.generateBytes(it, 0, 32) }
    }

    private fun headerMac(fileKey: ByteArray, header: ByteArray): ByteArray {
        val key = hkdf(fileKey, ByteArray(0), "header")
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        Arrays.fill(key, 0)
        mac.update(header, 0, header.size)
        return ByteArray(32).also { mac.doFinal(it, 0) }
    }

    private fun aead(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(c.getOutputSize(input.size))
        val n = c.processBytes(input, 0, input.size, out, 0)
        val total = n + c.doFinal(out, n)
        return if (total == out.size) out else out.copyOf(total)
    }

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)

    /** Strict base64 without padding: re-encoding must give back exactly the same text. */
    private fun canonicalB64(s: String): ByteArray? {
        if (s.isEmpty() || s.contains('=')) return null
        val bytes = try { java.util.Base64.getDecoder().decode(s) } catch (e: IllegalArgumentException) { return null }
        return if (b64(bytes) == s) bytes else null
    }
}
