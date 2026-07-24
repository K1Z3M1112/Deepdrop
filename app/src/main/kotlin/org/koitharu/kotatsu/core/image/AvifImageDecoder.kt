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

		private fun isApplicable(result: SourceFetchResult): Boolean {
			return result.mimeType == "image/avif"
		}
	}

	private companion object {

		// All frames of an animated AVIF are decoded up front to keep playback smooth; cap the
		// frame count so a pathological file can't be used to exhaust memory.
		const val MAX_ANIMATED_FRAMES = 512
	}
}
