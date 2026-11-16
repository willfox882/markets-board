package com.marketsboard.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The shape of the pane the app is drawn in, in design dp (the layout always behaves as if the pane were 440 dp wide, see
 * [DesignScale]): [heightDp] is `440 * height / width`. Full screen and quadrant panes are 9:16 (about 782 dp, neither flag);
 * a top/bottom split is about 391 dp ([compact]); a short strip is about 293 dp or less ([tiny], which is also compact).
 *
 * Name and thresholds are identical in the sister app Truck Dash: keep them exactly.
 */
data class PaneSpec(val heightDp: Float, val compact: Boolean, val tiny: Boolean) {
    /** Picks a value by pane shape: [full] normally, [compact] in a compact pane, [tiny] (default: the compact value) in a tiny one. */
    fun <T> pick(full: T, compact: T, tiny: T = compact): T = when {
        this.tiny -> tiny
        this.compact -> compact
        else -> full
    }

    companion object {
        const val DESIGN_WIDTH_DP = 440f
        /** Compact below this height (dp). */
        const val COMPACT_BELOW_DP = 620f
        /** Tiny below this height (dp). */
        const val TINY_BELOW_DP = 330f
        /** A state changes only after crossing a threshold by this much (dp), so dragging a pane edge near a threshold does not flicker. */
        const val HYSTERESIS_DP = 24f
        /** Height of a full 9:16 screen (440 * 16 / 9). */
        const val FULL_HEIGHT_DP = 782f

        /** The full-height spec, also the answer when the shape is unknown (zero, NaN, infinite sizes). */
        val Full = PaneSpec(FULL_HEIGHT_DP, compact = false, tiny = false)

        /** Design height in dp of a pane of the given pixel size, or null when the size is unusable. */
        fun heightDpOf(widthPx: Float, heightPx: Float): Float? {
            if (!widthPx.isFinite() || !heightPx.isFinite() || widthPx <= 0f || heightPx <= 0f) return null
            val h = DESIGN_WIDTH_DP * heightPx / widthPx
            return if (h.isFinite()) h else null
        }

        /**
         * Classifies a design height. Without [previous]: compact below 620, tiny below 330. With [previous]: a flag that is
         * on stays on until the height reaches its threshold plus 24 dp, and a flag that is off turns on only below the
         * threshold minus 24 dp; inside that band the previous state holds. A non-finite or non-positive height is [Full].
         */
        fun classify(heightDp: Float, previous: PaneSpec? = null): PaneSpec {
            if (!heightDp.isFinite() || heightDp <= 0f) return Full
            val compact = decide(heightDp, COMPACT_BELOW_DP, previous?.compact)
            val tiny = decide(heightDp, TINY_BELOW_DP, previous?.tiny)
            // Tiny always implies compact, whatever the history says.
            return PaneSpec(heightDp, compact = compact || tiny, tiny = tiny)
        }

        private fun decide(h: Float, threshold: Float, was: Boolean?): Boolean = when (was) {
            null -> h < threshold
            true -> h < threshold + HYSTERESIS_DP
            false -> h < threshold - HYSTERESIS_DP
        }

        /** Convenience: classify from pixel sizes. */
        fun of(widthPx: Float, heightPx: Float, previous: PaneSpec? = null): PaneSpec =
            heightDpOf(widthPx, heightPx)?.let { classify(it, previous) } ?: Full
    }
}

/** The current pane shape. Provided by [DesignScale]; defaults to full screen so a bare screen composable lays out as before. */
val LocalPaneSpec = staticCompositionLocalOf { PaneSpec.Full }

/** Remembers the previous classification so [PaneSpec.classify] can apply its hysteresis across recompositions. */
internal class PaneTracker {
    private var last: PaneSpec? = null
    fun update(widthPx: Float, heightPx: Float): PaneSpec {
        val s = PaneSpec.of(widthPx, heightPx, last)
        last = s
        return s
    }
}
