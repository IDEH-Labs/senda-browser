package org.senda.browser.core.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The saved assistant conversation: encrypted at rest, unreadable without the wrapping key, and only
 * deleted when asked. The hardware key is replaced by a software one (the keystore does not exist on the JVM).
 */
class AssistantChatStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeWrapper : AssistantChatStore.KeyWrapper {
        var key: SecretKey = newKey()
        var locked = false
        var resets = 0
        override fun wrap(key: ByteArray): Pair<ByteArray, ByteArray> {
            if (locked) throw IllegalStateException("locked")
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, this@FakeWrapper.key) }
            return Pair(c.doFinal(key), c.iv)
        }
        override fun unwrap(wrapped: ByteArray, iv: ByteArray): ByteArray {
            if (locked) throw IllegalStateException("locked")
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }.doFinal(wrapped)
        }
        override fun reset() { resets++; key = newKey() }
        companion object { fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    }

    private lateinit var wrapper: FakeWrapper
    private lateinit var original: AssistantChatStore.KeyWrapper
    private lateinit var dir: File
    private val chatFile get() = File(dir, "assistant_chat.enc")

    @Before fun setUp() {
        original = AssistantChatStore.wrapper
        wrapper = FakeWrapper()
        AssistantChatStore.wrapper = wrapper
        AssistantChatStore.lock()
        dir = tmp.newFolder()
    }

    @After fun tearDown() {
        AssistantChatStore.lock()
        AssistantChatStore.wrapper = original
    }

    private val conversation = """{"messages":[{"role":"USER","shown":"¿Qué es Senda? 日本語 🙂","sent":"secreto-unico-123"}]}"""

    @Test fun firstUseHasNoConversation() {
        assertNull(AssistantChatStore.unlock(dir))
        assertTrue(AssistantChatStore.isUnlocked)
        assertFalse(chatFile.exists())
    }

    @Test fun savedConversationSurvivesLockAndUnlock() {
        AssistantChatStore.unlock(dir)
        AssistantChatStore.save(dir, conversation)
        AssistantChatStore.lock()
        assertFalse(AssistantChatStore.isUnlocked)
        assertEquals(conversation, AssistantChatStore.unlock(dir))
    }

    @Test fun fileDoesNotContainTheConversationInClear() {
        AssistantChatStore.unlock(dir)
        AssistantChatStore.save(dir, conversation)
        val raw = chatFile.readText()
        assertFalse(raw.contains("secreto-unico-123"))
        assertFalse(raw.contains("Senda"))
    }

    @Test fun lockedStoreDoesNotWrite() {
        AssistantChatStore.save(dir, conversation)
        assertFalse(chatFile.exists())
    }

    @Test fun withoutFingerprintItCannotBeOpened() {
        AssistantChatStore.unlock(dir)
        AssistantChatStore.save(dir, conversation)
        AssistantChatStore.lock()
        wrapper.locked = true
        try { AssistantChatStore.unlock(dir); fail("opened without authentication") } catch (_: IllegalStateException) { }
        assertFalse(AssistantChatStore.isUnlocked)
    }

    @Test fun anotherHardwareKeyCannotOpenIt() {
        AssistantChatStore.unlock(dir)
        AssistantChatStore.save(dir, conversation)
        AssistantChatStore.lock()
        wrapper.key = FakeWrapper.newKey()
        try { AssistantChatStore.unlock(dir); fail("opened with another key") } catch (_: javax.crypto.AEADBadTagException) { }
        assertFalse(AssistantChatStore.isUnlocked)
    }

    @Test fun tamperedFileIsRejected() {
        AssistantChatStore.unlock(dir)
        AssistantChatStore.save(dir, conversation)
        AssistantChatStore.lock()
        val json = org.json.JSONObject(chatFile.readText())
        val data = java.util.Base64.getDecoder().decode(json.getString("data"))
        data[0] = (data[0].toInt() xor 1).toByte()
        chatFile.writeText(json.put("data", java.util.Base64.getEncoder().encodeToString(data)).toString())
        try { AssistantChatStore.unlock(dir); fail("tampered conversation accepted") } catch (_: javax.crypto.AEADBadTagException) { }
    }

    @Test fun deleteRemovesItButKeepsTheChatUsable() {
        AssistantChatStore.unlock(dir)
        AssistantChatStore.save(dir, conversation)
        AssistantChatStore.delete(dir)
        assertFalse(chatFile.exists())
        assertTrue(AssistantChatStore.isUnlocked)
        // A new conversation after deleting is saved and opens again
        AssistantChatStore.save(dir, "{\"messages\":[]}")
        AssistantChatStore.lock()
        assertEquals("{\"messages\":[]}", AssistantChatStore.unlock(dir))
    }

    @Test fun resetAfterInvalidationStartsClean() {
        AssistantChatStore.unlock(dir)
        AssistantChatStore.save(dir, conversation)
        AssistantChatStore.resetInvalidated(dir)
        assertFalse(chatFile.exists())
        assertFalse(AssistantChatStore.isUnlocked)
        assertEquals(1, wrapper.resets)
        assertNull(AssistantChatStore.unlock(dir))
    }

    @Test fun largeConversationWithAttachmentsRoundTrips() {
        val big = "{\"messages\":[{\"base64\":\"" + "A".repeat(8 * 1024 * 1024) + "\"}]}"
        AssistantChatStore.unlock(dir)
        AssistantChatStore.save(dir, big)
        AssistantChatStore.lock()
        assertEquals(big, AssistantChatStore.unlock(dir))
    }
}
