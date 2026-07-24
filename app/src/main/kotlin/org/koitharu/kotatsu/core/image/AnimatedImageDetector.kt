package org.koitharu.kotatsu.core.image

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toFile
import org.aomedia.avif.android.AvifDecoder
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isZipUri
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * Answers "does this locally-cached page have more than one frame" without fully decoding it,
 * so the reader can decide whether a page needs to go through an animation-capable target
 * (a plain `ImageView` driven by Coil) instead of `SubsamplingScaleImageView`, which only ever
 * renders a single static frame regardless of the source format.
 *
 * Deliberately conservative: on any doubt (unknown format, read error, truncated file) it
 * reports `false`, in which case the page just falls back to the previous, static behaviour.
 */
object AnimatedImageDetector {

	fun isAnimated(uri: Uri): Boolean = try {
		openStream(uri)?.use(::isAnimated) ?: false
	} catch (e: Exception) {
		false
	}

	/** Reads just enough of [uri] to learn its pixel dimensions, without decoding any frame --
	 * cheap enough to call right before starting the real (async) image load. Used so the webtoon
	 * container can be sized to the source's true aspect ratio from the very first layout pass,
	 * instead of only finding out once the full decode finishes (by which point the container may
	 * already have been measured against a fallback height and briefly shown stretched). Returns
	 * null on any read/decode failure -- callers just fall back to their previous behaviour. */
	fun peekSize(uri: Uri): Pair<Int, Int>? = try {
		openStream(uri)?.use(::peekSize)
	} catch (e: Exception) {
		null
	}

	private fun peekSize(stream: InputStream): Pair<Int, Int>? {
		val header = ByteArray(HEADER_SIZE)
		val headerLen = readFully(stream, header)
		if (headerLen < 12) {
			return null
		}
		// AVIF has no fixed-offset width/height field in its header box structure, so the only
		// reliable way to learn its size is via the same native parser used for the real decode --
		// still far cheaper than decoding a frame, since it stops once it has read the metadata.
		if (header.regionEquals(headerLen, 4, FTYP_MAGIC)) {
			val rest = ByteArray(AVIF_INFO_PEEK_SIZE - headerLen).let { buf ->
				val read = readFully(stream, buf)
				if (read < buf.size) buf.copyOf(read) else buf
			}
			val combined = header.copyOf(headerLen) + rest
			val info = AvifDecoder.Info()
			return if (AvifDecoder.getInfo(ByteBuffer.wrap(combined), combined.size, info) && info.width > 0 && info.height > 0) {
				info.width to info.height
			} else {
				null
			}
		}
		// GIF/WebP/PNG (and anything else BitmapFactory understands) all expose bounds through the
		// standard "decode bounds only" path, which reads headers only and allocates no pixels.
		val fullBytes = header.copyOf(headerLen) + stream.readBytes()
		val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeByteArray(fullBytes, 0, fullBytes.size, opts)
		return if (opts.outWidth > 0 && opts.outHeight > 0) {
			opts.outWidth to opts.outHeight
		} else {
			null
		}
	}

	private fun isAnimated(stream: InputStream): Boolean {
		val header = ByteArray(HEADER_SIZE)
		val headerLen = readFully(stream, header)
		if (headerLen < 12) {
			return false
		}
		return when {
			header.startsWith(headerLen, GIF_MAGIC) -> isAnimatedGif(header, headerLen, stream)
			header.startsWith(headerLen, RIFF_MAGIC) && header.regionEquals(headerLen, 8, WEBP_MAGIC) ->
				isAnimatedWebp(header, headerLen)

			header.regionEquals(headerLen, 4, FTYP_MAGIC) -> isAnimatedAvif(header, headerLen)
			header.startsWith(headerLen, PNG_MAGIC) -> isAnimatedPng(header, headerLen, stream)
			else -> false
		}
	}

	/** An animated PNG (APNG) is a plain PNG with an extra `acTL` (Animation Control) chunk that,
	 * per the APNG spec, must appear before the first `IDAT` chunk; a static PNG goes straight
	 * from `IHDR` to `IDAT` with no `acTL` in between. Walks the chunk chain from just after the
	 * 8-byte signature, growing the buffer from [rest] only as far as needed to reach whichever
	 * of the two chunks comes first. */
	private fun isAnimatedPng(header: ByteArray, headerLen: Int, rest: InputStream): Boolean {
		var buffer = header
		var bufferLen = headerLen
		var pos = PNG_SIGNATURE_SIZE
		val readBuf = ByteArray(PNG_SCAN_BUFFER)
		while (true) {
			while (bufferLen - pos < PNG_CHUNK_HEADER_SIZE) {
				val read = rest.read(readBuf)
				if (read <= 0) return false
				val tail = bufferLen - pos
				val newBuffer = ByteArray(tail + read)
				System.arraycopy(buffer, pos, newBuffer, 0, tail)
				System.arraycopy(readBuf, 0, newBuffer, tail, read)
				buffer = newBuffer
				bufferLen = newBuffer.size
				pos = 0
			}
			val length = ((buffer[pos].toInt() and 0xFF) shl 24) or
				((buffer[pos + 1].toInt() and 0xFF) shl 16) or
				((buffer[pos + 2].toInt() and 0xFF) shl 8) or
				(buffer[pos + 3].toInt() and 0xFF)
			if (length < 0) return false
			val typeOffset = pos + 4
			if (buffer.regionEquals(bufferLen, typeOffset, ACTL_MAGIC)) return true
			if (buffer.regionEquals(bufferLen, typeOffset, IDAT_MAGIC)) return false
			pos += PNG_CHUNK_HEADER_SIZE + length + PNG_CHUNK_CRC_SIZE
		}
	}

