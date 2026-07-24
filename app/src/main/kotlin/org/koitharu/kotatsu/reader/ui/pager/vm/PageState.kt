package org.koitharu.kotatsu.reader.ui.pager.vm

import com.davemorrissey.labs.subscaleview.ImageSource

sealed class PageState {

	data object Empty : PageState()

	data class Loading(
		val preview: ImageSource?,
		val progress: Int,
	) : PageState()

	data class Loaded(
		val source: ImageSource,
		val isConverted: Boolean,
	) : PageState()

	class Converting() : PageState()

	data class Shown(
		val source: ImageSource,
		val isConverted: Boolean,
	) : PageState()

	/** [source] is confirmed multi-frame (GIF/animated WebP/AVIF) but [SubsamplingScaleImageView]
	 * failed to decode it -- e.g. Android's [android.graphics.BitmapRegionDecoder] has no AVIF
	 * support at all, animated or not. Routing here instead of [Error] skips SSIV entirely and
	 * tells the page holder to play [source] directly via the animated-capable view, since trying
	 * to "recover" with a static re-encode would just throw away every frame but the first. */
	data class Animated(
		val source: ImageSource,
	) : PageState()

	data class Error(
		val error: Throwable,
	) : PageState()

	fun isFinalState(): Boolean = this is Error || this is Shown || this is Animated
}
