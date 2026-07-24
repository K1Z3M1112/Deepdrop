package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.aomedia.avif.android.AvifDecoder

/**
 * Plays back an animated AVIF, decoded progressively instead of all up front:
 *
 * - Frame 0 is already decoded (by [AvifImageDecoder]) before this drawable even exists, so
 *   something is on screen immediately -- playback never waits for the whole clip to decode.
 * - Every following frame is decoded on a background coroutine that always stays exactly one
 *   frame ahead of what's currently shown (see [produceFrames] / [publish]): enough to keep
 *   playback smooth without ever holding more than ~2 decoded frames in memory, no matter how
 *   long the animation is.
 * - If playback ever catches up to a frame that isn't decoded yet, [run] holds the current frame
 *   a little longer and checks again shortly instead of skipping ahead -- nothing is ever dropped,
 *   worst case it plays a touch slower while decode catches up.
 * - [stop] (e.g. when the reader page holding this scrolls off-screen and gets recycled) cancels
 *   the background decode and releases the native AVIF decoder, but deliberately leaves whatever
 *   frame is currently showing in place -- the drawable always has *something* to display, it
 *   just stops animating. [start] resumes by restarting playback from frame 0, which is instant
 *   since that frame's bitmap ([posterFrame]) is kept alive for the drawable's whole lifetime.
 *
 * Coil starts/stops any `Drawable` implementing [Animatable] automatically when it is attached to
 * a target, the same mechanism it already relies on for animated GIF/WebP, so nothing else needs
 * to change at the call sites for these images to animate.
 */
