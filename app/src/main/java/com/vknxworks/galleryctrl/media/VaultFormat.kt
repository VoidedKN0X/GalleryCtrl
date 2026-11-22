package com.vknxworks.galleryctrl.media

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps and unwraps a file's own data key with the vault's master key. On the phone the master key is the
 * Keystore key (it never leaves the secure hardware); tests use a software key.
 */
interface KeyWrapper {
    fun wrap(key: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/** A vault file that can't be read: wrong key, changed, cut off or not a vault file at all. */
class VaultFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The vault's file format (version 2): every file has its own random AES-256 key, wrapped by the master key, and
 * the content is encrypted in software in 64 KB pieces, each with its own AES-GCM tag. So a file can be read from
 * any position (videos play and seek) and copied out piece by piece, without ever holding the whole file in memory.
 *
 * ```
 * header: "GCV2" | version (1 byte) | log2(chunk size) (1) | wrapped key length (2, big endian) | wrapped key | nonce prefix (7)
 * body:   chunk 0 | chunk 1 | ... | last chunk      each chunk = AES-GCM(ciphertext of up to chunk size bytes + 16-byte tag)
 * ```
 *
 * The nonce of chunk `i` is the file's random 7-byte prefix, `i` (4 bytes, big endian) and one byte that is 1 only
 * for the last chunk (the same construction as Tink's streaming AEAD). The whole header is the associated data of
 * every chunk. Because of that:
 * - chunks can't be swapped, repeated or moved to another file (the counter and header are checked),
 * - a file cut off at a chunk boundary fails (its new last chunk wasn't sealed as the last one),
 * - and anything changed anywhere fails its chunk's tag.
 * Even an empty file has one (empty) last chunk.
 */
object VaultFormat {
    val MAGIC = byteArrayOf('G'.code.toByte(), 'C'.code.toByte(), 'V'.code.toByte(), '2'.code.toByte())
    const val VERSION = 2
    const val CHUNK_LOG2 = 16
    const val TAG_BYTES = 16
    const val KEY_BYTES = 32
    const val NONCE_PREFIX_BYTES = 7
    const val NONCE_BYTES = 12
    private const val MAX_WRAPPED = 1024

    internal fun nonce(prefix: ByteArray, index: Long, last: Boolean): ByteArray {
        require(index in 0..0xFFFF_FFFFL) { "file too large" }
        val n = ByteArray(NONCE_BYTES)
        System.arraycopy(prefix, 0, n, 0, NONCE_PREFIX_BYTES)
        n[7] = (index ushr 24).toByte()
        n[8] = (index ushr 16).toByte()
        n[9] = (index ushr 8).toByte()
        n[10] = index.toByte()
        n[11] = if (last) 1 else 0
        return n
    }

    internal fun header(wrapped: ByteArray, prefix: ByteArray, chunkLog2: Int): ByteArray {
        require(wrapped.size in 1..MAX_WRAPPED)
        val out = ByteArray(4 + 1 + 1 + 2 + wrapped.size + NONCE_PREFIX_BYTES)
        System.arraycopy(MAGIC, 0, out, 0, 4)
        out[4] = VERSION.toByte()
        out[5] = chunkLog2.toByte()
        out[6] = (wrapped.size ushr 8).toByte()
        out[7] = wrapped.size.toByte()
        System.arraycopy(wrapped, 0, out, 8, wrapped.size)
        System.arraycopy(prefix, 0, out, 8 + wrapped.size, NONCE_PREFIX_BYTES)
        return out
    }

    /** Whether [file] starts like a version-2 vault file (only the first bytes are looked at). */
    fun looksLikeV2(file: File): Boolean = runCatching {
        file.inputStream().use { s ->
            val head = ByteArray(5)
            s.readNBytes(head, 0, 5) == 5 && head.copyOf(4).contentEquals(MAGIC) && head[4].toInt() == VERSION
        }
    }.getOrDefault(false)

    /** Encrypts everything [write] produces into [file] (written to a temporary file first, then renamed into place). */
    fun writeFile(file: File, wrapper: KeyWrapper, write: (OutputStream) -> Unit) {
        val tmp = File(file.path + ".tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                val out = ChunkedOutputStream(fos, wrapper)
                write(out)
                out.finish()
                fos.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("rename failed")
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}

/**
 * Writes the version-2 format to [out]: the header straight away, then one sealed chunk for every [chunkSize] bytes.
 * The last chunk is sealed by [finish] (or [close]), which is why a full chunk is only written once more data
 * arrives. [finish] leaves [out] open (so the caller can sync it to disk); [close] closes it too.
 */
class ChunkedOutputStream(
    private val out: OutputStream,
    wrapper: KeyWrapper,
    random: SecureRandom = SecureRandom(),
    private val chunkLog2: Int = VaultFormat.CHUNK_LOG2,
) : OutputStream() {
    private val chunkSize = 1 shl chunkLog2
    private val key = ByteArray(VaultFormat.KEY_BYTES).also(random::nextBytes)
    private val prefix = ByteArray(VaultFormat.NONCE_PREFIX_BYTES).also(random::nextBytes)
    private val header: ByteArray
    private val spec = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val buf = ByteArray(chunkSize)
    private val sealed = ByteArray(chunkSize + VaultFormat.TAG_BYTES)
    private var pos = 0
    private var index = 0L
    private var finished = false

    init {
        require(chunkLog2 in 8..24)
        header = VaultFormat.header(wrapper.wrap(key), prefix, chunkLog2)
        out.write(header)
    }

    private fun seal(last: Boolean) {
        cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(VaultFormat.TAG_BYTES * 8, VaultFormat.nonce(prefix, index, last)))
        cipher.updateAAD(header)
        val n = cipher.doFinal(buf, 0, pos, sealed, 0)
        out.write(sealed, 0, n)
        index++
        pos = 0
    }

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!finished) { "already finished" }
        var o = off
        var left = len
        while (left > 0) {
            // A full chunk goes out only now that more data follows it: the last one is sealed differently.
            if (pos == chunkSize) seal(last = false)
            val n = minOf(left, chunkSize - pos)
            System.arraycopy(b, o, buf, pos, n)
            pos += n
            o += n
            left -= n
        }
    }

    /** Seals the last chunk and forgets the key. Doesn't close [out]. */
    fun finish() {
        if (finished) return
        seal(last = true)
        finished = true
        out.flush()
        key.fill(0)
        buf.fill(0)
    }

    override fun close() {
        try {
            finish()
        } finally {
            out.close()
        }
    }
}

/**
 * Reads a version-2 vault file: its plaintext [size], any range of it ([read]), or all of it in order ([inputStream]).
 * Only one 64 KB chunk is decrypted (and kept) at a time. Every chunk's tag is checked before any of its bytes are
 * handed out, so changed or cut-off data is never returned: it fails with [VaultFormatException].
 */
class ChunkedReader private constructor(private val raf: RandomAccessFile, wrapper: KeyWrapper) : Closeable {
    private val header: ByteArray
    private val prefix: ByteArray
    private val spec: SecretKeySpec
    private val chunkSize: Int
    private val chunks: Long
    private val lastPlain: Int
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    /** The plaintext size in bytes. */
    val size: Long

    private var cachedIndex = -1L
    private var cached = ByteArray(0)
    private var cachedLen = 0

    init {
        try {
            val fixed = ByteArray(8)
            raf.seek(0)
            raf.readFully(fixed)
            if (!fixed.copyOf(4).contentEquals(VaultFormat.MAGIC) || fixed[4].toInt() != VaultFormat.VERSION) {
                throw VaultFormatException("not a vault file")
            }
            val log2 = fixed[5].toInt()
            if (log2 !in 8..24) throw VaultFormatException("bad chunk size")
            val wrappedLen = ((fixed[6].toInt() and 0xFF) shl 8) or (fixed[7].toInt() and 0xFF)
            if (wrappedLen !in 1..1024) throw VaultFormatException("bad key length")
            val wrapped = ByteArray(wrappedLen)
            raf.readFully(wrapped)
            prefix = ByteArray(VaultFormat.NONCE_PREFIX_BYTES)
            raf.readFully(prefix)
            header = VaultFormat.header(wrapped, prefix, log2)
            chunkSize = 1 shl log2

            val body = raf.length() - header.size
            val full = (chunkSize + VaultFormat.TAG_BYTES).toLong()
            val whole = body / full
            val rest = body % full
            when {
                body < VaultFormat.TAG_BYTES -> throw VaultFormatException("cut off")
                rest == 0L -> { chunks = whole; lastPlain = chunkSize }
                rest < VaultFormat.TAG_BYTES -> throw VaultFormatException("cut off")
                else -> { chunks = whole + 1; lastPlain = (rest - VaultFormat.TAG_BYTES).toInt() }
            }
            size = (chunks - 1) * chunkSize + lastPlain

            val key = try {
                wrapper.unwrap(wrapped)
            } catch (e: Exception) {
                throw VaultFormatException("the key can't be unwrapped", e)
            }
            if (key.size != VaultFormat.KEY_BYTES) throw VaultFormatException("bad key")
            spec = SecretKeySpec(key, "AES")
            key.fill(0)
        } catch (e: EOFException) {
            throw VaultFormatException("cut off", e)
        }
    }

    private fun load(index: Long) {
        if (index == cachedIndex) return
        val last = index == chunks - 1
        val plain = if (last) lastPlain else chunkSize
        val sealed = ByteArray(plain + VaultFormat.TAG_BYTES)
        raf.seek(header.size + index * (chunkSize + VaultFormat.TAG_BYTES))
        try {
            raf.readFully(sealed)
        } catch (e: EOFException) {
            throw VaultFormatException("cut off", e)
        }
        if (cached.size < plain) cached = ByteArray(chunkSize)
        try {
            cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(VaultFormat.TAG_BYTES * 8, VaultFormat.nonce(prefix, index, last)))
            cipher.updateAAD(header)
            cachedLen = cipher.doFinal(sealed, 0, sealed.size, cached, 0)
        } catch (e: GeneralSecurityException) {
            cachedIndex = -1
            throw VaultFormatException("chunk $index doesn't check out", e)
        }
        cachedIndex = index
    }

    /**
     * Reads up to [len] plaintext bytes from [position] into [dst]. Returns how many were read (fewer only at the
     * end of the file), or -1 when [position] is at or past the end.
     */
    @Synchronized
    fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
        require(position >= 0 && off >= 0 && len >= 0 && off + len <= dst.size)
        if (position >= size) return -1
        if (len == 0) return 0
        var p = position
        var o = off
        var left = minOf(len.toLong(), size - position).toInt()
        val total = left
        while (left > 0) {
            val index = p / chunkSize
            load(index)
            val within = (p - index * chunkSize).toInt()
            val n = minOf(left, cachedLen - within)
            System.arraycopy(cached, within, dst, o, n)
            p += n
            o += n
            left -= n
        }
        return total
    }

    /** Checks every chunk; throws [VaultFormatException] if any fails. Returns the plaintext's SHA-256. */
    fun sha256(): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(chunkSize)
        var p = 0L
        while (true) {
            val n = read(p, buf, 0, buf.size)
            if (n <= 0) break
            md.update(buf, 0, n)
            p += n
        }
        // An empty file still has its one (empty) last chunk to check.
        if (size == 0L) load(0)
        return md.digest()
    }

