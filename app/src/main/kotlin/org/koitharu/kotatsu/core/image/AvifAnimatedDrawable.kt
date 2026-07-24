package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper

/**
 * Plays back a decoded animated-AVIF frame sequence.
 *
 * Coil starts/stops any `Drawable` implementing [Animatable] automatically when it is attached to
 * a target, the same mechanism it already relies on for animated GIF/WebP, so nothing else needs
 * to change at the call sites for these images to animate.
 */
class AvifAnimatedDrawable(
	private val frames: List<Bitmap>,
	private val frameDurationsMs: IntArray,
	// Non-negative n means "play back n + 1 times"; negative means loop forever (matches libavif's
	// AVIF_REPETITION_COUNT_INFINITE / _UNKNOWN convention of treating unknown as infinite too).
	repetitionCount: Int,
) : Drawable(), Animatable, Runnable {

	private val totalPlays = if (repetitionCount >= 0) repetitionCount + 1 else -1
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
	private val handler = Handler(Looper.getMainLooper())
	private var frameIndex = 0
	private var playsDone = 0
	private var running = false

	override fun draw(canvas: Canvas) {
		val frame = frames.getOrNull(frameIndex) ?: return
		canvas.drawBitmap(frame, null, bounds, paint)
	}

	override fun run() {
		if (!running) {
			return
		}
		frameIndex++
		if (frameIndex >= frames.size) {
			frameIndex = 0
			playsDone++
			if (totalPlays in 0..playsDone) {
				running = false
				invalidateSelf()
				return
			}
		}
		invalidateSelf()
		scheduleNextFrame()
	}

	private fun scheduleNextFrame() {
		val delay = frameDurationsMs.getOrElse(frameIndex) { DEFAULT_FRAME_DURATION_MS }
			.coerceAtLeast(MIN_FRAME_DURATION_MS)
		handler.postDelayed(this, delay.toLong())
	}

	override fun start() {
		if (running || frames.size <= 1) {
			return
		}
		running = true
		scheduleNextFrame()
	}

	override fun stop() {
		running = false
		handler.removeCallbacks(this)
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

	override fun getIntrinsicWidth(): Int = frames.firstOrNull()?.width ?: -1

	override fun getIntrinsicHeight(): Int = frames.firstOrNull()?.height ?: -1

	private companion object {

		const val DEFAULT_FRAME_DURATION_MS = 100
		const val MIN_FRAME_DURATION_MS = 16
	}
}
