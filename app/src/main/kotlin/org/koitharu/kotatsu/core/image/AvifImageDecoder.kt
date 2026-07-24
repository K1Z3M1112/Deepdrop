package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.util.component1
import coil3.util.component2
import com.davemorrissey.labs.subscaleview.decoder.ImageDecodeException
import kotlinx.coroutines.runInterruptible
import org.aomedia.avif.android.AvifDecoder
import org.koitharu.kotatsu.core.util.ext.readByteBuffer

class AvifImageDecoder(
	private val source: ImageSource,
	private val options: Options,
) : Decoder {

	override suspend fun decode(): DecodeResult = runInterruptible {
		val bytes = source.source().readByteBuffer()
		val decoder = AvifDecoder.create(bytes) ?: throw ImageDecodeException(
			uri = source.fileOrNull()?.toString(),
			format = "avif",
			message = "Requested to decode byte buffer which cannot be handled by AvifDecoder",
		)
		try {
			val config = if (decoder.depth == 8 || decoder.alphaPresent) {
				Bitmap.Config.ARGB_8888
			} else {
				Bitmap.Config.RGB_565
			}
			val frameCount = decoder.frameCount
			if (frameCount > 1 && frameCount <= MAX_ANIMATED_FRAMES) {
				decodeAnimated(decoder, config, frameCount)
			} else {
				decodeStatic(decoder, config)
			}
		} finally {
			decoder.release()
		}
	}

	/** Previous, single-frame behaviour: only ever look at the first frame of the AVIF. */
	private fun decodeStatic(decoder: AvifDecoder, config: Bitmap.Config): DecodeResult {
		val bitmap = createBitmap(decoder.width, decoder.height, config)
		val result = decoder.nextFrame(bitmap)
		if (result != 0) {
			bitmap.recycle()
			throw ImageDecodeException(
				uri = source.fileOrNull()?.toString(),
				format = "avif",
				message = AvifDecoder.resultToString(result),
			)
		}
		val (dstWidth, dstHeight) = dstSize(bitmap.width, bitmap.height)
		return if (dstWidth < bitmap.width || dstHeight < bitmap.height) {
			val scaled = bitmap.scale(dstWidth, dstHeight)
			bitmap.recycle()
			DecodeResult(image = scaled.asImage(), isSampled = true)
		} else {
			DecodeResult(image = bitmap.asImage(), isSampled = false)
		}
	}

	/** Decodes every frame of an animated AVIF up front and plays them back via [AvifAnimatedDrawable]. */
	private fun decodeAnimated(decoder: AvifDecoder, config: Bitmap.Config, frameCount: Int): DecodeResult {
		val (dstWidth, dstHeight) = dstSize(decoder.width, decoder.height)
		val isSampled = dstWidth < decoder.width || dstHeight < decoder.height
		val frames = ArrayList<Bitmap>(frameCount)
		for (i in 0 until frameCount) {
			val bitmap = createBitmap(decoder.width, decoder.height, config)
			val result = decoder.nextFrame(bitmap)
			if (result != 0) {
				bitmap.recycle()
				frames.forEach { it.recycle() }
				throw ImageDecodeException(
					uri = source.fileOrNull()?.toString(),
					format = "avif",
					message = AvifDecoder.resultToString(result),
				)
			}
			if (isSampled) {
				val scaled = bitmap.scale(dstWidth, dstHeight)
				bitmap.recycle()
				frames += scaled
			} else {
				frames += bitmap
			}
		}
		val durationsMs = IntArray(frameCount) { i ->
			val seconds = decoder.frameDurations.getOrElse(i) { 0.1 }
			(seconds * 1000).toInt()
		}
		val drawable = AvifAnimatedDrawable(frames, durationsMs, decoder.repetitionCount)
		return DecodeResult(image = drawable.asImage(), isSampled = isSampled)
	}

	private fun dstSize(srcWidth: Int, srcHeight: Int) = DecodeUtils.computeDstSize(
		srcWidth = srcWidth,
		srcHeight = srcHeight,
		targetSize = options.size,
		scale = options.scale,
		maxSize = options.maxBitmapSize,
	)

	class Factory : Decoder.Factory {

		override fun create(
			result: SourceFetchResult,
			options: Options,
			imageLoader: ImageLoader
		): Decoder? = if (isApplicable(result)) {
			AvifImageDecoder(result.source, options)
		} else {
			null
		}

		override fun equals(other: Any?) = other is Factory

		override fun hashCode() = javaClass.hashCode()

		/** Fetchers that read already-downloaded pages back off local storage (e.g. the reader's
		 * page cache, [MihonImageFetcher]'s disk-cache-hit branch) commonly report no mime type at
		 * all, so relying on [SourceFetchResult.mimeType] alone silently drops this decoder for
		 * exactly the local-file case the reader depends on. Fall back to sniffing the leading
		 * `ftyp` box for an `avif`/`avis` brand -- mirrors what [AnimatedImageDetector] already does
		 * for the same files -- whenever the mime type is missing or unhelpful. */
		private fun isApplicable(result: SourceFetchResult): Boolean {
			if (result.mimeType == "image/avif") {
				return true
			}
			if (result.mimeType != null && result.mimeType != "application/octet-stream") {
				return false
			}
			return runCatching { isAvifByHeader(result) }.getOrDefault(false)
		}

		private fun isAvifByHeader(result: SourceFetchResult): Boolean {
			val peeked = result.source.source().peek()
			val header = ByteArray(HEADER_PEEK_SIZE)
			var total = 0
			while (total < header.size) {
				val read = peeked.read(header, total, header.size - total)
				if (read < 0) break
				total += read
			}
			if (total < 12 || !header.regionEquals(total, 4, FTYP_MAGIC)) {
				return false
			}
			var offset = 8
			while (offset + 4 <= total) {
				if (header.regionEquals(total, offset, AVIF_MAGIC) || header.regionEquals(total, offset, AVIS_MAGIC)) {
					return true
				}
				offset += 4
			}
			return false
		}

		private fun ByteArray.regionEquals(len: Int, offset: Int, other: ByteArray): Boolean {
			if (offset < 0 || offset + other.size > len) return false
			for (i in other.indices) {
				if (this[offset + i] != other[i]) return false
			}
			return true
		}

		private const val HEADER_PEEK_SIZE = 32
		private val FTYP_MAGIC = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
		private val AVIF_MAGIC = byteArrayOf('a'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte())
		private val AVIS_MAGIC = byteArrayOf('a'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
	}

	private companion object {

		// All frames of an animated AVIF are decoded up front to keep playback smooth; cap the
		// frame count so a pathological file can't be used to exhaust memory.
		const val MAX_ANIMATED_FRAMES = 512
	}
}