class AvifAnimatedDrawable(
	/** Builds a fresh decoder positioned at the start of the sequence; used every time playback
	 * loops or resumes, since the AVIF decoder can only ever move forward. */
	private val decoderFactory: () -> AvifDecoder,
	/** The decoder [AvifImageDecoder] already used to produce [firstFrameBitmap]; reused as-is
	 * for the very first pass so frame 0 isn't decoded twice. */
	initialDecoder: AvifDecoder,
	private val config: Bitmap.Config,
	private val dstWidth: Int,
	private val dstHeight: Int,
	private val needsScaling: Boolean,
	firstFrameBitmap: Bitmap,
	private val frameDurationsMs: IntArray,
	// Non-negative n means "play back n + 1 times"; negative means loop forever (matches libavif's
	// AVIF_REPETITION_COUNT_INFINITE / _UNKNOWN convention of treating unknown as infinite too).
	repetitionCount: Int,
) : Drawable(), Animatable, Runnable {

	private val totalPlays = if (repetitionCount >= 0) repetitionCount + 1 else -1
	private val frameCount = frameDurationsMs.size
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
	private val handler = Handler(Looper.getMainLooper())
	private val bufferLock = Any()
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	// Frame 0, kept alive for as long as this drawable exists. Reused directly every time
	// playback loops back to the start (no re-decode) and is what stays visible if playback is
	// stopped before anything past it has decoded.
	private val posterFrame: Bitmap = firstFrameBitmap

	@Volatile private var decoder: AvifDecoder? = initialDecoder
	@Volatile private var currentFrame: Bitmap = firstFrameBitmap
	@Volatile private var bufferedNext: Bitmap? = null
	private var bufferedNextIndex = -1
	private var hasConsumedInitialDecoder = false

	private var frameIndex = 0
	private var playsDone = 0
	private var running = false
	private var producerJob: Job? = null

	// Where [currentFrame] is actually drawn: [bounds] resized to fit inside the drawable's
	// bounds while keeping the frame's original aspect ratio (dstWidth:dstHeight), centered,
	// with letterboxing on whichever axis has slack. Recomputed only in [onBoundsChange], not on
	// every draw, since the host view's bounds usually don't change between frames.
	private val destRect = Rect()

	override fun onBoundsChange(bounds: Rect) {
		super.onBoundsChange(bounds)
		updateDestRect(bounds)
	}

	/** Fit-inside (letterbox), not fill-stretch: preserves [dstWidth]:[dstHeight] regardless of
	 * the aspect ratio of [bounds], so a resize never distorts the frame. */
	private fun updateDestRect(bounds: Rect) {
		val boundsWidth = bounds.width()
		val boundsHeight = bounds.height()
		if (dstWidth <= 0 || dstHeight <= 0 || boundsWidth <= 0 || boundsHeight <= 0) {
			destRect.set(bounds)
			return
		}
		val srcAspect = dstWidth.toFloat() / dstHeight.toFloat()
		val boundsAspect = boundsWidth.toFloat() / boundsHeight.toFloat()
		if (srcAspect > boundsAspect) {
			// Source is relatively wider than the bounds: fit to width, letterbox top/bottom.
			val fitHeight = (boundsWidth / srcAspect).toInt()
			val top = bounds.top + (boundsHeight - fitHeight) / 2
			destRect.set(bounds.left, top, bounds.right, top + fitHeight)
		} else {
			// Source is relatively taller than the bounds: fit to height, letterbox left/right.
			val fitWidth = (boundsHeight * srcAspect).toInt()
			val left = bounds.left + (boundsWidth - fitWidth) / 2
			destRect.set(left, bounds.top, left + fitWidth, bounds.bottom)
		}
	}

	override fun draw(canvas: Canvas) {
		canvas.drawBitmap(currentFrame, null, destRect, paint)
	}

	override fun run() {
		if (!running) return
		val advanced = tryAdvanceFrame()
		invalidateSelf()
		if (running) {
			val delayMs = if (advanced) {
				frameDurationsMs.getOrElse(frameIndex) { DEFAULT_FRAME_DURATION_MS }.coerceAtLeast(MIN_FRAME_DURATION_MS).toLong()
			} else {
				RETRY_DELAY_MS
			}
			handler.postDelayed(this, delayMs)
		}
	}

	/** Moves to the next frame if it's ready; otherwise leaves [currentFrame] as-is so [run]
	 * retries shortly instead of showing a gap. Returns whether it actually advanced. */
	private fun tryAdvanceFrame(): Boolean {
		val wantedIndex = frameIndex + 1
		if (wantedIndex >= frameCount) {
			playsDone++
			if (totalPlays in 0..playsDone) {
				running = false
				return true
			}
			val old = currentFrame
			frameIndex = 0
			currentFrame = posterFrame
			if (old !== posterFrame) old.recycle()
			restartProducer()
			return true
		}
		return synchronized(bufferLock) {
			if (bufferedNextIndex == wantedIndex && bufferedNext != null) {
				val old = currentFrame
				currentFrame = bufferedNext!!
				bufferedNext = null
				bufferedNextIndex = -1
				frameIndex = wantedIndex
				if (old !== posterFrame) old.recycle()
				true
			} else {
				false
			}
		}
	}

	private fun ensureProducing() {
		if (producerJob?.isActive == true || frameCount <= 1) return
		val dec: AvifDecoder
		val startIndex: Int
		if (!hasConsumedInitialDecoder) {
			dec = decoder ?: return
			hasConsumedInitialDecoder = true
			startIndex = 1
		} else {
			dec = decoderFactory()
			decoder = dec
			startIndex = 0
		}
		producerJob = scope.launch { produceFrames(dec, startIndex) }
	}

	private fun restartProducer() {
		producerJob?.cancel()
		producerJob = null
		ensureProducing()
	}

	/** Decodes frames [1, frameCount) in order, publishing each one into the single-slot
	 * lookahead buffer as it's ready. [startIndex] is 0 for a freshly-created [dec] (container
	 * frame 0 must be decoded and thrown away first, since [posterFrame] already covers it) or 1
	 * for the decoder handed in from [AvifImageDecoder] (already positioned right after frame 0). */
	private suspend fun produceFrames(dec: AvifDecoder, startIndex: Int) {
		try {
			if (startIndex == 0) {
				val scratch = createBitmap(dec.width, dec.height, config)
				val result = dec.nextFrame(scratch)
				scratch.recycle()
				if (result != 0) return
			}
			var idx = 1
			while (idx < frameCount) {
				val bitmap = createBitmap(dec.width, dec.height, config)
				val result = dec.nextFrame(bitmap)
				if (result != 0) {
					bitmap.recycle()
					return
				}
				val finalBitmap = if (needsScaling) {
					bitmap.scale(dstWidth, dstHeight).also { bitmap.recycle() }
				} else {
					bitmap
				}
				publish(finalBitmap, idx)
				idx++
			}
		} finally {
			dec.release()
			if (decoder === dec) {
				decoder = null
			}
		}
	}

	/** Waits until the single-frame lookahead slot is free, then publishes into it. If cancelled
	 * while waiting (e.g. [stop] was called), recycles the bitmap instead of leaking it. */
	private suspend fun publish(bitmap: Bitmap, index: Int) {
		try {
			while (true) {
				val done = synchronized(bufferLock) {
					if (bufferedNext == null) {
						bufferedNext = bitmap
						bufferedNextIndex = index
						true
					} else {
						false
					}
				}
				if (done) return
				delay(FRAME_POLL_INTERVAL_MS)
			}
		} catch (e: CancellationException) {
			bitmap.recycle()
			throw e
		}
	}

	override fun start() {
		if (running || frameCount <= 1) return
		running = true
		if (decoder == null) {
			// Fully stopped before (native decoder + lookahead already released) -- restart
			// cleanly from frame 0 rather than trying to reconstruct exactly where playback left
			// off. Instant, since posterFrame needs no decoding.
			frameIndex = 0
			playsDone = 0
			currentFrame = posterFrame
			synchronized(bufferLock) {
				bufferedNext?.recycle()
				bufferedNext = null
				bufferedNextIndex = -1
			}
		}
		ensureProducing()
		val delayMs = frameDurationsMs.getOrElse(frameIndex) { DEFAULT_FRAME_DURATION_MS }.coerceAtLeast(MIN_FRAME_DURATION_MS).toLong()
		handler.postDelayed(this, delayMs)
	}

	override fun stop() {
		running = false
		handler.removeCallbacksAndMessages(null)
		val job = producerJob
		producerJob = null
		scope.launch {
			job?.cancelAndJoin()
			synchronized(bufferLock) {
				bufferedNext?.recycle()
				bufferedNext = null
				bufferedNextIndex = -1
			}
		}
	}

	override fun isRunning(): Boolean = running

	override fun setAlpha(alpha: Int) {
		paint.alpha = alpha
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		paint.colorFilter = colorFilter
	}

	@Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

	override fun getIntrinsicWidth(): Int = dstWidth

	override fun getIntrinsicHeight(): Int = dstHeight

	private companion object {

		const val DEFAULT_FRAME_DURATION_MS = 100
		const val MIN_FRAME_DURATION_MS = 16
		const val RETRY_DELAY_MS = 8L
		const val FRAME_POLL_INTERVAL_MS = 4L
	}
}
