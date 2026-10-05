package com.example.service

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.data.DataStoreManager
import com.example.data.LogType
import com.example.data.WatchDurationTier
import com.example.repository.WatchSessionRepository
import com.example.util.TimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FloatingTimerOverlayManager(private val context: Context) {

    companion object {
        private val globalAttachedViews = mutableListOf<View>()

        private fun removeAllGlobalViews(wm: WindowManager) {
            val iterator = globalAttachedViews.iterator()
            while (iterator.hasNext()) {
                val v = iterator.next()
                try {
                    wm.removeViewImmediate(v)
                } catch (_: Exception) {
                    try {
                        wm.removeView(v)
                    } catch (_: Exception) {}
                }
                iterator.remove()
            }
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dataStoreManager = DataStoreManager(context)

    private var overlayRootView: FrameLayout? = null
    private var timerTextView: TextView? = null
    private var statusTagTextView: TextView? = null
    private var liveDotView: View? = null
    private var progressFillView: View? = null
    private var progressTrackWidthPx: Int = 0
    private var milestoneBadgeTextView: TextView? = null
    private var likeBadgeView: TextView? = null
    private var commentBadgeView: TextView? = null
    private var celebrationContainer: LinearLayout? = null
    private var celebrationText: TextView? = null
    private var lastCelebratedTier: WatchDurationTier? = null
    private var incompletePopupView: FrameLayout? = null
    private var searchLoadingOverlayView: FrameLayout? = null
    private var searchLoadingStatusTextView: TextView? = null
    private var searchLoadingTitleTextView: TextView? = null
    private var searchLoadingChannelTextView: TextView? = null
    private var searchLoadingOverlayWm: WindowManager? = null

    // Suggested videos locked overlay under comments
    private var suggestedLockRootView: FrameLayout? = null
    private var suggestedLockTimerTextView: TextView? = null
    private var suggestedLockProgressFillView: View? = null
    private var suggestedLockStatusTagView: TextView? = null
    private var suggestedLockLikePillView: TextView? = null
    private var suggestedLockCommentPillView: TextView? = null
    private var closeCommentsFloatButton: View? = null

    private var isOpeningOverlayEnabled: Boolean = true

    init {
        overlayScope.launch {
            dataStoreManager.fullScreenOpeningOverlayFlow.collectLatest { enabled ->
                isOpeningOverlayEnabled = enabled
                runOnMain {
                    if (isAttached && WatchSessionRepository.sessionState.value == com.example.data.SessionState.ACTIVE) {
                        if (enabled) {
                            // When admin Opening toggle is ON: Hide top timer pill, keep only bottom lock bar!
                            overlayRootView?.let { v ->
                                try { windowManager.removeView(v) } catch (_: Exception) {}
                                globalAttachedViews.remove(v)
                            }
                            overlayRootView = null
                        } else {
                            // When admin Opening toggle is OFF: Show top timer pill!
                            if (overlayRootView == null) {
                                showOverlay()
                            }
                        }
                    }
                }
            }
        }
    }

    // Player drag-down / swipe-down freeze lock overlay
    private var playerLockRootView: FrameLayout? = null

    private var isAttached = false
    private var currentCommentCount = 0
    private var isTaskLiked = false
    private var density = context.resources.displayMetrics.density

    init {
        registerOverlayCallbacks()
    }

    fun registerOverlayCallbacks() {
        WatchSessionRepository.onTaskLikeDetected = {
            runOnMain { handleLikeDetected() }
        }
        WatchSessionRepository.onTaskCommentDetected = {
            runOnMain { handleCommentDetected() }
        }
        WatchSessionRepository.onCommentSheetVisibilityChanged = { isOpen ->
            runOnMain { setSuggestedLockVisible(!isOpen) }
        }
    }

    fun setSuggestedLockVisible(visible: Boolean) {
        runOnMain {
            val root = suggestedLockRootView
            if (visible) {
                removeCloseCommentsFloatButton()
                if (root != null) {
                    try {
                        val p = root.layoutParams as? WindowManager.LayoutParams
                        if (p != null) {
                            p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            windowManager.updateViewLayout(root, p)
                        }
                        root.visibility = View.VISIBLE
                    } catch (_: Exception) {}
                } else if (isAttached && WatchSessionRepository.sessionState.value == com.example.data.SessionState.ACTIVE) {
                    showSuggestedVideosLockOverlay()
                }
            } else {
                if (root != null) {
                    try {
                        root.visibility = View.GONE
                        val p = root.layoutParams as? WindowManager.LayoutParams
                        if (p != null) {
                            p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            windowManager.updateViewLayout(root, p)
                        }
                    } catch (_: Exception) {}
                }
                showCloseCommentsFloatButton()
            }
        }
    }

    private fun showCloseCommentsFloatButton() {
        if (!Settings.canDrawOverlays(context) || closeCommentsFloatButton != null) return
        try {
            val d = context.resources.displayMetrics.density
            val screenWidth = context.resources.displayMetrics.widthPixels
            val statusBarHeight = (30 * d).toInt()
            val playerHeight = statusBarHeight + ((screenWidth * 9) / 16).coerceAtLeast((200 * d).toInt())
            val btnParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (14 * d).toInt()
                y = playerHeight + (10 * d).toInt()
            }

            val btn = TextView(context).apply {
                text = "✕ Close Comments"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 20 * d
                    setColor(Color.parseColor("#E11D48")) // Crimson red
                    setStroke((1.2f * d).toInt(), Color.parseColor("#FBBF24")) // Gold border
                }
                elevation = 25 * d
                setOnClickListener {
                    YouTubeLiveSearchService.closeCurrentCommentOrChatSheet()
                    removeCloseCommentsFloatButton()
                }
            }
            windowManager.addView(btn, btnParams)
            globalAttachedViews.add(btn)
            closeCommentsFloatButton = btn
        } catch (_: Exception) {}
    }

    private fun removeCloseCommentsFloatButton() {
        closeCommentsFloatButton?.let { btn ->
            try { windowManager.removeView(btn) } catch (_: Exception) {}
            globalAttachedViews.remove(btn)
        }
        closeCommentsFloatButton = null
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun isOpeningToggleOn(): Boolean {
        return try {
            val prefs = context.getSharedPreferences("watchearn_prefs", Context.MODE_PRIVATE)
            if (prefs.contains("full_screen_opening_overlay")) {
                prefs.getBoolean("full_screen_opening_overlay", true)
            } else {
                isOpeningOverlayEnabled
            }
        } catch (_: Exception) {
            isOpeningOverlayEnabled
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun showOverlay() {
        runOnMain {
            if (WatchSessionRepository.sessionState.value != com.example.data.SessionState.ACTIVE ||
                WatchSessionRepository.isAppInForeground
            ) {
                return@runOnMain
            }

            if (!Settings.canDrawOverlays(context)) {
                WatchSessionRepository.addLog(
                    "Floating timer overlay not displayed: 'Display over other apps' permission required.",
                    LogType.WARNING
                )
                return@runOnMain
            }

            // Always lock the suggested videos section below the comments and freeze player drag
            showSuggestedVideosLockOverlay()
            showPlayerDragLockOverlay()
            registerSessionCallbacks()
            isAttached = true

            // Admin toggle control:
            // ON (default): Target video play time par upar chhota overlay pill hide rahega, only bottom lock bar rahega!
            // OFF: Target video play time par upar chhota timer pill bhi dikhega!
            val isOpeningOn = isOpeningToggleOn()
            if (isOpeningOn) {
                overlayRootView?.let {
                    try { windowManager.removeView(it) } catch (_: Exception) {}
                    globalAttachedViews.remove(it)
                }
                overlayRootView = null
                WatchSessionRepository.addLog("Top timer pill hidden (Admin Opening toggle is ON), bottom suggested lock active", LogType.INFO)
                return@runOnMain
            }

            // If already attached and active, do not recreate or duplicate the overlay
            if (overlayRootView != null && incompletePopupView == null) {
                return@runOnMain
            }

            overlayRootView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
                globalAttachedViews.remove(it)
            }
            incompletePopupView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
                globalAttachedViews.remove(it)
            }
            overlayRootView = null
            incompletePopupView = null

            density = context.resources.displayMetrics.density
            val hudWidthPx = (242 * density).toInt()
            progressTrackWidthPx = hudWidthPx - (22 * density).toInt()

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (12 * density).toInt()
                y = (76 * density).toInt()
            }

            val root = FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Professional Compact 2-Row HUD Card Container
            val pillLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    hudWidthPx,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(
                    (11 * density).toInt(),
                    (8 * density).toInt(),
                    (11 * density).toInt(),
                    (8 * density).toInt()
                )

                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16 * density
                    setColor(Color.parseColor("#EB0B1120")) // Translucent Deep Obsidian
                    setStroke((1.3f * density).toInt(), Color.parseColor("#F59E0B")) // Sleek Gold Border
                }
                background = bg
                elevation = 18 * density
            }

            // ================= ROW 1: Live Dot + Status + Timer + Milestone + Close =================
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Live pulsing status dot
            val liveDot = View(context).apply {
                val dotSize = (7 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    rightMargin = (5 * density).toInt()
                }
                val dotBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#10B981")) // Emerald Green
                }
                background = dotBg
            }
            topRow.addView(liveDot)
            this.liveDotView = liveDot

            // Status Tag ("LIVE" / "PAUSED")
            val statusTv = TextView(context).apply {
                text = "LIVE"
                setTextColor(Color.parseColor("#10B981"))
                textSize = 9.5f
                isSingleLine = true
                maxLines = 1
                includeFontPadding = false
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = (6 * density).toInt()
                }
            }
            topRow.addView(statusTv)
            this.statusTagTextView = statusTv

            // Crisp Monospace Timer Display
            val timerTv = TextView(context).apply {
                text = "00:00 / 03:00"
                setTextColor(Color.WHITE)
                textSize = 12f
                isSingleLine = true
                maxLines = 1
                includeFontPadding = false
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            topRow.addView(timerTv)
            this.timerTextView = timerTv

            // Milestone / Reward Pill Badge
            val milestoneTv = TextView(context).apply {
                text = "🎯 3m • +10c"
                setTextColor(Color.parseColor("#F59E0B"))
                textSize = 9.5f
                isSingleLine = true
                maxLines = 1
                includeFontPadding = false
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                val badgeBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6 * density
                    setColor(Color.parseColor("#1E293B"))
                    setStroke((0.8f * density).toInt(), Color.parseColor("#475569"))
                }
                background = badgeBg
                setPadding((6 * density).toInt(), (2.5f * density).toInt(), (6 * density).toInt(), (2.5f * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            topRow.addView(milestoneTv)
            this.milestoneBadgeTextView = milestoneTv
            pillLayout.addView(topRow)

            // ================= PROGRESS BAR: Sleek Live Progress Track =================
            val progressTrack = FrameLayout(context).apply {
                val trackHeight = (3.5f * density).toInt().coerceAtLeast(3)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    trackHeight
                ).apply {
                    topMargin = (6 * density).toInt()
                    bottomMargin = (6 * density).toInt()
                }
                val trackBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4 * density
                    setColor(Color.parseColor("#1E293B"))
                }
                background = trackBg
            }

            val progressFill = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    0,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                val fillBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4 * density
                    setColor(Color.parseColor("#10B981"))
                }
                background = fillBg
            }
            progressTrack.addView(progressFill)
            pillLayout.addView(progressTrack)
            this.progressFillView = progressFill

            // ================= ROW 2: Auto Like (+5c) & Auto Comment (+5c) Chips =================
            val bottomRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val likeBadge = TextView(context).apply {
                text = "👍 Like +5c"
                setTextColor(Color.parseColor("#FBBF24"))
                textSize = 9.5f
                isSingleLine = true
                maxLines = 1
                includeFontPadding = false
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 7 * density
                    setColor(Color.parseColor("#1E293B"))
                    setStroke((0.9f * density).toInt(), Color.parseColor("#F59E0B"))
                }
                background = bg
                setPadding((6 * density).toInt(), (3 * density).toInt(), (6 * density).toInt(), (3 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    rightMargin = (6 * density).toInt()
                }
            }
            bottomRow.addView(likeBadge)
            this.likeBadgeView = likeBadge

            val commentBadge = TextView(context).apply {
                text = "💬 Comment +5c (0/2)"
                setTextColor(Color.parseColor("#38BDF8")) // Sky blue
                textSize = 9.5f
                isSingleLine = true
                maxLines = 1
                includeFontPadding = false
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 7 * density
                    setColor(Color.parseColor("#1E293B"))
                    setStroke((0.9f * density).toInt(), Color.parseColor("#38BDF8"))
                }
                background = bg
                setPadding((6 * density).toInt(), (3 * density).toInt(), (6 * density).toInt(), (3 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1.25f
                )
            }
            bottomRow.addView(commentBadge)
            this.commentBadgeView = commentBadge
            pillLayout.addView(bottomRow)

            // Celebration banner (Animated badge popping below/above the HUD card)
            val celebrationBox = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                visibility = View.GONE
                val cBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12 * density
                    setColor(Color.parseColor("#065F46")) // Rich emerald
                    setStroke((1.2f * density).toInt(), Color.parseColor("#F59E0B"))
                }
                background = cBg
                setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = -(28 * density).toInt()
                }
            }

            val celebTv = TextView(context).apply {
                text = "🎉 +5 COINS UNLOCKED!"
                setTextColor(Color.WHITE)
                textSize = 10.5f
                isSingleLine = true
                maxLines = 1
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            celebrationBox.addView(celebTv)
            this.celebrationContainer = celebrationBox
            this.celebrationText = celebTv

            root.addView(pillLayout)
            root.addView(celebrationBox)

            // Smooth Dragging Listener from ANYWHERE on the HUD card
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var hasMoved = false

            pillLayout.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        hasMoved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()
                        if ( kotlin.math.abs(deltaX) > (5 * density) || kotlin.math.abs(deltaY) > (5 * density)) {
                            hasMoved = true
                        }
                        params.x = (initialX + deltaX).coerceAtLeast(0)
                        params.y = (initialY + deltaY).coerceAtLeast(24)
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (_: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!hasMoved) {
                            // Subtle hint on tap
                            if (!isTaskLiked) {
                                triggerCelebration("👍 Like & 💬 Comment on video for +5c bonus!")
                            }
                        }
                        true
                    }
                    else -> false
                }
            }

            try {
                windowManager.addView(root, params)
                globalAttachedViews.add(root)
                overlayRootView = root
                isAttached = true
                WatchSessionRepository.addLog("Side floating watch pill active on screen!", LogType.SUCCESS)
            } catch (e: Exception) {
                isAttached = false
                WatchSessionRepository.addLog("Failed to add floating timer: ${e.message}", LogType.ERROR)
            }

            registerSessionCallbacks()
        }
    }

    private fun registerSessionCallbacks() {
        // Hook live auto-detection listeners from accessibility & repository
        WatchSessionRepository.onTaskLikeDetected = {
            handleLikeDetected()
        }
        WatchSessionRepository.onVideoAlreadyLikedDetected = { _ ->
            // If video was already liked prior to this watch session, mark badge without awarding duplicate coins
            val activeId = WatchSessionRepository.activeTaskId.value ?: "default_task"
            overlayScope.launch {
                dataStoreManager.markTaskAlreadyLiked(activeId)
                runOnMain {
                    isTaskLiked = true
                    applyLikedBadgeStyle()
                }
            }
        }
        WatchSessionRepository.onTaskCommentDetected = {
            handleCommentDetected()
        }
        WatchSessionRepository.onCommentSheetVisibilityChanged = { isOpen ->
            runOnMain { setSuggestedLockVisible(!isOpen) }
        }
        WatchSessionRepository.onRequestHideOverlay = {
            hideOverlay()
        }
        WatchSessionRepository.onRequestShowOverlay = {
            showOverlay()
        }

        // Continuously collect liked and commented state in real-time so bottom lock bar is always 100% updated!
        overlayScope.launch {
            kotlinx.coroutines.flow.combine(
                dataStoreManager.likedTasksFlow,
                dataStoreManager.commentCountsFlow,
                WatchSessionRepository.activeTaskId
            ) { likedSet, commentMap, activeId ->
                val id = activeId ?: "default_task"
                val alreadyLiked = likedSet.contains(id)
                val cCount = commentMap[id] ?: 0
                Pair(alreadyLiked, cCount)
            }.collect { (alreadyLiked, cCount) ->
                runOnMain {
                    isTaskLiked = alreadyLiked
                    if (alreadyLiked) {
                        applyLikedBadgeStyle()
                    }
                    currentCommentCount = cCount
                    updateCommentBadge()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPlayerDragLockOverlay() {
        if (!Settings.canDrawOverlays(context)) return

        playerLockRootView?.let { prev ->
            try { windowManager.removeView(prev) } catch (_: Exception) {}
            globalAttachedViews.remove(prev)
        }
        playerLockRootView = null

        val screenWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(400)
        val density = context.resources.displayMetrics.density
        val statusBarHeight = (32 * density).toInt()
        val playerHeightPx = statusBarHeight + ((screenWidth * 9) / 16).coerceAtLeast((220 * density).toInt())

        val playerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            playerHeightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

        var startX = 0f
        var startY = 0f
        var isDragging = false
        val touchSlop = (12 * density)

        val root = FrameLayout(context).apply {
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        isDragging = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = Math.abs(event.rawX - startX)
                        val dy = Math.abs(event.rawY - startY)
                        if (dy > touchSlop || dx > touchSlop) {
                            if (!isDragging) {
                                isDragging = true
                                try {
                                    android.widget.Toast.makeText(
                                        context,
                                        "🔒 Video player locked! (Minimizing / drag down disabled during task)",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } catch (_: Exception) {}
                            }
                            true // Intercept & consume drag gesture completely
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasDragging = isDragging
                        isDragging = false
                        wasDragging
                    }
                    else -> false
                }
            }
        }

        try {
            windowManager.addView(root, playerParams)
            globalAttachedViews.add(root)
            playerLockRootView = root
            WatchSessionRepository.addLog("Player drag-down freeze lock active!", LogType.SUCCESS)
        } catch (_: Exception) {
            playerLockRootView = null
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showSuggestedVideosLockOverlay() {
        if (!Settings.canDrawOverlays(context)) return

        suggestedLockRootView?.let { prev ->
            try { windowManager.removeView(prev) } catch (_: Exception) {}
            globalAttachedViews.remove(prev)
        }
        suggestedLockRootView = null

        val density = context.resources.displayMetrics.density
        val realMetrics = android.util.DisplayMetrics()
        val realScreenHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                windowManager.currentWindowMetrics.bounds.height()
            } catch (_: Exception) {
                context.resources.displayMetrics.heightPixels
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                windowManager.defaultDisplay.getRealMetrics(realMetrics)
                realMetrics.heightPixels
            } catch (_: Exception) {
                context.resources.displayMetrics.heightPixels
            }
        }.coerceAtLeast(800)

        val lockParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 0
        }

        val root = FrameLayout(context).apply {
            // Transparent container so only the styled lock card is rendered without any blank filler
            setBackgroundColor(Color.TRANSPARENT)
            // Absolute Lock: Intercept & consume 100% of touches, scrolls, and clicks so underlying YouTube suggestions are completely locked!
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    try {
                        android.widget.Toast.makeText(
                            context,
                            "🔒 Suggested videos locked during task! Please watch the main video above.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } catch (_: Exception) {}
                }
                true
            }
        }

        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(
                (18 * density).toInt(),
                (14 * density).toInt(),
                (18 * density).toInt(),
                (16 * density).toInt()
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#FB0A0F1E"), // Deep Obsidian with top gold border
                    Color.parseColor("#FC060810")
                )
            ).apply {
                cornerRadii = floatArrayOf(
                    22 * density, 22 * density, // top-left
                    22 * density, 22 * density, // top-right
                    0f, 0f, 0f, 0f
                )
                setStroke((1.4f * density).toInt(), Color.parseColor("#F59E0B"))
            }
            elevation = 20 * density
        }

        // Notch / Indicator bar
        val notch = View(context).apply {
            layoutParams = LinearLayout.LayoutParams((38 * density).toInt(), (4 * density).toInt()).apply {
                bottomMargin = (12 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2 * density
                setColor(Color.parseColor("#475569"))
            }
        }
        cardLayout.addView(notch)

        // Lock Banner Header Row
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }

        val lockIconBadge = TextView(context).apply {
            text = "🔒"
            textSize = 15f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                (28 * density).toInt(),
                (28 * density).toInt()
            ).apply {
                rightMargin = (8 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E293B"))
                setStroke((1 * density).toInt(), Color.parseColor("#F59E0B"))
            }
        }
        headerRow.addView(lockIconBadge)

        val headerTitle = TextView(context).apply {
            text = "SUGGESTED VIDEOS LOCKED"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        headerRow.addView(headerTitle)

        val shieldStatusBadge = TextView(context).apply {
            text = "🛡️ Lock Active"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding((8 * density).toInt(), (3 * density).toInt(), (8 * density).toInt(), (3 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#064E3B"))
                setStroke((0.8f * density).toInt(), Color.parseColor("#10B981"))
            }
        }
        headerRow.addView(shieldStatusBadge)
        cardLayout.addView(headerRow)

        // Instruction description
        val descTv = TextView(context).apply {
            text = "Aapka target video watch task active hai. Niche ke recommended videos par click aur scroll puri tarah lock hai. Upar main video dekhein aur Like & Comment karke coins earn karein."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11.5f
            gravity = Gravity.CENTER
            setLineSpacing(2 * density, 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
        }
        cardLayout.addView(descTv)

        // Target Task & Live Timer Status Card
        val infoCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setColor(Color.parseColor("#111827"))
                setStroke((1 * density).toInt(), Color.parseColor("#1F2937"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val targetTitleTv = TextView(context).apply {
            val title = WatchSessionRepository.targetTaskTitle.value ?: "YouTube Video Task"
            text = "🎬 $title"
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * density).toInt()
            }
        }
        infoCard.addView(targetTitleTv)

        val timerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val timerTv = TextView(context).apply {
            text = "⏱️ 00:00 / 03:00"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        timerRow.addView(timerTv)
        suggestedLockTimerTextView = timerTv

        val liveTag = TextView(context).apply {
            text = "🟢 LIVE WATCHING"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        timerRow.addView(liveTag)
        suggestedLockStatusTagView = liveTag
        infoCard.addView(timerRow)

        // Mini progress fill
        val progressTrack = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (3.5f * density).toInt().coerceAtLeast(3)
            ).apply {
                topMargin = (6 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 3 * density
                setColor(Color.parseColor("#1E293B"))
            }
        }

        val progressFill = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 3 * density
                setColor(Color.parseColor("#10B981"))
            }
        }
        progressTrack.addView(progressFill)
        infoCard.addView(progressTrack)
        suggestedLockProgressFillView = progressFill

        // Reward pills
        val rewardRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val r1 = TextView(context).apply {
            text = "🪙 3m: +10c"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 9.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (10 * density).toInt()
            }
        }
        rewardRow.addView(r1)

        val r2 = TextView(context).apply {
            textSize = 9.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (10 * density).toInt()
            }
            if (isTaskLiked) {
                text = "✓ Liked (+5c)"
                setTextColor(Color.parseColor("#10B981"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#064E3B"))
                    setStroke((0.9f * density).toInt(), Color.parseColor("#10B981"))
                }
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            } else {
                text = "👍 Like: +5c"
                setTextColor(Color.parseColor("#FBBF24"))
            }
        }
        rewardRow.addView(r2)
        suggestedLockLikePillView = r2

        val r3 = TextView(context).apply {
            textSize = 9.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (currentCommentCount > 0) {
                text = "✓ Comment: $currentCommentCount/2 (+5c)"
                setTextColor(Color.parseColor("#10B981"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#064E3B"))
                    setStroke((0.9f * density).toInt(), Color.parseColor("#10B981"))
                }
                setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            } else {
                text = "💬 Comment: +5c"
                setTextColor(Color.parseColor("#38BDF8"))
            }
        }
        rewardRow.addView(r3)
        suggestedLockCommentPillView = r3
        infoCard.addView(rewardRow)

        cardLayout.addView(infoCard)

        root.addView(cardLayout)

        try {
            if (WatchSessionRepository.isCommentSheetOpen) {
                root.visibility = View.GONE
            }
            windowManager.addView(root, lockParams)
            globalAttachedViews.add(root)
            suggestedLockRootView = root
            WatchSessionRepository.addLog("Suggested videos area locked under comments!", LogType.SUCCESS)
        } catch (_: Exception) {
            suggestedLockRootView = null
        }
    }

    /**
     * Automatic Like Detection Handler:
     * When user genuinely likes the target video in YouTube for the first time,
     * award +5 coins ONCE, update DataStore, and update badge style to emerald "✓ Liked (+5c)".
     */
    private fun handleLikeDetected() {
        val taskId = WatchSessionRepository.activeTaskId.value ?: "default_task"
        val taskTitle = WatchSessionRepository.targetTaskTitle.value ?: "YouTube Video"

        overlayScope.launch {
            val result = dataStoreManager.recordTaskLike(taskId, taskTitle)
            runOnMain {
                isTaskLiked = true
                applyLikedBadgeStyle()
                if (result.first) {
                    triggerCelebration("✓ +5 Coins Added (Like)")
                    android.widget.Toast.makeText(
                        context,
                        "+5 Coins added for liking the video",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    WatchSessionRepository.addLog("Auto-detected genuine YouTube Like! +5 coins added (1-time reward).", LogType.SUCCESS)
                }
            }
        }
    }

    @Volatile
    private var lastRecordedCommentTime: Long = 0L

    private fun handleCommentDetected() {
        val now = System.currentTimeMillis()
        if (now - lastRecordedCommentTime < 15_000L) {
            return
        }
        lastRecordedCommentTime = now
        val taskId = WatchSessionRepository.activeTaskId.value ?: "default_task"
        val taskTitle = WatchSessionRepository.targetTaskTitle.value ?: "YouTube Video"

        overlayScope.launch {
            val result = dataStoreManager.recordTaskComment(taskId, taskTitle)
            val updatedCounts = dataStoreManager.commentCountsFlow.first()
            val newCount = (updatedCounts[taskId] ?: (currentCommentCount + 1)).coerceAtMost(2)
            runOnMain {
                currentCommentCount = newCount
                updateCommentBadge()
                if (result.first) {
                    triggerCelebration("✓ +5 Coins Added (Comment)")
                    android.widget.Toast.makeText(
                        context,
                        "+5 Coins added for Comment #$currentCommentCount",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    WatchSessionRepository.addLog("Auto-detected genuine YouTube Comment! +5 coins added (#$currentCommentCount).", LogType.SUCCESS)
                }
            }
        }
    }

    private fun applyLikedBadgeStyle() {
        likeBadgeView?.text = "✓ Liked (+5c)"
        likeBadgeView?.setTextColor(Color.parseColor("#10B981"))
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * density
            setColor(Color.parseColor("#064E3B")) // Emerald dark
            setStroke((1 * density).toInt(), Color.parseColor("#10B981"))
        }
        likeBadgeView?.background = bg

        suggestedLockLikePillView?.let { pill ->
            pill.text = "✓ Liked (+5c)"
            pill.setTextColor(Color.parseColor("#10B981"))
            pill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#064E3B"))
                setStroke((0.9f * density).toInt(), Color.parseColor("#10B981"))
            }
            pill.setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
        }
    }

    private fun updateCommentBadge() {
        val commentPillText = if (currentCommentCount >= 2) "✓ Comments (+10c)" else if (currentCommentCount > 0) "✓ Comment: $currentCommentCount/2 (+5c)" else "💬 Comment: +5c"
        if (currentCommentCount >= 2) {
            commentBadgeView?.text = "✓ Comments (+10c)"
            commentBadgeView?.setTextColor(Color.parseColor("#10B981"))
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#064E3B"))
                setStroke((1 * density).toInt(), Color.parseColor("#10B981"))
            }
            commentBadgeView?.background = bg
        } else if (currentCommentCount > 0) {
            commentBadgeView?.text = "💬 +5c ($currentCommentCount/2)"
        }

        suggestedLockCommentPillView?.let { pill ->
            if (currentCommentCount > 0) {
                pill.text = commentPillText
                pill.setTextColor(Color.parseColor("#10B981"))
                pill.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#064E3B"))
                    setStroke((0.9f * density).toInt(), Color.parseColor("#10B981"))
                }
                pill.setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            } else {
                pill.text = "💬 Comment: +5c"
                pill.setTextColor(Color.parseColor("#38BDF8"))
                pill.background = null
                pill.setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
            }
        }
    }

    fun isOverlayAttached(): Boolean = isAttached && (overlayRootView != null || suggestedLockRootView != null)

    @SuppressLint("SetTextI18n")
    fun updateProgress(
        watchedMillis: Long,
        requiredMillis: Long,
        milestone: WatchDurationTier?,
        isPaused: Boolean = false
    ) {
        runOnMain {
            if (!isAttached) {
                return@runOnMain
            }

            val watchedStr = TimeFormatter.formatMillisToMmSs(watchedMillis)
            val reqStr = TimeFormatter.formatMillisToMmSs(requiredMillis)
            val watchedSecs = (watchedMillis / 1000).toInt()
            val progressFraction = if (requiredMillis > 0) {
                (watchedMillis.toFloat() / requiredMillis.toFloat()).coerceIn(0.03f, 1f)
            } else 0.05f

            progressFillView?.let { fill ->
                val parentWidth = (fill.parent as? View)?.width ?: 0
                if (parentWidth > 0) {
                    val lp = fill.layoutParams
                    lp.width = (parentWidth * progressFraction).toInt().coerceAtLeast((8 * density).toInt())
                    fill.layoutParams = lp
                }
            }

            if (isPaused) {
                timerTextView?.text = "$watchedStr / $reqStr"
                timerTextView?.setTextColor(Color.parseColor("#FDE68A"))
                statusTagTextView?.text = "PAUSED"
                statusTagTextView?.setTextColor(Color.parseColor("#F59E0B"))
                (liveDotView?.background as? GradientDrawable)?.setColor(Color.parseColor("#F59E0B"))
                (progressFillView?.background as? GradientDrawable)?.setColor(Color.parseColor("#F59E0B"))

                suggestedLockTimerTextView?.text = "⏱️ $watchedStr / $reqStr"
                suggestedLockStatusTagView?.text = "⏸ PAUSED"
                suggestedLockStatusTagView?.setTextColor(Color.parseColor("#F59E0B"))
            } else {
                timerTextView?.text = "$watchedStr / $reqStr"
                timerTextView?.setTextColor(Color.WHITE)
                statusTagTextView?.text = "LIVE"
                statusTagTextView?.setTextColor(Color.parseColor("#10B981"))
                (liveDotView?.background as? GradientDrawable)?.setColor(Color.parseColor("#10B981"))
                (progressFillView?.background as? GradientDrawable)?.setColor(Color.parseColor("#10B981"))

                suggestedLockTimerTextView?.text = "⏱️ $watchedStr / $reqStr"
                suggestedLockStatusTagView?.text = "🟢 LIVE WATCHING"
                suggestedLockStatusTagView?.setTextColor(Color.parseColor("#10B981"))
            }

            suggestedLockProgressFillView?.let { fill ->
                val parentWidth = (fill.parent as? View)?.width ?: 0
                if (parentWidth > 0) {
                    val lp = fill.layoutParams
                    lp.width = (parentWidth * progressFraction).toInt().coerceAtLeast((6 * density).toInt())
                    fill.layoutParams = lp
                }
            }

            if (milestone != null) {
                milestoneBadgeTextView?.text = "🏆 +${milestone.coins}c"
                milestoneBadgeTextView?.setTextColor(Color.parseColor("#10B981"))

                if (milestone != lastCelebratedTier) {
                    lastCelebratedTier = milestone
                    triggerCelebration("🪙 +${milestone.coins} COINS UNLOCKED! 🎉")
                }
            } else {
                val remainSec = (180 - watchedSecs).coerceAtLeast(0)
                if (remainSec > 0) {
                    milestoneBadgeTextView?.text = if (isPaused) "⏸ ${remainSec}s left" else "🎯 ${remainSec}s → +10c"
                    milestoneBadgeTextView?.setTextColor(Color.parseColor("#F59E0B"))
                } else {
                    milestoneBadgeTextView?.text = "🏆 +10c Ready"
                    milestoneBadgeTextView?.setTextColor(Color.parseColor("#10B981"))
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun showCoinAddedCelebration(coins: Int, message: String = "+$coins COINS ADDED!") {
        runOnMain {
            milestoneBadgeTextView?.text = "🏆 +${coins}c Added"
            milestoneBadgeTextView?.setTextColor(Color.parseColor("#10B981"))
            triggerCelebration("🪙 +$coins COINS ADDED! 🎉")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun triggerCelebration(message: String) {
        val container = celebrationContainer ?: return
        val text = celebrationText ?: return

        text.text = message
        container.visibility = View.VISIBLE
        container.alpha = 0f
        container.scaleX = 0.5f
        container.scaleY = 0.5f

        val scaleX = ObjectAnimator.ofFloat(container, "scaleX", 0.5f, 1.15f, 1.0f)
        val scaleY = ObjectAnimator.ofFloat(container, "scaleY", 0.5f, 1.15f, 1.0f)
        val alpha = ObjectAnimator.ofFloat(container, "alpha", 0f, 1.0f)

        val set = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            interpolator = OvershootInterpolator(1.4f)
            duration = 400
        }
        set.start()

        container.postDelayed({
            if (isAttached && container.visibility == View.VISIBLE) {
                val fadeOut = ObjectAnimator.ofFloat(container, "alpha", 1f, 0f).apply {
                    duration = 300
                }
                fadeOut.start()
                container.postDelayed({
                    container.visibility = View.GONE
                }, 300)
            }
        }, 3200)
    }

    @SuppressLint("SetTextI18n")
    fun showTaskIncompletePopup(message: String, onDismissed: (() -> Unit)? = null) {
        runOnMain {
            // Remove all floating timer pills and previous popups first
            removeAllGlobalViews(windowManager)
            overlayRootView?.let { root ->
                try {
                    windowManager.removeView(root)
                } catch (_: Exception) {}
            }
            overlayRootView = null
            isAttached = false

            incompletePopupView?.let { prev ->
                try {
                    windowManager.removeView(prev)
                } catch (_: Exception) {}
            }
            incompletePopupView = null

            if (!Settings.canDrawOverlays(context) || WatchSessionRepository.isAppInForeground) {
                onDismissed?.invoke()
                return@runOnMain
            }

            density = context.resources.displayMetrics.density
            val screenWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(600)
            val cardWidth = (screenWidth * 0.88f).toInt().coerceAtMost((360 * density).toInt())

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            val scrimRoot = FrameLayout(context).apply {
                setBackgroundColor(Color.parseColor("#B3000000"))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            val dialogCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = (22 * density).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 20 * density
                    setColor(Color.parseColor("#0F172A"))
                    setStroke((1.5f * density).toInt(), Color.parseColor("#334155"))
                }
                elevation = 24 * density
                layoutParams = FrameLayout.LayoutParams(
                    cardWidth,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }
            }

            val iconBadge = TextView(context).apply {
                text = "ℹ️"
                textSize = 24f
                gravity = Gravity.CENTER
                val badgeSize = (54 * density).toInt()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#1E293B"))
                }
                layoutParams = LinearLayout.LayoutParams(badgeSize, badgeSize).apply {
                    bottomMargin = (12 * density).toInt()
                }
            }
            dialogCard.addView(iconBadge)

            try {
                android.widget.Toast.makeText(context, "⚠️ Task Incomplete!\n$message", android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}

            val titleTv = TextView(context).apply {
                text = "⚠️ Task Incomplete"
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * density).toInt()
                }
            }
            dialogCard.addView(titleTv)

            val msgTv = TextView(context).apply {
                text = message
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 13.5f
                gravity = Gravity.CENTER
                setLineSpacing(4 * density, 1f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (18 * density).toInt()
                }
            }
            dialogCard.addView(msgTv)

            val okBtn = TextView(context).apply {
                text = "OK"
                setTextColor(Color.BLACK)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12 * density
                    setColor(Color.parseColor("#F59E0B"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    dismissIncompletePopup()
                    WatchSessionRepository.dismissTaskIncompleteMessage()
                    onDismissed?.invoke()
                }
            }
            dialogCard.addView(okBtn)

            scrimRoot.addView(dialogCard)

            try {
                windowManager.addView(scrimRoot, params)
                globalAttachedViews.add(scrimRoot)
                incompletePopupView = scrimRoot
                dialogCard.postDelayed({
                    if (incompletePopupView === scrimRoot) {
                        dismissIncompletePopup()
                        WatchSessionRepository.dismissTaskIncompleteMessage()
                        onDismissed?.invoke()
                    }
                }, 7500L)
            } catch (_: Exception) {
                incompletePopupView = null
                onDismissed?.invoke()
            }
        }
    }

    fun dismissIncompletePopup() {
        runOnMain {
            incompletePopupView?.let { popup ->
                try {
                    windowManager.removeView(popup)
                } catch (_: Exception) {}
                globalAttachedViews.remove(popup)
            }
            incompletePopupView = null
        }
    }

    @SuppressLint("SetTextI18n")
    fun showOrUpdateSearchLoadingOverlay(
        @Suppress("UNUSED_PARAMETER") title: String,
        @Suppress("UNUSED_PARAMETER") channel: String,
        @Suppress("UNUSED_PARAMETER") statusText: String = "Opening..."
    ) {
        runOnMain {
            val a11yService = YouTubeLiveSearchService.instance
            val canDrawAppOverlay = Settings.canDrawOverlays(context)
            if (a11yService == null && !canDrawAppOverlay) return@runOnMain

            if (searchLoadingOverlayView != null) {
                searchLoadingStatusTextView?.text = "Opening..."
                return@runOnMain
            }

            val targetWm = if (canDrawAppOverlay) {
                windowManager
            } else {
                (a11yService?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: windowManager
            }
            val overlayType = if (canDrawAppOverlay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else if (a11yService != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val density = context.resources.displayMetrics.density
            val isFullScreen = try {
                val prefs = context.getSharedPreferences("watchearn_prefs", Context.MODE_PRIVATE)
                prefs.getBoolean("full_screen_opening_overlay", true)
            } catch (_: Exception) {
                true
            }

            val screenWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(400)
            val params = if (isFullScreen) {
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE
                ).apply {
                    gravity = Gravity.FILL
                }
            } else {
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = (48 * density).toInt()
                }
            }

            val wrapper = FrameLayout(context).apply {
                if (isFullScreen) {
                    // 100% Solid Pitch Dark - ZERO visibility of YouTube underneath!
                    setBackgroundColor(Color.parseColor("#FF040711"))
                }
            }

            if (isFullScreen) {
                val cardWidth = (screenWidth * 0.90f).toInt().coerceAtMost((360 * density).toInt())
                val centerCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        cardWidth,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER
                    }
                    setPadding(
                        (24 * density).toInt(),
                        (26 * density).toInt(),
                        (24 * density).toInt(),
                        (24 * density).toInt()
                    )
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(
                            Color.parseColor("#FF0F172A"),
                            Color.parseColor("#FF090D18")
                        )
                    ).apply {
                        cornerRadius = 24 * density
                        setStroke((1.2f * density).toInt(), Color.parseColor("#1E293B"))
                    }
                    elevation = 24 * density
                }

                // Sleek brand badge
                val brandBadge = TextView(context).apply {
                    text = "👑 KINGO KING • VIDEO REWARD"
                    setTextColor(Color.parseColor("#FBBF24"))
                    textSize = 10f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(
                        (14 * density).toInt(),
                        (5 * density).toInt(),
                        (14 * density).toInt(),
                        (5 * density).toInt()
                    )
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1C1917"))
                        cornerRadius = 14 * density
                        setStroke((1 * density).toInt(), Color.parseColor("#78350F"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (16 * density).toInt()
                    }
                }
                centerCard.addView(brandBadge)

                val spinner = android.widget.ProgressBar(context).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                    layoutParams = LinearLayout.LayoutParams(
                        (38 * density).toInt(),
                        (38 * density).toInt()
                    ).apply {
                        bottomMargin = (14 * density).toInt()
                    }
                }
                centerCard.addView(spinner)

                val statusTv = TextView(context).apply {
                    text = "Opening Target Video"
                    setTextColor(Color.WHITE)
                    textSize = 17f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                }
                searchLoadingStatusTextView = statusTv
                centerCard.addView(statusTv)

                val subtitleTv = TextView(context).apply {
                    text = "Verifying player & setting up reward timer..."
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 11.5f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (3 * density).toInt()
                    }
                }
                centerCard.addView(subtitleTv)

                // Dedicated, clean title card
                val titleBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(
                        (14 * density).toInt(),
                        (12 * density).toInt(),
                        (14 * density).toInt(),
                        (12 * density).toInt()
                    )
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#060A12"))
                        cornerRadius = 14 * density
                        setStroke((1 * density).toInt(), Color.parseColor("#1E293B"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (16 * density).toInt()
                    }
                }

                val subTv = TextView(context).apply {
                    text = if (title.isNotBlank()) "🎬 ${title.take(70)}" else "🎬 Preparing YouTube video player..."
                    setTextColor(Color.parseColor("#F1F5F9"))
                    textSize = 12.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                searchLoadingTitleTextView = subTv
                titleBox.addView(subTv)

                if (channel.isNotBlank()) {
                    val channelTv = TextView(context).apply {
                        text = "📺 Channel: $channel"
                        setTextColor(Color.parseColor("#38BDF8"))
                        textSize = 11.5f
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = (4 * density).toInt()
                        }
                    }
                    titleBox.addView(channelTv)
                }
                centerCard.addView(titleBox)

                val footerTv = TextView(context).apply {
                    text = "🪙 Coins start tracking automatically once playback begins"
                    setTextColor(Color.parseColor("#FCD34D"))
                    textSize = 11f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (16 * density).toInt()
                    }
                }
                centerCard.addView(footerTv)

                wrapper.addView(centerCard)
            } else {
                val pillCard = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        (18 * density).toInt(),
                        (10 * density).toInt(),
                        (20 * density).toInt(),
                        (10 * density).toInt()
                    )
                    background = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(
                            Color.parseColor("#F2141229"),
                            Color.parseColor("#F21E1B3A")
                        )
                    ).apply {
                        cornerRadius = 28 * density
                        setStroke((1 * density).toInt(), Color.parseColor("#80A78BFA"))
                    }
                    elevation = 10 * density
                }

                val spinner = android.widget.ProgressBar(context).apply {
                    isIndeterminate = true
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#38BDF8"))
                    layoutParams = LinearLayout.LayoutParams(
                        (20 * density).toInt(),
                        (20 * density).toInt()
                    ).apply {
                        rightMargin = (10 * density).toInt()
                    }
                }
                pillCard.addView(spinner)

                val statusTv = TextView(context).apply {
                    text = "Opening..."
                    setTextColor(Color.WHITE)
                    textSize = 13.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                searchLoadingStatusTextView = statusTv
                pillCard.addView(statusTv)
                wrapper.addView(pillCard)
            }

            try {
                targetWm.addView(wrapper, params)
                searchLoadingOverlayView = wrapper
                searchLoadingOverlayWm = targetWm
            } catch (_: Exception) {
                searchLoadingOverlayView = null
                searchLoadingOverlayWm = null
            }
        }
    }

    fun hideSearchLoadingOverlay() {
        runOnMain {
            searchLoadingOverlayView?.let { v ->
                val wm = searchLoadingOverlayWm ?: windowManager
                try {
                    wm.removeViewImmediate(v)
                } catch (_: Exception) {
                    try {
                        wm.removeView(v)
                    } catch (_: Exception) {}
                }
            }
            searchLoadingOverlayView = null
            searchLoadingStatusTextView = null
            searchLoadingTitleTextView = null
            searchLoadingChannelTextView = null
            searchLoadingOverlayWm = null
        }
    }

    fun hideOverlay() {
        runOnMain {
            hideSearchLoadingOverlay()
            removeAllGlobalViews(windowManager)
            overlayRootView?.let { root ->
                try {
                    windowManager.removeView(root)
                } catch (_: Exception) {}
            }
            overlayRootView = null
            suggestedLockRootView?.let { lockView ->
                try { windowManager.removeView(lockView) } catch (_: Exception) {}
            }
            suggestedLockRootView = null
            playerLockRootView?.let { pLock ->
                try { windowManager.removeView(pLock) } catch (_: Exception) {}
            }
            playerLockRootView = null
            suggestedLockTimerTextView = null
            suggestedLockProgressFillView = null
            suggestedLockStatusTagView = null
            incompletePopupView?.let { popup ->
                try {
                    windowManager.removeView(popup)
                } catch (_: Exception) {}
            }
            incompletePopupView = null
            isAttached = false
            lastCelebratedTier = null
            currentCommentCount = 0
            isTaskLiked = false
        }
    }
}
