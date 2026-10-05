package com.example.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.LogType
import com.example.repository.WatchSessionRepository
import com.example.util.TitleMatcher
import kotlinx.coroutines.launch

class YouTubeLiveSearchService : AccessibilityService() {

    enum class LiveSearchPhase {
        IDLE,
        OPEN_SEARCH_BAR,
        TYPE_QUERY,
        SUBMIT_QUERY,
        FIND_AND_CLICK_VIDEO,
        COMPLETED
    }

    companion object {
        val CHROME_LABELS = setOf(
            "subscribe", "subscribed", "join", "share", "remix", "download",
            "clip", "save", "report", "comments", "more", "...more", "show more", "show less",
            "play video", "pause video", "replay video", "autoplay is on", "autoplay is off",
            "mute", "unmute", "full screen", "enter full screen", "exit full screen",
            "collapse", "minimize", "close", "sponsored", "visit site", "live chat",
            "next video", "previous video", "settings", "captions", "video player",
            "hide controls", "show controls", "more options", "expand description",
            "collapse description", "description", "seek slider", "skip ad", "skip ads",
            "pull up for precise seeking", "slide left or right to seek", "release to cancel",
            "more videos", "tap to unmute", "double-tap to seek", "playing next", "auto-dubbed"
        )

        @Volatile
        var instance: YouTubeLiveSearchService? = null
            private set

        @Volatile
        var isServiceConnected: Boolean = false
            private set

        fun closeCurrentCommentOrChatSheet(): Boolean {
            val srv = instance ?: return false
            return try {
                srv.performGlobalAction(GLOBAL_ACTION_BACK)
            } catch (_: Exception) {
                false
            }
        }

        @Volatile
        var isYouTubeInForeground: Boolean = false

        @Volatile
        var isVideoExplicitlyPaused: Boolean = false

        @Volatile
        var lastExplicitPauseTime: Long = 0L

        @Volatile
        var lastExplicitPlayClickTime: Long = 0L

        @Volatile
        var isPlayButtonCurrentlyVisible: Boolean = false

        @Volatile
        var targetSearchTitle: String? = null

        @Volatile
        var targetSearchChannel: String? = null

        @Volatile
        var targetChannelHandle: String? = null

        @Volatile
        var targetVideoUrl: String? = null

        @Volatile
        var targetVideoId: String? = null

        @Volatile
        var targetVideoDurationSeconds: Int = 0

        @Volatile
        var isTargetLiveStream: Boolean = false

        private val rejectedSameTitleDurations = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

        @Volatile
        var hasClickedTarget: Boolean = false

        @Volatile
        var isWatchPlayerConfirmedOpen: Boolean = false

        @Volatile
        var lastClickTime: Long = 0L

        @Volatile
        var currentPhase: LiveSearchPhase = LiveSearchPhase.IDLE

        @Volatile
        private var wrongVideoStrikeCount = 0

        @Volatile
        private var notInYouTubeStrikeCount = 0

        @Volatile
        private var lastWatchHeaderCheckTime = 0L

        @Volatile
        private var lockedWatchPageTitle: String? = null

        @Volatile
        private var hasTypedCommentText: Boolean = false

        @Volatile
        private var lastTypedCommentText: String = ""

        @Volatile
        private var lastTypedCommentTime: Long = 0L

        @Volatile
        private var wasCommentComposerOpen: Boolean = false

        @Volatile
        private var wasCommentEditTextActive: Boolean = false

        @Volatile
        private var lastCommentComposerOpenTime: Long = 0L

        @Volatile
        private var lastCommentClickTime: Long = 0L

        @Volatile
        private var lastCommentCancelClickTime: Long = 0L

        @Volatile
        private var lastCommentRewardTriggerTime: Long = 0L

        @Volatile
        private var wasTargetVideoLikedInSession: Boolean = false

        @Volatile
        private var lastLikeClickTime: Long = 0L

        @Volatile
        private var lastProductClickTime: Long = 0L

        @Volatile
        private var isProductListOpen: Boolean = false

        private val rewardedLikedTaskIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        private var scrollAttempts = 0

        @Volatile
        private var lastSearchStepTime: Long = 0L

        @Volatile
        private var lastTypedQueryTime: Long = 0L

        @Volatile
        private var submitQueryAttempts: Int = 0

        @Volatile
        private var openSearchBarAttempts: Int = 0

        @Volatile
        private var hasLaunchedSearchIntentFallback: Boolean = false

        @Volatile
        private var hasTappedRecentFilterChip: Boolean = false

        @Volatile
        private var hasSearchedWithHandleOrQuotes: Boolean = false

        @Volatile
        private var hasOpenedChannelPage: Boolean = false

        @Volatile
        private var hasTappedChannelVideosTab: Boolean = false

        @Volatile
        private var searchStrategyStage: Int = 0

        @Volatile
        private var overrideSearchQuery: String? = null

        @Volatile
        private var lastSearchActionTimestamp: Long = 0L

        @Volatile
        var isSearchOverlayActive: Boolean = false
            private set

        @Volatile
        var searchOverlayStatusText: String = "Searching & opening video..."
            private set

        @Volatile
        private var searchOverlayStartedAtMillis: Long = 0L

        fun dismissSearchOverlay() {
            isSearchOverlayActive = false
        }

        private val searchDriverHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private val searchDriverRunnable = object : Runnable {
            override fun run() {
                try {
                    val title = targetSearchTitle
                    if (!hasClickedTarget &&
                        !title.isNullOrBlank() &&
                        currentPhase != LiveSearchPhase.IDLE &&
                        currentPhase != LiveSearchPhase.COMPLETED
                    ) {
                        val svc = instance
                        if (svc != null && isServiceConnected) {
                            val root = svc.getYouTubeRootNode() ?: try { svc.rootInActiveWindow } catch (_: Exception) { null }
                            if (root != null && root.packageName?.toString() == "com.google.android.youtube") {
                                isYouTubeInForeground = true
                                WatchSessionRepository.hasLeftAppForYouTube = true
                                svc.driveLiveSearchStep(root, title)
                            }
                        }
                        searchDriverHandler.postDelayed(this, 420L)
                    }
                } catch (_: Exception) {
                    searchDriverHandler.postDelayed(this, 500L)
                }
            }
        }

        fun triggerSearchResultsIntentFromService(context: android.content.Context) {
            val title = targetSearchTitle?.trim().orEmpty()
            if (title.isBlank() || hasClickedTarget || isWatchPlayerConfirmedOpen) return
            val channel = targetSearchChannel?.trim().orEmpty()
            val hasRealChannel = channel.isNotBlank() &&
                    !channel.equals("YouTube Creator", ignoreCase = true) &&
                    !channel.equals("YouTube Channel", ignoreCase = true)
            val query = overrideSearchQuery?.trim()?.takeIf { it.isNotBlank() }
                ?: if (hasRealChannel && !title.contains(channel, ignoreCase = true)) "$title $channel" else title
            try {
                val searchIntent = com.example.util.PermissionHelper.openYouTubeSearchResultsIntent(context, query)
                context.startActivity(searchIntent)
                hasLaunchedSearchIntentFallback = true
                lastSearchActionTimestamp = System.currentTimeMillis()
                currentPhase = LiveSearchPhase.FIND_AND_CLICK_VIDEO
                searchDriverHandler.removeCallbacks(searchDriverRunnable)
                searchDriverHandler.postDelayed(searchDriverRunnable, 450L)
                WatchSessionRepository.addLog(
                    "YouTube Search: Opened YouTube search results for \"$query\"",
                    LogType.INFO
                )
            } catch (_: Exception) {}
        }

        fun resetMonitoringCounters() {
            wrongVideoStrikeCount = 0
            notInYouTubeStrikeCount = 0
            lastWatchHeaderCheckTime = 0L
            lastExplicitPauseTime = 0L
            lastExplicitPlayClickTime = 0L
            isPlayButtonCurrentlyVisible = false
            lockedWatchPageTitle = null
            hasTypedCommentText = false
            lastTypedCommentText = ""
            lastTypedCommentTime = 0L
            wasCommentComposerOpen = false
            wasCommentEditTextActive = false
            lastCommentComposerOpenTime = 0L
            lastCommentClickTime = 0L
            lastCommentCancelClickTime = 0L
            lastCommentRewardTriggerTime = 0L
            wasTargetVideoLikedInSession = false
            lastLikeClickTime = 0L
            lastProductClickTime = 0L
            isProductListOpen = false
        }

        fun prepareForDirectWatch(title: String, channel: String?, videoUrl: String? = null, videoId: String? = null) {
            // Strictly route through organic YouTube Search / Browse Features — never direct URL watch
            armSearchTrigger(title, channel, null, null, null)
        }

        fun armSearchTrigger(
            title: String,
            channel: String?,
            videoUrl: String? = null,
            videoId: String? = null,
            channelHandle: String? = null,
            videoDurationSeconds: Int = 0,
            isLiveStream: Boolean = false
        ) {
            targetSearchTitle = title
            targetSearchChannel = channel
            targetChannelHandle = channelHandle
            targetVideoUrl = videoUrl
            targetVideoId = videoId?.trim()?.takeIf { it.isNotBlank() } ?: TitleMatcher.extractVideoId(videoUrl)
            isTargetLiveStream = isLiveStream || (videoUrl?.contains("/live/", ignoreCase = true) == true)
            targetVideoDurationSeconds = if (isTargetLiveStream) 0 else videoDurationSeconds
            rejectedSameTitleDurations.clear()
            hasClickedTarget = false
            isWatchPlayerConfirmedOpen = false
            scrollAttempts = 0
            lastClickTime = 0L
            lastSearchStepTime = 0L
            lastTypedQueryTime = 0L
            submitQueryAttempts = 0
            openSearchBarAttempts = 0
            hasLaunchedSearchIntentFallback = false
            hasTappedRecentFilterChip = false
            hasSearchedWithHandleOrQuotes = false
            hasOpenedChannelPage = false
            hasTappedChannelVideosTab = false
            searchStrategyStage = 0
            lastSearchActionTimestamp = 0L
            isVideoExplicitlyPaused = false
            resetMonitoringCounters()
            currentPhase = LiveSearchPhase.OPEN_SEARCH_BAR
            isSearchOverlayActive = true
            searchOverlayStatusText = "Opening..."
            searchOverlayStartedAtMillis = System.currentTimeMillis()
            searchDriverHandler.removeCallbacks(searchDriverRunnable)
            searchDriverHandler.postDelayed(searchDriverRunnable, 400L)
            WatchSessionRepository.addLog(
                "Organic YouTube Search/Browse armed for: \"$title\" [ID: ${targetVideoId ?: "N/A"}, Duration: ${if (videoDurationSeconds > 0) "${videoDurationSeconds}s" else "auto"}]",
                LogType.INFO
            )
        }

        fun disarm() {
            searchDriverHandler.removeCallbacks(searchDriverRunnable)
            isSearchOverlayActive = false
            isWatchPlayerConfirmedOpen = false
            targetSearchTitle = null
            targetSearchChannel = null
            targetChannelHandle = null
            targetVideoUrl = null
            targetVideoId = null
            targetVideoDurationSeconds = 0
            isTargetLiveStream = false
            rejectedSameTitleDurations.clear()
            hasClickedTarget = false
            scrollAttempts = 0
            lastClickTime = 0L
            lastSearchStepTime = 0L
            lastTypedQueryTime = 0L
            submitQueryAttempts = 0
            openSearchBarAttempts = 0
            hasLaunchedSearchIntentFallback = false
            hasTappedRecentFilterChip = false
            hasSearchedWithHandleOrQuotes = false
            hasOpenedChannelPage = false
            hasTappedChannelVideosTab = false
            searchStrategyStage = 0
            lastSearchActionTimestamp = 0L
            isVideoExplicitlyPaused = false
            resetMonitoringCounters()
            currentPhase = LiveSearchPhase.IDLE
        }

        fun runPeriodicVerificationIfActive() {
            val svc = instance ?: return
            if (!isServiceConnected || !isYouTubeInForeground || !svc.isReadyForWatchVerification()) return
            try {
                val root = svc.getYouTubeRootNode() ?: svc.rootInActiveWindow ?: return
                svc.checkPlaybackControls(root)
                svc.verifyActiveYouTubeVideo(root)
            } catch (_: Exception) {}
        }
    }

