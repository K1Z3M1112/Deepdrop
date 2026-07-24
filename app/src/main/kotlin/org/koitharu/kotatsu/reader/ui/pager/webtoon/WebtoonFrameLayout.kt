package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import org.koitharu.kotatsu.R
import kotlin.math.roundToInt

class WebtoonFrameLayout @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	@AttrRes defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

	private var _target: WebtoonImageView? = null
	val target: WebtoonImageView
		get() = _target ?: findViewById<WebtoonImageView?>(R.id.ssiv).also {
			_target = it
		}

	/** Set for pages routed to the animated-image view, whose real size [WebtoonImageView] never
	 * learns (its decoder rejected the source, so `sHeight` stays 0 and it would otherwise fall
	 * back to the full RecyclerView height -- stretching a landscape animation to fill the screen).
	 * When set (> 0), [onMeasure] derives this frame's height from the source's true aspect ratio
	 * instead of leaving it to that fallback. Cleared (0f) once the holder is recycled/reset. */
	var contentAspectRatio: Float = 0f
		set(value) {
			if (field != value) {
				field = value
				requestLayout()
			}
		}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val ratio = contentAspectRatio
		if (ratio > 0f && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
			val width = MeasureSpec.getSize(widthMeasureSpec)
			val height = (width / ratio).roundToInt()
			super.onMeasure(
				widthMeasureSpec,
				MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
			)
			return
		}
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
	}

	fun dispatchVerticalScroll(dy: Int): Int {
		if (dy == 0) {
			return 0
		}
		val oldScroll = target.getScroll()
		target.scrollBy(dy)
		return target.getScroll() - oldScroll
	}
}
