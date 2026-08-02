package com.example.url_blocker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.url_blocker.matching.FeedVideoCard

/**
 * Draws a "blocked" card over each blocked YouTube feed card in Chrome that
 * REPLACES the card's THUMBNAIL (the user's requested flow — red badges
 * weren't enough): the thumbnail area (top ~60% of the card) is covered by a
 * blurred version of the real thumbnail when the image pipeline ran (or a
 * near-opaque dark scrim when only text signals fired), with a centered
 * "🚫 BLOCKED / Explicit Content" label. The title and metadata BELOW the
 * thumbnail stay visible so the user can still read the feed.
 *
 * The overlay WINDOW spans the entire card and is deliberately INERT
 * (clickable + focusable with no click listener): a tap anywhere on the card —
 * thumbnail OR title — lands on this view and does nothing, so the blocked
 * video underneath cannot be opened. If the video somehow opens anyway
 * (search, related, reopened tab), the watch-page block still fires.
 *
 * BITMAP OWNERSHIP: this overlay only DRAWS the blurred thumbnails passed to
 * [showBadges]; it never recycles them. The UrlBlockerService owns those
 * bitmaps (its `badgeBlurMap` / `releaseBadgeBlur()` lifecycle) so a redraw or
 * reposition can never draw recycled pixels and recycles can never be issued
 * twice for the same bitmap.
 *
 * Uses a system overlay window ([TYPE_APPLICATION_OVERLAY]) so the cards
 * appear on top of Chrome. Requires the "Display over other apps" special
 * permission (SYSTEM_ALERT_WINDOW) — granted once in Settings, like the
 * accessibility permission.
 */
class FeedBadgeOverlay(private val context: Context) {