    private val backgroundPushSyncScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    private var backgroundPushSyncJob: kotlinx.coroutines.Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceConnected = true
        WatchSessionRepository.addLog("YouTube Human Live Search Accessibility Service Connected", LogType.INFO)
        startBackgroundAdminPushSync()
    }

    private fun startBackgroundAdminPushSync() {
        if (backgroundPushSyncJob?.isActive == true) return
        val appCtx = applicationContext
        val ds = com.example.data.DataStoreManager(appCtx)
        backgroundPushSyncJob = backgroundPushSyncScope.launch {
            while (isServiceConnected) {
                try {
                    val srvUrl = com.example.data.DataStoreManager.DEFAULT_CLOUD_SERVER_URL
                    if (srvUrl.isNotBlank()) {
                        com.example.admin.CloudDriveServerManager.syncData(
                            serverUrl = srvUrl,
                            dataStoreManager = ds,
                            pushAdminContent = false,
                            pushLocalChanges = false
                        )
                        NotificationChannels.checkAndDispatchAdminNotifications(appCtx, ds)
                    }
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(1_500L)
            }
        }
    }

    private fun isTransientSystemPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        val lower = pkg.lowercase()
        return lower == "android" ||
                lower.contains("systemui") ||
                lower.contains("inputmethod") ||
                lower.contains("keyboard") ||
                lower.contains("gboard") ||
                lower.contains("honeyboard") ||
                lower.contains("swiftkey") ||
                lower.contains("facemoji") ||
                lower.contains("bobble") ||
                lower.contains("mint") ||
                lower.contains("indic") ||
                lower.contains("kika") ||
                lower.contains("baidu") ||
                lower.contains("sogou") ||
                lower.contains("touchpal") ||
                lower.contains("fleksy") ||
                lower.contains("openboard") ||
                lower.contains("anysoft") ||
                lower.contains("latin") ||
                lower.contains("ime") ||
                lower.contains("autofill") ||
                lower.contains("credential") ||
                lower.contains("tts") ||
                lower.contains("accessibility") ||
                lower.contains("overlay") ||
                lower.contains("permission") ||
                lower.contains("packageinstaller")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: ""
        val myPkg = packageName ?: "com.example"
        if (pkg == myPkg) {
            return
        }

        val activeRootPkg = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        val elapsedSinceLaunch = System.currentTimeMillis() - WatchSessionRepository.taskLaunchTimestampMillis
        val isSessionActive = WatchSessionRepository.sessionState.value == com.example.data.SessionState.ACTIVE

        if (pkg == "com.google.android.youtube" || activeRootPkg == "com.google.android.youtube") {
            // Check that YouTube is not minimized into Picture-in-Picture (PiP) mode
            val inPip = try { rootInActiveWindow?.window?.isInPictureInPictureMode == true } catch (_: Exception) { false }
            if (inPip && isSessionActive && elapsedSinceLaunch > 4500L) {
                isYouTubeInForeground = false
                WatchSessionRepository.triggerTaskIncomplete(
                    "Task Incomplete! Aapne YouTube minimize kar diya hai. Task complete hone tak YouTube par target video full screen mein dekhna zaroori hai."
                )
                return
            }
            notInYouTubeStrikeCount = 0
            isYouTubeInForeground = true
            if (isSessionActive && elapsedSinceLaunch > 1500L) {
                WatchSessionRepository.hasLeftAppForYouTube = true
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg.isNotBlank() &&
                pkg != myPkg &&
                elapsedSinceLaunch > 4000L &&
                !isTransientSystemPackage(pkg)
            ) {
                // Verify activeRootPkg is also not YouTube before declaring exit
                if (activeRootPkg != "com.google.android.youtube") {
                    isYouTubeInForeground = false
                    WatchSessionRepository.setPlaybackPlaying(false)
                    WatchSessionRepository.onRequestHideOverlay?.invoke()
                    if (isSessionActive && WatchSessionRepository.hasLeftAppForYouTube) {
                        WatchSessionRepository.triggerTaskIncomplete(
                            "Task Incomplete! Aapne YouTube minimize kar diya ya YouTube se back kar ke doosre app mein switch kar liya."
                        )
                        return
                    }
                }
            }
        }

        // Check for YouTube "Comment added" / "Reply added" confirmation in any event
        if (isSessionActive && (pkg == "com.google.android.youtube" || isYouTubeInForeground || pkg == "android" || pkg == "com.android.systemui")) {
            try {
                val evTxt = event.text?.joinToString(" ") { it.toString() }?.trim() ?: ""
                val evDsc = event.contentDescription?.toString()?.trim() ?: ""
                val evCombined = "$evTxt $evDsc".lowercase()
                if (isCommentAddedConfirmationText(evCombined)) {
                    triggerGenuineCommentReward("YouTube confirmation banner/announcement detected")
                }
            } catch (_: Exception) {}
        }

        // Track genuine comment typing inside YouTube comment box (excluding top search bar)
        if ((pkg == "com.google.android.youtube" || (isYouTubeInForeground && isTransientSystemPackage(pkg))) &&
            (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
             event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
             event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED)
        ) {
            try {
                val src = event.source
                val vId = src?.viewIdResourceName?.lowercase() ?: ""
                val cls = src?.className?.toString()?.lowercase() ?: ""
                val isSearchField = vId.contains("search_edit_text") ||
                        vId.contains("search_src_text") ||
                        vId.contains("search_box") ||
                        (currentPhase != LiveSearchPhase.IDLE && currentPhase != LiveSearchPhase.COMPLETED && !hasClickedTarget)
                if (!isSearchField) {
                    val srcText = src?.text?.toString()?.trim().orEmpty()
                    val evTextStr = event.text?.joinToString(" ") { it.toString() }?.trim().orEmpty()
                    val typed = if (srcText.isNotEmpty() && !isCommentPlaceholder(srcText)) srcText else evTextStr
                    if (cls.contains("edittext") || src?.isEditable == true || vId.contains("comment")) {
                        wasCommentComposerOpen = true
                        lastCommentComposerOpenTime = System.currentTimeMillis()
                    }
                    if (typed.isNotEmpty() && !isCommentPlaceholder(typed)) {
                        hasTypedCommentText = true
                        lastTypedCommentText = typed
                        lastTypedCommentTime = System.currentTimeMillis()
                        wasCommentComposerOpen = true
                        wasCommentEditTextActive = true
                        lastCommentComposerOpenTime = System.currentTimeMillis()
                    }
                }
                src?.recycle()
            } catch (_: Exception) {}
        }

        // Detect user interactions INSIDE YouTube ONLY (ignore clicks on our own floating overlay!)
        if ((pkg == "com.google.android.youtube" || activeRootPkg == "com.google.android.youtube") &&
            isYouTubeInForeground &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            try {
                val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
                val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(400)
                val density = resources.displayMetrics.density
                val node = event.source
                val clickRect = android.graphics.Rect()
                node?.getBoundsInScreen(clickRect)
                val evText = event.text?.joinToString(" ") { it.toString() }?.trim() ?: ""
                val evDesc = event.contentDescription?.toString()?.trim() ?: ""
                val desc = node?.contentDescription?.toString()?.ifBlank { evDesc } ?: evDesc
                val text = node?.text?.toString()?.ifBlank { evText } ?: evText
                val viewId = node?.viewIdResourceName ?: ""
                val subtreeSb = StringBuilder()
                if (node != null) {
                    collectSubtreeText(node, subtreeSb, 0)
                }
                val subtreeText = subtreeSb.toString().trim()
                val combined = "$desc $text $evText $evDesc $viewId $subtreeText".lowercase()

                val isCommentRelated = desc.contains("comment", ignoreCase = true) ||
                        desc.contains("reply", ignoreCase = true) ||
                        desc.contains("टिप्पणी") ||
                        desc.contains("जवाब") ||
                        desc.equals("Send", ignoreCase = true) ||
                        desc.equals("Send comment", ignoreCase = true) ||
                        desc.equals("Post", ignoreCase = true) ||
                        desc.equals("Post comment", ignoreCase = true) ||
                        desc.equals("Comment", ignoreCase = true) ||
                        desc.equals("Reply", ignoreCase = true) ||
                        desc.equals("भेजें", ignoreCase = true) ||
                        text.contains("comment", ignoreCase = true) ||
                        text.contains("reply", ignoreCase = true) ||
                        text.contains("टिप्पणी") ||
                        text.contains("जवाब") ||
                        text.equals("Send", ignoreCase = true) ||
                        text.equals("Post", ignoreCase = true) ||
                        text.equals("Comment", ignoreCase = true) ||
                        text.equals("Reply", ignoreCase = true) ||
                        text.equals("भेजें", ignoreCase = true) ||
                        viewId.contains("comment", ignoreCase = true) ||
                        viewId.contains("composer", ignoreCase = true) ||
                        viewId.contains("reply", ignoreCase = true) ||
                        viewId.contains("send_button", ignoreCase = true) ||
                        viewId.contains("post_button", ignoreCase = true) ||
                        viewId.contains("comment_send", ignoreCase = true) ||
                        viewId.contains("bottom_sheet", ignoreCase = true) ||
                        viewId.contains("engagement_panel", ignoreCase = true) ||
                        combined.contains("add a comment") ||
                        combined.contains("add a reply") ||
                        combined.contains("टिप्पणी जोड़ें") ||
                        combined.contains("जवाब जोड़ें") ||
                        combined.contains("pinned by") ||
                        combined.contains("hearted by")

                val isProductRelated = desc.contains("product", ignoreCase = true) ||
                        desc.contains("shopping", ignoreCase = true) ||
                        desc.contains("view products", ignoreCase = true) ||
                        desc.contains("tagged products", ignoreCase = true) ||
                        desc.contains("products in this video", ignoreCase = true) ||
                        desc.contains("explore products", ignoreCase = true) ||
                        desc.contains("उत्पाद") ||
                        desc.contains("खरीदारी") ||
                        text.contains("product", ignoreCase = true) ||
                        text.contains("shopping", ignoreCase = true) ||
                        text.contains("उत्पाद") ||
                        text.contains("खरीदारी") ||
                        viewId.contains("product", ignoreCase = true) ||
                        viewId.contains("shopping", ignoreCase = true) ||
                        viewId.contains("commerce", ignoreCase = true) ||
                        viewId.contains("merch", ignoreCase = true) ||
                        viewId.contains("store", ignoreCase = true) ||
                        viewId.contains("shelf", ignoreCase = true) ||
                        viewId.contains("cart", ignoreCase = true) ||
                        combined.contains("view products") ||
                        combined.contains("tagged products") ||
                        combined.contains("products in this video") ||
                        combined.contains("explore products") ||
                        combined.contains("shopping") ||
                        (combined.contains("products") && combined.contains("(")) ||
                        combined.contains("₹")

                val looksLikeVideoCard = !isCommentRelated && !isProductRelated && (
                        combined.contains("go to channel") ||
                        combined.contains("चैनल पर जाएं") ||
                        (combined.contains("views") && (combined.contains("ago") || combined.contains("hours") || combined.contains("days") || combined.contains("months") || combined.contains("years"))) ||
                        viewId.contains("video_lockup", ignoreCase = true) ||
                        viewId.contains("compact_video", ignoreCase = true) ||
                        viewId.contains("video_card", ignoreCase = true) ||
                        viewId.contains("rich_item", ignoreCase = true)
                )

                // Track if user clicked to open the product list / shopping on target video
                if (isProductRelated) {
                    lastProductClickTime = System.currentTimeMillis()
                    isProductListOpen = true
                }

                // Track if user clicked to open the comment box / composer or clicked any comment on current video
                if (isCommentRelated || (
                    combined.contains("add a comment") ||
                    combined.contains("add a reply") ||
                    combined.contains("टिप्पणी जोड़ें") ||
                    combined.contains("जवाब जोड़ें") ||
                    desc.equals("Comments", ignoreCase = true) ||
                    text.equals("Comments", ignoreCase = true) ||
                    viewId.contains("comment_composer", ignoreCase = true) ||
                    viewId.contains("comment_box", ignoreCase = true)
                )) {
                    wasCommentComposerOpen = true
                    lastCommentComposerOpenTime = System.currentTimeMillis()
                    lastCommentClickTime = System.currentTimeMillis()
                    updateCommentSheetState(true)
                }

                // Track if user clicked Close comments / Cancel / Discard
                if (desc.equals("Close comments", ignoreCase = true) ||
                    desc.equals("Close", ignoreCase = true) ||
                    desc.equals("Close live chat", ignoreCase = true) ||
                    desc.equals("Cancel", ignoreCase = true) ||
                    desc.equals("Discard", ignoreCase = true) ||
                    text.equals("Cancel", ignoreCase = true) ||
                    text.equals("Discard", ignoreCase = true) ||
                    desc.contains("टिप्पणी बंद करें") ||
                    desc.contains("रद्द करें") ||
                    text.contains("रद्द करें")
                ) {
                    lastCommentCancelClickTime = System.currentTimeMillis()
                    hasTypedCommentText = false
                    wasCommentEditTextActive = false
                    wasCommentComposerOpen = false
                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    mainHandler.postDelayed({
                        val root = getYouTubeRootNode() ?: rootInActiveWindow
                        val entries = mutableListOf<UiNodeEntry>()
                        collectScreenNodes(root, entries)
                        updateCommentSheetState(isCommentsSheetOrKeyboardOpen(entries))
                    }, 350L)
                }

                val statusBarHeight = getStatusBarHeight()
                val playerBottomY = statusBarHeight + ((screenWidth * 9) / 16)
                val topPlayerMaxBottom = (playerBottomY + (48 * density).toInt()).coerceAtMost((screenHeight * 0.42f).toInt())
                val inTopPlayerArea = (clickRect.top in 0..topPlayerMaxBottom && clickRect.bottom in 1..(topPlayerMaxBottom + (30 * density).toInt())) ||
                        viewId.contains("player_control", ignoreCase = true) ||
                        viewId.contains("play_pause_replay_button", ignoreCase = true) ||
                        viewId.contains("player_overlay", ignoreCase = true) ||
                        viewId.contains("player_view", ignoreCase = true) ||
                        viewId.contains("player_fragment", ignoreCase = true) ||
                        desc.equals("Video player", ignoreCase = true) ||
                        desc.equals("Hide controls", ignoreCase = true) ||
                        desc.equals("Show controls", ignoreCase = true)

                val isNextOrPrevOrCollapse = desc.equals("Next video", ignoreCase = true) ||
                        desc.equals("Previous video", ignoreCase = true) ||
                        evDesc.equals("Next video", ignoreCase = true) ||
                        evDesc.equals("Previous video", ignoreCase = true) ||
                        desc.contains("अगला वीडियो") ||
                        desc.contains("पिछला वीडियो") ||
                        desc.equals("Minimize", ignoreCase = true) ||
                        desc.equals("Collapse", ignoreCase = true) ||
                        viewId.contains("player_control_next", ignoreCase = true) ||
                        viewId.contains("player_control_previous", ignoreCase = true) ||
                        viewId.contains("player_collapse_button", ignoreCase = true) ||
                        viewId.contains("autonav", ignoreCase = true)

                val isPlayPauseBtnClick = !isNextOrPrevOrCollapse && (
                        viewId.contains("play_pause_replay_button", ignoreCase = true) ||
                        viewId.contains("player_control_play_pause", ignoreCase = true) ||
                        evDesc.equals("Pause video", ignoreCase = true) ||
                        evDesc.equals("Pause", ignoreCase = true) ||
                        evDesc.equals("Play video", ignoreCase = true) ||
                        evDesc.equals("Replay video", ignoreCase = true) ||
                        evDesc.equals("Play", ignoreCase = true) ||
                        evDesc.equals("Replay", ignoreCase = true) ||
                        evDesc.contains("वीडियो रोकें") ||
                        evDesc.contains("वीडियो चलाएं") ||
                        evDesc.contains("फिर से चलाएं") ||
                        desc.equals("Pause video", ignoreCase = true) ||
                        desc.equals("Pause", ignoreCase = true) ||
                        desc.equals("Play video", ignoreCase = true) ||
                        desc.equals("Replay video", ignoreCase = true) ||
                        desc.equals("Play", ignoreCase = true) ||
                        desc.equals("Replay", ignoreCase = true) ||
                        desc.contains("वीडियो रोकें") ||
                        desc.contains("वीडियो चलाएं") ||
                        desc.contains("फिर से चलाएं")
                )

                val isDislike = combined.contains("dislike") || combined.contains("नापसंद")
                val explicitUnlikeLabel = combined.contains("unlike") ||
                        combined.contains("remove like") ||
                        combined.contains("हटाएं") ||
                        evDesc.contains("unlike", ignoreCase = true) ||
                        evDesc.contains("remove like", ignoreCase = true) ||
                        desc.startsWith("unlike", ignoreCase = true)

                // Genuine Like click on the target YouTube video's Like button
                val isCommentLike = combined.contains("comment") ||
                        combined.contains("टिप्पणी") ||
                        combined.contains("reply") ||
                        combined.contains("जवाब")

                val isVideoLikeButtonTarget = isSessionActive &&
                        !isDislike &&
                        !isCommentLike &&
                        !isProductRelated &&
                        !looksLikeVideoCard &&
                        combined.length < 180 && (
                                desc.startsWith("like this video", ignoreCase = true) ||
                                evDesc.startsWith("like this video", ignoreCase = true) ||
                                combined.contains("like this video") ||
                                viewId.contains("like_button", ignoreCase = true) ||
                                viewId.contains("segmented_like", ignoreCase = true) ||
                                desc.equals("Like", ignoreCase = true) ||
                                evDesc.equals("Like", ignoreCase = true) ||
                                desc.contains("पसंद करें") ||
                                evDesc.contains("पसंद करें")
                        )

                val isAnyLikeClick = isVideoLikeButtonTarget ||
                        combined.contains("like this video") ||
                        viewId.contains("like_button") ||
                        viewId.contains("segmented_like")

                val now = System.currentTimeMillis()
                val hadRecentCommentActivity = hasTypedCommentText ||
                        wasCommentComposerOpen ||
                        (now - lastTypedCommentTime) < 120_000L ||
                        (now - lastCommentComposerOpenTime) < 120_000L

                val isExplicitCommentSendLabel =
                        desc.equals("Send", ignoreCase = true) ||
                        desc.equals("Send comment", ignoreCase = true) ||
                        desc.equals("Post", ignoreCase = true) ||
                        desc.equals("Post comment", ignoreCase = true) ||
                        desc.equals("Comment", ignoreCase = true) ||
                        desc.equals("Reply", ignoreCase = true) ||
                        desc.equals("Submit", ignoreCase = true) ||
                        evDesc.equals("Send", ignoreCase = true) ||
                        evDesc.equals("Send comment", ignoreCase = true) ||
                        evDesc.equals("Post", ignoreCase = true) ||
                        evDesc.equals("Post comment", ignoreCase = true) ||
                        text.equals("Send", ignoreCase = true) ||
                        text.equals("Post", ignoreCase = true) ||
                        text.equals("Comment", ignoreCase = true) ||
                        text.equals("Reply", ignoreCase = true) ||
                        desc.contains("टिप्पणी भेजें") ||
                        desc.contains("टिप्पणी करें") ||
                        desc.equals("भेजें", ignoreCase = true) ||
                        evDesc.contains("टिप्पणी भेजें") ||
                        evDesc.equals("भेजें", ignoreCase = true) ||
                        text.equals("भेजें", ignoreCase = true) ||
                        subtreeText.equals("Send", ignoreCase = true) ||
                        subtreeText.equals("Send comment", ignoreCase = true) ||
                        subtreeText.equals("Post", ignoreCase = true) ||
                        viewId.contains("send_button", ignoreCase = true) ||
                        viewId.contains("post_button", ignoreCase = true) ||
                        viewId.contains("comment_send", ignoreCase = true) ||
                        viewId.contains("composer_send", ignoreCase = true) ||
                        (viewId.contains("send", ignoreCase = true) && !viewId.contains("share", ignoreCase = true))

                val isRightSideSendIcon = (hadRecentCommentActivity || isSoftKeyboardVisible() || wasCommentComposerOpen) &&
                        clickRect.right >= (screenWidth * 0.55f).toInt() &&
                        clickRect.top >= (screenHeight * 0.20f).toInt() &&
                        clickRect.width() in 10..(140 * density).toInt() &&
                        clickRect.height() in 10..(140 * density).toInt() &&
                        !inTopPlayerArea &&
                        !isPlayPauseBtnClick &&
                        !isNextOrPrevOrCollapse &&
                        !isDislike &&
                        !isAnyLikeClick &&
                        !isProductRelated &&
                        !desc.startsWith("like", ignoreCase = true) &&
                        !desc.equals("Close", ignoreCase = true) &&
                        !desc.equals("Close comments", ignoreCase = true) &&
                        !desc.equals("Cancel", ignoreCase = true) &&
                        !desc.contains("More", ignoreCase = true) &&
                        !desc.contains("Sort", ignoreCase = true)

                val isGenuineCommentSubmitted = isSessionActive &&
                        (isExplicitCommentSendLabel || isRightSideSendIcon)

                val isAnyCommentClick = isCommentRelated ||
                    combined.contains("add a comment") ||
                    combined.contains("add a reply") ||
                    combined.contains("टिप्पणी जोड़ें") ||
                    combined.contains("जवाब जोड़ें") ||
                    desc.equals("Comments", ignoreCase = true) ||
                    text.equals("Comments", ignoreCase = true) ||
                    desc.equals("Close comments", ignoreCase = true) ||
                    desc.equals("Close", ignoreCase = true) ||
                    desc.contains("comment", ignoreCase = true) ||
                    text.contains("comment", ignoreCase = true) ||
                    viewId.contains("comment_composer", ignoreCase = true) ||
                    viewId.contains("comment_box", ignoreCase = true) ||
                    viewId.contains("bottom_sheet", ignoreCase = true) ||
                    viewId.contains("engagement_panel", ignoreCase = true)

                if (isAnyLikeClick) {
                    if (explicitUnlikeLabel) {
                        wasTargetVideoLikedInSession = false
                        WatchSessionRepository.addLog("YouTube Like removed (Unlike detected)", LogType.INFO)
                    } else {
                        wasTargetVideoLikedInSession = true
                        lastLikeClickTime = System.currentTimeMillis()
                        WatchSessionRepository.addLog("YouTube Like detected on target video!", LogType.SUCCESS)
                        WatchSessionRepository.onTaskLikeDetected?.invoke()
                    }
                } else if (isGenuineCommentSubmitted) {
                    lastCommentClickTime = System.currentTimeMillis()
                    lastCommentComposerOpenTime = System.currentTimeMillis()
                    wasCommentComposerOpen = true
                    triggerGenuineCommentReward("Clicked YouTube Comment Send button")
                } else if (isPlayPauseBtnClick) {
                    // Toggle immediately for instant UI responsiveness, then verify actual post-click button state
                    updateVideoPausedState(!isVideoExplicitlyPaused)
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.postDelayed({ checkPlaybackControls(getYouTubeRootNode()) }, 180L)
                    handler.postDelayed({ checkPlaybackControls(getYouTubeRootNode()) }, 450L)
                    handler.postDelayed({ checkPlaybackControls(getYouTubeRootNode()) }, 900L)
                } else if (inTopPlayerArea && !isNextOrPrevOrCollapse) {
                    // User tapped video surface to show/hide controls or tapped player settings/seekbar:
                    // Never treat this as a video switch! Just inspect playback controls shortly after.
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.postDelayed({ checkPlaybackControls(getYouTubeRootNode()) }, 200L)
                    handler.postDelayed({ checkPlaybackControls(getYouTubeRootNode()) }, 500L)
                } else if (isAnyCommentClick) {
                    // Harmless comment click on target video (reading comments, opening comments box, typing, etc.)
                    lastCommentClickTime = System.currentTimeMillis()
                    lastCommentComposerOpenTime = System.currentTimeMillis()
                    val isCloseAction = desc.equals("Close", ignoreCase = true) ||
                            desc.equals("Close comments", ignoreCase = true) ||
                            desc.equals("Close chat", ignoreCase = true) ||
                            desc.equals("Close live chat", ignoreCase = true) ||
                            desc.contains("बंद करें") ||
                            text.equals("Close", ignoreCase = true)
                    wasCommentComposerOpen = !isCloseAction
                    updateCommentSheetState(!isCloseAction)
                } else if (isProductRelated) {
                    // Harmless click on target video's own product list / shopping shelf / tagged products
                    lastProductClickTime = System.currentTimeMillis()
                    isProductListOpen = true
                } else if (isSessionActive && !isAnyCommentClick && !isCommentRelated && !isProductRelated && !inTopPlayerArea && !isPlayPauseBtnClick && !isAnyLikeClick) {
                    checkIfUserClickedDifferentVideo(node, desc, text, viewId, "$evText $evDesc".trim())
                }

                if (isSessionActive) {
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.postDelayed({ inspectCurrentYouTubeState() }, 150L)
                    handler.postDelayed({ inspectCurrentYouTubeState() }, 450L)
                    handler.postDelayed({ inspectCurrentYouTubeState() }, 900L)
                }
                node?.recycle()
            } catch (_: Exception) {}
        }

        // If target was already clicked or idle, monitor playback controls & active video in YouTube
        if (hasClickedTarget || currentPhase == LiveSearchPhase.IDLE || currentPhase == LiveSearchPhase.COMPLETED) {
            if (isYouTubeInForeground && (
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
            )) {
                val ytRoot = getYouTubeRootNode() ?: (if (pkg == "com.google.android.youtube") event.source else null)
                if (ytRoot != null) {
                    if (hasClickedTarget && !isWatchPlayerConfirmedOpen && isFullWatchPlayerScreen(ytRoot)) {
                        confirmWatchPlayerOpened()
                    }
                    checkPlaybackControls(ytRoot)

                    val isSheetOpen = isCommentOrChatSheetCurrentlyOpen(ytRoot)
                    updateCommentSheetState(isSheetOpen)

                    // Unlocked Area Scroll Lock: If user tries to scroll the main watch page (not comment/chat), counter-scroll to lock it at the top
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && isSessionActive && isWatchPlayerConfirmedOpen && !isSheetOpen) {
                        try {
                            val src = event.source
                            if (src != null && src.isScrollable) {
                                src.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                            } else {
                                ytRoot.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                            }
                        } catch (_: Exception) {}
                    }

                    if (isSessionActive && isReadyForWatchVerification()) {
                        val now = System.currentTimeMillis()
                        if (now - lastWatchHeaderCheckTime >= 400L) {
                            lastWatchHeaderCheckTime = now
                            verifyActiveYouTubeVideo(ytRoot)
                        }
                    }
                }
            }
            return
        }

        val titleToFind = targetSearchTitle ?: return
        val rootNode = getYouTubeRootNode() ?: rootInActiveWindow ?: event.source ?: return
        driveLiveSearchStep(rootNode, titleToFind)
    }

    private fun buildSearchQuery(title: String, channel: String?, exactQuotedTitle: Boolean = false): String {
        val cleanTitle = title.trim()
        val cleanChannel = channel?.trim().orEmpty()
        val hasRealChannel = cleanChannel.isNotBlank() &&
                !cleanChannel.equals("YouTube Creator", ignoreCase = true) &&
                !cleanChannel.equals("YouTube Channel", ignoreCase = true)

        return if (exactQuotedTitle) {
            if (hasRealChannel && !cleanTitle.contains(cleanChannel, ignoreCase = true)) {
                "\"$cleanTitle\" $cleanChannel"
            } else {
                "\"$cleanTitle\""
            }
        } else {
            if (hasRealChannel && !cleanTitle.contains(cleanChannel, ignoreCase = true)) {
                "$cleanTitle $cleanChannel"
            } else {
                cleanTitle
            }
        }
    }

    private fun getStrategyQuery(stage: Int, title: String): String {
        val cleanTitle = title.trim()
        val cleanChannel = targetSearchChannel?.trim().orEmpty()
        val cleanHandle = targetChannelHandle?.trim().orEmpty()
        val cleanVid = targetVideoId?.trim().orEmpty()
        val hasRealChannel = cleanChannel.isNotBlank() &&
                !cleanChannel.equals("YouTube Creator", ignoreCase = true) &&
                !cleanChannel.equals("YouTube Channel", ignoreCase = true)

        return when (stage) {
            0 -> cleanTitle // Strategy 1: Pure exact title first!
            1 -> {
                // Strategy 2: Quoted Title + Channel Name ("$title" $channel)
                if (hasRealChannel) {
                    if (!cleanTitle.contains(cleanChannel, ignoreCase = true)) {
                        "\"$cleanTitle\" $cleanChannel"
                    } else {
                        "\"$cleanTitle\""
                    }
                } else {
                    "\"$cleanTitle\""
                }
            }
            2 -> {
                // Strategy 3: Channel Handle or Channel Name first + Title (@handle $title)
                if (cleanHandle.startsWith("@") && cleanHandle.length >= 3) {
                    "$cleanHandle $cleanTitle"
                } else if (hasRealChannel) {
                    "$cleanChannel $cleanTitle"
                } else {
                    cleanTitle
                }
            }
            3 -> {
                // Strategy 4: Title + Admin Video ID from URL ($title $videoId)
                if (cleanVid.length == 11) {
                    "$cleanTitle $cleanVid"
                } else if (hasRealChannel) {
                    "$cleanTitle $cleanChannel"
                } else {
                    cleanTitle
                }
            }
            else -> cleanTitle
        }
    }

    private fun getActiveQueryToType(title: String): String {
        val custom = overrideSearchQuery?.trim()
        if (!custom.isNullOrBlank()) return custom
        return getStrategyQuery(searchStrategyStage, title)
    }

    private fun triggerInAppSearchWithQuery(queryText: String, reasonLog: String) {
        overrideSearchQuery = queryText.trim()
        openSearchBarAttempts = 0
        submitQueryAttempts = 0
        lastSearchActionTimestamp = System.currentTimeMillis()
        WatchSessionRepository.addLog(reasonLog, LogType.INFO)
        if (!launchYouTubeSearchResultsFallback(queryText.trim())) {
            currentPhase = LiveSearchPhase.OPEN_SEARCH_BAR
        }
    }

    private fun launchYouTubeSearchResultsFallback(queryText: String): Boolean {
        val cleanQuery = queryText.trim()
        if (cleanQuery.isBlank()) return false
        return try {
            val searchIntent = com.example.util.PermissionHelper.openYouTubeSearchResultsIntent(this, cleanQuery)
            startActivity(searchIntent)
            hasLaunchedSearchIntentFallback = true
            overrideSearchQuery = null
            lastSearchActionTimestamp = System.currentTimeMillis()
            currentPhase = LiveSearchPhase.FIND_AND_CLICK_VIDEO
            WatchSessionRepository.addLog(
                "YouTube Search: Searching \"$cleanQuery\" in YouTube...",
                LogType.INFO
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isHomeOrIncognitoEmptyScreen(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val combined = "$text $desc".lowercase()
        if (combined.contains("your watch history is off") ||
            combined.contains("watch history is paused") ||
            combined.contains("you're incognito") ||
            combined.contains("try searching to get started") ||
            combined.contains("search youtube")
        ) {
            val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            if (r.top >= (screenHeight * 0.09f).toInt()) {
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isHomeOrIncognitoEmptyScreen(child)) return true
        }
        return false
    }

    private fun driveLiveSearchStep(rootNode: AccessibilityNodeInfo, titleToFind: String) {
        if (hasClickedTarget || currentPhase == LiveSearchPhase.IDLE || currentPhase == LiveSearchPhase.COMPLETED) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastSearchStepTime < 360L) {
            return
        }
        lastSearchStepTime = now

        try {
            when (currentPhase) {
                LiveSearchPhase.OPEN_SEARCH_BAR -> {
                    // 1. If the real editable search EditText in the top bar is already open, go straight to typing!
                    val existingEditText = findSearchEditText(rootNode)
                    if (existingEditText != null) {
                        currentPhase = LiveSearchPhase.TYPE_QUERY
                        handleTyping(rootNode, titleToFind)
                        return
                    }

                    // 2. If clicking the search button didn't open the EditText within 3 tries (~1.1s),
                    // open YouTube's Search Results page for the target query via ACTION_SEARCH
                    if (openSearchBarAttempts >= 3) {
                        val queryToSearch = getActiveQueryToType(titleToFind)
                        if (launchYouTubeSearchResultsFallback(queryToSearch)) {
                            return
                        }
                    }

                    // 3. Look for the YouTube Search button:
                    // Attempt 0: Prefer Top-Right Toolbar Search icon (always a real clickable button)
                    // Attempt 1: Prefer Center "Search YouTube" pill (on Incognito / Watch History Off screen)
                    val preferTopIcon = (openSearchBarAttempts % 2 == 0)
                    val searchBtnMatch = findSearchButton(rootNode, preferTopIcon)
                    openSearchBarAttempts++

                    if (searchBtnMatch != null) {
                        val searchBtn = searchBtnMatch.first
                        val tapRect = searchBtnMatch.second

                        // Perform accessibility click on node and up to 2 parents
                        var clickedByAction = searchBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        var p = searchBtn.parent
                        var hops = 0
                        while (!clickedByAction && p != null && hops < 3) {
                            if (p.isClickable) {
                                clickedByAction = p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }
                            p = p.parent
                            hops++
                        }

                        // Only dispatch physical tap if ACTION_CLICK returned false or on retry attempt
                        if ((!clickedByAction || openSearchBarAttempts >= 2) &&
                            tapRect.centerX() > 0 && tapRect.centerY() > 0
                        ) {
                            dispatchTapGesture(tapRect.centerX(), tapRect.centerY())
                        }

                        WatchSessionRepository.addLog(
                            "YouTube Search: Tapped Search button (${tapRect.centerX()}, ${tapRect.centerY()})",
                            LogType.INFO
                        )
                        return
                    }

                    // 4. Coordinate tap fallback if Litho tree hasn't exposed label yet
                    val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(400)
                    val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
                    val density = resources.displayMetrics.density
                    if (openSearchBarAttempts == 1) {
                        val topSearchX = (screenWidth * 0.82f).toInt()
                        val topSearchY = getStatusBarHeight() + (26 * density).toInt()
                        dispatchTapGesture(topSearchX, topSearchY)
                    } else {
                        val midSearchX = screenWidth / 2
                        val midSearchY = (screenHeight * 0.22f).toInt()
                        dispatchTapGesture(midSearchX, midSearchY)
                    }
                }

                LiveSearchPhase.TYPE_QUERY -> {
                    val existingEditText = findSearchEditText(rootNode)
                    if (existingEditText == null) {
                        if (openSearchBarAttempts >= 2) {
                            launchYouTubeSearchResultsFallback(getActiveQueryToType(titleToFind))
                        } else {
                            currentPhase = LiveSearchPhase.OPEN_SEARCH_BAR
                        }
                        return
                    }
                    handleTyping(rootNode, titleToFind)
                }

                LiveSearchPhase.SUBMIT_QUERY -> {
                    handleSubmitQuery(rootNode, titleToFind)
                }

                LiveSearchPhase.FIND_AND_CLICK_VIDEO -> {
                    // Make sure the search EditText / autocomplete dropdown isn't still open covering results
                    val activeEdit = findSearchEditText(rootNode)
                    val hasAutocompleteDropdown = hasAutocompleteSuggestionsOnScreen(rootNode)
                    if ((activeEdit != null && (activeEdit.isFocused || isSoftKeyboardVisible())) || hasAutocompleteDropdown) {
                        if (submitQueryAttempts < 3) {
                            handleSubmitQuery(rootNode, titleToFind)
                            return
                        }
                    }

                    // If YouTube is still on the empty Home / Incognito "Your watch history is off" screen,
                    // immediately trigger the search results page so we never sit on an empty screen!
                    if (!hasLaunchedSearchIntentFallback && isHomeOrIncognitoEmptyScreen(rootNode)) {
                        if (launchYouTubeSearchResultsFallback(getActiveQueryToType(titleToFind))) {
                            return
                        }
                    }

                    // If we navigated into the creator's Channel Page (as last fallback option),
                    // switch to the "Live" tab when isTargetLiveStream is enabled, or "Videos" tab otherwise!
                    if (hasOpenedChannelPage && !hasTappedChannelVideosTab) {
                        if (isTargetLiveStream) {
                            if (findAndClickChannelLiveTab(rootNode)) {
                                hasTappedChannelVideosTab = true
                                scrollAttempts = 0
                                lastSearchActionTimestamp = System.currentTimeMillis()
                                WatchSessionRepository.addLog(
                                    "Channel Browse: Switched to channel 'Live' tab for live stream task",
                                    LogType.INFO
                                )
                                return
                            }
                        } else {
                            if (findAndClickChannelVideosTab(rootNode)) {
                                hasTappedChannelVideosTab = true
                                scrollAttempts = 0
                                lastSearchActionTimestamp = System.currentTimeMillis()
                                WatchSessionRepository.addLog(
                                    "Channel Browse: Switched to channel 'Videos' tab for newest uploads",
                                    LogType.INFO
                                )
                                return
                            }
                        }
                    }

                    val sinceLastAction = System.currentTimeMillis() - lastSearchActionTimestamp
                    if (scrollAttempts == 0 && lastSearchActionTimestamp > 0L && sinceLastAction < 850L) {
                        // Wait for YouTube Search Results page to finish loading after submitting query
                        return
                    }

                    val found = findAndClickVideoNode(rootNode, titleToFind, targetSearchChannel, requireFeedCardMetadata = true)
                    if (!found) {
                        // Give YouTube search results / channel tab enough time (1350ms) to load over network before scrolling or refining
                        if (lastSearchActionTimestamp > 0L && sinceLastAction < 1350L) {
                            return
                        }

                        // Priority 1: Multi-strategy search progression in Search Results List FIRST
                        // Stage A (Scroll 3): Apply "Live" filter chip in Search Results for live tasks, or "Recently uploaded" chip
                        if (scrollAttempts == 3 && !hasOpenedChannelPage && !hasTappedRecentFilterChip) {
                            val chipClicked = if (isTargetLiveStream) {
                                findAndClickLiveFilterChip(rootNode)
                            } else {
                                findAndClickRecentlyUploadedChip(rootNode)
                            }
                            if (chipClicked) {
                                hasTappedRecentFilterChip = true
                                lastSearchActionTimestamp = System.currentTimeMillis()
                                WatchSessionRepository.addLog(
                                    if (isTargetLiveStream) "YouTube Search: Applied 'Live' filter chip in search list"
                                    else "YouTube Search: Applied filter chip for video discovery",
                                    LogType.INFO
                                )
                                return
                            }
                        }

                        // Strategy 2 (Scroll 5): If not found with pure title, search with Quoted Title + Channel ("$title" $channel)
                        if (scrollAttempts == 5 && searchStrategyStage < 1 && !hasOpenedChannelPage) {
                            searchStrategyStage = 1
                            val refinedQuery = getStrategyQuery(1, titleToFind)
                            scrollAttempts++
                            triggerInAppSearchWithQuery(
                                queryText = refinedQuery,
                                reasonLog = "YouTube Search (Strategy 2): Searching with quotes & channel: \"$refinedQuery\""
                            )
                            return
                        }

                        // Strategy 3 (Scroll 8): If still not found, search with Channel Handle / Name first + Title
                        if (scrollAttempts == 8 && searchStrategyStage < 2 && !hasOpenedChannelPage) {
                            searchStrategyStage = 2
                            val refinedQuery = getStrategyQuery(2, titleToFind)
                            scrollAttempts++
                            triggerInAppSearchWithQuery(
                                queryText = refinedQuery,
                                reasonLog = "YouTube Search (Strategy 3): Searching with channel handle/name: \"$refinedQuery\""
                            )
                            return
                        }

                        // Strategy 4 (Scroll 11): If still not found, refine with Title + Video ID
                        val cleanVid = targetVideoId?.trim().orEmpty()
                        if (scrollAttempts == 11 && searchStrategyStage < 3 && cleanVid.length == 11 && !hasOpenedChannelPage) {
                            searchStrategyStage = 3
                            val refinedQuery = getStrategyQuery(3, titleToFind)
                            scrollAttempts++
                            triggerInAppSearchWithQuery(
                                queryText = refinedQuery,
                                reasonLog = "YouTube Search (Strategy 4): Searching with Admin Video ID: \"$refinedQuery\""
                            )
                            return
                        }

                        // Priority 2 — LAST OPTION ONLY (Scroll 14+): Only if the target video was NOT found in the Search Results list
                        // after thorough multi-strategy search, open the Creator's Channel Card -> Live / Videos tab!
                        if (scrollAttempts in 14..16 && !hasOpenedChannelPage) {
                            if (findAndClickChannelCard(rootNode, targetSearchChannel, targetChannelHandle)) {
                                hasOpenedChannelPage = true
                                scrollAttempts = 0
                                lastSearchActionTimestamp = System.currentTimeMillis()
                                WatchSessionRepository.addLog(
                                    "YouTube Search -> Channel Browse (Last Option): Opened creator channel card to locate video",
                                    LogType.INFO
                                )
                                return
                            }
                        }

                        // Last-option fallback if Channel Card wasn't visible yet — search the creator's @handle / channel name
                        if (scrollAttempts == 17 && !hasOpenedChannelPage) {
                            val channelQuery = targetChannelHandle?.takeIf { it.length >= 3 }
                                ?: targetSearchChannel?.takeIf {
                                    it.length >= 2 &&
                                        !it.equals("YouTube Creator", ignoreCase = true) &&
                                        !it.equals("YouTube Channel", ignoreCase = true)
                                }
                            if (!channelQuery.isNullOrBlank()) {
                                scrollAttempts++
                                triggerInAppSearchWithQuery(
                                    queryText = channelQuery,
                                    reasonLog = "YouTube Search (Last Option): Searching creator channel \"$channelQuery\""
                                )
                                return
                            }
                        }

                        if (scrollAttempts in 18..20 && !hasOpenedChannelPage) {
                            if (findAndClickChannelCard(rootNode, targetSearchChannel, targetChannelHandle)) {
                                hasOpenedChannelPage = true
                                scrollAttempts = 0
                                lastSearchActionTimestamp = System.currentTimeMillis()
                                WatchSessionRepository.addLog(
                                    "YouTube Search -> Channel Browse (Last Option): Opened creator channel card to locate video",
                                    LogType.INFO
                                )
                                return
                            }
                        }

                        // If inside Channel Page and not found on first 3 scrolls of initial tab, check Live / Videos tab
                        if (hasOpenedChannelPage && scrollAttempts == 3) {
                            val switched = if (isTargetLiveStream) {
                                findAndClickChannelLiveTab(rootNode) || findAndClickChannelVideosTab(rootNode)
                            } else {
                                findAndClickChannelShortsOrLiveTab(rootNode)
                            }
                            if (switched) {
                                lastSearchActionTimestamp = System.currentTimeMillis()
                                scrollAttempts++
                                return
                            }
                        }

                        if (scrollAttempts < 22) {
                            scrollAttempts++
                            lastSearchActionTimestamp = System.currentTimeMillis() - 650L // ~700ms between scrolls
                            if (!scrollForward(rootNode)) {
                                dispatchSwipeUpGesture()
                            }
                        }
                    }
                }

                else -> {}
            }
        } catch (_: Exception) {
            // Traversal resilience
        }
    }

    private fun handleTyping(rootNode: AccessibilityNodeInfo, titleToFind: String) {
        val searchEditText = findSearchEditText(rootNode)
        if (searchEditText == null) {
            currentPhase = LiveSearchPhase.OPEN_SEARCH_BAR
            return
        }
        try {
            val fullQuery = getActiveQueryToType(titleToFind)
            overrideSearchQuery = null

            // Step A: Focus the edit text
            searchEditText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            searchEditText.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            // Step B: Set text with target query
            val typeArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, fullQuery)
            }
            val typed = searchEditText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, typeArgs)

            if (typed) {
                lastTypedQueryTime = System.currentTimeMillis()
                submitQueryAttempts = 0
                WatchSessionRepository.addLog(
                    "YouTube Search: Typed \"$fullQuery\" into YouTube search bar",
                    LogType.INFO
                )

                // Try submitting immediately via ACTION_IME_ENTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    searchEditText.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
                lastSearchActionTimestamp = System.currentTimeMillis()
                currentPhase = LiveSearchPhase.SUBMIT_QUERY
            } else {
                // Tap inside the EditText to ensure focus and retry on next tick
                val r = android.graphics.Rect()
                searchEditText.getBoundsInScreen(r)
                if (r.centerX() > 0 && r.centerY() > 0) {
                    dispatchTapGesture(r.centerX(), r.centerY())
                }
                launchYouTubeSearchResultsFallback(fullQuery)
            }
        } catch (_: Exception) {
            launchYouTubeSearchResultsFallback(getActiveQueryToType(titleToFind))
        }
    }

    private fun handleSubmitQuery(rootNode: AccessibilityNodeInfo, query: String) {
        submitQueryAttempts++

        val activeEdit = findSearchEditText(rootNode)
        val hasAutocomplete = hasAutocompleteSuggestionsOnScreen(rootNode)

        // Consider the search submitted once the editable search box is no longer focused/open and autocomplete list is gone
        if ((activeEdit == null || (!activeEdit.isFocused && !isSoftKeyboardVisible())) && !hasAutocomplete) {
            lastSearchActionTimestamp = System.currentTimeMillis()
            currentPhase = LiveSearchPhase.FIND_AND_CLICK_VIDEO
            return
        }

        // 1. Trigger ACTION_IME_ENTER on the focused search EditText (API 30+)
        if (activeEdit != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (activeEdit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                lastSearchActionTimestamp = System.currentTimeMillis()
                if (submitQueryAttempts == 1) {
                    return
                }
            }
        }

        // 2. Click a submit / search button in YouTube's search bar or inside the soft keyboard (TYPE_INPUT_METHOD window)
        val submitBtn = findSearchSubmitButton(rootNode)
        if (submitBtn != null) {
            val clicked = submitBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    (submitBtn.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            if (clicked) {
                lastSearchActionTimestamp = System.currentTimeMillis()
                WatchSessionRepository.addLog("YouTube Search: Clicked search submit button", LogType.INFO)
                currentPhase = LiveSearchPhase.FIND_AND_CLICK_VIDEO
                return
            }
        }

        if (clickImeSearchActionKey()) {
            lastSearchActionTimestamp = System.currentTimeMillis()
            WatchSessionRepository.addLog("YouTube Search: Pressed keyboard Search key", LogType.INFO)
            currentPhase = LiveSearchPhase.FIND_AND_CLICK_VIDEO
            return
        }

        // 3. Click an exact matching search suggestion row in YouTube's autocomplete list
        val fullQuery = buildSearchQuery(query, targetSearchChannel)
        val suggestion = findFirstSearchSuggestion(rootNode, fullQuery, allowFirstRowFallback = false)
            ?: findFirstSearchSuggestion(rootNode, query, allowFirstRowFallback = false)
        if (suggestion != null) {
            val sugRect = android.graphics.Rect()
            suggestion.getBoundsInScreen(sugRect)
            val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(400)
            val safeTapX = (screenWidth * 0.38f).toInt()
            val safeTapY = sugRect.centerY()
            val clicked = suggestion.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    (suggestion.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) ||
                    (safeTapY > 0 && dispatchTapGesture(safeTapX, safeTapY))
            if (clicked) {
                lastSearchActionTimestamp = System.currentTimeMillis()
                WatchSessionRepository.addLog("YouTube Search: Tapped search suggestion for \"$query\"", LogType.INFO)
                currentPhase = LiveSearchPhase.FIND_AND_CLICK_VIDEO
                return
            }
        }

        // 4. Guaranteed fallback: launch YouTube's internal Search Results page for fullQuery via ACTION_SEARCH
        if (submitQueryAttempts >= 2) {
            if (launchYouTubeSearchResultsFallback(fullQuery)) {
                return
            }
        }

        // 5. Final fallback: tap the bottom-right keyboard Search key coordinate
        val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(400)
        val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
        val density = resources.displayMetrics.density
        val keyX = (screenWidth * 0.91f).toInt()
        val keyY = (screenHeight - (78 * density)).toInt()
        dispatchTapGesture(keyX, keyY)
        lastSearchActionTimestamp = System.currentTimeMillis()
        if (submitQueryAttempts >= 3) {
            currentPhase = LiveSearchPhase.FIND_AND_CLICK_VIDEO
        }
    }

    private fun clickImeSearchActionKey(): Boolean {
        try {
            val winList = windows ?: return false
            for (w in winList) {
                if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    val imeRoot = try { w.root } catch (_: Exception) { null }
                    if (imeRoot != null) {
                        val keyNode = findImeActionKeyNode(imeRoot)
                        if (keyNode != null) {
                            val r = android.graphics.Rect()
                            keyNode.getBoundsInScreen(r)
                            val clicked = keyNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                                    (keyNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                            if (r.centerX() > 0 && r.centerY() > 0) {
                                dispatchTapGesture(r.centerX(), r.centerY())
                            }
                            if (clicked || (r.centerX() > 0 && r.centerY() > 0)) {
                                return true
                            }
                        }
                    }
                    val imeRect = android.graphics.Rect()
                    w.getBoundsInScreen(imeRect)
                    if (imeRect.width() > 100 && imeRect.height() > 100) {
                        val tapX = imeRect.right - (imeRect.width() * 0.08f).toInt()
                        val tapY = imeRect.bottom - (imeRect.height() * 0.12f).toInt()
                        if (dispatchTapGesture(tapX, tapY)) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun findImeActionKeyNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val text = node.text?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val isSearchOrEnter = viewId.contains("key_pos_ime_action") ||
                viewId.contains("ime_action") ||
                viewId.contains("enter") ||
                desc.equals("Search", ignoreCase = true) ||
                desc.equals("Enter", ignoreCase = true) ||
                desc.equals("Go", ignoreCase = true) ||
                desc.equals("Done", ignoreCase = true) ||
                desc.contains("खोजें") ||
                text.equals("Search", ignoreCase = true) ||
                text.equals("Go", ignoreCase = true) ||
                text.equals("खोजें", ignoreCase = true)
        if (isSearchOrEnter && node.isVisibleToUser) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findImeActionKeyNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun hasAutocompleteSuggestionsOnScreen(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser) {
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            if (desc.equals("Refine", ignoreCase = true) ||
                desc.startsWith("Refine ", ignoreCase = true) ||
                viewId.contains("edit_query", ignoreCase = true)
            ) {
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasAutocompleteSuggestionsOnScreen(child)) return true
        }
        return false
    }

    private fun findSearchButton(
        node: AccessibilityNodeInfo,
        preferTopRightIcon: Boolean = true
    ): Pair<AccessibilityNodeInfo, android.graphics.Rect>? {
        val candidates = mutableListOf<Triple<AccessibilityNodeInfo, android.graphics.Rect, Boolean>>() // Triple(node, tapRect, isCenterSearchBox)
        collectSearchButtons(node, candidates)
        if (candidates.isEmpty()) return null

        val chosen = if (preferTopRightIcon) {
            candidates.firstOrNull { !it.third } ?: candidates.first()
        } else {
            candidates.firstOrNull { it.third } ?: candidates.first()
        }
        return Pair(chosen.first, chosen.second)
    }

    private fun collectSearchButtons(
        node: AccessibilityNodeInfo?,
        out: MutableList<Triple<AccessibilityNodeInfo, android.graphics.Rect, Boolean>>
    ) {
        if (node == null) return
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val text = node.text?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        val lowerDesc = desc.lowercase()
        val lowerText = text.lowercase()
        val lowerId = viewId.lowercase()

        // Never click Voice Search, Cast, Notifications, Filter, Explore/Compass, or Account/Incognito buttons
        val isExcluded = lowerDesc.contains("voice") ||
                lowerDesc.contains("आवाज़") ||
                lowerDesc.contains("cast") ||
                lowerDesc.contains("notification") ||
                lowerDesc.contains("सूचना") ||
                lowerDesc.contains("filter") ||
                lowerDesc.equals("more options", ignoreCase = true) ||
                lowerDesc.equals("action menu", ignoreCase = true) ||
                lowerDesc.contains("account") ||
                lowerDesc.contains("explore") ||
                lowerDesc.contains("incognito") ||
                lowerText.contains("incognito") ||
                lowerText.contains("turn off") ||
                lowerId.contains("voice") ||
                lowerId.contains("fab")

        if (!isExcluded) {
            val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
            val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(400)
            val density = resources.displayMetrics.density
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)

            if (r.top in 0..(screenHeight * 0.48f).toInt() && r.width() > 10 && r.height() in 10..(220 * density).toInt()) {
                val isCenterSearchPill = r.top >= (screenHeight * 0.12f).toInt() && (
                        text.equals("Search YouTube", ignoreCase = true) ||
                        desc.equals("Search YouTube", ignoreCase = true) ||
                        text.contains("Search YouTube", ignoreCase = true) ||
                        desc.contains("Search YouTube", ignoreCase = true) ||
                        text.contains("YouTube में खोजें") ||
                        desc.contains("YouTube में खोजें") ||
                        lowerId.contains("search_box") ||
                        lowerId.contains("search_bar") ||
                        lowerId.contains("search_query")
                )

                val isTopSearchIcon = r.top < (screenHeight * 0.13f).toInt() &&
                        r.left >= (screenWidth * 0.55f).toInt() && (
                        desc.equals("Search", ignoreCase = true) ||
                        text.equals("Search", ignoreCase = true) ||
                        desc.equals("Search YouTube", ignoreCase = true) ||
                        desc.equals("खोजें", ignoreCase = true) ||
                        text.equals("खोजें", ignoreCase = true) ||
                        desc.contains("खोजें") ||
                        lowerId.contains("menu_item_search") ||
                        lowerId.contains("search_button") ||
                        lowerId.contains("action_search") ||
                        lowerId.contains("menu_search")
                )

                if (isCenterSearchPill || isTopSearchIcon) {
                    val tapRect = android.graphics.Rect(r)
                    // If Litho grouped the entire "Your watch history is off" card into one node,
                    // target the bottom 24% of the card where the "Search YouTube" pill button sits
                    if (isCenterSearchPill && r.height() > (85 * density).toInt()) {
                        tapRect.top = (r.bottom - (48 * density).toInt()).coerceAtLeast(r.top)
                    }
                    var target: AccessibilityNodeInfo = node
                    var p = node.parent
                    var hops = 0
                    while (!target.isClickable && p != null && hops < 3) {
                        val pr = android.graphics.Rect()
                        p.getBoundsInScreen(pr)
                        if (pr.height() in 10..(220 * density).toInt() && pr.width() > 10) {
                            target = p
                            if (p.isClickable) break
                        } else {
                            break
                        }
                        p = p.parent
                        hops++
                    }
                    out.add(Triple(target, tapRect, isCenterSearchPill))
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectSearchButtons(child, out)
        }
    }

    private fun findSearchEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        if (node.isVisibleToUser) {
            val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            // Strictly match ONLY the real open top-toolbar Search EditText (r.top <= 13% of screen height),
            // NEVER the center "Search YouTube" button on the "Your watch history is off" Home screen (~21% height)!
            if (r.top in 0..(screenHeight * 0.13f).toInt() && r.bottom <= (screenHeight * 0.18f).toInt()) {
                if (node.isEditable && (
                        className.contains("EditText", ignoreCase = true) ||
                        viewId.contains("search_edit_text", ignoreCase = true) ||
                        viewId.contains("search_input", ignoreCase = true) ||
                        viewId.contains("search_src_text", ignoreCase = true)
                    )
                ) {
                    return node
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSearchEditText(child)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun findFirstSearchSuggestion(
        node: AccessibilityNodeInfo,
        query: String,
        allowFirstRowFallback: Boolean = false
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val cls = node.className?.toString() ?: ""

        // Skip the top EditText itself and skip the right-hand "Refine" diagonal arrow button
        val isEditOrRefine = cls.contains("EditText", ignoreCase = true) ||
                node.isEditable ||
                viewId.contains("search_edit_text", ignoreCase = true) ||
                viewId.contains("edit_query", ignoreCase = true) ||
                desc.contains("Refine", ignoreCase = true) ||
                desc.contains("Clear", ignoreCase = true) ||
                desc.contains("voice", ignoreCase = true) ||
                desc.contains("Search YouTube", ignoreCase = true) ||
                text.equals("Search YouTube", ignoreCase = true)

        if (!isEditOrRefine && node.isVisibleToUser) {
            val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
            val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(400)
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)

            if (r.top in (screenHeight * 0.08f).toInt()..(screenHeight * 0.45f).toInt() &&
                r.left < (screenWidth * 0.72f).toInt() &&
                r.height() in 20..(screenHeight * 0.11f).toInt()
            ) {
                val rawLabel = text.ifBlank { desc }
                val normText = TitleMatcher.normalize(rawLabel)
                val normQuery = TitleMatcher.normalize(query)

                val isMatch = normText.length >= 5 && normQuery.length >= 5 && (
                        normText == normQuery ||
                        normText.replace(" ", "") == normQuery.replace(" ", "")
                )

                if (isMatch) {
                    var target: AccessibilityNodeInfo? = node
                    while (target != null && !target.isClickable) {
                        target = target.parent
                    }
                    return target ?: node
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstSearchSuggestion(child, query, allowFirstRowFallback)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun findSearchSubmitButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val lowerDesc = desc.lowercase()

        if (!lowerDesc.contains("voice") && !lowerDesc.contains("आवाज़") &&
            (desc.equals("Search", ignoreCase = true) ||
             viewId.contains("search_button", ignoreCase = true) ||
             viewId.contains("btn_search", ignoreCase = true))
        ) {
            if (node.isClickable) return node
            val parent = node.parent
            if (parent?.isClickable == true) return parent
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSearchSubmitButton(child)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun findAndClickVideoNode(
        node: AccessibilityNodeInfo,
        targetTitle: String,
        targetChannel: String?,
        requireFeedCardMetadata: Boolean = false
    ): Boolean {
        if (hasClickedTarget) return true

        val cls = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        if (node.isEditable ||
            cls.contains("EditText", ignoreCase = true) ||
            viewId.contains("search_edit_text", ignoreCase = true) ||
            viewId.contains("search_box", ignoreCase = true) ||
            viewId.contains("search_query", ignoreCase = true) ||
            viewId.contains("suggestion", ignoreCase = true) ||
            viewId.contains("typeahead", ignoreCase = true)
        ) {
            return false
        }

        // First traverse children so we prefer matching a specific title node or video card leaf before a large parent container
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findAndClickVideoNode(child, targetTitle, targetChannel, requireFeedCardMetadata)
            if (found) return true
        }

        val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
        val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(400)
        val density = resources.displayMetrics.density
        val nodeRect = android.graphics.Rect()
        node.getBoundsInScreen(nodeRect)

        if (!node.isVisibleToUser) return false

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""

        // Only match direct text/desc on the node itself (never combined parent container subtrees)
        val candidateTexts = mutableListOf<String>()
        if (desc.isNotBlank()) candidateTexts.add(desc)
        if (text.isNotBlank() && !desc.equals(text, ignoreCase = true)) candidateTexts.add(text)
        if (text.isNotBlank() && desc.isNotBlank() && !desc.contains(text, ignoreCase = true)) {
            candidateTexts.add("$text - $desc")
        }

        if (candidateTexts.isEmpty()) return false

        // Skip 3-dot action menu buttons, channel avatars, or top search bar chrome
        val isMenuOrChannelAvatar = desc.equals("Action menu", ignoreCase = true) ||
                desc.equals("More", ignoreCase = true) ||
                desc.equals("More options", ignoreCase = true) ||
                desc.startsWith("Go to channel", ignoreCase = true) ||
                desc.equals("Search filters", ignoreCase = true) ||
                desc.equals("Voice search", ignoreCase = true) ||
                desc.equals("Navigate up", ignoreCase = true) ||
                desc.equals("Clear", ignoreCase = true) ||
                desc.equals("Refine", ignoreCase = true) ||
                nodeRect.left > (screenWidth * 0.84f).toInt()

        val isValidCardPosition = nodeRect.bottom > (screenHeight * 0.15f).toInt() &&
                nodeRect.centerY() > (screenHeight * 0.13f).toInt() &&
                nodeRect.top < (screenHeight * 0.90f).toInt() &&
                nodeRect.height() in 10..(screenHeight * 0.62f).toInt()

        if (!isMenuOrChannelAvatar && isValidCardPosition) {
            val matchedCandidate = candidateTexts.firstOrNull { candidate ->
                TitleMatcher.isStrictTargetVideoMatch(
                    rawCandidateText = candidate,
                    targetTitle = targetTitle,
                    targetChannel = targetChannel,
                    targetHandle = targetChannelHandle
                )
            }

            if (matchedCandidate != null) {
                // Climb up to nearest clickable video card container (avoiding full-screen RecyclerView)
                var clickTarget: AccessibilityNodeInfo? = node
                var climbDepth = 0
                while (clickTarget != null && !clickTarget.isClickable && climbDepth < 5) {
                    val p = clickTarget.parent ?: break
                    if (p.isScrollable) break
                    val pRect = android.graphics.Rect()
                    p.getBoundsInScreen(pRect)
                    if (pRect.height() > (screenHeight * 0.62f).toInt()) break
                    clickTarget = p
                    climbDepth++
                }

                val toClick = if (clickTarget != null && clickTarget.isClickable) clickTarget else node
                val cardRect = android.graphics.Rect()
                toClick.getBoundsInScreen(cardRect)

                var rowContainer: AccessibilityNodeInfo = toClick
                var rowClimb = 0
                while (rowClimb < 3) {
                    val p = rowContainer.parent ?: break
                    if (p.isScrollable || p.childCount > 12) break
                    val pRect = android.graphics.Rect()
                    p.getBoundsInScreen(pRect)
                    if (pRect.height() > (screenHeight * 0.52f).toInt()) break
                    rowContainer = p
                    rowClimb++
                }

                val subtreeSb = StringBuilder(matchedCandidate).append(" ")
                collectSubtreeText(rowContainer, subtreeSb, 0)
                val fullCardText = subtreeSb.toString()
                val lowerCard = fullCardText.lowercase()
                val rowViewId = (rowContainer.viewIdResourceName ?: toClick.viewIdResourceName ?: "").lowercase()

                val hasLiveStreamSignals = lowerCard.contains("live") ||
                        lowerCard.contains("लाइव") ||
                        lowerCard.contains("watching") ||
                        lowerCard.contains("लोग देख रहे हैं") ||
                        lowerCard.contains("streamed") ||
                        lowerCard.contains("streaming") ||
                        lowerCard.contains("premiere") ||
                        lowerCard.contains("scheduled")
                val hasVideoDuration = Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(lowerCard) ||
                        Regex("\\b\\d+\\s*(?:minutes?|mins?|seconds?|secs?|hours?|मिनट|सेकंड|घंटे)\\b", RegexOption.IGNORE_CASE).containsMatchIn(lowerCard)
                val hasViewsOrTime = hasLiveStreamSignals ||
                        lowerCard.contains("views") ||
                        lowerCard.contains("no views") ||
                        lowerCard.contains("watching") ||
                        lowerCard.contains("ago") ||
                        lowerCard.contains("पहले") ||
                        lowerCard.contains("just now") ||
                        lowerCard.contains("play video") ||
                        lowerCard.contains("बार देखा गया") ||
                        lowerCard.contains("कोई व्यू नहीं")
                val hasVideoCardViewId = rowViewId.contains("video_lockup") ||
                        rowViewId.contains("compact_video") ||
                        rowViewId.contains("video_card") ||
                        rowViewId.contains("rich_item")

                val isAdOrSponsored = lowerCard.startsWith("sponsored") ||
                        lowerCard.contains("sponsored ·") ||
                        lowerCard.contains("ad ·")

                val normChannel = TitleMatcher.normalize(targetChannel)
                val normHandle = TitleMatcher.normalize(targetChannelHandle?.removePrefix("@"))
                val normExtracted = TitleMatcher.normalize(
                    TitleMatcher.extractCardVideoTitleOnly(matchedCandidate, targetChannel, targetChannelHandle)
                )
                val normTarget = TitleMatcher.normalize(
                    TitleMatcher.extractCardVideoTitleOnly(targetTitle, targetChannel, targetChannelHandle).ifBlank { targetTitle }
                )

                val isTitleOnlyChannelName = normChannel.isNotEmpty() &&
                        (normExtracted == normChannel || normExtracted.replace(" ", "") == normChannel.replace(" ", ""))

                val isLikelyChannelProfileRow = (isTitleOnlyChannelName || (!hasVideoDuration && !hasLiveStreamSignals && (lowerCard.contains("subscribers") || lowerCard.contains("subscriber") || lowerCard.contains("सदस्य")))) &&
                        !hasVideoDuration && !hasLiveStreamSignals

                val normFullCard = TitleMatcher.normalize(fullCardText)
                val compactFullCard = normFullCard.replace(" ", "")
                val channelWords = normChannel.split(" ").filter { it.length >= 2 }
                val hasRealTargetChannel = normChannel.length >= 2 &&
                        !normChannel.equals("youtube creator", ignoreCase = true) &&
                        !normChannel.equals("youtube channel", ignoreCase = true)

                val matchesChannelOnCard = !hasRealTargetChannel ||
                        hasOpenedChannelPage ||
                        normFullCard.contains(normChannel) ||
                        compactFullCard.contains(normChannel.replace(" ", "")) ||
                        (normHandle.length >= 3 && (normFullCard.contains(normHandle) || compactFullCard.contains(normHandle))) ||
                        (channelWords.isNotEmpty() && channelWords.all { w -> normFullCard.contains(w) }) ||
                        (!hasVideoDuration && !hasViewsOrTime && normExtracted == normTarget && normTarget.length >= 12)

                val hasRequiredCardSignals = hasOpenedChannelPage ||
                        !requireFeedCardMetadata ||
                        hasVideoDuration ||
                        hasViewsOrTime ||
                        hasLiveStreamSignals ||
                        hasVideoCardViewId ||
                        (normExtracted == normTarget && normTarget.length >= 10)

                // Verify that the candidate card matches the exact video from the Admin's YouTube link (targetVideoId):
                // When a channel has multiple videos with the same title, each video has its own specific duration (and videoId).
                val cleanVid = targetVideoId?.trim().orEmpty()
                val cardHasExplicitVideoId = cleanVid.length == 11 && fullCardText.contains(cleanVid, ignoreCase = false)
                val cardDurationSecs = if (isTargetLiveStream) 0 else extractCardDurationSeconds(fullCardText)
                val expectedDurationSecs = if (isTargetLiveStream) 0 else targetVideoDurationSeconds

                val isRejectedDuplicateDuration = !isTargetLiveStream && cardDurationSecs > 0 && rejectedSameTitleDurations.contains(cardDurationSecs)
                val durationTolerance = maxOf(25, (expectedDurationSecs * 0.15f).toInt())
                val matchesAdminVideoIdDuration = isTargetLiveStream ||
                        hasLiveStreamSignals ||
                        cardHasExplicitVideoId ||
                        (!isRejectedDuplicateDuration && (
                            expectedDurationSecs <= 0 ||
                            cardDurationSecs <= 0 ||
                            kotlin.math.abs(cardDurationSecs - expectedDurationSecs) <= durationTolerance
                        ))

                if (!isAdOrSponsored && !isLikelyChannelProfileRow && hasRequiredCardSignals && matchesChannelOnCard && !matchesAdminVideoIdDuration) {
                    WatchSessionRepository.addLog(
                        "Skipped same-title video (${cardDurationSecs}s != target ${expectedDurationSecs}s for Admin link ID ${cleanVid.ifEmpty { "N/A" }}). Searching for exact target video...",
                        LogType.INFO
                    )
                }

                if (!isAdOrSponsored && !isLikelyChannelProfileRow && hasRequiredCardSignals && matchesChannelOnCard && matchesAdminVideoIdDuration) {
                    val tapX = nodeRect.centerX().takeIf { it in (screenWidth * 0.12f).toInt()..(screenWidth * 0.84f).toInt() }
                        ?: (screenWidth * 0.44f).toInt()
                    val tapY = nodeRect.centerY().coerceIn((screenHeight * 0.16f).toInt(), (screenHeight * 0.88f).toInt())

                    if (node.isClickable) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (toClick !== node && toClick.isClickable) toClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    // Guarantee video playback trigger by dispatching physical tap gesture on video card coordinates!
                    dispatchTapGesture(tapX, tapY)

                    hasClickedTarget = true
                    lastClickTime = System.currentTimeMillis()
                    lockedWatchPageTitle = targetTitle
                    searchOverlayStatusText = "Opening..."
                    searchDriverHandler.removeCallbacks(searchDriverRunnable)
                    WatchSessionRepository.addLog(
                        "🎉 Organic YouTube Search/Browse: Matched & clicked target video \"$targetTitle\"!",
                        LogType.SUCCESS
                    )

                    // Verify Watch Player opened after transition; NEVER blindly tap coordinates on the Watch Page!
                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    mainHandler.postDelayed({
                        ensureWatchPlayerExpandedAfterClick(tapX, tapY, tapY, 1)
                    }, 950L)

                    return true
                }
            }
        }

        return false
    }

    /**
     * Taps "Live" / "लाइव" filter chip on the YouTube search results page when the task is a Live Stream.
     */
    private fun findAndClickLiveFilterChip(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val label = text.ifBlank { desc }.lowercase()
            if (label.isNotEmpty()) {
                val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val inChipRegion = rect.top in (screenHeight * 0.06f).toInt()..(screenHeight * 0.28f).toInt()
                val isLiveChip = label == "live" ||
                        label == "लाइव" ||
                        label.startsWith("live,") ||
                        label.startsWith("लाइव,")
                if (inChipRegion && isLiveChip) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                            (node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) ||
                            (rect.centerX() > 0 && rect.centerY() > 0 && dispatchTapGesture(rect.centerX(), rect.centerY()))
                    if (clicked) return true
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickLiveFilterChip(child)) return true
        }
        return false
    }

    /**
     * Once inside a Creator's Channel Page for a Live Stream task, finds and taps the "Live" ("लाइव") tab
     * instead of the "Videos" tab! If the horizontal channel tab strip hasn't revealed "Live" yet, scrolls the tab strip horizontally.
     */
    private fun findAndClickChannelLiveTab(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (clickChannelLiveTabRecursive(node)) return true
        // If "Live" tab is slightly to the right of "Videos"/"Shorts" in the horizontal channel tab bar, scroll the tab bar horizontally
        if (scrollChannelTabStripRight(node)) {
            return false // Next tick will see and click the "Live" tab
        }
        return false
    }

    private fun clickChannelLiveTabRecursive(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val label = text.ifBlank { desc }.lowercase()
            if (label == "live" ||
                label == "लाइव" ||
                label.startsWith("live,") ||
                label.startsWith("live ") ||
                label.startsWith("लाइव,") ||
                label.contains("live") ||
                label.contains("लाइव")
            ) {
                val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
                val density = resources.displayMetrics.density
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (rect.top in (screenHeight * 0.08f).toInt()..(screenHeight * 0.65f).toInt() &&
                    rect.height() <= (72 * density).toInt()
                ) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                            (node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                    if (rect.centerX() > 0 && rect.centerY() > 0) {
                        dispatchTapGesture(rect.centerX(), rect.centerY())
                    }
                    return true
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickChannelLiveTabRecursive(child)) return true
        }
        return false
    }

    private fun scrollChannelTabStripRight(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            val label = text.ifBlank { desc }
            if (label == "videos" || label == "shorts" || label == "वीडियो" || label.startsWith("videos,") || label.startsWith("shorts,")) {
                val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
                val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(400)
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (rect.top in (screenHeight * 0.08f).toInt()..(screenHeight * 0.65f).toInt()) {
                    var p = node.parent
                    while (p != null) {
                        if (p.isScrollable && p.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                            return true
                        }
                        p = p.parent
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val y = rect.centerY().toFloat()
                        val path = android.graphics.Path().apply {
                            moveTo(screenWidth * 0.82f, y)
                            lineTo(screenWidth * 0.22f, y)
                        }
                        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 220)
                        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
                        return dispatchGesture(gesture, null, null)
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scrollChannelTabStripRight(child)) return true
        }
        return false
    }

    /**
     * Taps "Recently uploaded" / "Unwatched" / "New to you" / "Videos" filter chip on the YouTube search results page
     * so brand-new 0-view videos and video-only cards surface immediately.
     */
    private fun findAndClickRecentlyUploadedChip(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val label = text.ifBlank { desc }.lowercase()
            if (label.isNotEmpty()) {
                val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val inChipRegion = rect.top in (screenHeight * 0.06f).toInt()..(screenHeight * 0.28f).toInt()
                val isRecentChip = label == "recently uploaded" ||
                        label == "unwatched" ||
                        label == "new to you" ||
                        label == "videos" ||
                        label.contains("recently uploaded") ||
                        label.contains("हाल ही में अपलोड") ||
                        label.contains("नए वीडियो")
                if (inChipRegion && isRecentChip) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                            (node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) ||
                            (rect.centerX() > 0 && rect.centerY() > 0 && dispatchTapGesture(rect.centerX(), rect.centerY()))
                    if (clicked) return true
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickRecentlyUploadedChip(child)) return true
        }
        return false
    }

    /**
     * Finds and taps the Creator's Channel Card in YouTube Search results (tapping the left/center
     * of the card, away from the right-side "Subscribe" button) to open the Channel Page.
     */
    private fun findAndClickChannelCard(
        node: AccessibilityNodeInfo?,
        targetChannel: String?,
        targetHandle: String?
    ): Boolean {
        if (node == null) return false
        val cleanChannel = targetChannel?.trim().orEmpty()
        val cleanHandle = targetHandle?.removePrefix("@")?.lowercase()?.trim().orEmpty()
        if (cleanChannel.isBlank() && cleanHandle.isBlank()) return false

        if (node.isVisibleToUser) {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val combined = "$text $desc".trim()
            if (combined.isNotBlank()) {
                val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
                val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(400)
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)

                val isSubscribeBtn = combined.equals("Subscribe", ignoreCase = true) ||
                        combined.startsWith("Subscribe to", ignoreCase = true) ||
                        combined.contains("सदस्यता लें") ||
                        rect.left > (screenWidth * 0.65f).toInt()

                if (!isSubscribeBtn &&
                    rect.top >= (screenHeight * 0.12f).toInt() &&
                    rect.bottom <= (screenHeight * 0.88f).toInt() &&
                    rect.height() > 16
                ) {
                    var rowContainer: AccessibilityNodeInfo = node
                    var climb = 0
                    while (climb < 3) {
                        val p = rowContainer.parent ?: break
                        if (p.isScrollable || p.childCount > 16) break
                        val pRect = android.graphics.Rect()
                        p.getBoundsInScreen(pRect)
                        if (pRect.height() > (screenHeight * 0.45f).toInt()) break
                        rowContainer = p
                        climb++
                    }

                    val sb = StringBuilder(combined).append(" ")
                    collectSubtreeText(rowContainer, sb, 0)
                    val fullRowText = sb.toString()
                    val lowerRow = fullRowText.lowercase()
                    val normRow = TitleMatcher.normalize(fullRowText)
                    val compactRow = normRow.replace(" ", "")

                    val normChannel = TitleMatcher.normalize(cleanChannel)
                    val compactChannel = normChannel.replace(" ", "")

                    val matchesChannelName = normChannel.length >= 2 && (
                            normRow.contains(normChannel) || compactRow.contains(compactChannel)
                    )
                    val matchesHandle = cleanHandle.length >= 3 && (
                            lowerRow.contains("@$cleanHandle") ||
                            lowerRow.contains(cleanHandle) ||
                            compactRow.contains(cleanHandle)
                    )

                    val hasVideoDuration = Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(lowerRow)
                    val hasChannelRowSignals = !hasVideoDuration && (
                            lowerRow.contains("subscribers") ||
                            lowerRow.contains("subscriber") ||
                            lowerRow.contains("videos") ||
                            lowerRow.contains("सदस्य") ||
                            lowerRow.contains("वीडियो") ||
                            lowerRow.contains("@") ||
                            lowerRow.contains("subscribe")
                    )

                    if ((matchesChannelName || matchesHandle) && hasChannelRowSignals) {
                        val rowRect = android.graphics.Rect()
                        rowContainer.getBoundsInScreen(rowRect)
                        val tapX = (screenWidth * 0.34f).toInt()
                        val tapY = rowRect.centerY().coerceIn((screenHeight * 0.16f).toInt(), (screenHeight * 0.85f).toInt())
                        val clicked = rowContainer.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                                node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                                dispatchTapGesture(tapX, tapY)
                        if (clicked) {
                            return true
                        }
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickChannelCard(child, targetChannel, targetHandle)) return true
        }
        return false
    }

    /**
     * Once inside a Creator's Channel Page, finds and taps the "Videos" tab
     * so all newly uploaded videos appear in strict newest-first order at the top!
     */
    private fun findAndClickChannelVideosTab(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val label = text.ifBlank { desc }.lowercase()
            if (label == "videos" ||
                label == "वीडियो" ||
                label.startsWith("videos,") ||
                label.startsWith("videos ")
            ) {
                val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (rect.top in (screenHeight * 0.08f).toInt()..(screenHeight * 0.65f).toInt()) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                            (node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) ||
                            (rect.centerX() > 0 && rect.centerY() > 0 && dispatchTapGesture(rect.centerX(), rect.centerY()))
                    if (clicked) return true
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickChannelVideosTab(child)) return true
        }
        return false
    }

    private fun findAndClickChannelShortsOrLiveTab(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val label = text.ifBlank { desc }.lowercase()
            if (label == "shorts" || label == "live" || label.startsWith("shorts,") || label.startsWith("live,")) {
                val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (rect.top in (screenHeight * 0.08f).toInt()..(screenHeight * 0.65f).toInt()) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                            (node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) ||
                            (rect.centerX() > 0 && rect.centerY() > 0 && dispatchTapGesture(rect.centerX(), rect.centerY()))
                    if (clicked) return true
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickChannelShortsOrLiveTab(child)) return true
        }
        return false
    }

    private fun isFullWatchPlayerScreen(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        return try {
            val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
            val entries = mutableListOf<UiNodeEntry>()
            collectScreenNodes(rootNode, entries)
            if (entries.isEmpty()) return false

            val hasTopSearchToolbar = entries.any { e ->
                val v = e.viewId.lowercase()
                val d = e.desc.lowercase()
                e.rect.top < (screenHeight * 0.16f).toInt() && (
                        v.contains("search_query") ||
                        v.contains("search_edit_text") ||
                        v.contains("menu_item_search") ||
                        v.contains("search_box") ||
                        d.equals("voice search", ignoreCase = true) ||
                        d.equals("search filters", ignoreCase = true) ||
                        d.equals("search youtube", ignoreCase = true) ||
                        d.equals("clear", ignoreCase = true)
                )
            }
            if (hasTopSearchToolbar) return false

            // On Home, Search Results, and Channel screens, YouTube's Bottom Navigation Bar (Home, Shorts, Subscriptions, You)
            // is visible at the bottom. On the Full Watch Player screen, the Bottom Navigation Bar is hidden!
            val hasBottomPivotBar = entries.any { e ->
                val v = e.viewId.lowercase()
                val d = e.desc.lowercase()
                val t = e.text.lowercase()
                e.rect.top >= (screenHeight * 0.84f).toInt() && (
                        v.contains("pivot_bar") ||
                        v.contains("bottom_bar") ||
                        d == "home" ||
                        d == "subscriptions" ||
                        t == "subscriptions" ||
                        d.startsWith("subscriptions,") ||
                        d.startsWith("home,")
                )
            }
            if (hasBottomPivotBar) return false

            val hasWatchPageActionOrMeta = entries.any { e ->
                val v = e.viewId.lowercase()
                val d = e.desc.lowercase()
                val t = e.text.lowercase()
                v.contains("watch_metadata") ||
                        v.contains("like_button") ||
                        v.contains("share_button") ||
                        v.contains("comments_entry_point") ||
                        v.contains("player_collapse_button") ||
                        v.contains("player_control_play_pause_replay_button") ||
                        v.contains("player_video_title") ||
                        d.startsWith("like this video") ||
                        d.startsWith("dislike this video") ||
                        d.equals("expand description", ignoreCase = true) ||
                        d.equals("minimize", ignoreCase = true) ||
                        d.equals("collapse", ignoreCase = true) ||
                        d.equals("enter full screen", ignoreCase = true) ||
                        t == "...more" ||
                        t == "…more" ||
                        t == "remix" ||
                        t == "clip"
            }

            val hasTopAnchoredWatchPlayer = entries.any { e ->
                val v = e.viewId.lowercase()
                val d = e.desc.lowercase()
                e.rect.top <= (screenHeight * 0.08f).toInt() &&
                        e.rect.bottom >= (screenHeight * 0.22f).toInt() && (
                        v.contains("player_view") ||
                        v.contains("player_fragment") ||
                        v.contains("player_collapse_button") ||
                        d.equals("video player", ignoreCase = true) ||
                        d.equals("hide controls", ignoreCase = true) ||
                        d.equals("show controls", ignoreCase = true)
                )
            }

            hasWatchPageActionOrMeta || hasTopAnchoredWatchPlayer
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Extracts a video card's duration in seconds from its accessibility description or badge text
     * (e.g. "3 minutes, 45 seconds", "3 मिनट, 45 सेकंड", or "3:45" / "1:02:15").
     */
    private fun extractCardDurationSeconds(cardText: String): Int {
        if (cardText.isBlank()) return 0
        // 1. Check explicit H:MM:SS or MM:SS badge
        val colonMatch = Regex("\\b(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})\\b").find(cardText)
        if (colonMatch != null) {
            val h = colonMatch.groupValues[1].toIntOrNull() ?: 0
            val m = colonMatch.groupValues[2].toIntOrNull() ?: 0
            val s = colonMatch.groupValues[3].toIntOrNull() ?: 0
            val total = h * 3600 + m * 60 + s
            if (total > 0) return total
        }

        // 2. Cut everything from "Go to channel" onwards and strip relative "X hours/minutes/seconds ago" / "X पहले" upload timestamps
        // so upload times like "2 hours ago" or "10 minutes ago" are NEVER mistaken for the video's duration!
        var cleanedForSpokenDuration = cardText.lowercase()
        val goToChannelIdx = Regex("\\b(?:go to channel|चैनल पर जाएं)\\b").find(cleanedForSpokenDuration)?.range?.first
        if (goToChannelIdx != null && goToChannelIdx > 0) {
            cleanedForSpokenDuration = cleanedForSpokenDuration.substring(0, goToChannelIdx)
        }
        cleanedForSpokenDuration = cleanedForSpokenDuration
            .replace(Regex("\\b(?:streamed|started\\s+streaming|premiered|scheduled)\\b.*$"), " ")
            .replace(Regex("\\b\\d+\\s*(?:hours?|hrs?|minutes?|mins?|seconds?|secs?|days?|weeks?|months?|years?)\\s+ago\\b"), " ")
            .replace(Regex("\\b\\d+\\s*(?:घंटे|घंटा|मिनट|सेकंड|दिन|हफ़्ते|महीने|साल)\\s+पहले\\b"), " ")

        val hoursMatch = Regex("\\b(\\d+)\\s*(?:hours?|hrs?|घंटे|घंटा)\\b").find(cleanedForSpokenDuration)
        val minsMatch = Regex("\\b(\\d+)\\s*(?:minutes?|mins?|मिनट)\\b").find(cleanedForSpokenDuration)
        val secsMatch = Regex("\\b(\\d+)\\s*(?:seconds?|secs?|सेकंड)\\b").find(cleanedForSpokenDuration)
        if (hoursMatch != null || minsMatch != null || secsMatch != null) {
            val h = hoursMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val m = minsMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val s = secsMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val total = h * 3600 + m * 60 + s
            if (total > 0) return total
        }
        return 0
    }

    /**
     * Extracts the total video duration in seconds from the Watch Player's timebar/scrubber
     * (e.g. "0:03 / 3:45" or "0 minutes 3 seconds of 3 minutes 45 seconds").
     */
    private fun extractWatchPlayerTotalDurationSeconds(entries: List<UiNodeEntry>): Int {
        for (e in entries) {
            for (raw in listOf(e.text, e.desc)) {
                val s = raw.trim()
                if (s.isEmpty()) continue
                val slashMatch = Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\s*/\\s*(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})\\b").find(s)
                if (slashMatch != null) {
                    val h = slashMatch.groupValues[1].toIntOrNull() ?: 0
                    val m = slashMatch.groupValues[2].toIntOrNull() ?: 0
                    val sec = slashMatch.groupValues[3].toIntOrNull() ?: 0
                    val total = h * 3600 + m * 60 + sec
                    if (total > 0) return total
                }
                val ofMatch = Regex("\\b(?:of|में से)\\s+(.+)$", RegexOption.IGNORE_CASE).find(s)
                if (ofMatch != null) {
                    val tailSecs = extractCardDurationSeconds(ofMatch.groupValues[1])
                    if (tailSecs > 0) return tailSecs
                }
            }
        }
        return 0
    }

    private fun confirmWatchPlayerOpened() {
        if (isWatchPlayerConfirmedOpen) return
        val root = getYouTubeRootNode() ?: try { rootInActiveWindow } catch (_: Exception) { null }
        isWatchPlayerConfirmedOpen = true
        isSearchOverlayActive = false
        currentPhase = LiveSearchPhase.COMPLETED
        searchDriverHandler.removeCallbacks(searchDriverRunnable)
        val title = targetSearchTitle
        if (!title.isNullOrBlank()) {
            WatchSessionRepository.onMediaMetadataChanged(title, targetSearchChannel, fromConfirmedWatchPlayer = true)
        }
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.postDelayed({
            try {
                val r = getYouTubeRootNode() ?: rootInActiveWindow
                if (r != null) verifyActiveYouTubeVideo(r)
            } catch (_: Exception) {}
        }, 600L)
    }

    private fun ensureWatchPlayerExpandedAfterClick(tapX: Int, titleTapY: Int, thumbTapY: Int, attempt: Int) {
        if (!isYouTubeInForeground) return
        try {
            val root = getYouTubeRootNode() ?: rootInActiveWindow ?: return
            if (isFullWatchPlayerScreen(root)) {
                confirmWatchPlayerOpened()
                return
            }

            // Check if we are still on the Search Results / Channel screen (top search toolbar or bottom pivot bar present).
            // NEVER fire tap gestures if YouTube has already left the Search Results screen!
            val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
            val entries = mutableListOf<UiNodeEntry>()
            collectScreenNodes(root, entries)
            val isStillOnSearchOrChannelList = entries.any { e ->
                val v = e.viewId.lowercase()
                val d = e.desc.lowercase()
                (e.rect.top < (screenHeight * 0.16f).toInt() && (
                    v.contains("search_query") ||
                    v.contains("search_edit_text") ||
                    v.contains("menu_item_search") ||
                    v.contains("search_box") ||
                    d.equals("voice search", ignoreCase = true) ||
                    d.equals("search filters", ignoreCase = true) ||
                    d.equals("clear", ignoreCase = true)
                )) || (e.rect.top >= (screenHeight * 0.84f).toInt() && (
                    v.contains("pivot_bar") ||
                    v.contains("bottom_bar") ||
                    d == "home" ||
                    d == "subscriptions"
                ))
            }

            if (!isStillOnSearchOrChannelList) {
                // YouTube is already transitioning to the Watch Player — wait for it to finish loading without tapping!
                if (attempt <= 2) {
                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    mainHandler.postDelayed({
                        ensureWatchPlayerExpandedAfterClick(tapX, titleTapY, thumbTapY, attempt + 1)
                    }, 600L)
                }
                return
            }

            // Still on Search Results list (e.g. inline preview absorbed first click) -> re-arm FIND_AND_CLICK_VIDEO
            // so the search loop locates the target video title node's live coordinates and clicks it cleanly
            val title = targetSearchTitle
            if (!title.isNullOrBlank()) {
                if (attempt == 1) {
                    dispatchTapGesture(tapX, titleTapY)
                    lastClickTime = System.currentTimeMillis()
                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    mainHandler.postDelayed({
                        ensureWatchPlayerExpandedAfterClick(tapX, titleTapY, thumbTapY, 2)
                    }, 850L)
                } else {
                    hasClickedTarget = false
                    isWatchPlayerConfirmedOpen = false
                    currentPhase = LiveSearchPhase.FIND_AND_CLICK_VIDEO
                    searchOverlayStatusText = "Opening..."
                    searchDriverHandler.removeCallbacks(searchDriverRunnable)
                    searchDriverHandler.postDelayed(searchDriverRunnable, 250L)
                }
            }
        } catch (_: Exception) {}
    }

    private fun getStatusBarHeight(): Int {
        return try {
            val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) {
                resources.getDimensionPixelSize(resId)
            } else {
                (32 * resources.displayMetrics.density).toInt()
            }
        } catch (_: Exception) {
            (32 * resources.displayMetrics.density).toInt()
        }
    }

    private fun updateVideoPausedState(paused: Boolean) {
        if (paused) {
            isVideoExplicitlyPaused = true
            lastExplicitPauseTime = System.currentTimeMillis()
            WatchSessionRepository.setPlaybackPlaying(false)
        } else {
            isVideoExplicitlyPaused = false
            lastExplicitPlayClickTime = System.currentTimeMillis()
            WatchSessionRepository.setPlaybackPlaying(true)
        }
    }

    private fun checkPlaybackControls(node: AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            val state = findPlayerControlState(node)
            when (state) {
                true -> {
                    // Explicit "Play video" / "Replay video" player control is visible -> video is paused
                    isPlayButtonCurrentlyVisible = true
                    updateVideoPausedState(true)
                }
                false -> {
                    // Explicit "Pause video" player control is visible -> video is playing
                    isPlayButtonCurrentlyVisible = false
                    updateVideoPausedState(false)
                }
                null -> {
                    isPlayButtonCurrentlyVisible = false
                    // Controls overlay not visible in this node; preserve current pause state
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Returns true if the YouTube video player control is showing "Play video" / "Replay video" (meaning paused),
     * false if showing "Pause video" (meaning playing), or null if player controls overlay is hidden.
     */
    private fun findPlayerControlState(node: AccessibilityNodeInfo?): Boolean? {
        if (node == null) return null
        if (node.isVisibleToUser) {
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val label = desc.ifBlank { text }
            val viewId = node.viewIdResourceName ?: ""
            val isPlayerControlBtn = viewId.contains("player_control_play_pause_replay_button", ignoreCase = true) ||
                    viewId.contains("play_pause_replay_button", ignoreCase = true) ||
                    viewId.contains("player_control_play_pause", ignoreCase = true)

            if (label.isNotEmpty() || isPlayerControlBtn) {
                val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                val inPlayerRegion = isPlayerControlBtn || (r.bottom in 1..(screenHeight * 0.65f).toInt() && r.top >= 0)

                if (inPlayerRegion) {
                    if (label.equals("Play video", ignoreCase = true) ||
                        label.equals("Replay video", ignoreCase = true) ||
                        (isPlayerControlBtn && label.equals("Play", ignoreCase = true)) ||
                        (isPlayerControlBtn && label.equals("Replay", ignoreCase = true)) ||
                        label.contains("वीडियो चलाएं") ||
                        label.contains("फिर से चलाएं")
                    ) {
                        return true
                    }
                    if (label.equals("Pause video", ignoreCase = true) ||
                        (isPlayerControlBtn && label.equals("Pause", ignoreCase = true)) ||
                        label.contains("वीडियो रोकें")
                    ) {
                        return false
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findPlayerControlState(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun isCommentPlaceholder(raw: String): Boolean {
        val lower = raw.trim().lowercase()
        return lower.isEmpty() ||
                lower.startsWith("add a comment") ||
                lower.startsWith("add a public comment") ||
                lower.startsWith("add a reply") ||
                lower.startsWith("comment as ") ||
                lower.startsWith("reply as ") ||
                lower.startsWith("search youtube") ||
                lower.contains("टिप्पणी जोड़ें") ||
                lower.contains("जवाब जोड़ें")
    }

    private fun isCommentAddedConfirmationText(lowerText: String): Boolean {
        if (lowerText.isBlank()) return false
        return lowerText.contains("comment added") ||
                lowerText.contains("comment posted") ||
                lowerText.contains("comment sent") ||
                lowerText.contains("comment submitted") ||
                lowerText.contains("reply added") ||
                lowerText.contains("reply posted") ||
                lowerText.contains("reply sent") ||
                lowerText.contains("your comment was added") ||
                lowerText.contains("टिप्पणी जोड़ी गई") ||
                lowerText.contains("टिप्पणी पोस्ट की गई") ||
                lowerText.contains("टिप्पणी भेजी गई") ||
                lowerText.contains("जवाब जोड़ा गया") ||
                lowerText.contains("जवाब भेजा गया")
    }

    private fun triggerGenuineCommentReward(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastCommentRewardTriggerTime < 3500L) {
            return
        }
        lastCommentRewardTriggerTime = now
        hasTypedCommentText = false
        lastTypedCommentText = ""
        lastTypedCommentTime = 0L
        wasCommentComposerOpen = false
        wasCommentEditTextActive = false
        lastCommentComposerOpenTime = 0L
        WatchSessionRepository.addLog("Comment detected ($reason)", LogType.SUCCESS)
        WatchSessionRepository.onTaskCommentDetected?.invoke()
    }

    private fun getAllYouTubeRootNodes(primaryRoot: AccessibilityNodeInfo? = null): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            if (primaryRoot != null && primaryRoot.packageName?.toString() == "com.google.android.youtube") {
                roots.add(primaryRoot)
            }
            val active = try { rootInActiveWindow } catch (_: Exception) { null }
            if (active != null && active.packageName?.toString() == "com.google.android.youtube" && !roots.contains(active)) {
                roots.add(active)
            }
            val winList = try { windows } catch (_: Exception) { null }
            if (!winList.isNullOrEmpty()) {
                for (w in winList) {
                    val wRoot = try { w.root } catch (_: Exception) { null }
                    if (wRoot != null && wRoot.packageName?.toString() == "com.google.android.youtube" && !roots.contains(wRoot)) {
                        roots.add(wRoot)
                    }
                }
            }
        } catch (_: Exception) {}
        return roots
    }

    @Volatile
    private var lastReportedCommentSheetState: Boolean = false
    private var lastCommentStateChangeTime: Long = 0L

    private fun updateCommentSheetState(isOpen: Boolean) {
        val now = System.currentTimeMillis()
        if (lastReportedCommentSheetState != isOpen) {
            // Debounce state transitions to prevent any blinking/flickering of the bottom lock bar
            if (now - lastCommentStateChangeTime > 400L || !isOpen) {
                lastReportedCommentSheetState = isOpen
                lastCommentStateChangeTime = now
                WatchSessionRepository.setCommentSheetOpen(isOpen)
            }
        }
    }

    private fun isCommentOrChatSheetCurrentlyOpen(root: AccessibilityNodeInfo?): Boolean {
        if (isSoftKeyboardVisible()) return true
        val now = System.currentTimeMillis()
        if (wasCommentComposerOpen && (now - lastCommentComposerOpenTime) < 3500L) {
            return true
        }
        val node = root ?: getYouTubeRootNode() ?: return false
        return checkCommentOrChatNodes(node)
    }

    private fun checkCommentOrChatNodes(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser) {
            val vId = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""

            // Explicit close button of comments panel or live chat
            val isExplicitCloseBtn = desc.equals("close comments", ignoreCase = true) ||
                    desc.equals("close chat", ignoreCase = true) ||
                    desc.equals("close live chat", ignoreCase = true) ||
                    desc.equals("close engagement panel", ignoreCase = true) ||
                    desc.contains("टिप्पणी बंद करें") ||
                    desc.contains("लाइव चैट बंद करें") ||
                    (vId.contains("close_button") && (desc.contains("close") || desc.contains("बंद") || text.contains("close")))

            // Active comment composer box or keyboard input area
            val isComposerActive = vId.contains("comment_composer") ||
                    vId.contains("reply_composer") ||
                    vId.contains("chat_input") ||
                    vId.contains("comment_box")

            if (isExplicitCloseBtn || isComposerActive) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = checkCommentOrChatNodes(child)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun isSoftKeyboardVisible(): Boolean {
        return try {
            val winList = windows
            winList?.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun climbToWindowRoot(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node ?: return null
        var hops = 0
        while (hops < 25) {
            val p = try { current.parent } catch (_: Exception) { null } ?: break
            current = p
            hops++
        }
        return current
    }

    private fun getYouTubeRootNode(): AccessibilityNodeInfo? {
        try {
            val active = try { rootInActiveWindow } catch (_: Exception) { null }
            if (active?.packageName?.toString() == "com.google.android.youtube" && active.childCount > 0) {
                return climbToWindowRoot(active)
            }
            val winList = try { windows } catch (_: Exception) { null }
            if (!winList.isNullOrEmpty()) {
                for (w in winList) {
                    if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val wRoot = try { w.root } catch (_: Exception) { null }
                        if (wRoot?.packageName?.toString() == "com.google.android.youtube" && wRoot.childCount > 0) {
                            return climbToWindowRoot(wRoot)
                        }
                    }
                }
            }
            if (active?.packageName?.toString() == "com.google.android.youtube") {
                return climbToWindowRoot(active)
            }
        } catch (_: Exception) {}
        return null
    }

    private fun isReadyForWatchVerification(): Boolean {
        if (!isWatchPlayerConfirmedOpen) return false
        val elapsedSinceLaunch = System.currentTimeMillis() - WatchSessionRepository.taskLaunchTimestampMillis
        val isLiveSearching = currentPhase != LiveSearchPhase.IDLE && currentPhase != LiveSearchPhase.COMPLETED
        return !isLiveSearching && elapsedSinceLaunch > 2000L
    }

    fun inspectCurrentYouTubeState() {
        val isSessionActive = WatchSessionRepository.sessionState.value == com.example.data.SessionState.ACTIVE
        if (!isSessionActive) return

        val elapsedSinceLaunch = System.currentTimeMillis() - WatchSessionRepository.taskLaunchTimestampMillis
        if (elapsedSinceLaunch < 900L) return

        val ytRootNow = getYouTubeRootNode()
        if (hasClickedTarget && !isWatchPlayerConfirmedOpen && ytRootNow != null && isFullWatchPlayerScreen(ytRootNow)) {
            confirmWatchPlayerOpened()
        }

        // Drive the organic YouTube Search / Browse state machine periodically every 500ms while searching
        if (!hasClickedTarget && currentPhase != LiveSearchPhase.IDLE && currentPhase != LiveSearchPhase.COMPLETED) {
            val ytRootForSearch = ytRootNow ?: getYouTubeRootNode()
            val titleToFind = targetSearchTitle
            if (ytRootForSearch != null && !titleToFind.isNullOrBlank()) {
                isYouTubeInForeground = true
                WatchSessionRepository.hasLeftAppForYouTube = true
                driveLiveSearchStep(ytRootForSearch, titleToFind)
            }
        }

        if (elapsedSinceLaunch < 3200L || !WatchSessionRepository.hasLeftAppForYouTube) return

        val myPkg = packageName ?: "com.example"
        var ytAppRoot: AccessibilityNodeInfo? = null

        try {
            val winList = try { windows } catch (_: Exception) { null }
            if (!winList.isNullOrEmpty()) {
                var topExternalAppPkg: String? = null
                for (w in winList) {
                    if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val wRoot = try { w.root } catch (_: Exception) { null }
                        val wPkg = wRoot?.packageName?.toString() ?: ""
                        if (wPkg == "com.google.android.youtube") {
                            if (w.isInPictureInPictureMode) {
                                isYouTubeInForeground = false
                                WatchSessionRepository.triggerTaskIncomplete(
                                    "Task Incomplete! Aapne YouTube minimize kar diya hai. Task complete hone tak YouTube par target video dekhna zaroori hai."
                                )
                                return
                            }
                            if (ytAppRoot == null) {
                                ytAppRoot = wRoot
                            }
                        }
                        if (topExternalAppPkg == null && wPkg.isNotBlank() && wPkg != myPkg && !isTransientSystemPackage(wPkg)) {
                            topExternalAppPkg = wPkg
                        }
                    }
                }

                if (ytAppRoot != null) {
                    // YouTube application window is actively visible on screen (even if a keyboard/IME window is also open)
                    notInYouTubeStrikeCount = 0
                    isYouTubeInForeground = true
                } else if (topExternalAppPkg != null && topExternalAppPkg != "com.google.android.youtube") {
                    notInYouTubeStrikeCount++
                    if (notInYouTubeStrikeCount >= 2) {
                        isYouTubeInForeground = false
                        WatchSessionRepository.triggerTaskIncomplete(
                            "Task Incomplete! Aapne YouTube minimize kar diya ya YouTube se back kar ke doosre app mein switch kar liya."
                        )
                    }
                    return
                } else if (topExternalAppPkg == "com.google.android.youtube") {
                    notInYouTubeStrikeCount = 0
                    isYouTubeInForeground = true
                }
            } else {
                val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
                val inPip = try { activeRoot?.window?.isInPictureInPictureMode == true } catch (_: Exception) { false }
                if (inPip) {
                    isYouTubeInForeground = false
                    WatchSessionRepository.triggerTaskIncomplete(
                        "Task Incomplete! Aapne YouTube minimize kar diya hai."
                    )
                    return
                }
                val activePkg = activeRoot?.packageName?.toString() ?: ""
                if (activePkg.isNotBlank() && activePkg != "com.google.android.youtube" && activePkg != myPkg && !isTransientSystemPackage(activePkg)) {
                    notInYouTubeStrikeCount++
                    if (notInYouTubeStrikeCount >= 2) {
                        isYouTubeInForeground = false
                        WatchSessionRepository.triggerTaskIncomplete(
                            "Task Incomplete! Aapne YouTube minimize kar diya ya YouTube se back kar ke doosre app mein switch kar liya."
                        )
                    }
                    return
                } else if (activePkg == "com.google.android.youtube") {
                    notInYouTubeStrikeCount = 0
                    isYouTubeInForeground = true
                    ytAppRoot = activeRoot
                }
            }

            if (isYouTubeInForeground && isReadyForWatchVerification()) {
                val rootToInspect = getYouTubeRootNode() ?: ytAppRoot
                if (rootToInspect != null) {
                    checkPlaybackControls(rootToInspect)
                    val now = System.currentTimeMillis()
                    if (now - lastWatchHeaderCheckTime >= 400L) {
                        lastWatchHeaderCheckTime = now
                        verifyActiveYouTubeVideo(rootToInspect)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun checkIfUserClickedDifferentVideo(
        clickedNode: AccessibilityNodeInfo?,
        desc: String,
        text: String,
        viewId: String,
        eventSummary: String = ""
    ) {
        val targetTitle = WatchSessionRepository.targetTaskTitle.value ?: return
        val targetAuthor = WatchSessionRepository.targetTaskAuthor.value

        // ABSOLUTE GUARD: Commenting, posting comment, typing, reading comments, scrolling, or interacting with the watch page must NEVER trigger task incomplete!
        val now = System.currentTimeMillis()
        val isCommentOrSendAction = desc.equals("Send", ignoreCase = true) ||
                desc.equals("Send comment", ignoreCase = true) ||
                desc.equals("Post", ignoreCase = true) ||
                desc.equals("Post comment", ignoreCase = true) ||
                desc.equals("Comment", ignoreCase = true) ||
                desc.equals("Reply", ignoreCase = true) ||
                desc.equals("भेजें", ignoreCase = true) ||
                desc.contains("टिप्पणी") ||
                desc.contains("जवाब") ||
                text.equals("Send", ignoreCase = true) ||
                text.equals("Post", ignoreCase = true) ||
                text.equals("Comment", ignoreCase = true) ||
                text.equals("Reply", ignoreCase = true) ||
                text.equals("भेजें", ignoreCase = true) ||
                text.contains("टिप्पणी") ||
                text.contains("जवाब") ||
                viewId.contains("comment", ignoreCase = true) ||
                viewId.contains("composer", ignoreCase = true) ||
                viewId.contains("reply", ignoreCase = true) ||
                viewId.contains("send_button", ignoreCase = true) ||
                viewId.contains("post_button", ignoreCase = true) ||
                viewId.contains("comment_send", ignoreCase = true) ||
                viewId.contains("bottom_sheet", ignoreCase = true) ||
                viewId.contains("engagement_panel", ignoreCase = true) ||
                isSoftKeyboardVisible() ||
                (now - lastCommentClickTime) < 4000L ||
                (now - lastTypedCommentTime) < 4000L

        if (isCommentOrSendAction) {
            lastCommentClickTime = now
            return
        }

        // ABSOLUTE GUARD: Target video's product list, tagged products, or shopping shelf must NEVER trigger task incomplete!
        val isProductAction = desc.contains("product", ignoreCase = true) ||
                desc.contains("shopping", ignoreCase = true) ||
                desc.contains("view products", ignoreCase = true) ||
                desc.contains("tagged products", ignoreCase = true) ||
                desc.contains("products in this video", ignoreCase = true) ||
                desc.contains("explore products", ignoreCase = true) ||
                desc.contains("उत्पाद") ||
                desc.contains("खरीदारी") ||
                text.contains("product", ignoreCase = true) ||
                text.contains("shopping", ignoreCase = true) ||
                text.contains("उत्पाद") ||
                text.contains("खरीदारी") ||
                viewId.contains("product", ignoreCase = true) ||
                viewId.contains("shopping", ignoreCase = true) ||
                viewId.contains("commerce", ignoreCase = true) ||
                viewId.contains("merch", ignoreCase = true) ||
                viewId.contains("store", ignoreCase = true) ||
                viewId.contains("shelf", ignoreCase = true) ||
                viewId.contains("cart", ignoreCase = true) ||
                (now - lastProductClickTime) < 5000L

        if (isProductAction) {
            lastProductClickTime = now
            isProductListOpen = true
            return
        }

        // Check if clicked node or any ancestor is part of a comment thread, sheet, or product panel
        var ancestor = clickedNode
        var aDepth = 0
        while (ancestor != null && aDepth < 6) {
            val aId = ancestor.viewIdResourceName?.lowercase() ?: ""
            val aDesc = ancestor.contentDescription?.toString()?.lowercase() ?: ""
            val aText = ancestor.text?.toString()?.lowercase() ?: ""
            if (aId.contains("comment") || aId.contains("engagement_panel") || aId.contains("bottom_sheet") || aId.contains("composer")) {
                lastCommentClickTime = now
                return
            }
            if (aId.contains("product") || aId.contains("shopping") || aId.contains("commerce") || aId.contains("merch") || aId.contains("store") || aId.contains("shelf") ||
                aDesc.contains("product") || aDesc.contains("shopping") || aText.contains("product") || aText.contains("shopping")
            ) {
                lastProductClickTime = now
                isProductListOpen = true
                return
            }
            ancestor = ancestor.parent
            aDepth++
        }

        // ONLY fail task if user explicitly pressed player control to skip video, or navigated away from Watch screen
        if (desc.equals("Next video", ignoreCase = true) ||
            desc.equals("Previous video", ignoreCase = true) ||
            desc.contains("अगला वीडियो") ||
            desc.contains("पिछला वीडियो") ||
            viewId.contains("player_control_next", ignoreCase = true) ||
            viewId.contains("player_control_previous", ignoreCase = true) ||
            viewId.contains("autonav", ignoreCase = true)
        ) {
            WatchSessionRepository.triggerTaskIncomplete(
                "Task Incomplete! Aapne YouTube player mein doosra video switch kar diya. Sirf target video dekhne par hi timer chalega."
            )
            return
        }

        if ((desc.equals("Shorts", ignoreCase = true) || text.equals("Shorts", ignoreCase = true) || viewId.contains("reel", ignoreCase = true)) &&
            !targetTitle.contains("shorts", ignoreCase = true)
        ) {
            WatchSessionRepository.triggerTaskIncomplete(
                "Task Incomplete! Aapne target video chod kar YouTube Shorts open kar liya."
            )
            return
        }

        // If user manually clicks YouTube bottom navigation tabs (Home, Subscriptions, You) or Search / Collapse while watching
        if (desc.equals("Home", ignoreCase = true) ||
            desc.equals("Subscriptions", ignoreCase = true) ||
            desc.equals("Library", ignoreCase = true) ||
            desc.equals("You", ignoreCase = true) ||
            desc.equals("Minimize", ignoreCase = true) ||
            desc.equals("Collapse", ignoreCase = true) ||
            viewId.contains("search_edit_text", ignoreCase = true) ||
            viewId.contains("menu_item_search", ignoreCase = true) ||
            viewId.contains("player_collapse_button", ignoreCase = true) ||
            desc.equals("Search", ignoreCase = true) ||
            desc.equals("Search YouTube", ignoreCase = true)
        ) {
            WatchSessionRepository.triggerTaskIncomplete(
                "Task Incomplete! Aapne target video se hat kar YouTube mein doosra page ya search open kar liya."
            )
            return
        }

        // Check if user clicked a DIFFERENT video recommendation card below the player
        val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(400)
        val statusBarHeight = getStatusBarHeight()
        val playerBottomY = statusBarHeight + ((screenWidth * 9) / 16)
        val clickRect = android.graphics.Rect()
        clickedNode?.getBoundsInScreen(clickRect)

        if (clickRect.width() > 0 && clickRect.height() > 0 && clickRect.top >= playerBottomY) {
            var cardNode: AccessibilityNodeInfo? = clickedNode
            var depth = 0
            while (cardNode != null && depth < 4) {
                val parent = cardNode.parent ?: break
                if (parent.isScrollable) break
                val pViewId = parent.viewIdResourceName?.lowercase() ?: ""
                if (pViewId.contains("comment") || pViewId.contains("bottom_sheet") || pViewId.contains("engagement") ||
                    pViewId.contains("product") || pViewId.contains("shopping") || pViewId.contains("composer")
                ) {
                    return
                }
                val pRect = android.graphics.Rect()
                parent.getBoundsInScreen(pRect)
                if (pRect.top >= playerBottomY) {
                    cardNode = parent
                } else {
                    break
                }
                depth++
            }

            val sb = StringBuilder()
            if (eventSummary.isNotBlank()) sb.append(eventSummary).append(" ")
            if (text.isNotBlank() && !sb.contains(text)) sb.append(text).append(" ")
            if (desc.isNotBlank() && !sb.contains(desc)) sb.append(desc).append(" ")
            if (cardNode != null) {
                collectSubtreeText(cardNode, sb, 0)
            }
            val cardText = sb.toString().trim()
            val lowerCard = cardText.lowercase()
            val cardViewId = (cardNode?.viewIdResourceName ?: viewId).lowercase()

            val isCommentOrSheet = lowerCard.contains("comment") || lowerCard.contains("reply") || lowerCard.contains("टिप्पणी") ||
                    lowerCard.contains("add a comment") || cardViewId.contains("comment") || cardViewId.contains("composer") ||
                    cardViewId.contains("engagement") || cardViewId.contains("bottom_sheet")

            val isProductOrShop = lowerCard.contains("product") || lowerCard.contains("shopping") || lowerCard.contains("view products") ||
                    cardViewId.contains("product") || cardViewId.contains("shopping") || cardViewId.contains("commerce")

            val isActionButton = lowerCard.contains("like this") || lowerCard.contains("dislike this") || lowerCard.contains("subscribe") ||
                    lowerCard.contains("share") || lowerCard.contains("download") || lowerCard.contains("remix") || lowerCard.contains("save") ||
                    lowerCard.contains("...more") || lowerCard.contains("show more") || lowerCard.contains("show less")

            if (!isCommentOrSheet && !isProductOrShop && !isActionButton) {
                val isVideoCard = cardViewId.contains("video") || cardViewId.contains("thumbnail") || cardViewId.contains("lockup") ||
                        cardViewId.contains("rich_item") || cardViewId.contains("feed") ||
                        lowerCard.contains("views") || lowerCard.contains("views •") ||
                        lowerCard.contains("व्यू") || lowerCard.contains("दृश्य") ||
                        lowerCard.contains("ago") || lowerCard.contains("पहले") ||
                        lowerCard.contains("go to channel") || lowerCard.contains("चैनल पर जाएं") ||
                        Regex("""\b\d+:\d{2}\b""").containsMatchIn(cardText)

                if (isVideoCard) {
                    val clickedTitle = TitleMatcher.extractCardVideoTitleOnly(cardText, null, null).ifBlank {
                        extractCleanTitleCandidate(cardText)
                    }
                    if (clickedTitle.length >= 4 && !CHROME_LABELS.contains(clickedTitle.lowercase())) {
                        val match = TitleMatcher.evaluateMatch(
                            playingTitle = clickedTitle,
                            taskTitle = targetTitle,
                            playingArtist = null,
                            taskAuthor = targetAuthor
                        )
                        if (match == com.example.data.MatchResult.MISMATCH) {
                            WatchSessionRepository.triggerTaskIncomplete(
                                "Task Incomplete! Aapne YouTube mein target video (\"$targetTitle\") ke bajaye doosra video (\"$clickedTitle\") play kar diya."
                            )
                            return
                        }
                    }
                }
            }
        }
    }

    private fun collectSubtreeText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 8) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        if (!t.isNullOrBlank()) sb.append(t).append(" ")
        if (!d.isNullOrBlank() && d != t) sb.append(d).append(" ")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectSubtreeText(child, sb, depth + 1)
            child.recycle()
        }
    }

    private data class UiNodeEntry(
        val text: String,
        val desc: String,
        val viewId: String,
        val rect: android.graphics.Rect,
        val className: String = "",
        val isEditable: Boolean = false,
        val isFocused: Boolean = false
    )

    private fun collectScreenNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<UiNodeEntry>,
        depth: Int = 0,
        visitedCount: IntArray = intArrayOf(0)
    ) {
        if (node == null || depth > 42 || out.size > 750 || visitedCount[0] > 1500) return
        visitedCount[0]++

        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim() ?: ""
            val d = node.contentDescription?.toString()?.trim() ?: ""
            val v = node.viewIdResourceName ?: ""
            val cls = node.className?.toString() ?: ""
            val editable = node.isEditable || cls.contains("EditText", ignoreCase = true)
            val focused = node.isFocused
            val hasRelevantViewId = v.isNotEmpty() && (
                    v.contains("player", ignoreCase = true) ||
                    v.contains("reel", ignoreCase = true) ||
                    v.contains("subscribe", ignoreCase = true) ||
                    v.contains("like", ignoreCase = true) ||
                    v.contains("title", ignoreCase = true) ||
                    v.contains("miniplayer", ignoreCase = true) ||
                    v.contains("floaty", ignoreCase = true) ||
                    v.contains("watch", ignoreCase = true) ||
                    v.contains("search", ignoreCase = true) ||
                    v.contains("pivot", ignoreCase = true) ||
                    v.contains("comment", ignoreCase = true) ||
                    v.contains("engagement", ignoreCase = true) ||
                    v.contains("send", ignoreCase = true) ||
                    v.contains("snackbar", ignoreCase = true)
            )
            if (t.isNotEmpty() || d.isNotEmpty() || hasRelevantViewId || editable) {
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
                val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(400)
                if (r.width() > 0 && r.height() > 0 && r.bottom > 0 && r.top < screenHeight && r.right > 0 && r.left < screenWidth) {
                    out.add(UiNodeEntry(t, d, v, r, cls, editable, focused))
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectScreenNodes(child, out, depth + 1, visitedCount)
            child.recycle()
        }
    }

    /**
     * Strips trailing YouTube view count, upload time ("125K views 3 days ago ...more"), and action suffixes
     * from a combined Litho watch header node while discarding standalone view-count/subscriber strings.
     */
    private fun extractCleanTitleCandidate(raw: String): String {
        var singleLine = raw.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        if (singleLine.length < 4) return ""

        // Strip leading "Expand description" or "Description" prefixes added by accessibility labels
        singleLine = singleLine
            .replace(Regex("^(?:expand description|collapse description|description)\\s*[:,\\-•·|]?\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        // Strip leading hashtags if followed by actual title words
        val withoutLeadingHashtags = singleLine.replace(Regex("^(?:#\\S+\\s+)+"), "").trim()
        if (withoutLeadingHashtags.length >= 4) {
            singleLine = withoutLeadingHashtags
        }
        if (singleLine.startsWith("#") && !singleLine.contains(" ")) return ""

        // Discard player seekbar / duration strings ("0 minutes 15 seconds of 4 minutes 30 seconds" or "0:15 / 4:30")
        if (Regex("^\\d+\\s*(?:hours?|minutes?|seconds?|घंटे|मिनट|सेकंड)\\b.*\\b(?:of|में से)\\b.*$", RegexOption.IGNORE_CASE).matches(singleLine)) {
            return ""
        }
        if (Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*/\\s*\\d{1,2}:\\d{2}(?::\\d{2})?)?$", RegexOption.IGNORE_CASE).matches(singleLine)) {
            return ""
        }
        // Discard strings that start directly with a numeric view/subscriber/like count (including lakh/crore)
        if (Regex("^\\d[0-9.,]*\\s*(?:k|m|b|lakh|lakhs|crore|crores|हज़ार|लाख|करोड़)?\\s*(?:views|view|watching|subscribers|likes|comments|बार|सदस्य)\\b.*$", RegexOption.IGNORE_CASE).matches(singleLine)) {
            return ""
        }
        if (singleLine.equals("No views", ignoreCase = true)) return ""
        // Discard strings that are only relative time ("3 days ago", "Streamed 2 hours ago")
        if (Regex("^(?:streamed\\s+|premiered\\s+)?\\d+\\s+(?:second|minute|hour|day|week|month|year)s?\\s+ago\\b.*$", RegexOption.IGNORE_CASE).matches(singleLine)) {
            return ""
        }

        var cleaned = singleLine
            .replace(Regex("(?:\\.\\.\\.more|…more|\\bshow more\\b|\\bexpand description\\b)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()

        // Strip trailing "<number> views / watching / No views ..." metadata appended to the title in YouTube's Litho header
        cleaned = cleaned.replace(
            Regex("(?:[,\\-•·|]|\\s)+(?:no\\s+views|\\d[0-9.,]*\\s*(?:k|m|b|lakh|lakhs|crore|crores|हज़ार|लाख|करोड़)?\\s*(?:views|view|watching|subscribers|बार देखा गया|लोग देख रहे हैं))\\b.*$", RegexOption.IGNORE_CASE),
            ""
        ).trim()

        // Strip trailing "<number> minutes/seconds" duration or "<number> days ago"
        cleaned = cleaned.replace(
            Regex("(?:[,\\-•·|]|\\s)+\\d+\\s+(?:second|minute|hour|day|week|month|year)s?\\s+ago\\b.*$", RegexOption.IGNORE_CASE),
            ""
        ).trim()

        return cleaned
    }

    private fun isYouTubeAdPlaying(entries: List<UiNodeEntry>): Boolean {
        if (entries.isEmpty()) return false
        return entries.any { e ->
            val v = e.viewId.lowercase()
            val t = e.text.trim().lowercase()
            val d = e.desc.trim().lowercase()
            val comb = "$t $d"

            val isAdViewId = v.contains("ad_") ||
                    v.contains("ad_countdown") ||
                    v.contains("skip_ad") ||
                    v.contains("skip_button") ||
                    v.contains("ad_companion") ||
                    v.contains("ad_badge") ||
                    v.contains("brand_interaction") ||
                    v.contains("ad_cta") ||
                    v.contains("cta_button") ||
                    v.contains("advertiser") ||
                    v.contains("sparkles_")

            val isAdText = comb.contains("skip ad") ||
                    comb.contains("skip ads") ||
                    comb.contains("skip in") ||
                    comb.contains("video will play after ad") ||
                    comb.contains("video will begin in") ||
                    comb.contains("ad will end in") ||
                    comb.contains("visit advertiser") ||
                    comb.contains("visit site") ||
                    comb.contains("visit sponsor") ||
                    comb.contains("shop now") ||
                    comb.contains("install now") ||
                    comb.contains("open app") ||
                    comb.contains("ad ·") ||
                    comb.contains("ad •") ||
                    comb.startsWith("ad ") ||
                    comb.contains("sponsored ·") ||
                    comb.startsWith("sponsored") ||
                    comb.contains("विज्ञापन") ||
                    comb.contains("प्रायोजित") ||
                    comb.matches(Regex(".*\\b(?:ad|sponsored)\\s*[•·]?\\s*\\d+\\s+of\\s+\\d+.*", RegexOption.IGNORE_CASE))

            isAdViewId || isAdText
        }
    }

    private fun isCommentsSheetOrKeyboardOpen(entries: List<UiNodeEntry>): Boolean {
        if (isSoftKeyboardVisible()) return true
        val now = System.currentTimeMillis()
        if ((now - lastCommentClickTime) < 8000L || (now - lastTypedCommentTime) < 8000L || (now - lastCommentComposerOpenTime) < 15000L) {
            return true
        }
        val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
        
        // True comment sheet or composer is open ONLY when active composer, close button, or engagement panel is displayed
        val hasCloseCommentBtn = entries.any { e ->
            val d = e.desc.trim().lowercase()
            val v = e.viewId.lowercase()
            (d == "close comments" || d == "टिप्पणियां बंद करें" || d.contains("close comment") || d == "close live chat" ||
             (d == "close" && (v.contains("close_button") || v.contains("panel") || v.contains("sheet") || v.contains("header")))) &&
            e.rect.top in (screenHeight * 0.10f).toInt()..(screenHeight * 0.95f).toInt()
        }
        if (hasCloseCommentBtn) return true

        return entries.any { e ->
            val v = e.viewId.lowercase()
            val d = e.desc.trim().lowercase()
            val t = e.text.trim().lowercase()
            val comb = "$t $d $v"

            (e.isEditable && (v.contains("comment") || v.contains("reply") || v.contains("composer") || v.contains("input"))) ||
            v.contains("comment_sheet") ||
            v.contains("comment_composer") ||
            v.contains("comment_box") ||
            (v.contains("engagement_panel") && (v.contains("comment") || comb.contains("comment_item") || comb.contains("टिप्पणी") || comb.contains("live chat"))) ||
            (t.contains("community guidelines") || comb.contains("respectful by following") || comb.contains("top messages"))
        }
    }

    private fun isProductListOrShoppingOpen(entries: List<UiNodeEntry>): Boolean {
        val now = System.currentTimeMillis()
        if ((now - lastProductClickTime) < 6000L || isProductListOpen) return true
        val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
        return entries.any { e ->
            val v = e.viewId.lowercase()
            val d = e.desc.trim().lowercase()
            val t = e.text.trim().lowercase()
            val comb = "$d $t $v"

            v.contains("product_sheet") ||
            v.contains("shopping_sheet") ||
            v.contains("commerce_sheet") ||
            v.contains("products_panel") ||
            v.contains("shopping_panel") ||
            v.contains("shopping_view") ||
            v.contains("product_list") ||
            v.contains("tagged_products") ||
            ((d == "close products" || d == "close shopping" || d == "उत्पाद बंद करें" || (v.contains("close_button") && (v.contains("product") || v.contains("shopping")))) &&
             e.rect.top in (screenHeight * 0.20f).toInt()..(screenHeight * 0.95f).toInt()) ||
            comb.contains("products in this video") ||
            comb.contains("view products") ||
            comb.contains("tagged products") ||
            comb.contains("explore products") ||
            (comb.contains("products") && comb.contains("("))
        }
    }

    private fun verifyActiveYouTubeVideo(rootNode: AccessibilityNodeInfo?) {
        if (rootNode == null) return
        val targetTitle = WatchSessionRepository.targetTaskTitle.value ?: return
        val targetAuthor = WatchSessionRepository.targetTaskAuthor.value

        try {
            val entries = mutableListOf<UiNodeEntry>()
            val allRoots = getAllYouTubeRootNodes(rootNode)
            if (allRoots.isEmpty()) {
                collectScreenNodes(rootNode, entries)
            } else {
                for (r in allRoots) {
                    collectScreenNodes(r, entries)
                }
            }
            if (entries.isEmpty()) return

            val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
            val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(400)
            val density = resources.displayMetrics.density
            val statusBarHeight = getStatusBarHeight()
            val playerBottomY = statusBarHeight + ((screenWidth * 9) / 16)
            val now = System.currentTimeMillis()

            // Check if YouTube is currently running an Ad (pre-roll or mid-roll)
            val isAdPlaying = isYouTubeAdPlaying(entries)
            if (isAdPlaying) {
                // Normal video playback starts with an Ad; never fail or strike during ads!
                wrongVideoStrikeCount = 0
                return
            }

            // Check if user is typing comments or comments sheet is open
            val isCommentActive = isCommentsSheetOrKeyboardOpen(entries)
            updateCommentSheetState(isCommentActive)
            if (!isCommentActive) {
                wasCommentComposerOpen = false
                wasCommentEditTextActive = false
                hasTypedCommentText = false
            }

            // Check if target video's product list / shopping engagement panel is open
            val isProductActive = isProductListOrShoppingOpen(entries)
            if (!isProductActive) {
                isProductListOpen = false
            } else {
                wrongVideoStrikeCount = 0
            }

            // Also check if YouTube's Like button is currently in Liked / Selected state
            val likeEntry = entries.firstOrNull { e ->
                val v = e.viewId.lowercase()
                val d = e.desc.lowercase()
                v.contains("like_button") || v.contains("segmented_like") || d.startsWith("like this video") || d.startsWith("unlike this video")
            }
            if (likeEntry != null) {
                val d = likeEntry.desc.lowercase()
                val isLikedOnScreen = d.startsWith("unlike") || d.contains("remove like") || d.contains("liked")
                if (isLikedOnScreen && !wasTargetVideoLikedInSession) {
                    wasTargetVideoLikedInSession = true
                    WatchSessionRepository.addLog("YouTube target video is in Liked state!", LogType.SUCCESS)
                    WatchSessionRepository.onTaskLikeDetected?.invoke()
                }
            }

            // 0. Check for YouTube Comment Added / Composer State / Newly Posted Comment in real-time
            if (entries.any { e -> isCommentAddedConfirmationText("${e.text} ${e.desc}".lowercase()) }) {
                triggerGenuineCommentReward("YouTube 'Comment added' screen confirmation")
            } else {
                val activeCommentEditEntry = entries.firstOrNull { e ->
                    val v = e.viewId.lowercase()
                    val isSearch = v.contains("search_edit_text") || v.contains("search_src_text") || v.contains("search_box") || e.rect.top < (screenHeight * 0.16f).toInt()
                    !isSearch && (
                        e.isEditable ||
                        e.className.contains("EditText", ignoreCase = true) ||
                        v.contains("comment_composer") ||
                        v.contains("comment_box")
                    )
                }

                val isSendButtonCurrentlyVisible = entries.any { e ->
                    val d = e.desc.trim()
                    val t = e.text.trim()
                    val v = e.viewId.lowercase()
                    e.rect.top >= (screenHeight * 0.25f).toInt() && (
                        d.equals("Send", ignoreCase = true) ||
                        d.equals("Send comment", ignoreCase = true) ||
                        d.equals("Post", ignoreCase = true) ||
                        d.equals("Post comment", ignoreCase = true) ||
                        d.contains("टिप्पणी भेजें") ||
                        d.equals("भेजें", ignoreCase = true) ||
                        t.equals("Send", ignoreCase = true) ||
                        t.equals("Post", ignoreCase = true) ||
                        v.contains("send_button") ||
                        v.contains("comment_send") ||
                        v.contains("post_button")
                    )
                }

                if (activeCommentEditEntry != null || isSendButtonCurrentlyVisible) {
                    wasCommentComposerOpen = true
                    lastCommentComposerOpenTime = now
                    val editTxt = activeCommentEditEntry?.text?.trim().orEmpty()
                    if ((editTxt.isNotEmpty() && !isCommentPlaceholder(editTxt)) || isSendButtonCurrentlyVisible) {
                        hasTypedCommentText = true
                        if (editTxt.isNotEmpty() && !isCommentPlaceholder(editTxt)) {
                            lastTypedCommentText = editTxt
                        }
                        lastTypedCommentTime = now
                        wasCommentEditTextActive = true
                    }
                } else if (isSoftKeyboardVisible() && entries.any { e ->
                        val comb = "${e.text} ${e.desc} ${e.viewId}".lowercase()
                        comb.contains("comment") || comb.contains("reply") || comb.contains("टिप्पणी")
                    }) {
                    wasCommentComposerOpen = true
                    lastCommentComposerOpenTime = now
                } else {
                    val notCancelled = (now - lastCommentCancelClickTime) > 3500L
                    if (wasCommentEditTextActive && hasTypedCommentText && notCancelled && (now - lastTypedCommentTime) in 120L..30_000L) {
                        triggerGenuineCommentReward("Comment composer submitted and closed")
                    } else if ((hasTypedCommentText || wasCommentComposerOpen) && notCancelled && (now - lastCommentComposerOpenTime) < 90_000L) {
                        val freshCommentEntry = entries.firstOrNull { e ->
                            val comb = "${e.text} ${e.desc}".lowercase()
                            !e.isEditable && e.rect.top >= playerBottomY && (
                                Regex("\\b(?:0|1|2|3|4|5|6|7|8)\\s*(?:seconds?|secs?|s)\\s+ago\\b", RegexOption.IGNORE_CASE).containsMatchIn(comb) ||
                                comb.contains("just now") ||
                                comb.contains("a moment ago") ||
                                comb.contains("few seconds ago") ||
                                comb.contains("अभी") ||
                                comb.contains("कुछ सेकंड पहले") ||
                                Regex("\\b[0-8]\\s*सेकंड\\s*पहले\\b").containsMatchIn(comb) ||
                                (lastTypedCommentText.length >= 2 && comb.contains(lastTypedCommentText.lowercase()))
                            )
                        }
                        if (freshCommentEntry != null) {
                            triggerGenuineCommentReward("Newly posted comment visible in comments list")
                        }
                    }
                }
            }

            // 1. Check if user switched to YouTube Shorts player
            val isShortsPlayer = entries.any { e ->
                val v = e.viewId.lowercase()
                v.contains("reel_player") || v.contains("reel_recycler") || v.contains("reel_dyn_")
            }
            if (isShortsPlayer) {
                val allShortsText = entries.joinToString(" ") { "${it.text} ${it.desc}" }
                val shortsMatch = TitleMatcher.evaluateMatch(allShortsText, targetTitle, null, targetAuthor)
                if (shortsMatch == com.example.data.MatchResult.MISMATCH) {
                    wrongVideoStrikeCount = 0
                    WatchSessionRepository.triggerTaskIncomplete(
                        "Task Incomplete! Aapne target video chod kar YouTube Shorts play kar diya."
                    )
                    return
                }
            }

            // 2. Check if user minimized the video into YouTube's bottom Miniplayer bar
            val hasMiniplayerBarAtBottom = entries.any { e ->
                val v = e.viewId.lowercase()
                val d = e.desc.lowercase()
                val inBottomZone = e.rect.top >= (screenHeight * 0.65f).toInt()
                inBottomZone && (
                        v.contains("miniplayer") ||
                        v.contains("floaty_bar") ||
                        d.equals("expand miniplayer", ignoreCase = true) ||
                        d.equals("close miniplayer", ignoreCase = true)
                )
            }

            if (hasMiniplayerBarAtBottom) {
                wrongVideoStrikeCount = 0
                WatchSessionRepository.triggerTaskIncomplete(
                    "Task Incomplete! Aapne YouTube mein target video minimize (miniplayer) kar diya."
                )
                return
            }

            // 3. Check explicit player title if visible inside the top player
            val explicitPlayerTitleNode = entries.firstOrNull { e ->
                val v = e.viewId.lowercase()
                (v.contains("player_video_title") || (v.contains("title") && e.rect.top in 0..playerBottomY)) &&
                        (e.text.length >= 4 || e.desc.length >= 4)
            }
            if (explicitPlayerTitleNode != null && !isAdPlaying) {
                val rawPTitle = explicitPlayerTitleNode.text.ifBlank { explicitPlayerTitleNode.desc }
                val pTitle = extractCleanTitleCandidate(rawPTitle).ifBlank { rawPTitle }
                val lowP = pTitle.lowercase()
                val isPAd = lowP.startsWith("ad ·") || lowP.startsWith("ad •") || lowP.startsWith("sponsored") || lowP.contains("advertiser") || lowP.contains("skip ad")
                if (!isPAd) {
                    val match = TitleMatcher.evaluateMatch(pTitle, targetTitle, null, targetAuthor)
                    if (match == com.example.data.MatchResult.MATCH) {
                        if (lockedWatchPageTitle == null && pTitle.length >= 5) {
                            lockedWatchPageTitle = pTitle
                        }
                        wrongVideoStrikeCount = 0
                        return
                    } else if (match == com.example.data.MatchResult.MISMATCH) {
                        wrongVideoStrikeCount = 0
                        WatchSessionRepository.triggerTaskIncomplete(
                            "Task Incomplete! Aapne YouTube mein target video (\"$targetTitle\") ke bajaye doosra video (\"$pTitle\") play kar diya."
                        )
                        return
                    }
                }
            }

            // 3b. Also check active MediaSession metadata title if YouTube reported a new video title
            val mediaTitle = WatchSessionRepository.currentMediaTitle.value
            val mediaArtist = WatchSessionRepository.currentMediaArtist.value
            val mediaDetected = WatchSessionRepository.mediaSessionDetected.value
            if (mediaDetected && !mediaTitle.isNullOrBlank() && mediaTitle.length >= 4 && !isAdPlaying) {
                val lowM = mediaTitle.lowercase()
                val isMAd = lowM.startsWith("ad ·") || lowM.startsWith("ad •") || lowM.startsWith("sponsored") || lowM.contains("advertiser") || lowM.contains("skip ad")
                if (!isMAd) {
                    val isGenericTarget = targetTitle.equals("YouTube Video Task", ignoreCase = true) ||
                            targetTitle.equals("YouTube Video", ignoreCase = true) ||
                            targetTitle.startsWith("YouTube Video (", ignoreCase = true)
                    if (!isGenericTarget) {
                        val mediaMatch = TitleMatcher.evaluateMatch(mediaTitle, targetTitle, mediaArtist, targetAuthor)
                        if (mediaMatch == com.example.data.MatchResult.MISMATCH) {
                            wrongVideoStrikeCount = 0
                            WatchSessionRepository.triggerTaskIncomplete(
                                "Task Incomplete! Aapne YouTube par target video (\"$targetTitle\") ke bajaye doosra video (\"$mediaTitle\") play kar diya."
                            )
                            return
                        } else if (mediaMatch == com.example.data.MatchResult.MATCH) {
                            wrongVideoStrikeCount = 0
                        }
                    }
                }
            }

            // 4. Check Watch Metadata Header (below 16:9 video player)
            val subscribeAnchor = entries.firstOrNull { e ->
                val t = e.text.lowercase()
                val d = e.desc.lowercase()
                val v = e.viewId.lowercase()
                e.rect.top in (playerBottomY - (8 * density).toInt())..(playerBottomY + (220 * density).toInt()) && (
                    t.contains("subscribe") || d.contains("subscribe") || v.contains("subscribe") ||
                    t.contains("सदस्यता") || d.contains("सदस्यता")
                )
            }
            val headerTopY = (playerBottomY - (16 * density).toInt()).coerceAtLeast((screenHeight * 0.15f).toInt())
            val headerBottomY = (subscribeAnchor?.rect?.top ?: (playerBottomY + (160 * density).toInt())).coerceAtMost((screenHeight * 0.55f).toInt())

            val sortedHeaderEntries = entries
                .filter { e ->
                    val vLow = e.viewId.lowercase()
                    val isPlayerControlView = vLow.contains("player") ||
                            vLow.contains("time_bar") ||
                            vLow.contains("scrubber") ||
                            vLow.contains("control") ||
                            vLow.contains("overlay") ||
                            vLow.contains("inline") ||
                            vLow.contains("autonav") ||
                            vLow.contains("seek") ||
                            vLow.contains("chapter") ||
                            vLow.contains("caption") ||
                            vLow.contains("subtitle") ||
                            vLow.contains("live_chat") ||
                            vLow.contains("tooltip") ||
                            vLow.contains("hint") ||
                            vLow.contains("comment") ||
                            vLow.contains("composer") ||
                            vLow.contains("bottom_sheet") ||
                            vLow.contains("engagement") ||
                            vLow.contains("product") ||
                            vLow.contains("shopping") ||
                            vLow.contains("commerce") ||
                            vLow.contains("merch") ||
                            vLow.contains("store") ||
                            vLow.contains("shelf")
                    !isPlayerControlView &&
                            e.rect.top in headerTopY..headerBottomY &&
                            e.rect.height() <= (screenHeight * 0.40f).toInt()
                }
                .sortedWith(compareBy<UiNodeEntry> { it.rect.top }.thenByDescending { it.rect.width() })

            val cleanedTitleCandidates = mutableListOf<String>()
            val normAuthor = TitleMatcher.normalize(targetAuthor)

            for (e in sortedHeaderEntries) {
                for (candidate in listOf(e.text, e.desc)) {
                    val rawClean = candidate.trim()
                    if (rawClean.length >= 3 && !CHROME_LABELS.contains(rawClean.lowercase())) {
                        val extracted = extractCleanTitleCandidate(rawClean)
                        val low = extracted.lowercase()
                        val normTxt = TitleMatcher.normalize(extracted)
                        val isJustChannel = normAuthor.isNotEmpty() &&
                                (normTxt == normAuthor || normTxt.replace(" ", "") == normAuthor.replace(" ", ""))

                        if (extracted.length >= 4 &&
                            !isJustChannel &&
                            !CHROME_LABELS.contains(low) &&
                            !low.startsWith("@") &&
                            !low.matches(Regex("^[0-9:\\s/•·.,%-]+$")) &&
                            !low.startsWith("ad ·") &&
                            !low.startsWith("sponsored ·") &&
                            !low.startsWith("skip ad") &&
                            !low.startsWith("like this") &&
                            !low.startsWith("dislike this") &&
                            !low.startsWith("subscribe to") &&
                            !low.startsWith("unsubscribe from") &&
                            !low.startsWith("options for") &&
                            !low.startsWith("save to") &&
                            !low.startsWith("share") &&
                            !low.startsWith("comments") &&
                            !low.startsWith("add a comment") &&
                            !low.startsWith("add a reply") &&
                            !low.startsWith("pinned by") &&
                            !low.startsWith("go to channel") &&
                            !low.startsWith("view products") &&
                            !low.startsWith("tagged products") &&
                            !low.contains("products in this video") &&
                            !low.startsWith("explore products") &&
                            !low.contains("products (") &&
                            !low.contains("₹") &&
                            !low.contains("खरीदारी") &&
                            !low.contains("उत्पाद")
                        ) {
                            if (!cleanedTitleCandidates.contains(extracted)) {
                                cleanedTitleCandidates.add(extracted)
                            }
                        }
                    }
                }
            }

            val channelEntry = if (subscribeAnchor != null) {
                entries.firstOrNull { e ->
                    e != subscribeAnchor &&
                    Math.abs(e.rect.top - subscribeAnchor.rect.top) <= (36 * density).toInt() &&
                    (e.text.isNotBlank() || e.desc.isNotBlank()) &&
                    !e.text.contains("subscribe", ignoreCase = true) &&
                    !e.desc.contains("subscribe", ignoreCase = true) &&
                    !e.desc.contains("bell", ignoreCase = true)
                }
            } else null
            val onScreenChannel = channelEntry?.text?.ifBlank { channelEntry.desc }?.trim().takeIf { !it.isNullOrBlank() }
            val activeChannel = onScreenChannel ?: mediaArtist

            // 4a. Check primary wide watch title directly below the video player
            val primaryWatchTitleEntry = entries.firstOrNull { e ->
                val v = e.viewId.lowercase()
                val isControl = v.contains("player") || v.contains("time_bar") || v.contains("control") ||
                        v.contains("comment") || v.contains("composer") || v.contains("engagement") || v.contains("bottom_sheet")
                !isControl &&
                        e.rect.top in (playerBottomY - (16 * density).toInt())..(playerBottomY + (130 * density).toInt()) &&
                        e.rect.width() >= (screenWidth * 0.58f).toInt() &&
                        (e.text.length >= 4 || e.desc.length >= 4)
            }

            if (primaryWatchTitleEntry != null && !isAdPlaying && !isCommentActive && !isProductActive) {
                val primaryRaw = primaryWatchTitleEntry.text.ifBlank { primaryWatchTitleEntry.desc }
                val cleanPrimary = extractCleanTitleCandidate(primaryRaw)
                val lowPrimary = cleanPrimary.lowercase()
                val isAdCandidate = lowPrimary.startsWith("ad ·") || lowPrimary.startsWith("sponsored") || lowPrimary.contains("skip ad")
                if (cleanPrimary.length >= 4 && !CHROME_LABELS.contains(lowPrimary) && !isAdCandidate) {
                    val isGenericTarget = targetTitle.equals("YouTube Video Task", ignoreCase = true) ||
                            targetTitle.equals("YouTube Video", ignoreCase = true) ||
                            targetTitle.startsWith("YouTube Video (", ignoreCase = true)

                    val pMatch = if (isGenericTarget) com.example.data.MatchResult.MATCH else
                        TitleMatcher.evaluateMatch(cleanPrimary, targetTitle, activeChannel, targetAuthor)

                    if (pMatch == com.example.data.MatchResult.MATCH) {
                        wrongVideoStrikeCount = 0
                        if (lockedWatchPageTitle == null) {
                            lockedWatchPageTitle = cleanPrimary
                        }
                    } else if (pMatch == com.example.data.MatchResult.MISMATCH) {
                        wrongVideoStrikeCount++
                        val isSessionActive = WatchSessionRepository.sessionState.value == com.example.data.SessionState.ACTIVE
                        if (isSessionActive && wrongVideoStrikeCount >= 2) {
                            wrongVideoStrikeCount = 0
                            WatchSessionRepository.triggerTaskIncomplete(
                                "Task Incomplete! Target video (\"$targetTitle\") ke bajaye doosra video (\"$cleanPrimary\") chal raha hai."
                            )
                            return
                        }
                    }
                }
            }

            if (cleanedTitleCandidates.isNotEmpty()) {
                val isGenericTarget = targetTitle.equals("YouTube Video Task", ignoreCase = true) ||
                        targetTitle.equals("YouTube Video", ignoreCase = true) ||
                        targetTitle.startsWith("YouTube Video (", ignoreCase = true)

                // Check if ANY candidate on the active Watch screen matches our target video
                val matchingCandidate = cleanedTitleCandidates.firstOrNull { candidate ->
                    isGenericTarget ||
                            TitleMatcher.evaluateMatch(candidate, targetTitle, activeChannel, targetAuthor) == com.example.data.MatchResult.MATCH
                }

                if (matchingCandidate != null) {
                    if (lockedWatchPageTitle == null) {
                        lockedWatchPageTitle = matchingCandidate
                    }
                    wrongVideoStrikeCount = 0
                } else if (!isAdPlaying && !isCommentActive && !isProductActive) {
                    // Check if MediaSession currently matches target video! If media session matches, target video is playing!
                    val mediaTitleNow = WatchSessionRepository.currentMediaTitle.value
                    val mediaArtistNow = WatchSessionRepository.currentMediaArtist.value
                    val isMediaMatching = !mediaTitleNow.isNullOrBlank() &&
                            TitleMatcher.evaluateMatch(mediaTitleNow, targetTitle, mediaArtistNow, targetAuthor) == com.example.data.MatchResult.MATCH

                    if (isMediaMatching) {
                        wrongVideoStrikeCount = 0
                    } else {
                        val wrongCandidate = cleanedTitleCandidates.firstOrNull { candidate ->
                            !isGenericTarget &&
                                    TitleMatcher.evaluateMatch(candidate, targetTitle, activeChannel, targetAuthor) == com.example.data.MatchResult.MISMATCH
                        }
                        if (wrongCandidate != null) {
                            wrongVideoStrikeCount++
                            val isSessionActive = WatchSessionRepository.sessionState.value == com.example.data.SessionState.ACTIVE
                            if (isSessionActive && wrongVideoStrikeCount >= 3) {
                                wrongVideoStrikeCount = 0
                                WatchSessionRepository.triggerTaskIncomplete(
                                    "Task Incomplete! Target video (\"$targetTitle\") ke bajaye doosra video (\"$wrongCandidate\") chal raha hai."
                                )
                                return
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun scrollForward(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable) {
            val scrolled = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (scrolled) {
                WatchSessionRepository.addLog("Human search: Scrolling YouTube search results to locate video...", LogType.INFO)
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scrollForward(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    private fun dispatchTapGesture(x: Int, y: Int): Boolean {
        if (x <= 0 || y <= 0) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = android.graphics.Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 75)
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    private fun dispatchSwipeUpGesture(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(400)
            val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(800)
            val centerX = (screenWidth * 0.50f)
            val startY = (screenHeight * 0.72f)
            val endY = (screenHeight * 0.32f)
            val path = android.graphics.Path().apply {
                moveTo(centerX, startY)
                lineTo(centerX, endY)
            }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 280)
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    override fun onInterrupt() {
        WatchSessionRepository.addLog("YouTube Live Search Service Interrupted", LogType.WARNING)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        isServiceConnected = false
        currentPhase = LiveSearchPhase.IDLE
    }
}