    /** All of the plaintext. Only for small files (thumbnails, photos that fit in memory). */
    fun readAll(): ByteArray {
        require(size <= Int.MAX_VALUE - 16) { "too large" }
        val out = ByteArray(size.toInt())
        var p = 0
        while (p < out.size) {
            val n = read(p.toLong(), out, p, out.size - p)
            if (n <= 0) throw VaultFormatException("cut off")
            p += n
        }
        if (size == 0L) load(0)
        return out
    }

    /** The plaintext from the start, in order. Closing the stream closes this reader. */
    fun inputStream(): InputStream = object : InputStream() {
        private var p = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (p == 0L && size == 0L) load(0)
            val n = this@ChunkedReader.read(p, b, off, len)
            if (n > 0) p += n
            return n
        }

        override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), size - p).toInt()

        override fun close() = this@ChunkedReader.close()
    }

    @Synchronized
    override fun close() {
        cached.fill(0)
        cachedIndex = -1
        raf.close()
    }

    companion object {
        /** Opens [file]; throws [VaultFormatException] if it isn't a readable version-2 file for this key. */
        fun open(file: File, wrapper: KeyWrapper): ChunkedReader {
            val raf = RandomAccessFile(file, "r")
            try {
                return ChunkedReader(raf, wrapper)
            } catch (t: Throwable) {
                raf.close()
                throw t
            }
        }
    }
}

