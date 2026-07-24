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
import java.nio.ByteBuffer
import kotlin.math.sqrt

class AvifImageDecoder(
	private val source: ImageSource,
	private val options: Options,
) : Decoder {

	override suspend fun decode(): DecodeResult = runInterruptible {
		val bytes = source.source().readByteBuffer()
		// Always hand out a fresh duplicate of the buffer to AvifDecoder.create, never the
		// pristine `bytes` itself: the same `bytes` gets reused later (see decoderFactory in
		// decodeAnimated) every time an animated AVIF loops or resumes after being stopped, since
		// libavif can only ever decode a sequence forwards from its start.
		val decoder = AvifDecoder.create(bytes.duplicate()) ?: throw ImageDecodeException(
			uri = source.fileOrNull()?.toString(),
			format = "avif",
			message = "Requested to decode byte buffer which cannot be handled by AvifDecoder",
		)
		val config = if (decoder.depth == 8 || decoder.alphaPresent) {
			Bitmap.Config.ARGB_8888
		} else {
			Bitmap.Config.RGB_565
		}
		val frameCount = decoder.frameCount
		if (frameCount > 1 && frameCount <= MAX_ANIMATED_FRAMES) {
			// Ownership of `decoder` transfers to the returned AvifAnimatedDrawable from here:
			// it keeps decoding subsequent frames in the background and releases it itself when
			// appropriate (see AvifAnimatedDrawable). Only release it here if handing off fails.
			decodeAnimated(decoder, config, frameCount, bytes)
		} else {
			try {
				decodeStatic(decoder, config)
			} finally {
				decoder.release()
			}
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

	/**
	 * Decodes only the *first* frame of an animated AVIF synchronously -- enough to paint
	 * something on screen right away -- then wraps it in an [AvifAnimatedDrawable] that decodes
	 * every frame after that progressively, in the background, one frame ahead of playback. See
	 * [AvifAnimatedDrawable] for how the rest of the sequence is produced, buffered and looped.
	 *
	 * Resolution is still shrunk (aspect ratio preserved) beyond whatever Coil originally
	 * requested if a single frame alone would be unreasonably large; unlike before, this no longer
	 * needs to account for the *whole* sequence's memory footprint, since at most ~2 decoded
	 * frames are ever held in memory at once regardless of how many frames the animation has.
	 */
	private fun decodeAnimated(
		decoder: AvifDecoder,
		config: Bitmap.Config,
		frameCount: Int,
		rawBytes: ByteBuffer,
	): DecodeResult {
		val (reqWidth, reqHeight) = dstSize(decoder.width, decoder.height)
		val bytesPerPixel = if (config == Bitmap.Config.ARGB_8888) 4 else 2
		val scale = perFrameScale(reqWidth, reqHeight, bytesPerPixel)
		val dstWidth = (reqWidth * scale).toInt().coerceAtLeast(1)
		val dstHeight = (reqHeight * scale).toInt().coerceAtLeast(1)
		val needsScaling = dstWidth < decoder.width || dstHeight < decoder.height

		val firstBitmap = createBitmap(decoder.width, decoder.height, config)
		val result = decoder.nextFrame(firstBitmap)
		if (result != 0) {
			firstBitmap.recycle()
			decoder.release()
			throw ImageDecodeException(
				uri = source.fileOrNull()?.toString(),
				format = "avif",
				message = AvifDecoder.resultToString(result),
			)
		}
		val firstFrame = if (needsScaling) {
			firstBitmap.scale(dstWidth, dstHeight).also { firstBitmap.recycle() }
		} else {
			firstBitmap
		}

		val durationsMs = IntArray(frameCount) { i ->
			val seconds = decoder.frameDurations.getOrElse(i) { 0.1 }
			(seconds * 1000).toInt()
		}

		val drawable = AvifAnimatedDrawable(
			decoderFactory = {
				AvifDecoder.create(rawBytes.duplicate()) ?: throw ImageDecodeException(
					uri = source.fileOrNull()?.toString(),
					format = "avif",
					message = "Requested to decode byte buffer which cannot be handled by AvifDecoder",
				)
			},
			initialDecoder = decoder,
			config = config,
			dstWidth = dstWidth,
			dstHeight = dstHeight,
			needsScaling = needsScaling,
			firstFrameBitmap = firstFrame,
			frameDurationsMs = durationsMs,
			repetitionCount = decoder.repetitionCount,
		)
		return DecodeResult(image = drawable.asImage(), isSampled = needsScaling)
	}

	/** How far to shrink a single frame's resolution (aspect ratio preserved), beyond what Coil
	 * already requested, so that one decoded frame alone stays under [MAX_SINGLE_FRAME_BYTES].
	 * Floors at [MIN_FRAME_SCALE] -- past that a very blurry animation is worse than a large one. */
	private fun perFrameScale(reqWidth: Int, reqHeight: Int, bytesPerPixel: Int): Double {
		val frameBytes = (reqWidth.toLong() * reqHeight * bytesPerPixel).coerceAtLeast(1)
		if (frameBytes <= MAX_SINGLE_FRAME_BYTES) return 1.0
		return sqrt(MAX_SINGLE_FRAME_BYTES.toDouble() / frameBytes).coerceAtLeast(MIN_FRAME_SCALE)
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

		private companion object {
			const val HEADER_PEEK_SIZE = 32
			val FTYP_MAGIC = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
			val AVIF_MAGIC = byteArrayOf('a'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte())
			val AVIS_MAGIC = byteArrayOf('a'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
		}
	}

	private companion object {

		// All frames of an animated AVIF used to be decoded up front; now only ~2 are ever held
		// in memory at once (see AvifAnimatedDrawable), but this cap remains as a sanity check
		// against pathologically-framed files before we commit to the animated path at all.
		const val MAX_ANIMATED_FRAMES = 512

		// Safety cap on a *single* decoded frame's size; only matters for unusually large source
		// images, since we no longer multiply this by frame count.
		const val MAX_SINGLE_FRAME_BYTES = 64L * 1024 * 1024

		const val MIN_FRAME_SCALE = 0.5
	}
}
