package com.vknxworks.galleryctrl.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class VaultFormatTest {
    /** A software stand-in for the Keystore key: AES-GCM with a random IV, like the phone does. */
    private class SoftWrapper(seed: Byte) : KeyWrapper {
        private val master = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")
        private val random = SecureRandom()
        override fun wrap(key: ByteArray): ByteArray {
            val iv = ByteArray(12).also(random::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, master, GCMParameterSpec(128, iv)) }
            return byteArrayOf(12) + iv + c.doFinal(key)
        }
        override fun unwrap(wrapped: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, wrapped, 1, 12)) }
            return c.doFinal(wrapped, 13, wrapped.size - 13)
        }
    }

    private val wrapper = SoftWrapper(1)
    private val chunk = 1 shl VaultFormat.CHUNK_LOG2

    private fun tempDir(): File = Files.createTempDirectory("vaulttest").toFile().also { it.deleteOnExit() }

    private fun data(n: Int, seed: Int = 7): ByteArray {
        val r = java.util.Random(seed.toLong() + n)
        return ByteArray(n).also(r::nextBytes)
    }

    private fun write(file: File, plain: ByteArray, w: KeyWrapper = wrapper, log2: Int = VaultFormat.CHUNK_LOG2) {
        file.outputStream().use { fos ->
            val out = ChunkedOutputStream(fos, w, SecureRandom(), log2)
            // Odd-sized writes, so chunk boundaries fall in the middle of a write.
            var p = 0
            while (p < plain.size) {
                val n = minOf(1000, plain.size - p)
                out.write(plain, p, n)
                p += n
            }
            out.finish()
        }
    }

    private fun readAll(file: File, w: KeyWrapper = wrapper): ByteArray = ChunkedReader.open(file, w).use { it.inputStream().readBytes() }

    @Test fun roundTripAtChunkBoundaries() {
        val dir = tempDir()
        for (n in listOf(0, 1, 15, 16, 17, chunk - 1, chunk, chunk + 1, 2 * chunk - 1, 2 * chunk, 2 * chunk + 1, 3 * chunk + 12345)) {
            val f = File(dir, "f$n.gcv")
            val plain = data(n)
            write(f, plain)
            ChunkedReader.open(f, wrapper).use { r -> assertEquals("size for $n", n.toLong(), r.size) }
            assertArrayEquals("stream for $n", plain, readAll(f))
            assertArrayEquals("readAll for $n", plain, ChunkedReader.open(f, wrapper).use { it.readAll() })
            // Every chunk carries a 16-byte tag; an empty file still has one empty chunk.
            val chunks = if (n == 0) 1 else (n + chunk - 1) / chunk
            ChunkedReader.open(f, wrapper).use { }
            assertEquals("file length for $n", n.toLong() + chunks * 16L, f.length() - headerSize(f))
        }
    }

    private fun headerSize(f: File): Long = RandomAccessFile(f, "r").use { raf ->
        raf.seek(6)
        val len = (raf.read() shl 8) or raf.read()
        8L + len + VaultFormat.NONCE_PREFIX_BYTES
    }

    @Test fun writeFileHelperAndSingleByteWrites() {
        val dir = tempDir()
        val f = File(dir, "x.gcv")
        val plain = data(chunk + 3)
        VaultFormat.writeFile(f, wrapper) { out -> plain.forEach { out.write(it.toInt()) } }
        assertFalse(File(f.path + ".tmp").exists())
        assertTrue(VaultFormat.looksLikeV2(f))
        assertArrayEquals(plain, readAll(f))
    }

    @Test fun seeking() {
        val dir = tempDir()
        val f = File(dir, "s.gcv")
        val plain = data(5 * chunk + 777)
        write(f, plain)
        ChunkedReader.open(f, wrapper).use { r ->
            val rnd = java.util.Random(42)
            // Random positions and lengths, many across chunk boundaries, in no particular order.
            repeat(300) {
                val pos = rnd.nextInt(plain.size)
                val len = 1 + rnd.nextInt(3 * chunk)
                val dst = ByteArray(len + 10)
                val n = r.read(pos.toLong(), dst, 5, len)
                val expected = minOf(len, plain.size - pos)
                assertEquals(expected, n)
                assertArrayEquals(plain.copyOfRange(pos, pos + n), dst.copyOfRange(5, 5 + n))
            }
            // Exactly on boundaries, backwards.
            for (i in 5 downTo 1) {
                val pos = i * chunk
                val dst = ByteArray(2)
                assertEquals(2, r.read(pos - 1L, dst, 0, 2))
                assertEquals(plain[pos - 1], dst[0])
                assertEquals(plain[pos], dst[1])
            }
            assertEquals(-1, r.read(plain.size.toLong(), ByteArray(4), 0, 4))
            assertEquals(-1, r.read(plain.size + 100L, ByteArray(4), 0, 4))
            assertEquals(1, r.read(plain.size - 1L, ByteArray(4), 0, 4))
        }
    }

    @Test fun wrongKeyFails() {
        val dir = tempDir()
        val f = File(dir, "k.gcv")
        write(f, data(1000))
        expectFormatError { ChunkedReader.open(f, SoftWrapper(2)) }
    }

    @Test fun anyChangedByteFails() {
        val dir = tempDir()
        val f = File(dir, "c.gcv")
        val plain = data(2 * chunk + 100)
        write(f, plain)
        val good = f.readBytes()
        val rnd = java.util.Random(1)
        // Every header byte, and a sample of body bytes in each chunk.
        val positions = (0 until headerSize(f).toInt()) + List(60) { rnd.nextInt(good.size - headerSize(f).toInt()) + headerSize(f).toInt() }
        for (p in positions) {
            val bad = good.copyOf()
            bad[p] = (bad[p].toInt() xor 0x01).toByte()
            f.writeBytes(bad)
            expectFormatError("byte $p") { ChunkedReader.open(f, wrapper).use { it.sha256() } }
        }
    }

    @Test fun cutOffAndExtendedFilesFail() {
        val dir = tempDir()
        val f = File(dir, "t.gcv")
        val plain = data(3 * chunk + 50)
        write(f, plain)
        val good = f.readBytes()
        val header = headerSize(f).toInt()
        val sealedChunk = chunk + 16
        // Cut exactly at a chunk boundary (the remaining last chunk was not sealed as last), inside a chunk, inside a tag.
        for (len in listOf(header + sealedChunk, header + 2 * sealedChunk, header + 3 * sealedChunk, good.size - 1, good.size - 20, header + 5, header, 10, 0)) {
            f.writeBytes(good.copyOf(len))
            expectFormatError("cut to $len") { ChunkedReader.open(f, wrapper).use { it.sha256() } }
        }
        // A chunk appended or repeated.
        f.writeBytes(good + good.copyOfRange(header, header + sealedChunk))
        expectFormatError("chunk repeated") { ChunkedReader.open(f, wrapper).use { it.sha256() } }
        // Two chunks swapped.
        val swapped = good.copyOf()
        System.arraycopy(good, header + sealedChunk, swapped, header, sealedChunk)
        System.arraycopy(good, header, swapped, header + sealedChunk, sealedChunk)
        f.writeBytes(swapped)
        expectFormatError("chunks swapped") { ChunkedReader.open(f, wrapper).use { it.sha256() } }
        // Back to the original: fine again.
        f.writeBytes(good)
        assertArrayEquals(plain, readAll(f))
    }

    @Test fun badDataNeverComesOut() {
        val dir = tempDir()
        val f = File(dir, "b.gcv")
        write(f, data(2 * chunk))
        val bytes = f.readBytes()
        bytes[bytes.size - 30] = (bytes[bytes.size - 30].toInt() xor 0x40).toByte()
        f.writeBytes(bytes)
        ChunkedReader.open(f, wrapper).use { r ->
            // The first chunk is fine; the second fails, and none of its bytes are returned.
            assertEquals(10, r.read(0, ByteArray(10), 0, 10))
            val dst = ByteArray(chunk + 10)
            try {
                r.read(0, dst, 0, dst.size)
                fail("expected a failure")
            } catch (e: VaultFormatException) {
                // expected
            }
        }
    }

    @Test fun notAVaultFile() {
        val dir = tempDir()
        val f = File(dir, "n.gcv")
        f.writeBytes(byteArrayOf(12) + data(200))
        assertFalse(VaultFormat.looksLikeV2(f))
        expectFormatError { ChunkedReader.open(f, wrapper) }
    }

    // ---- conversion from the old format ----

    /** The old format, for the test: anything the legacy opener can read back. Here a plain XOR, kept simple. */
    private fun writeLegacy(file: File, plain: ByteArray) = file.writeBytes(plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray())
    private val legacyOpen: (File) -> InputStream = { f -> ByteArrayInputStream(f.readBytes().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()) }

    @Test fun convertsAndDeletesTheOldFileOnlyAfterwards() {
        val dir = tempDir()
        val plain = data(3 * chunk + 9)
        writeLegacy(VaultConversion.legacyFile(dir, "a"), plain)
        assertEquals(VaultConversion.Result.CONVERTED, VaultConversion.convert(dir, "a", wrapper, legacyOpen, Long.MAX_VALUE))
        assertFalse(VaultConversion.legacyFile(dir, "a").exists())
        assertFalse(VaultConversion.tempFile(dir, "a").exists())
        assertArrayEquals(plain, readAll(VaultConversion.chunkedFile(dir, "a")))
        // Running it again changes nothing.
        assertEquals(VaultConversion.Result.ALREADY, VaultConversion.convert(dir, "a", wrapper, legacyOpen, Long.MAX_VALUE))
        assertArrayEquals(plain, readAll(VaultConversion.chunkedFile(dir, "a")))
    }

    @Test fun emptyFileConverts() {
        val dir = tempDir()
        writeLegacy(VaultConversion.legacyFile(dir, "e"), ByteArray(0))
        assertEquals(VaultConversion.Result.CONVERTED, VaultConversion.convert(dir, "e", wrapper, legacyOpen, Long.MAX_VALUE))
        assertArrayEquals(ByteArray(0), readAll(VaultConversion.chunkedFile(dir, "e")))
    }

    @Test fun cutOffWhileWritingResumes() {
        val dir = tempDir()
        val plain = data(2 * chunk + 5)
        writeLegacy(VaultConversion.legacyFile(dir, "w"), plain)
        // A run cut off half-way through writing: a partial temporary file next to the untouched old one.
        val partial = ByteArrayOutputStream().also { b -> ChunkedOutputStream(b, wrapper).apply { write(plain, 0, chunk + 10) } }.toByteArray()
        VaultConversion.tempFile(dir, "w").writeBytes(partial)
        assertEquals(VaultConversion.Result.CONVERTED, VaultConversion.convert(dir, "w", wrapper, legacyOpen, Long.MAX_VALUE))
        assertArrayEquals(plain, readAll(VaultConversion.chunkedFile(dir, "w")))
        assertFalse(VaultConversion.tempFile(dir, "w").exists())
        assertFalse(VaultConversion.legacyFile(dir, "w").exists())
    }

    @Test fun cutOffAfterRenameResumes() {
        val dir = tempDir()
        val plain = data(chunk + 1)
        writeLegacy(VaultConversion.legacyFile(dir, "r"), plain)
        // Cut off after the new file was put in place, before the old one was deleted: both exist.
        write(VaultConversion.chunkedFile(dir, "r"), plain)
        assertEquals(VaultConversion.Result.RESUMED, VaultConversion.convert(dir, "r", wrapper, legacyOpen, Long.MAX_VALUE))
        assertFalse(VaultConversion.legacyFile(dir, "r").exists())
        assertArrayEquals(plain, readAll(VaultConversion.chunkedFile(dir, "r")))
    }

    @Test fun unreadableNewFileNextToOldIsRedone() {
        val dir = tempDir()
        val plain = data(chunk * 2)
        writeLegacy(VaultConversion.legacyFile(dir, "u"), plain)
        VaultConversion.chunkedFile(dir, "u").writeBytes(data(500))
        assertEquals(VaultConversion.Result.CONVERTED, VaultConversion.convert(dir, "u", wrapper, legacyOpen, Long.MAX_VALUE))
        assertArrayEquals(plain, readAll(VaultConversion.chunkedFile(dir, "u")))
        assertFalse(VaultConversion.legacyFile(dir, "u").exists())
    }

    @Test fun failuresKeepTheOldFile() {
        val dir = tempDir()
        val plain = data(chunk * 3)
        val old = VaultConversion.legacyFile(dir, "f")
        writeLegacy(old, plain)
        val oldBytes = old.readBytes()

        // The old file can't be decrypted (for example out of memory, or a broken tag): it stays.
        val failing: (File) -> InputStream = { f ->
            val src = legacyOpen(f)
            object : InputStream() {
                var n = 0
                override fun read(): Int = if (++n > chunk + 3) throw IOException("bad tag") else src.read()
            }
        }
        assertEquals(VaultConversion.Result.FAILED, VaultConversion.convert(dir, "f", wrapper, failing, Long.MAX_VALUE))
        assertArrayEquals(oldBytes, old.readBytes())
        assertFalse(VaultConversion.chunkedFile(dir, "f").exists())
        assertFalse(VaultConversion.tempFile(dir, "f").exists())

        // Cut off right before the rename (an exception there): old kept, nothing half-done left.
        assertEquals(VaultConversion.Result.FAILED, VaultConversion.convert(dir, "f", wrapper, legacyOpen, Long.MAX_VALUE, beforeRename = { throw OutOfMemoryError("test") }))
        assertArrayEquals(oldBytes, old.readBytes())
        assertFalse(VaultConversion.chunkedFile(dir, "f").exists())

        // The new file doesn't read back the same (a wrapper whose keys can't be unwrapped): old kept.
        val broken = object : KeyWrapper {
            override fun wrap(key: ByteArray) = wrapper.wrap(key)
            override fun unwrap(wrapped: ByteArray): ByteArray = throw IllegalStateException("no key")
        }
        assertEquals(VaultConversion.Result.FAILED, VaultConversion.convert(dir, "f", broken, legacyOpen, Long.MAX_VALUE))
        assertArrayEquals(oldBytes, old.readBytes())
        assertFalse(VaultConversion.chunkedFile(dir, "f").exists())

        // Too large to decrypt in memory: left alone.
        assertEquals(VaultConversion.Result.TOO_LARGE, VaultConversion.convert(dir, "f", wrapper, legacyOpen, old.length() - 1))
        assertArrayEquals(oldBytes, old.readBytes())

        // And after all that, a normal run still converts it.
        assertEquals(VaultConversion.Result.CONVERTED, VaultConversion.convert(dir, "f", wrapper, legacyOpen, Long.MAX_VALUE))
        assertArrayEquals(plain, readAll(VaultConversion.chunkedFile(dir, "f")))
        assertFalse(old.exists())
    }

    @Test fun missingFile() {
        val dir = tempDir()
        assertEquals(VaultConversion.Result.MISSING, VaultConversion.convert(dir, "m", wrapper, legacyOpen, Long.MAX_VALUE))
    }

    private fun expectFormatError(what: String = "", block: () -> Unit) {
        try {
            block()
            fail("expected VaultFormatException $what")
        } catch (e: VaultFormatException) {
            // expected
        }
    }
}