/**
 * The one-time conversion of a vault file from the old format (`<id>.bin`: one AES-GCM stream straight through the
 * Keystore, which only gives back the plaintext once the whole file is in memory) to version 2 (`<id>.gcv`).
 *
 * It never loses a file: the new file is written under a temporary name, read back completely and compared
 * (SHA-256) with what the old one held, only then renamed into place, and only after that is the old file deleted.
 * Cut off at any point (app closed, phone off, out of memory), the next run finds one of these states and
 * carries on: a leftover temporary file is thrown away and redone; a new file next to an old one (cut off between
 * the rename and the delete) is checked again before the old one goes.
 */
object VaultConversion {
    enum class Result {
        /** Converted now: the new file checked out and the old one is gone. */
        CONVERTED,
        /** Already in the new format. */
        ALREADY,
        /** A conversion cut off before the old file was deleted: the new file checked out, the old one is now gone. */
        RESUMED,
        /** Neither file exists. */
        MISSING,
        /** The old file is too large to decrypt in memory right now; it stays as it is. */
        TOO_LARGE,
        /** Something failed; the old file stays as it is (and is tried again next time). */
        FAILED,
    }

    fun legacyFile(dir: File, id: String) = File(dir, "$id.bin")
    fun chunkedFile(dir: File, id: String) = File(dir, "$id.gcv")
    fun tempFile(dir: File, id: String) = File(dir, "$id.gcv.tmp")