	/** GIF has no frame-count field; an animated GIF has more than one Graphic Control Extension
	 * block (`0x21 0xF9`), one of which normally precedes every frame's image data. Scans the
	 * already-buffered header first, then keeps reading the rest of the stream. */
	private fun isAnimatedGif(header: ByteArray, headerLen: Int, rest: InputStream): Boolean {
		var prev = -1
		var gceCount = 0
		for (i in 0 until headerLen) {
			val b = header[i].toInt() and 0xFF
			if (prev == 0x21 && b == 0xF9) {
				gceCount++
				if (gceCount >= 2) return true
			}
			prev = b
		}
		val buffer = ByteArray(GIF_SCAN_BUFFER)
		while (gceCount < 2) {
			val read = rest.read(buffer)
			if (read <= 0) break
			for (i in 0 until read) {
				val b = buffer[i].toInt() and 0xFF
				if (prev == 0x21 && b == 0xF9) {
					gceCount++
					if (gceCount >= 2) break
				}
				prev = b
			}
		}
		return gceCount >= 2
	}

	/** Extended WebP files carry a `VP8X` chunk right after the `WEBP` fourCC; bit 1 of its
	 * flags byte (offset 20) is the "has ANIM chunk" flag. */
	private fun isAnimatedWebp(header: ByteArray, headerLen: Int): Boolean {
		if (headerLen < 21 || !header.regionEquals(headerLen, 12, VP8X_MAGIC)) {
			return false
		}
		val flags = header[20].toInt() and 0xFF
		return flags and 0x02 != 0
	}

	/** An AVIF image sequence declares the `avis` brand as its major brand or one of its
	 * compatible brands inside the leading `ftyp` box; a plain still image only ever has `avif`. */
	private fun isAnimatedAvif(header: ByteArray, headerLen: Int): Boolean {
		var offset = 8
		while (offset + 4 <= headerLen) {
			if (header.regionEquals(headerLen, offset, AVIS_MAGIC)) {
				return true
			}
			offset += 4
		}
		return false
	}

	private fun readFully(stream: InputStream, buffer: ByteArray): Int {
		var total = 0
		while (total < buffer.size) {
			val read = stream.read(buffer, total, buffer.size - total)
			if (read < 0) break
			total += read
		}
		return total
	}

	private fun ByteArray.startsWith(len: Int, prefix: ByteArray): Boolean = regionEquals(len, 0, prefix)

	private fun ByteArray.regionEquals(len: Int, offset: Int, other: ByteArray): Boolean {
		if (offset < 0 || offset + other.size > len) return false
		for (i in other.indices) {
			if (this[offset + i] != other[i]) return false
		}
		return true
	}

	private fun openStream(uri: Uri): InputStream? = when {
		uri.isFileUri() -> uri.toFile().inputStream()
		uri.isZipUri() -> openZipEntryStream(uri)
		else -> null
	}

	private fun openZipEntryStream(uri: Uri): InputStream? {
		val file = File(requireNotNull(uri.schemeSpecificPart))
		val zip = ZipFile(file)
		val entry = uri.fragment?.let(zip::getEntry)
		if (entry == null) {
			zip.close()
			return null
		}
		return ZipEntryClosingStream(zip, zip.getInputStream(entry))
	}

	/** Closes the owning [ZipFile] together with the entry stream so callers can `use { }` it
	 * as a plain [InputStream] without leaking the zip handle. */
	private class ZipEntryClosingStream(
		private val zip: ZipFile,
		private val delegate: InputStream,
	) : InputStream() {

		override fun read(): Int = delegate.read()
		override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
		override fun available(): Int = delegate.available()
		override fun close() {
			delegate.close()
			zip.close()
		}
	}

	private const val HEADER_SIZE = 64
	private const val AVIF_INFO_PEEK_SIZE = 4096
	private const val GIF_SCAN_BUFFER = 8192
	private const val PNG_SCAN_BUFFER = 8192
	private const val PNG_SIGNATURE_SIZE = 8
	private const val PNG_CHUNK_HEADER_SIZE = 8 // 4-byte length + 4-byte type
	private const val PNG_CHUNK_CRC_SIZE = 4
	private val GIF_MAGIC = byteArrayOf('G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), '8'.code.toByte())
	private val RIFF_MAGIC = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())
	private val WEBP_MAGIC = byteArrayOf('W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())
	private val VP8X_MAGIC = byteArrayOf('V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), 'X'.code.toByte())
	private val FTYP_MAGIC = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
	private val AVIS_MAGIC = byteArrayOf('a'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
	private val PNG_MAGIC = byteArrayOf(
		0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
		0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
	)
	private val ACTL_MAGIC = byteArrayOf('a'.code.toByte(), 'c'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte())
	private val IDAT_MAGIC = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte())
}
