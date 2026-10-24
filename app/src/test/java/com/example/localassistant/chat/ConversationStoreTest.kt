package com.example.localassistant.chat

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationStoreTest {

    private lateinit var dir: File
    private lateinit var store: ConversationStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("conversation").toFile()
        store = ConversationStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `saved conversation survives a round trip`() = runTest {
        val messages = listOf(
            ChatMessage(1, Role.USER, "Привет", MessageStatus.COMPLETE, 100),
            ChatMessage(2, Role.ASSISTANT, "Здравствуйте", MessageStatus.COMPLETE, 200),
        )

        store.save(messages)
        val loaded = store.load()

        assertEquals(2, loaded.size)
        assertEquals("Привет", loaded[0].text)
        assertEquals(Role.ASSISTANT, loaded[1].role)
        assertEquals(MessageStatus.COMPLETE, loaded[1].status)
    }

    @Test
    fun `streaming response becomes interrupted after process death`() = runTest {
        store.save(listOf(ChatMessage(7, Role.ASSISTANT, "незакончено", MessageStatus.STREAMING)))

        val loaded = store.load()

        assertEquals(MessageStatus.INTERRUPTED, loaded.single().status)
        assertEquals("незакончено", loaded.single().text)
    }

    @Test
    fun `corrupted file yields an empty conversation`() = runTest {
        File(dir, "conversation.json").writeText("{not json")

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `clear removes persisted history`() = runTest {
        store.save(listOf(ChatMessage(1, Role.USER, "текст", MessageStatus.COMPLETE)))
        store.clear()

        assertTrue(store.load().isEmpty())
    }
}
