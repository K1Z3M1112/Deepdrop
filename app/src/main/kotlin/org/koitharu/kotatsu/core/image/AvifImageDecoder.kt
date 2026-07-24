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
import kotlin.math.sqrt

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

	/**
	 * Decodes every frame of an animated AVIF up front and plays them back via [AvifAnimatedDrawable].
	 *
	 * All decoded frames are kept in memory at once for smooth, seek-free playback, so on a long
	 * and/or high-resolution animation a naive "decode every frame at full size" approach can
	 * exhaust RAM: cost is roughly `frameCount * width * height * bytesPerPixel`. To keep that
	 * bounded within [MAX_ANIMATED_MEMORY_BYTES] we apply two reductions, in order:
	 *
	 * 1. Resolution: shrunk further (aspect ratio preserved, on top of whatever Coil already
	 *    requested via [dstSize]) down to [MIN_FRAME_SCALE] of the requested size.
	 * 2. Frame count: only touched if step 1 alone -- even at its floor -- still doesn't fit the
	 *    budget. Frames to drop are picked evenly across the *entire* timeline (see
	 *    [evenlySpacedIndices]) rather than lopping off one contiguous stretch, and each dropped
	 *    frame's display time is folded into its nearest surviving neighbour (see
	 *    [mergedDurations]) so the total duration is preserved and no motion just "disappears" --
	 *    that stretch of the animation plays back faster instead of being cut.
	 */
	private fun decodeAnimated(decoder: AvifDecoder, config: Bitmap.Config, frameCount: Int): DecodeResult {
		val (reqWidth, reqHeight) = dstSize(decoder.width, decoder.height)
		val bytesPerPixel = if (config == Bitmap.Config.ARGB_8888) 4 else 2
		val reqFrameBytes = (reqWidth.toLong() * reqHeight * bytesPerPixel).coerceAtLeast(1)

		// How much extra shrinking (beyond what Coil already asked for) would it take to fit
		// *every* frame within the memory budget? Never upscale past what was requested.
		val scaleForAllFrames = sqrt(MAX_ANIMATED_MEMORY_BYTES.toDouble() / (frameCount.toLong() * reqFrameBytes))
			.coerceAtMost(1.0)

		val scale: Double
		val keepCount: Int
		if (scaleForAllFrames >= MIN_FRAME_SCALE) {
			// Shrinking resolution a bit is enough on its own; every frame survives.
			scale = scaleForAllFrames
			keepCount = frameCount
		} else {
			// Even at the smallest resolution we're willing to go, all frames won't fit -- drop some.
			scale = MIN_FRAME_SCALE
			val shrunkFrameBytes = (reqFrameBytes * scale * scale).toLong().coerceAtLeast(1)
			val framesAtFloor = (MAX_ANIMATED_MEMORY_BYTES / shrunkFrameBytes).toInt()
			keepCount = framesAtFloor.coerceIn(MIN_KEPT_FRAMES.coerceAtMost(frameCount), frameCount)
		}

		val dstWidth = (reqWidth * scale).toInt().coerceAtLeast(1)
		val dstHeight = (reqHeight * scale).toInt().coerceAtLeast(1)
		val needsScaling = dstWidth < decoder.width || dstHeight < decoder.height
		val isSampled = needsScaling

		val keptIndices = evenlySpacedIndices(frameCount, keepCount)
		val originalDurationsMs = IntArray(frameCount)
		val frames = ArrayList<Bitmap>(keepCount)
		var keptPtr = 0
		// Reused destination for frames we decode only to immediately discard, so a long run of
		// dropped frames doesn't allocate a full-size bitmap for each one.
		var scratch: Bitmap? = null

		for (i in 0 until frameCount) {
			val isKept = keptPtr < keptIndices.size && keptIndices[keptPtr] == i
			val dest = if (isKept) {
				createBitmap(decoder.width, decoder.height, config)
			} else {
				scratch ?: createBitmap(decoder.width, decoder.height, config).also { scratch = it }
			}
			val result = decoder.nextFrame(dest)
			if (result != 0) {
				if (isKept) dest.recycle()
				scratch?.takeIf { it !== dest }?.recycle()
				frames.forEach { it.recycle() }
				throw ImageDecodeException(
					uri = source.fileOrNull()?.toString(),
					format = "avif",
					message = AvifDecoder.resultToString(result),
				)
			}
			originalDurationsMs[i] = (decoder.frameDurations.getOrElse(i) { 0.1 } * 1000).toInt()
			if (isKept) {
				keptPtr++
				if (needsScaling) {
					val scaled = dest.scale(dstWidth, dstHeight)
					dest.recycle()
					frames += scaled
				} else {
					frames += dest
				}
			}
		}
		scratch?.recycle()

		val durationsMs = mergedDurations(originalDurationsMs, keptIndices)
		val drawable = AvifAnimatedDrawable(frames, durationsMs, decoder.repetitionCount)
		return DecodeResult(image = drawable.asImage(), isSampled = isSampled)
	}

	/** Picks [keepCount] indices out of [totalCount], spread as evenly as possible across the
	 * whole range (always including the first and last frame) instead of one contiguous run, so
	 * dropped frames come from throughout the animation rather than a single missing stretch. */
	private fun evenlySpacedIndices(totalCount: Int, keepCount: Int): IntArray {
		if (keepCount >= totalCount) return IntArray(totalCount) { it }
		if (keepCount <= 1) return intArrayOf(0)
		return IntArray(keepCount) { i -> (i.toLong() * (totalCount - 1) / (keepCount - 1)).toInt() }
	}

	/** Assigns every original frame's display duration to whichever surviving frame in
	 * [keptIndices] is closest to it (nearest-neighbour / Voronoi split of the timeline), so the
	 * sum of the returned durations equals the sum of [originalDurationsMs]: the animation still
	 * takes about as long overall, it just plays that stretch back faster instead of truncating it. */
	private fun mergedDurations(originalDurationsMs: IntArray, keptIndices: IntArray): IntArray {
		val result = IntArray(keptIndices.size)
		var keptPos = 0
		for (i in originalDurationsMs.indices) {
			while (keptPos < keptIndices.size - 1 &&
				i >= (keptIndices[keptPos] + keptIndices[keptPos + 1] + 1) / 2
			) {
				keptPos++
			}
			result[keptPos] += originalDurationsMs[i]
		}
		return result
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

		// All frames of an animated AVIF are decoded up front to keep playback smooth; cap the
		// frame count so a pathological file can't be used to exhaust memory.
		const val MAX_ANIMATED_FRAMES = 512

		// Soft cap on how much RAM the fully-decoded frame list for one animated AVIF may use.
		// When a file would exceed this, resolution and then frame count are reduced to fit.
		// 96 MiB ~= 384 frames at 256x256 ARGB_8888, or ~24 frames at 1024x1024.
		const val MAX_ANIMATED_MEMORY_BYTES = 96L * 1024 * 1024

		// Never shrink resolution, purely to save memory, below this fraction of what Coil
		// originally requested -- past this point frame count is reduced instead, since a very
		// blurry animation is worse than a slightly less smooth one.
		const val MIN_FRAME_SCALE = 0.5

		// Never drop below this many frames (unless the source has fewer to begin with), so
		// heavy frame reduction doesn't turn the animation into a slideshow.
		const val MIN_KEPT_FRAMES = 24
	}
}