    companion object {
        private const val TAG = "FeedBadgeOverlay"
        /** Fraction of the card height occupied by the thumbnail. */
        private const val THUMBNAIL_FRACTION = 0.6f
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val badges = mutableListOf<View>()

    /**
     * Signature of the last drawn marker set (title + bounds + whether a
     * blurred thumbnail was available per card). The service re-calls
     * [showBadges] on every 500ms poll while markers are on screen (so they
     * re-position on scroll); without this guard each poll would remove +
     * re-add every marker view — visible 2Hz flicker. Blur availability is
     * part of the signature so a scrim-only show REDRAWS when the image
     * pipeline finishes and hands over a real blurred thumbnail.
     */
    private var lastShownSignature: String? = null

    /** True when the app has the "Display over other apps" permission. */
    fun canDrawOverlays(): Boolean =
        try {
            Settings.canDrawOverlays(context)
        } catch (e: Exception) {
            false
        }

    /** True when at least one marker is currently on screen. */
    val isShowing: Boolean
        get() = badges.isNotEmpty()

    /**
     * Replace any existing markers with markers for [cards]. Cards without
     * usable bounds are skipped. [blurredThumbnails] maps a card title to a
     * pre-blurred bitmap of its real thumbnail (from the image pipeline);
     * cards without an entry get a dark scrim instead. The bitmaps are owned
     * by the CALLER (the service) — this overlay draws them and never
     * recycles them. No-op (and logged) when the overlay permission is
     * missing.
     */
    fun showBadges(cards: List<FeedVideoCard>, blurredThumbnails: Map<String, Bitmap> = emptyMap()) {
        if (!canDrawOverlays()) {
            Log.w(TAG, "FEED_BADGE_PERMISSION_MISSING — cannot draw markers (grant 'Display over other apps' in Settings)")
            return
        }
        // Skip the redraw when the marker set (titles + positions + blur
        // availability) is unchanged — the 500ms poll re-calls this while
        // markers are shown for scroll tracking, and remove+re-add every poll
        // would flicker visibly.
        val signature = cards.joinToString("|") {
            val b = it.bounds
            "${it.title}@${b?.left},${b?.top},${b?.right},${b?.bottom}" +
                (if (blurredThumbnails.containsKey(it.title)) "~B" else "~S")
        }
        if (signature == lastShownSignature) {
            return
        }
        // clearBadges() resets lastShownSignature to null — assign the new
        // signature AFTER it so the poll-time guard actually short-circuits.
        clearBadges()
        lastShownSignature = signature
        var shown = 0
        var blurredCount = 0
        for (card in cards) {
            val bounds = card.bounds ?: continue
            if (bounds.isEmpty) continue
            val blurred = blurredThumbnails[card.title]
            if (blurred != null) blurredCount++
            // Diagnostic: logs the covered rect so full-card coverage (vs a
            // title-text-only node) can be verified on-device — a small rect
            // means the thumbnail above stays clickable on that device.
            Log.d(TAG, "FEED_BADGE_BOUNDS title=${card.title} rect=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] w=${bounds.width()} h=${bounds.height()}")
            val thumbHeight = (bounds.height() * THUMBNAIL_FRACTION).toInt()
            val blockCard = buildBlockCard(card.title, card.blockedKeyword, thumbHeight, blurred)
            val params = WindowManager.LayoutParams(
                bounds.width(),
                bounds.height(),
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = bounds.left
            params.y = bounds.top
            try {
                windowManager.addView(blockCard, params)
                badges.add(blockCard)
                shown++
            } catch (e: Exception) {
                Log.e(TAG, "FEED_BADGE_ADD_FAILED: ${e.message}")
            }
        }
        if (shown > 0) {
            Log.i(TAG, "FEED_BADGES_SHOWN count=$shown blurred=$blurredCount")
        }
    }

    /** Remove all markers. Safe to call when none are showing. */
    fun clearBadges() {
        lastShownSignature = null
        if (badges.isEmpty()) return
        for (badge in badges) {
            try {
                windowManager.removeView(badge)
            } catch (e: Exception) {
                // already detached — ignore
            }
        }
        badges.clear()
        Log.d(TAG, "FEED_BADGES_CLEARED")
    }

    /**
     * Build the marker view for one blocked card. The view spans the ENTIRE
     * card (so every tap is intercepted) but only paints over the thumbnail
     * area: the blurred real thumbnail (or a dark scrim), a scrim for text
     * legibility, and the centered "🚫 BLOCKED / Explicit Content" label.
     */
    private fun buildBlockCard(
        title: String,
        keyword: String?,
        thumbnailHeight: Int,
        blurred: Bitmap?
    ): View {
        val density = context.resources.displayMetrics.density
        return object : View(context) {
            private val scrim = Paint().apply {
                // Near-opaque when there's no blurred thumbnail behind us
                // (text-blocked cards); lighter when blur is underneath so the
                // blurred image shows through.
                color = Color.argb(if (blurred != null) 150 else 235, 0, 0, 0)
            }
            private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                textSize = 17f * density
            }
            private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = 12f * density
                alpha = 215
            }

            init {
                // Inert: clickable+focusable but with NO click listener, so a
                // tap lands here and does nothing — the video cannot be opened.
                isClickable = true
                isFocusable = true
                val kw = keyword?.trim().orEmpty()
                contentDescription =
                    if (kw.isEmpty()) "Blocked video: $title" else "Blocked video: $title ($kw)"
            }

            override fun onDraw(canvas: Canvas) {
                val thumbBottom = thumbnailHeight.coerceIn(0, height).toFloat()
                if (blurred != null && thumbBottom > 0f) {
                    canvas.drawBitmap(
                        blurred,
                        null,
                        RectF(0f, 0f, width.toFloat(), thumbBottom),
                        null
                    )
                }
                canvas.drawRect(0f, 0f, width.toFloat(), thumbBottom, scrim)
                // Only paint the label when the thumbnail area has room for it.
                if (thumbBottom > 44f * density) {
                    val cx = width / 2f
                    val cy = thumbBottom / 2f
                    canvas.drawText("🚫 BLOCKED", cx, cy - 9f * density, titlePaint)
                    canvas.drawText("Explicit Content", cx, cy + 11f * density, subPaint)
                }
            }
        }
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