    /**
     * Converts the vault file [id] in [dir]. [legacyOpen] gives the old file's plaintext; [maxLegacyBytes] skips old
     * files too large to decrypt in memory. [beforeRename] is only for tests (to cut a conversion off there).
     */
    fun convert(
        dir: File,
        id: String,
        wrapper: KeyWrapper,
        legacyOpen: (File) -> InputStream,
        maxLegacyBytes: Long,
        random: SecureRandom = SecureRandom(),
        beforeRename: () -> Unit = {},
    ): Result {
        val old = legacyFile(dir, id)
        val new = chunkedFile(dir, id)
        val tmp = tempFile(dir, id)
        // Left by a run that was cut off before it was checked: never trusted, made again.
        if (tmp.exists()) tmp.delete()
        if (new.exists()) {
            if (!old.exists()) return Result.ALREADY
            // Cut off between putting the new file in place and deleting the old one. The new file was checked
            // before the rename; check it again (it must read completely) before the old copy goes.
            val readable = runCatching { ChunkedReader.open(new, wrapper).use { it.sha256() } }.isSuccess
            if (readable) {
                old.delete()
                return Result.RESUMED
            }
            // Not readable: it doesn't count. The old file is still there, so convert again from it.
            new.delete()
        }
        if (!old.exists()) return Result.MISSING
        if (old.length() > maxLegacyBytes) return Result.TOO_LARGE
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            FileOutputStream(tmp).use { fos ->
                val out = ChunkedOutputStream(fos, wrapper, random)
                legacyOpen(old).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
                out.finish()
                fos.fd.sync()
            }
            val expected = md.digest()
            val actual = ChunkedReader.open(tmp, wrapper).use { it.sha256() }
            if (!actual.contentEquals(expected)) {
                tmp.delete()
                return Result.FAILED
            }
            beforeRename()
            if (!tmp.renameTo(new)) {
                tmp.delete()
                return Result.FAILED
            }
            old.delete()
            Result.CONVERTED
        } catch (t: Throwable) {
            // Out of memory included: the old file is untouched, and the half-written new one goes.
            tmp.delete()
            Result.FAILED
        }
    }
}
