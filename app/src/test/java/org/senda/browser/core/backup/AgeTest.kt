package org.senda.browser.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Senda's age implementation against the official test vectors (C2SP CCTV, see
 * src/test/resources/age-testkit/README.md) and round trips of its own files.
 */
class AgeTest {
    private class Vector(val name: String, val headers: Map<String, List<String>>, val data: ByteArray) {
        val expect get() = headers.getValue("expect").single()
    }

    private fun load(file: File): Vector {
        val bytes = file.readBytes()
        val headers = mutableMapOf<String, MutableList<String>>()
        var pos = 0
        while (true) {
            val end = bytes.indexOfFirstFrom(pos, '\n'.code.toByte())
            val line = String(bytes, pos, end - pos, Charsets.UTF_8)
            pos = end + 1
            if (line.isEmpty()) break
            val (k, v) = line.split(": ", limit = 2)
            headers.getOrPut(k) { mutableListOf() }.add(v)
        }
        var data = bytes.copyOfRange(pos, bytes.size)
        if (headers["compressed"]?.single() == "zlib") data = java.util.zip.InflaterInputStream(data.inputStream()).readBytes()
        return Vector(file.name, headers, data)
    }

    private fun ByteArray.indexOfFirstFrom(from: Int, b: Byte): Int { for (i in from until size) if (this[i] == b) return i; return -1 }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val vectors = File(javaClass.classLoader!!.getResource("age-testkit/scrypt")!!.toURI()).parentFile
        .listFiles()!!.filter { it.name != "README.md" }.sortedBy { it.name }.map(::load)

    private fun kindFor(expect: String) = when (expect) {
        "header failure" -> Age.AgeException.Kind.HEADER
        "no match" -> Age.AgeException.Kind.NO_MATCH
        "HMAC failure" -> Age.AgeException.Kind.HMAC
        "payload failure" -> Age.AgeException.Kind.PAYLOAD
        else -> null
    }

    private fun check(v: Vector, run: () -> ByteArray): String? {
        return try {
            val out = run()
            when {
                v.expect != "success" -> "${v.name}: expected ${v.expect}, decrypted"
                sha256(out) != v.headers.getValue("payload").single() -> "${v.name}: wrong payload"
                else -> null
            }
        } catch (e: Age.AgeException) {
            val wanted = kindFor(v.expect)
            // A work factor above what Senda accepts is reported as unsupported; age reports it as no match
            val ok = e.kind == wanted || (v.expect == "no match" && e.kind == Age.AgeException.Kind.UNSUPPORTED)
            if (ok) null else "${v.name}: expected ${v.expect}, got ${e.kind} (${e.message})"
        }
    }

    @Test fun passphraseVectors() {
        val tested = vectors.filter { it.headers.containsKey("passphrase") }
        assertTrue(tested.size >= 25)
        val errors = tested.mapNotNull { v ->
            check(v) {
                // Some vectors list several passphrases: the file must open with one of them
                val passphrases = v.headers.getValue("passphrase")
                var result: ByteArray? = null
                var lastError: Age.AgeException? = null
                for (p in passphrases) {
                    try { result = Age.decrypt(v.data, p.toCharArray()); break } catch (e: Age.AgeException) {
                        lastError = e
                        if (e.kind != Age.AgeException.Kind.NO_MATCH) break
                    }
                }
                result ?: throw lastError!!
            }
        }
        assertEquals(errors.joinToString("\n"), 0, errors.size)
    }

    /** STREAM, header and HMAC vectors use other recipients: their file key is given, so the rest is tested directly. */
    @Test fun fileKeyVectors() {
        val tested = vectors.filter { !it.headers.containsKey("passphrase") && it.headers.containsKey("file key") && it.expect != "no match" }
        assertTrue(tested.size >= 30)
        val errors = tested.mapNotNull { v ->
            check(v) { Age.decryptWithFileKey(v.data, Age.parseHeader(v.data), hex(v.headers.getValue("file key").single())) }
        }
        assertEquals(errors.joinToString("\n"), 0, errors.size)
    }

    @Test fun roundTripSizes() {
        // Empty, one byte, exactly one chunk, one chunk plus one, several chunks
        for (size in listOf(0, 1, 65536, 65537, 200_000)) {
            val plain = ByteArray(size) { (it * 31).toByte() }
            val enc = Age.encrypt(plain, "correct horse".toCharArray(), logN = 10)
            assertArrayEquals("size $size", plain, Age.decrypt(enc, "correct horse".toCharArray()))
        }
    }

    @Test fun wrongPassphraseIsNoMatch() {
        val enc = Age.encrypt("secret".toByteArray(), "one".toCharArray(), logN = 10)
        try { Age.decrypt(enc, "two".toCharArray()); fail() } catch (e: Age.AgeException) { assertEquals(Age.AgeException.Kind.NO_MATCH, e.kind) }
    }

    @Test fun tamperingIsDetected() {
        val enc = Age.encrypt(ByteArray(1000) { 7 }, "pw".toCharArray(), logN = 10)
        for (i in listOf(30, enc.size - 1)) {
            val bad = enc.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            try { Age.decrypt(bad, "pw".toCharArray()); fail("byte $i") } catch (_: Age.AgeException) { }
        }
    }

    @Test fun headerIsCanonicalAge() {
        val text = String(Age.encrypt(ByteArray(0), "pw".toCharArray(), logN = 12), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("age-encryption.org/v1\n-> scrypt "))
        assertTrue(Regex("-> scrypt [A-Za-z0-9+/]{22} 12\n[A-Za-z0-9+/]{43}\n--- [A-Za-z0-9+/]{43}\n").containsMatchIn(text))
    }
}

/** Exchange with the official `age` tool: only runs when AGE_INTEROP_DIR is set (see docs/backup-format.md). */
class AgeInteropTest {
    @Test fun exchangeFilesWithOfficialAge() {
        val dir = System.getenv("AGE_INTEROP_DIR")?.let(::File) ?: return
        val pass = File(dir, "passphrase.txt").readText().trimEnd('\n').toCharArray()
        val plain = File(dir, "plain.bin").readBytes()
        File(dir, "senda_made.age").writeBytes(Age.encrypt(plain, pass))
        val fromAge = File(dir, "age_made.age")
        if (fromAge.exists()) File(dir, "age_made.out").writeBytes(Age.decrypt(fromAge.readBytes(), pass))
    }
}
