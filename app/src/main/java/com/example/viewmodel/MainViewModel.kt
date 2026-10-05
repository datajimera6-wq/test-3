package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DataStoreManager
import com.example.data.LogType
import com.example.data.MatchResult
import com.example.data.OEmbedFetcher
import com.example.data.OEmbedResult
import com.example.data.SampleTask
import com.example.data.SessionState
import com.example.data.VideoPlaybackState
import com.example.data.WalletTransaction
import com.example.repository.WatchSessionRepository
import com.example.service.WatchTimerService
import com.example.util.PermissionHelper
import com.example.util.TitleMatcher
import com.example.data.YouTubeSearchEngine
import com.example.service.YouTubeLiveSearchService
import com.example.data.VideoTaskItem
import com.example.data.UserProfile
import com.example.data.PayoutStatus
import com.example.data.PayoutRequest
import com.example.data.WatchDurationTier
import com.example.data.WATCH_DURATION_TIERS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppScreen {
    HOME,
    TASKS,
    TASK,
    TASK_DETAIL,
    WALLET,
    ME,
    SETUP,
    DIAGNOSTICS,
    ADMIN
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = DataStoreManager(application)

    // Navigation
    private val _currentScreen = MutableStateFlow(AppScreen.HOME)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val screenBackStack = mutableListOf<AppScreen>()

    // Local DataStore states
    val walletBalance: StateFlow<Int> = dataStoreManager.walletBalanceFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val isTaskCompleted: StateFlow<Boolean> = dataStoreManager.isTaskCompletedFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val transactions: StateFlow<List<WalletTransaction>> = dataStoreManager.transactionsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val liveSearchMode: StateFlow<Boolean> = dataStoreManager.liveSearchModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val videoTasks: StateFlow<List<VideoTaskItem>> = dataStoreManager.videoTasksFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, dataStoreManager.getDefaultTasks())

    val selectedTaskId: StateFlow<String?> = dataStoreManager.selectedTaskIdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentUser: StateFlow<UserProfile?> = dataStoreManager.currentUserFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val allUsers: StateFlow<List<UserProfile>> = dataStoreManager.usersFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val payoutRequests: StateFlow<List<PayoutRequest>> = dataStoreManager.payoutRequestsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val supportMessages: StateFlow<List<com.example.data.SupportMessage>> = dataStoreManager.supportMessagesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val cloudServerUrl: StateFlow<String> = dataStoreManager.cloudServerUrlFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, DataStoreManager.DEFAULT_CLOUD_SERVER_URL)

    val cloudServerStatus: StateFlow<String> = dataStoreManager.cloudServerStatusFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Connected to Default Drive Server")

    val adminPosts: StateFlow<List< com.example.data.AdminPostItem >> = dataStoreManager.adminPostsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, dataStoreManager.getDefaultAdminPosts())

    val dismissedPostIds: StateFlow<Set<String>> = dataStoreManager.dismissedPostIdsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val remoteAppUpdate: StateFlow<com.example.data.AppUpdateInfo?> = dataStoreManager.remoteAppUpdateFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val installedUpdateSignature: StateFlow<String> = dataStoreManager.installedUpdateSignatureFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val updateDriveFolderUrl: StateFlow<String> = dataStoreManager.updateDriveFolderUrlFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val appDownloadUrl: StateFlow<String> = dataStoreManager.appDownloadUrlFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, DataStoreManager.DEFAULT_APP_DOWNLOAD_URL)

    val pendingReferralCode: StateFlow<String> = dataStoreManager.pendingReferralCodeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun saveAppDownloadUrl(url: String, onResult: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            val cleanUrl = DataStoreManager.normalizeAppDownloadUrl(url)
            dataStoreManager.saveAppDownloadUrl(cleanUrl)
            val srvUrl = cloudServerUrl.value.ifBlank { DataStoreManager.DEFAULT_CLOUD_SERVER_URL }
            if (srvUrl.isNotBlank()) {
                val res = com.example.admin.CloudDriveServerManager.syncData(
                    serverUrl = srvUrl,
                    dataStoreManager = dataStoreManager,
                    pushAdminContent = true,
                    pushLocalChanges = true,
                    pullRemoteFirst = false
                )
                onResult?.invoke(
                    res.first,
                    if (res.first) "App Download Link saved & synced: $cleanUrl" else "Saved locally: $cleanUrl (${res.second})"
                )
            } else {
                onResult?.invoke(true, "Saved App Download Link: $cleanUrl")
            }
        }
    }

    fun recordSharedReferralCode(code: String) {
        viewModelScope.launch {
            dataStoreManager.recordSharedReferralCode(code)
            val srvUrl = cloudServerUrl.value.ifBlank { DataStoreManager.DEFAULT_CLOUD_SERVER_URL }
            if (srvUrl.isNotBlank()) {
                try {
                    com.example.admin.CloudDriveServerManager.syncData(
                        serverUrl = srvUrl,
                        dataStoreManager = dataStoreManager,
                        pushAdminContent = false,
                        pushLocalChanges = true,
                        pullRemoteFirst = false
                    )
                } catch (_: Exception) {}
            }
        }
    }

    fun savePendingReferralCode(code: String) {
        viewModelScope.launch {
            dataStoreManager.savePendingReferralCode(code)
        }
    }

    /**
     * Returns the latest updated direct download APK URL (from Google Drive update or configured download link),
     * with the 6-digit referral key embedded.
     */
    fun getEffectiveShareDownloadUrl(referralCode: String): String {
        val latestUpdate = remoteAppUpdate.value
        val base = if (latestUpdate != null && latestUpdate.hasUpdate && latestUpdate.downloadUrl.isNotBlank()) {
            latestUpdate.downloadUrl
        } else {
            val configured = appDownloadUrl.value.ifBlank { updateDriveFolderUrl.value }
            if (configured.isNotBlank()) configured else DataStoreManager.DEFAULT_APP_DOWNLOAD_URL
        }
        return DataStoreManager.toDirectDownloadUrl(base, referralCode)
    }

    /**
     * Automatically scans for attached referral key on app startup/resume via:
     * 1. System Clipboard (if user tapped or copied an invite message/link)
     * 2. Downloaded APK filename in Downloads directory
     */
    fun scanForPendingReferralCode(context: Context) {
        viewModelScope.launch {
            val current = pendingReferralCode.value
            if (current.length == 6 && current.all { it.isDigit() }) {
                return@launch
            }

            // 1. Check Clipboard
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                val match = Regex("""(?:ref=|code=|key[:\s*]|referral[:\s*]|refer[:\s*]|\b)(\d{6})\b""", RegexOption.IGNORE_CASE).find(clipText)
                val extracted = match?.groupValues?.getOrNull(1) ?: ""
                if (extracted.length == 6 && extracted.all { it.isDigit() }) {
                    savePendingReferralCode(extracted)
                    return@launch
                }
            } catch (_: Exception) {}

            // 2. Check recently downloaded APK filename in Downloads folder
            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir.exists() && downloadsDir.isDirectory) {
                    val apkFiles = downloadsDir.listFiles { file ->
                        file.isFile && file.name.endsWith(".apk", ignoreCase = true)
                    }?.sortedByDescending { it.lastModified() }
                    if (apkFiles != null) {
                        for (apk in apkFiles) {
                            val nameMatch = Regex("""(?:ref_|_|kingo[_-]?)(\d{6})\b""", RegexOption.IGNORE_CASE).find(apk.name)
                                ?: Regex("""\b(\d{6})\b""").find(apk.name)
                            val code = nameMatch?.groupValues?.getOrNull(1) ?: ""
                            if (code.length == 6 && code.all { it.isDigit() }) {
                                savePendingReferralCode(code)
                                return@launch
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun markAppUpdateInstalled(signature: String) {
        viewModelScope.launch {
            dataStoreManager.setInstalledUpdateSignature(signature)
        }
    }

    fun saveUpdateDriveFolderUrl(url: String, onResult: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            dataStoreManager.setUpdateDriveFolderUrl(url.trim())
            val srvUrl = cloudServerUrl.value
            if (srvUrl.isNotBlank()) {
                val res = com.example.admin.CloudDriveServerManager.syncData(
                    serverUrl = srvUrl,
                    dataStoreManager = dataStoreManager,
                    pushAdminContent = true,
                    pushLocalChanges = true,
                    pullRemoteFirst = true
                )
                onResult?.invoke(res.first, if (res.first) "Update folder synced!" else res.second)
            } else {
                onResult?.invoke(true, "Saved update folder URL locally.")
            }
        }
    }

    val taskIncompleteMessage: StateFlow<String?> = WatchSessionRepository.taskIncompleteMessage

    fun dismissTaskIncompleteMessage() {
        WatchSessionRepository.dismissTaskIncompleteMessage()
    }

    private val _adminServerRunning = MutableStateFlow(false)
    val adminServerRunning: StateFlow<Boolean> = _adminServerRunning.asStateFlow()

    private val _adminServerUrl = MutableStateFlow("")
    val adminServerUrl: StateFlow<String> = _adminServerUrl.asStateFlow()

    fun getDataStoreManager(): DataStoreManager = dataStoreManager

    fun updateAdminServerState(running: Boolean, url: String) {
        _adminServerRunning.value = running
        _adminServerUrl.value = url
    }

    private val _activeRewardCoins = MutableStateFlow(10)
    val activeRewardCoins: StateFlow<Int> = _activeRewardCoins.asStateFlow()

    val targetTaskTitle: StateFlow<String?> = WatchSessionRepository.targetTaskTitle

    fun toggleLiveSearchMode(enabled: Boolean) {
        viewModelScope.launch {
            dataStoreManager.setLiveSearchMode(enabled)
            WatchSessionRepository.addLog(
                if (enabled) "Live YouTube Search Mode enabled" else "In-app animated search simulation enabled",
                LogType.INFO
            )
        }
    }

    val fullScreenOpeningOverlay: StateFlow<Boolean> = dataStoreManager.fullScreenOpeningOverlayFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun toggleFullScreenOpeningOverlay(enabled: Boolean) {
        viewModelScope.launch {
            dataStoreManager.setFullScreenOpeningOverlayEnabled(enabled)
            val srvUrl = cloudServerUrl.value
            if (srvUrl.isNotBlank()) {
                com.example.admin.CloudDriveServerManager.syncData(
                    serverUrl = srvUrl,
                    dataStoreManager = dataStoreManager,
                    pushAdminContent = true,
                    pushLocalChanges = true,
                    pullRemoteFirst = false
                )
            }
        }
    }

    // Active video URL to track
    private val _currentVideoUrl = MutableStateFlow(SampleTask.videoUrl)
    val currentVideoUrl: StateFlow<String> = _currentVideoUrl.asStateFlow()

    // Active selected duration tier
    private val _selectedTierSeconds = MutableStateFlow(SampleTask.requiredSeconds)
    val selectedTierSeconds: StateFlow<Int> = _selectedTierSeconds.asStateFlow()

    private val _selectedTierCoins = MutableStateFlow(SampleTask.rewardCoins)
    val selectedTierCoins: StateFlow<Int> = _selectedTierCoins.asStateFlow()

    // oEmbed metadata state
    private val _oEmbedState = MutableStateFlow<OEmbedResult>(OEmbedResult.Idle)
    val oEmbedState: StateFlow<OEmbedResult> = _oEmbedState.asStateFlow()

    // Success dialog
    private val _showSuccessDialog = MutableStateFlow(false)
    val showSuccessDialog: StateFlow<Boolean> = _showSuccessDialog.asStateFlow()

    // Delegated from repository
    val sessionState: StateFlow<SessionState> = WatchSessionRepository.sessionState
    val matchResult: StateFlow<MatchResult> = WatchSessionRepository.matchResult
    val playbackState: StateFlow<VideoPlaybackState> = WatchSessionRepository.playbackState
    val currentMediaTitle: StateFlow<String?> = WatchSessionRepository.currentMediaTitle
    val currentMediaArtist: StateFlow<String?> = WatchSessionRepository.currentMediaArtist
    val watchedMillis: StateFlow<Long> = WatchSessionRepository.watchedMillis
    val requiredMillis: StateFlow<Long> = WatchSessionRepository.requiredMillis
    val isServiceRunning: StateFlow<Boolean> = WatchSessionRepository.isServiceRunning
    val isGracePeriodActive: StateFlow<Boolean> = WatchSessionRepository.isGracePeriodActive
    val graceSecondsRemaining: StateFlow<Int> = WatchSessionRepository.graceSecondsRemaining
    val mediaSessionDetected: StateFlow<Boolean> = WatchSessionRepository.mediaSessionDetected
    val redAlertMessage: StateFlow<String?> = WatchSessionRepository.redAlertMessage
    val eventLogs = WatchSessionRepository.eventLogs
    val searchProgress = WatchSessionRepository.searchProgress

    val likedTasks: StateFlow<Set<String>> = dataStoreManager.likedTasksFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val commentCounts: StateFlow<Map<String, Int>> = dataStoreManager.commentCountsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _sessionInterruptedMessage = MutableStateFlow<String?>(null)
    val sessionInterruptedMessage: StateFlow<String?> = _sessionInterruptedMessage.asStateFlow()

    fun dismissInterruptedMessage() {
        _sessionInterruptedMessage.value = null
    }

    init {
        // Enforce Strict Continuous Watch: Every session must start at 00:00!
        // No incomplete session is accumulated across multiple days or returns.
        WatchSessionRepository.setWatchedMillis(0L)

        // Listen for session interruption when user returns to app before milestone
        WatchSessionRepository.onSessionInterrupted = { message ->
            _sessionInterruptedMessage.value = message
        }

        WatchSessionRepository.onTaskIncompleteAndLocked = { taskId, _, lockDuration ->
            viewModelScope.launch {
                dataStoreManager.lockTask(taskId, lockDuration)
            }
        }

        WatchSessionRepository.onVideoAlreadyLikedDetected = { taskId ->
            viewModelScope.launch {
                dataStoreManager.markTaskAlreadyLiked(taskId)
            }
        }

        viewModelScope.launch {
            WatchSessionRepository.rewardCoins.collectLatest { coins ->
                _activeRewardCoins.value = coins
            }
        }

        // Check if custom URL was saved
        viewModelScope.launch {
            dataStoreManager.activeVideoUrlFlow.collectLatest { savedUrl ->
                if (!savedUrl.isNullOrBlank()) {
                    _currentVideoUrl.value = savedUrl
                }
                fetchOEmbed()
            }
        }

        // Repository completion callback - ONLY shown on genuine completion
        WatchSessionRepository.onCompletionTriggered = { coins, title ->
            viewModelScope.launch {
                dataStoreManager.addRewardTransaction(title, coins)
                val activeId = WatchSessionRepository.activeTaskId.value
                if (activeId != null) {
                    dataStoreManager.markTaskCompleted(activeId, coins)
                }
                _activeRewardCoins.value = coins
                _showSuccessDialog.value = true
            }
        }

        WatchSessionRepository.onSaveProgressNeeded = { millis ->
            viewModelScope.launch {
                dataStoreManager.setWatchedMillis(millis)
            }
        }

        viewModelScope.launch {
            while (true) {
                dataStoreManager.unlockExpiredTasks()
                val url = cloudServerUrl.value.ifBlank { DataStoreManager.DEFAULT_CLOUD_SERVER_URL }
                if (url.isNotBlank()) {
                    try {
                        com.example.admin.CloudDriveServerManager.syncData(
                            serverUrl = url,
                            dataStoreManager = dataStoreManager,
                            pushAdminContent = false,
                            pushLocalChanges = false
                        )
                    } catch (_: Exception) {}
                }
                delay(3_000L)
            }
        }

        // Periodic Remote APK Update Checker (prioritizes Google Drive update folder)
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val folderUrl = dataStoreManager.updateDriveFolderUrlFlow.first().trim()
                    var upd = if (folderUrl.isNotBlank()) {
                        com.example.admin.CloudDriveServerManager.inspectPublicDriveUpdateLink(folderUrl)
                    } else null

                    if (upd == null || !upd.hasUpdate) {
                        val appDlUrl = dataStoreManager.appDownloadUrlFlow.first().trim()
                        if (appDlUrl.isNotBlank() && !appDlUrl.contains("drive.google.com/drive/folders")) {
                            upd = com.example.admin.CloudDriveServerManager.inspectPublicDriveUpdateLink(appDlUrl)
                        }
                    }

                    if (upd != null && upd.hasUpdate) {
                        dataStoreManager.saveRemoteAppUpdate(upd)
                    }
                } catch (_: Exception) {}
                delay(12_000L)
            }
        }

        // Real-time watcher for DataStore changes (Transactions, Payouts, Tasks, Posts, Support, Remote Updates)
        // Dispatches through centralized NotificationChannels.checkAndDispatchAdminNotifications with strict deduplication
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                listOf(
                    dataStoreManager.transactionsFlow,
                    dataStoreManager.payoutRequestsFlow,
                    dataStoreManager.videoTasksFlow,
                    dataStoreManager.adminPostsFlow,
                    dataStoreManager.supportMessagesFlow,
                    dataStoreManager.remoteAppUpdateFlow
                )
            ) { _ -> Unit }
                .debounce(500L)
                .collectLatest {
                    com.example.service.NotificationChannels.checkAndDispatchAdminNotifications(
                        context = getApplication(),
                        dataStoreManager = dataStoreManager
                    )
                }
        }
    }

    private val _showGlobalSupportChat = MutableStateFlow(false)
    val showGlobalSupportChat: StateFlow<Boolean> = _showGlobalSupportChat.asStateFlow()

    fun openSupportChat() {
        _showGlobalSupportChat.value = true
    }

    fun dismissSupportChat() {
        _showGlobalSupportChat.value = false
    }

    fun navigateTo(screen: AppScreen) {
        if (_currentScreen.value != screen) {
            screenBackStack.add(_currentScreen.value)
            _currentScreen.value = screen
        }
    }

    fun navigateBack(): Boolean {
        if (screenBackStack.isNotEmpty()) {
            _currentScreen.value = screenBackStack.removeAt(screenBackStack.lastIndex)
            return true
        }
        if (_currentScreen.value != AppScreen.HOME) {
            _currentScreen.value = AppScreen.HOME
            return true
        }
        return false
    }

    fun switchTab(screen: AppScreen) {
        screenBackStack.clear()
        _currentScreen.value = screen
    }

    fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val targetScreen = intent.getStringExtra("target_screen")?.trim()?.lowercase() ?: ""
        val targetItemId = intent.getStringExtra("target_item_id")?.trim() ?: ""
        when (targetScreen) {
            "tasks" -> {
                switchTab(AppScreen.TASKS)
                if (targetItemId.isNotBlank()) {
                    viewModelScope.launch {
                        val matchingTask = videoTasks.value.find { it.id == targetItemId }
                            ?: dataStoreManager.videoTasksFlow.first().find { it.id == targetItemId }
                        if (matchingTask != null) {
                            selectTask(matchingTask)
                        }
                    }
                }
            }
            "wallet" -> {
                switchTab(AppScreen.WALLET)
            }
            "me" -> {
                switchTab(AppScreen.ME)
            }
            "chat" -> {
                openSupportChat()
            }
            "home" -> {
                switchTab(AppScreen.HOME)
            }
            else -> {
                if (targetScreen.isNotBlank()) {
                    switchTab(AppScreen.HOME)
                }
            }
        }
    }

    fun selectTask(task: VideoTaskItem) {
        _currentVideoUrl.value = task.videoUrl
        _selectedTierSeconds.value = task.selectedDurationSeconds
        _selectedTierCoins.value = task.rewardCoins
        if (!task.isCompleted) {
            WatchSessionRepository.resetSessionForNewTask(task.id)
        }
        viewModelScope.launch {
            dataStoreManager.setSelectedTaskId(task.id)
            dataStoreManager.setActiveVideoUrl(task.videoUrl)
            fetchOEmbed(task.videoUrl)
        }
    }

    fun addVideoTask(task: VideoTaskItem) {
        val rawClean = TitleMatcher.extractCleanYouTubeUrl(task.videoUrl)
        val cleanUrl = TitleMatcher.cleanYouTubeUrl(rawClean).ifEmpty { rawClean }
        val thumb = task.thumbnailUrl.ifBlank { TitleMatcher.getThumbnailUrl(cleanUrl) ?: "" }
        val cleanedTask = task.copy(videoUrl = cleanUrl, thumbnailUrl = thumb)
        _currentVideoUrl.value = cleanUrl
        _selectedTierSeconds.value = cleanedTask.selectedDurationSeconds
        _selectedTierCoins.value = cleanedTask.rewardCoins

        viewModelScope.launch {
            dataStoreManager.markItemsNotified(setOf(cleanedTask.id))
            dataStoreManager.addVideoTask(cleanedTask)
            dataStoreManager.setSelectedTaskId(cleanedTask.id)
            dataStoreManager.setActiveVideoUrl(cleanUrl)

            var finalTitle = cleanedTask.title
            var finalChannel = cleanedTask.channelName

            val result = kotlinx.coroutines.withTimeoutOrNull(2500L) {
                OEmbedFetcher.fetchOEmbed(cleanUrl)
            }
            if (result is OEmbedResult.Success) {
                _oEmbedState.value = result
                val fetchedTitle = result.title.takeIf {
                    it.isNotBlank() && it != "YouTube Video" && !it.startsWith("YouTube Video (")
                }
                val fetchedAuthor = result.authorName.takeIf {
                    it.isNotBlank() && it != "YouTube Creator" && it != "YouTube Channel"
                }
                if (cleanedTask.title.isBlank() ||
                    cleanedTask.title == "YouTube Video" ||
                    cleanedTask.title.startsWith("YouTube Video (") ||
                    fetchedTitle != null
                ) {
                    finalTitle = fetchedTitle ?: cleanedTask.title
                    finalChannel = fetchedAuthor ?: cleanedTask.channelName
                    val updated = cleanedTask.copy(
                        title = finalTitle,
                        channelName = finalChannel,
                        thumbnailUrl = result.thumbnailUrl.ifBlank { cleanedTask.thumbnailUrl }
                    )
                    dataStoreManager.updateVideoTask(updated)
                }
            } else {
                _oEmbedState.value = OEmbedResult.Success(
                    title = cleanedTask.title,
                    authorName = cleanedTask.channelName,
                    authorUrl = "",
                    thumbnailUrl = cleanedTask.thumbnailUrl
                )
            }
            val url = cloudServerUrl.value.ifBlank { DataStoreManager.DEFAULT_CLOUD_SERVER_URL }
            if (url.isNotBlank()) {
                try {
                    com.example.admin.CloudDriveServerManager.syncData(
                        serverUrl = url,
                        dataStoreManager = dataStoreManager,
                        pushAdminContent = true,
                        pushLocalChanges = true,
                        pullRemoteFirst = false
                    )
                } catch (_: Exception) {}
            }
            WatchSessionRepository.addLog("Created new video task: \"$finalTitle\"", LogType.SUCCESS)
        }
    }

    fun startTaskWithTier(task: VideoTaskItem, tier: WatchDurationTier, context: Context) {
        _selectedTierSeconds.value = tier.seconds
        _selectedTierCoins.value = tier.coins
        selectTask(task)
        startTaskInternal(
            context = context,
            videoUrl = task.videoUrl,
            requiredSeconds = tier.seconds,
            rewardCoins = tier.coins,
            taskId = task.id
        )
    }

    fun fetchOEmbed(targetUrl: String? = null) {
        val url = targetUrl ?: _currentVideoUrl.value
        _oEmbedState.value = OEmbedResult.Loading
        WatchSessionRepository.addLog("Fetching oEmbed for: $url", LogType.INFO)

        viewModelScope.launch {
            val result = OEmbedFetcher.fetchOEmbed(url)
            _oEmbedState.value = result
            when (result) {
                is OEmbedResult.Success -> {
                    WatchSessionRepository.addLog(
                        "oEmbed title fetched: \"${result.title}\" (${result.authorName})",
                        LogType.SUCCESS
                    )
                }
                is OEmbedResult.Error -> {
                    WatchSessionRepository.addLog("oEmbed fetch failed: ${result.message}", LogType.ERROR)
                }
                else -> {}
            }
        }
    }

    fun useDemoVideo() {
        setVideoUrl(SampleTask.fallbackDemoUrl)
    }

    fun setVideoUrl(url: String) {
        val rawClean = TitleMatcher.extractCleanYouTubeUrl(url)
        val cleanUrl = TitleMatcher.cleanYouTubeUrl(rawClean).ifEmpty { rawClean }
        _currentVideoUrl.value = cleanUrl
        _oEmbedState.value = OEmbedResult.Loading
        viewModelScope.launch {
            dataStoreManager.setActiveVideoUrl(cleanUrl)
            val result = OEmbedFetcher.fetchOEmbed(cleanUrl)
            _oEmbedState.value = result
            val thumb = (result as? OEmbedResult.Success)?.thumbnailUrl?.ifBlank { null }
                ?: TitleMatcher.getThumbnailUrl(cleanUrl)
                ?: ""
            val vid = TitleMatcher.extractVideoId(cleanUrl)
            val resolvedTitle = (result as? OEmbedResult.Success)?.title
                ?: if (!vid.isNullOrBlank()) "YouTube Video ($vid)" else "YouTube Video Task"
            val resolvedAuthor = (result as? OEmbedResult.Success)?.authorName?.ifBlank { null }
                ?: "YouTube Creator"

            val activeId = selectedTaskId.value
            val existingTask = videoTasks.value.find { it.id == activeId }
            if (existingTask != null) {
                val updatedTask = existingTask.copy(
                    title = resolvedTitle,
                    channelName = resolvedAuthor,
                    videoUrl = cleanUrl,
                    thumbnailUrl = thumb,
                    isCompleted = false,
                    lockedUntilMillis = 0L
                )
                dataStoreManager.updateVideoTask(updatedTask)
            } else {
                val newTask = VideoTaskItem(
                    id = "task_${System.currentTimeMillis()}",
                    title = resolvedTitle,
                    channelName = resolvedAuthor,
                    videoUrl = cleanUrl,
                    thumbnailUrl = thumb,
                    durationSeconds = 600,
                    rewardCoins = _selectedTierCoins.value,
                    selectedDurationSeconds = _selectedTierSeconds.value
                )
                dataStoreManager.addVideoTask(newTask)
                dataStoreManager.setSelectedTaskId(newTask.id)
            }
        }
    }

    fun startTask(context: Context) {
        val activeTask = videoTasks.value.find { it.id == selectedTaskId.value }
            ?: videoTasks.value.firstOrNull { !it.isCompleted && !it.isLocked }
            ?: videoTasks.value.firstOrNull()
        startTaskInternal(
            context = context,
            videoUrl = activeTask?.videoUrl ?: _currentVideoUrl.value,
            requiredSeconds = _selectedTierSeconds.value,
            rewardCoins = _selectedTierCoins.value,
            taskId = activeTask?.id ?: selectedTaskId.value
        )
    }

    private var lastVerifiedOtpEmail: String = ""
    private var lastGeneratedOtpCode: String = ""
    private var lastOtpGeneratedAtMillis: Long = 0L

    private fun isValidEmailFormat(email: String): Boolean {
        val clean = email.trim().lowercase()
        val regex = Regex("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$")
        return regex.matches(clean)
    }

    fun sendEmailVerificationOtp(
        email: String,
        isPasswordReset: Boolean,
        onResult: (Boolean, String, String?) -> Unit
    ) {
        val cleanEmail = email.trim().lowercase()
        if (!isValidEmailFormat(cleanEmail)) {
            onResult(false, "Please enter a valid email address (e.g. name@gmail.com).", null)
            return
        }

        viewModelScope.launch {
            val url = cloudServerUrl.value
            // Pull latest users from server first so we accurately check existing accounts
            if (url.isNotBlank()) {
                try {
                    com.example.admin.CloudDriveServerManager.syncData(
                        serverUrl = url,
                        dataStoreManager = dataStoreManager,
                        pushAdminContent = false,
                        pushLocalChanges = false
                    )
                } catch (_: Exception) {}
            }

            val emailExists = dataStoreManager.doesUserEmailExist(cleanEmail)
            if (isPasswordReset && !emailExists) {
                onResult(false, "No account found with $cleanEmail. Please Create Account first.", null)
                return@launch
            }
            if (!isPasswordReset && emailExists) {
                onResult(false, "Account with $cleanEmail already exists. Please Sign In or use Forgot Password.", null)
                return@launch
            }

            val otp = (100000..999999).random().toString()
            lastVerifiedOtpEmail = cleanEmail
            lastGeneratedOtpCode = otp
            lastOtpGeneratedAtMillis = System.currentTimeMillis()

            // Dispatch verification OTP email via connected Google Drive Script (MailApp.sendEmail)
            if (url.isNotBlank()) {
                try {
                    com.example.admin.CloudDriveServerManager.sendOtpEmail(
                        serverUrl = url,
                        email = cleanEmail,
                        otpCode = otp,
                        purpose = if (isPasswordReset) "Password Reset" else "Account Verification"
                    )
                } catch (_: Exception) {}
            }

            onResult(
                true,
                "6-digit verification OTP has been sent to $cleanEmail. Please check your Email Inbox (and Spam folder).",
                null
            )
        }
    }

    fun verifyEmailOtp(email: String, enteredOtp: String): Boolean {
        val cleanEmail = email.trim().lowercase()
        val cleanOtp = enteredOtp.trim()
        if (cleanOtp.length != 6 || lastGeneratedOtpCode.isBlank()) return false
        val notExpired = (System.currentTimeMillis() - lastOtpGeneratedAtMillis) < 15 * 60 * 1000L
        return notExpired && cleanEmail.equals(lastVerifiedOtpEmail, ignoreCase = true) && cleanOtp == lastGeneratedOtpCode
    }

    fun resetPasswordWithOtp(
        email: String,
        enteredOtp: String,
        newPassword: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val cleanEmail = email.trim().lowercase()
        if (!verifyEmailOtp(cleanEmail, enteredOtp)) {
            onResult(false, "Invalid or expired 6-digit OTP code. Please check and try again.")
            return
        }
        if (newPassword.length < 4) {
            onResult(false, "New password must be at least 4 characters.")
            return
        }

        viewModelScope.launch {
            val res = dataStoreManager.resetUserPassword(cleanEmail, newPassword)
            onResult(res.first, res.second)
            if (res.first) {
                lastGeneratedOtpCode = ""
                WatchSessionRepository.addLog("Password reset completed for: $cleanEmail", LogType.SUCCESS)
                val url = cloudServerUrl.value
                if (url.isNotBlank()) {
                    launch {
                        try {
                            com.example.admin.CloudDriveServerManager.syncData(
                                serverUrl = url,
                                dataStoreManager = dataStoreManager,
                                pushAdminContent = false,
                                pushLocalChanges = true,
                                pullRemoteFirst = false
                            )
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    fun signUp(
        email: String,
        password: String,
        name: String,
        referralCodeInput: String = "",
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            val res = dataStoreManager.signUpUser(email, password, name, referralCodeInput)
            // Immediately return result so UI transitions instantly without waiting for slow Drive file locks
            onResult(res.first, res.second)
            if (res.first) {
                lastGeneratedOtpCode = ""
                WatchSessionRepository.addLog("User signed up: $email (syncing to Drive in background)", LogType.SUCCESS)
                val url = cloudServerUrl.value
                if (url.isNotBlank()) {
                    launch {
                        try {
                            com.example.admin.CloudDriveServerManager.syncData(
                                serverUrl = url,
                                dataStoreManager = dataStoreManager,
                                pushAdminContent = false,
                                pushLocalChanges = true,
                                pullRemoteFirst = false
                            )
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    fun login(email: String, password: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            // 1. Try instant local login first (< 10ms)
            val localRes = dataStoreManager.loginUser(email, password)
            val url = cloudServerUrl.value

            if (localRes.first) {
                onResult(true, localRes.second)
                WatchSessionRepository.addLog("User logged in instantly: $email", LogType.SUCCESS)
                if (url.isNotBlank()) {
                    launch {
                        try {
                            com.example.admin.CloudDriveServerManager.syncData(
                                serverUrl = url,
                                dataStoreManager = dataStoreManager,
                                pushAdminContent = false,
                                pushLocalChanges = false
                            )
                        } catch (_: Exception) {}
                    }
                }
                return@launch
            }

            // 2. If account not yet cached on this phone (or password was updated), pull latest from Drive
            if (url.isNotBlank()) {
                try {
                    com.example.admin.CloudDriveServerManager.syncData(
                        serverUrl = url,
                        dataStoreManager = dataStoreManager,
                        pushAdminContent = false,
                        pushLocalChanges = false
                    )
                } catch (_: Exception) {}
            }

            val remoteRes = dataStoreManager.loginUser(email, password)
            onResult(remoteRes.first, remoteRes.second)
            if (remoteRes.first) {
                WatchSessionRepository.addLog("User logged in & loaded Drive profile: $email", LogType.SUCCESS)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            // 1. Immediately clear active session & save user snapshot locally so UI logs out in <10ms
            dataStoreManager.logoutUser()
            screenBackStack.clear()
            _currentScreen.value = AppScreen.HOME
            WatchSessionRepository.addLog("User logged out", LogType.INFO)

            // 2. Push final user snapshot to Google Drive in the background without blocking logout
            val url = cloudServerUrl.value
            if (url.isNotBlank()) {
                launch {
                    try {
                        com.example.admin.CloudDriveServerManager.syncData(
                            serverUrl = url,
                            dataStoreManager = dataStoreManager,
                            pushAdminContent = false,
                            pushLocalChanges = true,
                            pullRemoteFirst = false
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun requestWithdrawal(coins: Int, method: String, destination: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val success = dataStoreManager.withdrawCoins(coins, method, destination)
            if (success) {
                onResult(true, "Payout request submitted! Admin will verify and process.")
                WatchSessionRepository.addLog("Payout request created: $coins coins to $method ($destination)", LogType.INFO)
                val url = cloudServerUrl.value
                if (url.isNotBlank()) {
                    launch {
                        try {
                            com.example.admin.CloudDriveServerManager.syncData(
                                serverUrl = url,
                                dataStoreManager = dataStoreManager,
                                pushAdminContent = false,
                                pushLocalChanges = true,
                                pullRemoteFirst = false
                            )
                        } catch (_: Exception) {}
                    }
                }
            } else {
                onResult(false, "Insufficient balance or invalid coins amount.")
            }
        }
    }

    fun approvePayout(requestId: String, note: String = "Approved • Payment Processing") {
        viewModelScope.launch {
            dataStoreManager.approvePayout(requestId, note)
            val url = cloudServerUrl.value
            if (url.isNotBlank()) {
                try {
                    com.example.admin.CloudDriveServerManager.syncData(
                        serverUrl = url,
                        dataStoreManager = dataStoreManager,
                        pushAdminContent = true,
                        pushLocalChanges = true,
                        pullRemoteFirst = false
                    )
                } catch (_: Exception) {}
            }
            WatchSessionRepository.addLog("Admin: Payout approved for request #$requestId (awaiting payment Done)", LogType.SUCCESS)
        }
    }

    fun completePayout(requestId: String, note: String = "Payment Completed & Sent") {
        viewModelScope.launch {
            dataStoreManager.completePayout(requestId, note)
            val url = cloudServerUrl.value
            if (url.isNotBlank()) {
                try {
                    com.example.admin.CloudDriveServerManager.syncData(
                        serverUrl = url,
                        dataStoreManager = dataStoreManager,
                        pushAdminContent = true,
                        pushLocalChanges = true,
                        pullRemoteFirst = false
                    )
                } catch (_: Exception) {}
            }
            WatchSessionRepository.addLog("Admin: Payout marked DONE (paid) for request #$requestId", LogType.SUCCESS)
        }
    }

    fun rejectPayout(requestId: String, reason: String = "Declined by Admin") {
        viewModelScope.launch {
            dataStoreManager.rejectPayout(requestId, reason)
            val url = cloudServerUrl.value
            if (url.isNotBlank()) {
                try {
                    com.example.admin.CloudDriveServerManager.syncData(
                        serverUrl = url,
                        dataStoreManager = dataStoreManager,
                        pushAdminContent = true,
                        pushLocalChanges = true,
                        pullRemoteFirst = false
                    )
                } catch (_: Exception) {}
            }
            WatchSessionRepository.addLog("Admin: Payout rejected ($reason). Coins refunded.", LogType.WARNING)
        }
    }

    fun adminDeleteTask(taskId: String) {
        viewModelScope.launch {
            dataStoreManager.adminDeleteVideoTask(taskId)
            val url = cloudServerUrl.value
            if (url.isNotBlank()) {
                try {
                    com.example.admin.CloudDriveServerManager.syncData(
                        serverUrl = url,
                        dataStoreManager = dataStoreManager,
                        pushAdminContent = true,
                        pushLocalChanges = true,
                        pullRemoteFirst = false
                    )
                } catch (_: Exception) {}
            }
            WatchSessionRepository.addLog("Admin: Deleted task #$taskId", LogType.INFO)
        }
    }

    fun togglePinVideoTask(taskId: String) {
        viewModelScope.launch {
            val isPinned = dataStoreManager.togglePinVideoTask(taskId)
            val url = cloudServerUrl.value
            if (url.isNotBlank()) {
                try {
                    com.example.admin.CloudDriveServerManager.syncData(
                        serverUrl = url,
                        dataStoreManager = dataStoreManager,
                        pushAdminContent = true,
                        pushLocalChanges = true,
                        pullRemoteFirst = false
                    )
                } catch (_: Exception) {}
            }
            WatchSessionRepository.addLog(
                if (isPinned) "Admin: Pinned task #$taskId to top" else "Admin: Unpinned task #$taskId",
                LogType.INFO
            )
        }
    }

    fun addAdminPost(
        title: String,
        message: String,
        targetTab: String,
        postType: String,
        actionUrl: String = "",
        imageUrl: String = "",
        isPinned: Boolean = false
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val post = com.example.data.AdminPostItem(
                id = "post_${now}",
                title = title.trim(),
                message = message.trim(),
                targetTab = targetTab.uppercase(),
                postType = postType.uppercase(),
                actionUrl = actionUrl.trim(),
                imageUrl = imageUrl.trim(),
                createdAt = now,
                isPinned = isPinned,
                pinnedAt = if (isPinned) now else 0L
            )
            dataStoreManager.markItemsNotified(setOf(post.id))
            dataStoreManager.addAdminPost(post)

            val url = cloudServerUrl.value
            if (url.isNotBlank()) {
                try {
                    com.example.admin.CloudDriveServerManager.syncData(
                        serverUrl = url,
                        dataStoreManager = dataStoreManager,
                        pushAdminContent = true,
                        pushLocalChanges = true,
                        pullRemoteFirst = false
                    )
                } catch (_: Exception) {}
            }
            WatchSessionRepository.addLog("Admin published ${post.postType} to ${post.targetTab}: \"${post.title}\"", LogType.SUCCESS)
        }
    }

    fun togglePinAdminPost(postId: String) {
        viewModelScope.launch {
            val isPinned = dataStoreManager.togglePinAdminPost(postId)
            val url = cloudServerUrl.value
            if (url.isNotBlank()) {
                try {
                    com.example.admin.CloudDriveServerManager.syncData(
                        serverUrl = url,
                        dataStoreManager = dataStoreManager,
                        pushAdminContent = true,
                        pushLocalChanges = true,
                        pullRemoteFirst = false
                    )
                } catch (_: Exception) {}
            }
            WatchSessionRepository.addLog(
                if (isPinned) "Admin: Pinned post #$postId to top" else "Admin: Unpinned post #$postId",
                LogType.INFO
            )
        }
    }

    fun deleteAdminPost(postId: String) {
        viewModelScope.launch {
            dataStoreManager.deleteAdminPost(postId)
            val url = cloudServerUrl.value
            if (url.isNotBlank()) {
                try {
                    com.example.admin.CloudDriveServerManager.syncData(
                        serverUrl = url,
                        dataStoreManager = dataStoreManager,
                        pushAdminContent = true,
                        pushLocalChanges = true,
                        pullRemoteFirst = false
                    )
                } catch (_: Exception) {}
            }
            WatchSessionRepository.addLog("Admin deleted post #$postId", LogType.INFO)
        }
    }

    fun dismissAdminPost(postId: String) {
        viewModelScope.launch {
            dataStoreManager.dismissAdminPost(postId)
        }
    }

    fun adminUpdateUserCoins(userEmail: String, newCoins: Int) {
        viewModelScope.launch {
            dataStoreManager.adminUpdateUserCoins(userEmail, newCoins)
            val url = cloudServerUrl.value
            if (url.isNotBlank()) {
                try {
                    com.example.admin.CloudDriveServerManager.syncData(
                        serverUrl = url,
                        dataStoreManager = dataStoreManager,
                        pushAdminContent = true,
                        pushLocalChanges = true,
                        pullRemoteFirst = false
                    )
                } catch (_: Exception) {}
            }
            WatchSessionRepository.addLog("Admin: Updated coins to $newCoins for $userEmail", LogType.SUCCESS)
        }
    }

    fun likeTask(taskId: String, taskTitle: String, onResult: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            val res = dataStoreManager.recordTaskLike(taskId, taskTitle)
            onResult?.invoke(res.first, res.second)
            if (res.first) {
                WatchSessionRepository.addLog("Liked video \"$taskTitle\": +5 coins rewarded!", LogType.SUCCESS)
                val url = cloudServerUrl.value
                if (url.isNotBlank()) {
                    launch {
                        try {
                            com.example.admin.CloudDriveServerManager.syncData(
                                serverUrl = url,
                                dataStoreManager = dataStoreManager,
                                pushAdminContent = false,
                                pushLocalChanges = true,
                                pullRemoteFirst = false
                            )
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    fun commentTask(taskId: String, taskTitle: String, onResult: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            val res = dataStoreManager.recordTaskComment(taskId, taskTitle)
            onResult?.invoke(res.first, res.second)
            if (res.first) {
                WatchSessionRepository.addLog("Comment on \"$taskTitle\": +5 coins rewarded!", LogType.SUCCESS)
                val url = cloudServerUrl.value
                if (url.isNotBlank()) {
                    launch {
                        try {
                            com.example.admin.CloudDriveServerManager.syncData(
                                serverUrl = url,
                                dataStoreManager = dataStoreManager,
                                pushAdminContent = false,
                                pushLocalChanges = true,
                                pullRemoteFirst = false
                            )
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    fun sendSupportMessage(
        messageText: String,
        targetUserEmail: String? = null,
        targetUserId: String? = null,
        targetUserName: String? = null
    ) {
        val cleanText = messageText.trim()
        if (cleanText.isEmpty()) return
        val isAdmin = com.example.BuildConfig.APP_ROLE == "ADMIN"
        val activeUser = currentUser.value
        val resolvedEmail = (targetUserEmail ?: activeUser?.email ?: "guest@watchearn.com").trim().lowercase()
        val resolvedId = targetUserId ?: activeUser?.userId ?: "usr_${Math.abs(resolvedEmail.hashCode()) % 100000}"
        val resolvedName = (targetUserName ?: activeUser?.name ?: resolvedEmail.substringBefore("@")).ifBlank { "User" }
        val role = if (isAdmin) "ADMIN" else "USER"

        viewModelScope.launch {
            val msg = dataStoreManager.addSupportMessage(
                userId = resolvedId,
                userEmail = resolvedEmail,
                userName = resolvedName,
                senderRole = role,
                messageText = cleanText
            )
            dataStoreManager.markItemsNotified(setOf(msg.id))
            val url = cloudServerUrl.value
            if (url.isNotBlank()) {
                launch {
                    try {
                        com.example.admin.CloudDriveServerManager.syncData(
                            serverUrl = url,
                            dataStoreManager = dataStoreManager,
                            pushAdminContent = isAdmin,
                            pushLocalChanges = true,
                            pullRemoteFirst = false
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun lockTask(taskId: String, durationMillis: Long = 6 * 60 * 60 * 1000L) {
        viewModelScope.launch {
            dataStoreManager.lockTask(taskId, durationMillis)
            WatchSessionRepository.addLog("Task #$taskId locked for 6 hours due to incomplete watch.", LogType.WARNING)
        }
    }

    fun unlockTask(taskId: String) {
        viewModelScope.launch {
            dataStoreManager.unlockTask(taskId)
            WatchSessionRepository.addLog("Task #$taskId unlocked.", LogType.SUCCESS)
        }
    }

    fun saveCloudServerUrl(url: String) {
        viewModelScope.launch {
            dataStoreManager.setCloudServerUrl(url)
            WatchSessionRepository.addLog("Saved Google Drive Server URL: $url", LogType.INFO)
        }
    }

    fun syncWithGoogleDriveServer(onResult: (Boolean, String) -> Unit) {
        val url = cloudServerUrl.value.ifBlank { DataStoreManager.DEFAULT_CLOUD_SERVER_URL }
        if (url.isBlank()) {
            onResult(false, "Please enter your Google Drive Web App URL first.")
            return
        }
        viewModelScope.launch {
            val isAdmin = com.example.BuildConfig.APP_ROLE == "ADMIN"
            val shouldPush = isAdmin || currentUser.value != null
            val res = com.example.admin.CloudDriveServerManager.syncData(
                serverUrl = url,
                dataStoreManager = dataStoreManager,
                pushAdminContent = isAdmin,
                pushLocalChanges = shouldPush
            )
            onResult(res.first, res.second)
            if (res.first) {
                WatchSessionRepository.addLog("Sync with Google Drive successful!", LogType.SUCCESS)
            } else {
                WatchSessionRepository.addLog("Drive sync error: ${res.second}", LogType.ERROR)
            }
        }
    }

    fun testGoogleDriveConnection(onResult: (Boolean, String) -> Unit) {
        val url = cloudServerUrl.value
        if (url.isBlank()) {
            onResult(false, "Please enter your Google Drive Web App URL first.")
            return
        }
        viewModelScope.launch {
            val res = com.example.admin.CloudDriveServerManager.testConnection(url)
            onResult(res.first, res.second)
            if (res.first) {
                dataStoreManager.setCloudServerStatus("Connected to Google Drive")
                WatchSessionRepository.addLog("Google Drive connection verified!", LogType.SUCCESS)
            } else {
                dataStoreManager.setCloudServerStatus("Connection Failed")
                WatchSessionRepository.addLog("Google Drive connection failed: ${res.second}", LogType.WARNING)
            }
        }
    }

    private fun startTaskInternal(
        context: Context,
        videoUrl: String,
        requiredSeconds: Int,
        rewardCoins: Int,
        taskId: String?
    ) {
        if (searchProgress.value.isSearching) {
            return // Avoid duplicate search clicks
        }

        val resolvedTaskId = taskId
            ?: selectedTaskId.value
            ?: videoTasks.value.find { it.videoUrl == videoUrl }?.id
            ?: videoTasks.value.firstOrNull()?.id
            ?: "default_task"

        // Check if task is currently locked (4h for completed)
        val currentTask = videoTasks.value.find { it.id == resolvedTaskId }
        if (currentTask != null && currentTask.isLocked) {
            val remainStr = currentTask.getLockRemainingFormatted()
            if (currentTask.isCompleted) {
                WatchSessionRepository.showTaskIncompleteMessage(
                    "This task is completed and locked for 4 hours ($remainStr remaining)."
                )
                return
            } else {
                unlockTask(currentTask.id)
            }
        }

        viewModelScope.launch {
            WatchSessionRepository.resetSessionForNewTask(resolvedTaskId)
            // Strict Continuous Watch Rule: Every session starts strictly at 00:00!
            dataStoreManager.setWatchedMillis(0L)
            WatchSessionRepository.setWatchedMillis(0L)

            val taskUrl = currentTask?.videoUrl?.takeIf { it.startsWith("http") } ?: videoUrl
            val effectiveUrl = if (taskUrl == "PASTE_MY_YOUTUBE_LINK_HERE" || !taskUrl.startsWith("http")) {
                SampleTask.fallbackDemoUrl
            } else {
                taskUrl
            }

            // Show clean "Opening..." overlay immediately so the user sees instant feedback
            WatchSessionRepository.updateSearchProgress(
                com.example.data.SearchProgressState(
                    isSearching = true,
                    stepText = "Opening..."
                )
            )

            val taskTitleCandidate = currentTask?.title?.takeIf {
                it.isNotBlank() && it != "YouTube Video" && !it.startsWith("YouTube Video (") && it != "YouTube Video Task"
            }
            val taskChannelCandidate = currentTask?.channelName?.takeIf {
                it.isNotBlank() && it != "YouTube Creator" && it != "YouTube Channel"
            }

            val extractedVideoId = com.example.util.TitleMatcher.extractVideoId(effectiveUrl)
            // Fetch fresh oEmbed and exact video duration for this exact videoId in parallel
            val fetchTimeoutMs = if (taskTitleCandidate != null) 2000L else 4500L
            var exactVideoDurationSecs = 0
            val fetchedOEmbed = kotlinx.coroutines.withTimeoutOrNull(fetchTimeoutMs) {
                val oEmbedDeferred = async { OEmbedFetcher.fetchOEmbed(effectiveUrl) }
                val durationDeferred = async { OEmbedFetcher.fetchVideoExactDurationSeconds(extractedVideoId) }
                exactVideoDurationSecs = durationDeferred.await()
                oEmbedDeferred.await()
            }
            if (fetchedOEmbed is OEmbedResult.Success) {
                _oEmbedState.value = fetchedOEmbed
            }

            val oEmbedSuccess = fetchedOEmbed as? OEmbedResult.Success
            val oEmbedTitleCandidate = oEmbedSuccess?.title?.takeIf {
                it.isNotBlank() && it != "YouTube Video" && !it.startsWith("YouTube Video (")
            }
            val oEmbedAuthorCandidate = oEmbedSuccess?.authorName?.takeIf {
                it.isNotBlank() && it != "YouTube Creator" && it != "YouTube Channel"
            }

            val title: String = taskTitleCandidate ?: oEmbedTitleCandidate ?: currentTask?.title ?: "YouTube Video Task"
            val author: String = taskChannelCandidate ?: oEmbedAuthorCandidate ?: currentTask?.channelName ?: ""

            if (currentTask != null && oEmbedTitleCandidate != null &&
                (taskTitleCandidate == null || currentTask.title != oEmbedTitleCandidate || currentTask.channelName != author)
            ) {
                dataStoreManager.updateVideoTask(
                    currentTask.copy(
                        title = oEmbedTitleCandidate,
                        channelName = oEmbedAuthorCandidate ?: currentTask.channelName
                    )
                )
            }

            // Strictly use the video URL ONLY for fetching Title, Channel Name, Video ID, and Duration above.
            // NEVER use the video URL to open or play the video in YouTube!
            // Instead, open YouTube via its standard Home Launcher Intent and let YouTubeLiveSearchService
            // locate & play the exact video matching targetVideoId via Browse Features or YouTube Search.
            WatchSessionRepository.addLog(
                "Opening YouTube app for organic search/browse: \"$title\" ($author) [ID: ${extractedVideoId ?: "N/A"}]",
                LogType.INFO
            )

            val extractedHandle = com.example.util.TitleMatcher.extractChannelHandle(oEmbedSuccess?.authorUrl)
            val isLiveStreamTask = (currentTask?.isLive == true) || effectiveUrl.contains("/live/", ignoreCase = true)

            YouTubeLiveSearchService.armSearchTrigger(
                title = title,
                channel = author,
                videoUrl = effectiveUrl,
                videoId = extractedVideoId,
                channelHandle = extractedHandle,
                videoDurationSeconds = if (isLiveStreamTask) 0 else exactVideoDurationSecs,
                isLiveStream = isLiveStreamTask
            )

            WatchSessionRepository.startTask(
                taskTitle = title,
                taskAuthor = author,
                requiredSeconds = requiredSeconds,
                initialWatchedMillis = 0L,
                rewardCoins = rewardCoins,
                taskId = resolvedTaskId
            )
            WatchTimerService.start(context)

            try {
                val hasRealChannel = author.isNotBlank() &&
                        !author.equals("YouTube Creator", ignoreCase = true) &&
                        !author.equals("YouTube Channel", ignoreCase = true)
                val searchQuery = if (hasRealChannel && !title.contains(author, ignoreCase = true)) "$title $author" else title
                val ytSearchIntent = PermissionHelper.openYouTubeSearchResultsIntent(context, searchQuery)
                context.startActivity(ytSearchIntent)
            } catch (_: Exception) {
                try {
                    val ytHomeIntent = PermissionHelper.openYouTubeAppHomeIntent(context)
                    context.startActivity(ytHomeIntent)
                } catch (_: Exception) {}
            }

            WatchSessionRepository.addLog(
                "YouTube launched cleanly! Auto-searching \"$title\" inside YouTube...",
                LogType.SUCCESS
            )

            // Keep in-app "Opening..." overlay visible during activity transition, then clear in-app state
            delay(1200L)
            WatchSessionRepository.updateSearchProgress(com.example.data.SearchProgressState(isSearching = false))
        }
    }

    val currentMilestoneTier: StateFlow<WatchDurationTier?> = WatchSessionRepository.currentMilestoneTier

    fun resumeVideoInYouTube(context: Context) {
        val title = targetTaskTitle.value ?: videoTasks.value.find { it.id == selectedTaskId.value }?.title ?: ""
        val author = WatchSessionRepository.targetTaskAuthor.value ?: videoTasks.value.find { it.id == selectedTaskId.value }?.channelName ?: ""
        val handle = com.example.util.TitleMatcher.extractChannelHandle((_oEmbedState.value as? OEmbedResult.Success)?.authorUrl)
        if (title.isNotBlank()) {
            YouTubeLiveSearchService.armSearchTrigger(title = title, channel = author, channelHandle = handle)
        }
        try {
            val hasRealChannel = author.isNotBlank() && !author.equals("YouTube Creator", ignoreCase = true)
            val query = if (hasRealChannel && !title.contains(author, ignoreCase = true)) "$title $author" else title
            val openIntent = PermissionHelper.openYouTubeSearchResultsIntent(context, query)
            context.startActivity(openIntent)
        } catch (_: Exception) {
            try {
                val openIntent = PermissionHelper.openYouTubeAppHomeIntent(context)
                context.startActivity(openIntent)
            } catch (_: Exception) {}
        }
    }

    fun claimMilestoneReward(context: Context) {
        val watchedSecs = (watchedMillis.value / 1000).toInt()
        val requiredSecs = _selectedTierSeconds.value
        val milestone = currentMilestoneTier.value ?: com.example.data.calculateContinuousWatchMilestone(watchedSecs, requiredSecs)

        if (watchedSecs < 180 || milestone == null) {
            val remaining = (180 - watchedSecs).coerceAtLeast(0)
            WatchSessionRepository.addLog(
                "Watched $watchedSecs sec (less than 3 continuous minutes). Need $remaining more seconds to earn coins!",
                LogType.WARNING
            )
            return
        }

        viewModelScope.launch {
            val title = targetTaskTitle.value ?: "YouTube Video Task"
            dataStoreManager.addRewardTransaction("${milestone.minutes}m Continuous Watch: $title", milestone.coins)
            dataStoreManager.setTaskCompleted(true)
            val activeId = selectedTaskId.value ?: WatchSessionRepository.activeTaskId.value
            if (activeId != null) {
                dataStoreManager.markTaskCompleted(activeId, milestone.coins)
            }
            _activeRewardCoins.value = milestone.coins
            _showSuccessDialog.value = true
            WatchSessionRepository.setCompletedState()
            WatchTimerService.stop(context)
            WatchSessionRepository.addLog(
                "🎉 Milestone reward claimed: ${milestone.minutes}m watch time = +${milestone.coins} coins!",
                LogType.SUCCESS
            )
            val url = cloudServerUrl.value
            if (url.isNotBlank()) {
                launch {
                    try {
                        com.example.admin.CloudDriveServerManager.syncData(
                            serverUrl = url,
                            dataStoreManager = dataStoreManager,
                            pushAdminContent = false,
                            pushLocalChanges = true,
                            pullRemoteFirst = false
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun stopTask(context: Context) {
        val milestone = WatchSessionRepository.currentMilestoneTier.value
        val title = WatchSessionRepository.targetTaskTitle.value ?: "YouTube Video Task"
        val activeId = WatchSessionRepository.activeTaskId.value

        if (milestone != null) {
            viewModelScope.launch {
                dataStoreManager.addRewardTransaction("$title (${milestone.minutes}m continuous watch)", milestone.coins)
                dataStoreManager.setTaskCompleted(true)
                if (activeId != null) {
                    dataStoreManager.markTaskCompleted(activeId, milestone.coins)
                }
                _activeRewardCoins.value = milestone.coins
                _showSuccessDialog.value = true
                WatchSessionRepository.addLog(
                    "Watch session ended: Reached ${milestone.minutes}m milestone! Awarded +${milestone.coins} coins.",
                    LogType.SUCCESS
                )
            }
        } else {
            val watchedSec = (WatchSessionRepository.watchedMillis.value / 1000).toInt()
            WatchSessionRepository.addLog(
                "Watch session stopped at ${watchedSec}s. Watched less than 3 minutes minimum continuous requirement. 0 coins awarded.",
                LogType.WARNING
            )
        }

        WatchTimerService.stop(context)
        WatchSessionRepository.resetSession()
    }

    fun withdrawCoins(
        coins: Int,
        method: String,
        destination: String,
        onComplete: (success: Boolean, message: String) -> Unit
    ) {
        if (coins < 1000) {
            onComplete(false, "Minimum payout is 1000 Coins (₹10.00 INR).")
            return
        }
        if (coins > walletBalance.value) {
            onComplete(false, "Insufficient balance! You have ${walletBalance.value} Coins.")
            return
        }
        if (destination.isBlank()) {
            onComplete(false, "Please enter a valid payout ID or number.")
            return
        }

        viewModelScope.launch {
            val success = dataStoreManager.withdrawCoins(coins, method, destination.trim())
            if (success) {
                val inr = coins.toDouble() / com.example.data.COINS_PER_INR.toDouble()
                val formatted = String.format(java.util.Locale.US, "%.2f", inr)
                WatchSessionRepository.addLog(
                    "Withdrawal request submitted: $coins coins (₹$formatted INR) to $method: $destination",
                    LogType.SUCCESS
                )
                com.example.service.NotificationChannels.sendAdminUpdateNotification(
                    context = getApplication(),
                    title = "⏳ Withdrawal Request Submitted (₹$formatted)",
                    body = "Your payout request of $coins Coins (₹$formatted INR) via $method is being processed."
                )
                onComplete(true, "Payout request for $coins Coins (₹$formatted INR) via $method submitted successfully!")
                val url = cloudServerUrl.value
                if (url.isNotBlank()) {
                    launch {
                        try {
                            com.example.admin.CloudDriveServerManager.syncData(
                                serverUrl = url,
                                dataStoreManager = dataStoreManager,
                                pushAdminContent = false,
                                pushLocalChanges = true,
                                pullRemoteFirst = false
                            )
                        } catch (_: Exception) {}
                    }
                }
            } else {
                onComplete(false, "Payout processing failed. Check wallet balance.")
            }
        }
    }

    fun resetAll(context: Context) {
        WatchTimerService.stop(context)
        WatchSessionRepository.resetSession()
        viewModelScope.launch {
            dataStoreManager.resetAll()
            WatchSessionRepository.addLog("Wallet, transactions, and task progress reset to zero.", LogType.INFO)
        }
    }

    fun dismissSuccessDialog() {
        _showSuccessDialog.value = false
    }
}
