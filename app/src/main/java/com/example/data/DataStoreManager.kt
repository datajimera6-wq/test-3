package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "watchearn_prefs")

class DataStoreManager(private val context: Context) {
    val appContext: Context = context.applicationContext

    companion object {
        // =========================================================================
        // 1. ADMIN APP DEFAULT GOOGLE DRIVE SERVER URL ("Kingo Admin")
        // Future mein Admin App ka link code se change karne ke liye yahan badlein:
        // =========================================================================
        const val DEFAULT_ADMIN_CLOUD_SERVER_URL =
            "https://script.google.com/macros/s/AKfycbzeuvE2McdDn-PAw9CNUHs_QkoIbk7R4oAFtrk8c-RKmJX9r0a8p_xEqaS6bLV6FuW3/exec"

        // =========================================================================
        // 2. USER APP DEFAULT GOOGLE DRIVE SERVER URL ("Kingo King")
        // Future mein User App ka link code se change karne ke liye yahan badlein:
        // =========================================================================
        const val DEFAULT_USER_CLOUD_SERVER_URL =
            "https://script.google.com/macros/s/AKfycbzeuvE2McdDn-PAw9CNUHs_QkoIbk7R4oAFtrk8c-RKmJX9r0a8p_xEqaS6bLV6FuW3/exec"

        val DEFAULT_CLOUD_SERVER_URL: String
            get() = if (com.example.BuildConfig.APP_ROLE == "ADMIN") {
                DEFAULT_ADMIN_CLOUD_SERVER_URL
            } else {
                DEFAULT_USER_CLOUD_SERVER_URL
            }

        private val KEY_WALLET_BALANCE = intPreferencesKey("wallet_balance")
        private val KEY_TASK_COMPLETED = booleanPreferencesKey("task_completed")
        private val KEY_WATCHED_MILLIS = longPreferencesKey("watched_millis")
        private val KEY_TRANSACTIONS = stringPreferencesKey("transactions_json")
        private val KEY_ACTIVE_VIDEO_URL = stringPreferencesKey("active_video_url")
        private val KEY_LIVE_SEARCH_MODE = booleanPreferencesKey("live_search_mode")
        private val KEY_VIDEO_TASKS = stringPreferencesKey("video_tasks_json")
        private val KEY_SELECTED_TASK_ID = stringPreferencesKey("selected_task_id")
        private val KEY_USERS = stringPreferencesKey("users_json")
        private val KEY_CURRENT_USER_EMAIL = stringPreferencesKey("current_user_email")
        private val KEY_PAYOUT_REQUESTS = stringPreferencesKey("payout_requests_json")
        private val KEY_LIKED_TASKS = stringPreferencesKey("liked_tasks_json")
        private val KEY_COMMENT_COUNTS = stringPreferencesKey("comment_counts_json")
        private val KEY_CLOUD_SERVER_URL = stringPreferencesKey("cloud_server_url")
        private val KEY_CLOUD_SERVER_STATUS = stringPreferencesKey("cloud_server_status")
        private val KEY_DELETED_TASK_IDS = stringPreferencesKey("deleted_task_ids_json")
        private val KEY_ADMIN_POSTS = stringPreferencesKey("admin_posts_json")
        private val KEY_NOTIFIED_ITEM_IDS = stringPreferencesKey("notified_item_ids_json")
        private val KEY_DISMISSED_POST_IDS = stringPreferencesKey("dismissed_post_ids_json")
        private val KEY_SUPPORT_MESSAGES = stringPreferencesKey("support_messages_json")
        private val KEY_REMOTE_APP_UPDATE_JSON = stringPreferencesKey("remote_app_update_json")
        private val KEY_INSTALLED_UPDATE_SIGNATURE = stringPreferencesKey("installed_update_signature")
        private val KEY_UPDATE_DRIVE_FOLDER_URL = stringPreferencesKey("update_drive_folder_url")
        private val KEY_DELETED_POST_IDS = stringPreferencesKey("deleted_post_ids_json")
        private val KEY_APP_DOWNLOAD_URL = stringPreferencesKey("app_download_url")
        private val KEY_PENDING_REFERRAL_CODE = stringPreferencesKey("pending_referral_code")
        private val KEY_LAST_SHARED_REFERRAL_CODE = stringPreferencesKey("last_shared_referral_code")
        private val KEY_FULL_SCREEN_OPENING_OVERLAY = booleanPreferencesKey("full_screen_opening_overlay")

        const val SYSTEM_CONFIG_APP_LINK_ID = "__system_config_app_download_url__"
        const val SYSTEM_CONFIG_REF_SHARE_ID = "__system_config_last_referral_share__"
        const val SYSTEM_CONFIG_FULL_SCREEN_OVERLAY_ID = "__system_config_full_screen_overlay__"

        const val DEFAULT_APP_DOWNLOAD_URL =
            "https://github.com/datajimera2-web/Kingo-King-App/releases/download/v1/kingoking.apk"

        /**
         * Normalizes any raw or malformed app download link (such as GitHub releases or Google Drive)
         * into a clean, clickable https:// URL. Falls back to DEFAULT_APP_DOWNLOAD_URL when blank.
         */
        fun normalizeAppDownloadUrl(raw: String?): String {
            if (raw.isNullOrBlank()) return DEFAULT_APP_DOWNLOAD_URL
            var decoded = try {
                java.net.URLDecoder.decode(raw.trim(), "UTF-8")
            } catch (_: Exception) {
                raw.trim()
            }
            decoded = decoded.replace("%20", " ").trim()

            // 1. GitHub Releases URLs
            if (decoded.contains("github.com", ignoreCase = true)) {
                val tagRegex = Regex("""github\.com/([^/]+)/([^/]+)/releases/tag/([^/\s#?]+)""", RegexOption.IGNORE_CASE)
                val tagMatch = tagRegex.find(decoded)
                if (tagMatch != null) {
                    val owner = tagMatch.groupValues[1]
                    val repo = tagMatch.groupValues[2]
                    val tag = tagMatch.groupValues[3]
                    return "https://github.com/$owner/$repo/releases/download/$tag/kingoking.apk"
                }
                val directDownloadRegex = Regex("""github\.com/([^/]+)/([^/]+)/releases/download/([^/\s#?]+)/([^\s#?]+)""", RegexOption.IGNORE_CASE)
                val directMatch = directDownloadRegex.find(decoded)
                if (directMatch != null) {
                    val owner = directMatch.groupValues[1]
                    val repo = directMatch.groupValues[2]
                    val tag = directMatch.groupValues[3]
                    val file = directMatch.groupValues[4]
                    return "https://github.com/$owner/$repo/releases/download/$tag/$file"
                }
                val repoRegex = Regex("""github\.com/([^/]+)/([^/\s#?]+)""", RegexOption.IGNORE_CASE)
                val repoMatch = repoRegex.find(decoded)
                if (repoMatch != null) {
                    val owner = repoMatch.groupValues[1]
                    val repo = repoMatch.groupValues[2]
                    return "https://github.com/$owner/$repo/releases/latest/download/kingoking.apk"
                }
            }

            // 2. Check if a Google Drive file ID is present anywhere in the string (e.g., /file/d/<ID>, /d/<ID>, or id=<ID>)
            val driveFileIdRegex = Regex("""(?:/file/d/|/d/|[?&]id=)([a-zA-Z0-9_-]{18,60})""")
            val driveMatch = driveFileIdRegex.find(decoded)
            if (driveMatch != null) {
                val fileId = driveMatch.groupValues[1]
                return "https://drive.google.com/file/d/$fileId/view?usp=sharing"
            }

            // 3. Check if the user pasted a bare Google Drive file ID
            val firstToken = decoded.split(Regex("\\s+")).firstOrNull()?.trim() ?: ""
            if (firstToken.matches(Regex("^[a-zA-Z0-9_-]{24,50}$")) && !firstToken.contains(".")) {
                return "https://drive.google.com/file/d/$firstToken/view?usp=sharing"
            }

            // 4. Check if an explicit https:// or http:// URL is inside the string
            val httpMatch = Regex("""https?://[^\s"<>]+""", RegexOption.IGNORE_CASE).find(decoded)
            if (httpMatch != null) {
                return httpMatch.value.trimEnd('.', ',', ';', ')')
            }

            // 5. Fix broken scheme like "drive : //..." or "drive://..."
            val collapsed = decoded.replace(Regex("""^[a-zA-Z]+\s*:\s*//\s*"""), "")
                .split(Regex("\\s+"))
                .firstOrNull()
                ?.trim() ?: ""
            if (collapsed.startsWith("file/d/")) {
                return "https://drive.google.com/$collapsed"
            }
            if (collapsed.contains(".") && !collapsed.startsWith("http", ignoreCase = true)) {
                return "https://$collapsed"
            }

            return DEFAULT_APP_DOWNLOAD_URL
        }

        /**
         * Transforms any Google Drive link or download URL into a DIRECT 1-Click download link
         * that immediately triggers the raw APK download without showing an intermediate browser preview page.
         */
        fun toDirectDownloadUrl(raw: String?, referralCode: String? = null): String {
            val cleanUrl = normalizeAppDownloadUrl(raw)
            val cleanRef = referralCode?.trim()?.takeIf { it.length == 6 && it.all { ch -> ch.isDigit() } }

            // 1. Check for Google Drive file ID
            val driveFileIdRegex = Regex("""(?:/file/d/|/d/|[?&]id=)([a-zA-Z0-9_-]{18,60})""")
            val driveMatch = driveFileIdRegex.find(cleanUrl)
            if (driveMatch != null) {
                val fileId = driveMatch.groupValues[1]
                val base = "https://drive.google.com/uc?export=download&id=$fileId&confirm=t"
                return if (cleanRef != null) "$base#ref=$cleanRef" else base
            }

            // 2. Direct Web or HTTP link
            return if (cleanRef != null) {
                if (cleanUrl.contains("#")) "$cleanUrl&ref=$cleanRef"
                else if (cleanUrl.contains("?")) "$cleanUrl&ref=$cleanRef"
                else "$cleanUrl#ref=$cleanRef"
            } else {
                cleanUrl
            }
        }

        @Volatile
        var lastLocalMutationMillis: Long = 0L

        val pendingAdminCoinUpdates = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()
        val pendingPasswordResets = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    }

    val adminPostsFlow: Flow<List<AdminPostItem>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_ADMIN_POSTS]
        val list = if (json == null) {
            getDefaultAdminPosts()
        } else {
            parseAdminPostsJson(json)
        }
        list.filter { !it.postType.startsWith("CONFIG_") && !it.id.startsWith("__system_config_") }
            .sortedWith(
                compareByDescending<AdminPostItem> { it.isPinned }
                    .thenByDescending { if (it.isPinned) it.pinnedAt else 0L }
            )
    }

    val rawAdminPostsWithConfigFlow: Flow<List<AdminPostItem>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_ADMIN_POSTS]
        val baseList = if (json == null) {
            getDefaultAdminPosts()
        } else {
            parseAdminPostsJson(json)
        }.filter { !it.postType.startsWith("CONFIG_") && !it.id.startsWith("__system_config_") }.toMutableList()

        val appDownloadUrl = normalizeAppDownloadUrl(prefs[KEY_APP_DOWNLOAD_URL])
        if (appDownloadUrl.isNotBlank()) {
            baseList.add(
                AdminPostItem(
                    id = SYSTEM_CONFIG_APP_LINK_ID,
                    title = "App Download Link",
                    message = appDownloadUrl,
                    targetTab = "NONE",
                    postType = "CONFIG_APP_LINK",
                    actionUrl = appDownloadUrl,
                    imageUrl = "",
                    createdAt = System.currentTimeMillis(),
                    isPinned = false,
                    pinnedAt = 0L
                )
            )
        }

        val lastSharedRef = prefs[KEY_LAST_SHARED_REFERRAL_CODE]?.trim() ?: ""
        if (lastSharedRef.length == 6 && lastSharedRef.all { it.isDigit() }) {
            baseList.add(
                AdminPostItem(
                    id = SYSTEM_CONFIG_REF_SHARE_ID,
                    title = lastSharedRef,
                    message = lastSharedRef,
                    targetTab = "NONE",
                    postType = "CONFIG_REF_SHARE",
                    actionUrl = lastSharedRef,
                    imageUrl = "",
                    createdAt = System.currentTimeMillis(),
                    isPinned = false,
                    pinnedAt = 0L
                )
            )
        }

        val isFullScreenOverlay = prefs[KEY_FULL_SCREEN_OPENING_OVERLAY] ?: true
        baseList.add(
            AdminPostItem(
                id = SYSTEM_CONFIG_FULL_SCREEN_OVERLAY_ID,
                title = isFullScreenOverlay.toString(),
                message = isFullScreenOverlay.toString(),
                targetTab = "NONE",
                postType = "CONFIG_FULL_SCREEN_OVERLAY",
                actionUrl = isFullScreenOverlay.toString(),
                imageUrl = "",
                createdAt = System.currentTimeMillis(),
                isPinned = false,
                pinnedAt = 0L
            )
        )
        baseList
    }

    val notifiedItemIdsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_NOTIFIED_ITEM_IDS] ?: "[]"
        try {
            val arr = JSONArray(json)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    val dismissedPostIdsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_DISMISSED_POST_IDS] ?: "[]"
        try {
            val arr = JSONArray(json)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    val remoteAppUpdateFlow: Flow<AppUpdateInfo?> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_REMOTE_APP_UPDATE_JSON] ?: return@map null
        parseAppUpdateInfoJson(json)
    }

    val installedUpdateSignatureFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_INSTALLED_UPDATE_SIGNATURE] ?: ""
    }

    val updateDriveFolderUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_UPDATE_DRIVE_FOLDER_URL] ?: ""
    }

    val appDownloadUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        normalizeAppDownloadUrl(prefs[KEY_APP_DOWNLOAD_URL])
    }

    val pendingReferralCodeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        val pending = prefs[KEY_PENDING_REFERRAL_CODE]?.trim() ?: ""
        if (pending.length == 6 && pending.all { it.isDigit() }) {
            pending
        } else {
            val shared = prefs[KEY_LAST_SHARED_REFERRAL_CODE]?.trim() ?: ""
            if (shared.length == 6 && shared.all { it.isDigit() }) shared else ""
        }
    }

    val deletedPostIdsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_DELETED_POST_IDS] ?: "[]"
        try {
            val arr = JSONArray(json)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    val deletedTaskIdsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_DELETED_TASK_IDS] ?: "[]"
        try {
            val arr = JSONArray(json)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    val cloudServerUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        if (com.example.BuildConfig.APP_ROLE != "ADMIN") {
            DEFAULT_USER_CLOUD_SERVER_URL
        } else {
            val saved = prefs[KEY_CLOUD_SERVER_URL]?.trim()
            if (saved.isNullOrBlank()) DEFAULT_ADMIN_CLOUD_SERVER_URL else saved
        }
    }

    val cloudServerStatusFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_SERVER_STATUS] ?: "Connected to Default Drive Server"
    }

    val likedTasksFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_LIKED_TASKS] ?: "[]"
        try {
            val arr = JSONArray(json)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
            set
        } catch (_: Exception) {
            emptySet()
        }
    }

    val commentCountsFlow: Flow<Map<String, Int>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_COMMENT_COUNTS] ?: "{}"
        try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, Int>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optInt(k, 0)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    val liveSearchModeFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_LIVE_SEARCH_MODE] ?: true
    }

    val fullScreenOpeningOverlayFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_FULL_SCREEN_OPENING_OVERLAY] ?: true
    }

    suspend fun setFullScreenOpeningOverlayEnabled(enabled: Boolean) {
        lastLocalMutationMillis = System.currentTimeMillis()
        try {
            val legacy = context.getSharedPreferences("watchearn_prefs", Context.MODE_PRIVATE)
            legacy.edit().putBoolean("full_screen_opening_overlay", enabled).apply()
        } catch (_: Exception) {}
        context.dataStore.edit { prefs ->
            prefs[KEY_FULL_SCREEN_OPENING_OVERLAY] = enabled
        }
    }

    val selectedTaskIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_TASK_ID]
    }

    val currentUserEmailFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_CURRENT_USER_EMAIL]
    }

    val usersFlow: Flow<List<UserProfile>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_USERS] ?: "[]"
        parseUsersJson(json)
    }

    val currentUserFlow: Flow<UserProfile?> = context.dataStore.data.map { prefs ->
        val currentEmail = prefs[KEY_CURRENT_USER_EMAIL]
        if (currentEmail.isNullOrBlank()) {
            null
        } else {
            val usersJson = prefs[KEY_USERS] ?: "[]"
            val users = parseUsersJson(usersJson)
            val walletBal = prefs[KEY_WALLET_BALANCE] ?: 0
            users.find { it.email.equals(currentEmail, ignoreCase = true) }?.copy(
                coinsBalance = walletBal
            ) ?: UserProfile(
                userId = "usr_${Math.abs(currentEmail.hashCode()) % 100000}",
                email = currentEmail,
                name = currentEmail.substringBefore("@"),
                coinsBalance = walletBal
            )
        }
    }

    val payoutRequestsFlow: Flow<List<PayoutRequest>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_PAYOUT_REQUESTS] ?: "[]"
        parsePayoutRequestsJson(json)
    }

    val supportMessagesFlow: Flow<List<SupportMessage>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_SUPPORT_MESSAGES] ?: "[]"
        parseSupportMessagesJson(json)
    }

    val videoTasksFlow: Flow<List<VideoTaskItem>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_VIDEO_TASKS]
        val list = if (json == null) {
            getDefaultTasks()
        } else {
            parseVideoTasksJson(json)
        }
        val visibleList = if (com.example.BuildConfig.APP_ROLE == "ADMIN") {
            list.filter { !it.isCompletionLimitReached }
        } else {
            list.filter { !it.isCompletionLimitReached }
        }
        visibleList.sortedWith(
            compareByDescending<VideoTaskItem> { it.isPinned }
                .thenByDescending { if (it.isPinned) it.pinnedAt else 0L }
        )
    }

    val walletBalanceFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_WALLET_BALANCE] ?: 0
    }

    val isTaskCompletedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_TASK_COMPLETED] ?: false
    }

    val watchedMillisFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_WATCHED_MILLIS] ?: 0L
    }

    val activeVideoUrlFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_VIDEO_URL]
    }

    val transactionsFlow: Flow<List<WalletTransaction>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[KEY_TRANSACTIONS] ?: "[]"
        parseTransactionsJson(jsonStr)
    }

    suspend fun saveWatchedMillis(millis: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WATCHED_MILLIS] = millis
        }
    }

    suspend fun setWatchedMillis(millis: Long) {
        saveWatchedMillis(millis)
    }

    suspend fun setTaskCompleted(completed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TASK_COMPLETED] = completed
        }
    }

    suspend fun setActiveVideoUrl(url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) {
                prefs.remove(KEY_ACTIVE_VIDEO_URL)
            } else {
                prefs[KEY_ACTIVE_VIDEO_URL] = url
            }
        }
    }

    suspend fun setLiveSearchMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LIVE_SEARCH_MODE] = enabled
        }
    }

    suspend fun addRewardTransaction(taskTitle: String, coins: Int) {
        lastLocalMutationMillis = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            val currentBalance = prefs[KEY_WALLET_BALANCE] ?: 0
            val newBalance = currentBalance + coins
            prefs[KEY_WALLET_BALANCE] = newBalance

            val currentJson = prefs[KEY_TRANSACTIONS] ?: "[]"
            val list = parseTransactionsJson(currentJson).toMutableList()
            list.add(
                0,
                WalletTransaction(
                    id = UUID.randomUUID().toString(),
                    title = taskTitle,
                    coins = coins,
                    timestampMillis = System.currentTimeMillis()
                )
            )
            val serializedTx = serializeTransactionsJson(list)
            prefs[KEY_TRANSACTIONS] = serializedTx
            syncActiveUserIntoUsersList(prefs, newBalanceOverride = newBalance, newTxJsonOverride = serializedTx)
        }
    }

    suspend fun clearContinuousWatchSession() {
        context.dataStore.edit { prefs ->
            prefs[KEY_WATCHED_MILLIS] = 0L
        }
    }

    suspend fun recordTaskLike(taskId: String, taskTitle: String = "YouTube Video"): Pair<Boolean, String> {
        lastLocalMutationMillis = System.currentTimeMillis()
        var added = false
        var message = ""
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_LIKED_TASKS] ?: "[]"
            val arr = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
            var alreadyLiked = false
            for (i in 0 until arr.length()) {
                if (arr.getString(i) == taskId) {
                    alreadyLiked = true
                    break
                }
            }
            if (alreadyLiked) {
                message = "Already earned like reward for this video."
                added = false
            } else {
                arr.put(taskId)
                prefs[KEY_LIKED_TASKS] = arr.toString()

                val currentBalance = prefs[KEY_WALLET_BALANCE] ?: 0
                prefs[KEY_WALLET_BALANCE] = currentBalance + 5

                val currentTxJson = prefs[KEY_TRANSACTIONS] ?: "[]"
                val list = parseTransactionsJson(currentTxJson).toMutableList()
                list.add(
                    0,
                    WalletTransaction(
                        id = UUID.randomUUID().toString(),
                        title = "👍 Video Like Bonus: $taskTitle",
                        coins = 5,
                        timestampMillis = System.currentTimeMillis()
                    )
                )
                val serializedTx = serializeTransactionsJson(list)
                prefs[KEY_TRANSACTIONS] = serializedTx
                syncActiveUserIntoUsersList(
                    prefs,
                    newBalanceOverride = currentBalance + 5,
                    newTxJsonOverride = serializedTx,
                    newLikedJsonOverride = arr.toString()
                )
                added = true
                message = "🎉 +5 Coins added for Liking the video!"
            }
        }
        return Pair(added, message)
    }

    suspend fun markTaskAlreadyLiked(taskId: String) {
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_LIKED_TASKS] ?: "[]"
            val arr = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
            var alreadyLiked = false
            for (i in 0 until arr.length()) {
                if (arr.getString(i) == taskId) {
                    alreadyLiked = true
                    break
                }
            }
            if (!alreadyLiked) {
                arr.put(taskId)
                prefs[KEY_LIKED_TASKS] = arr.toString()
            }
        }
    }

    suspend fun setCloudServerUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CLOUD_SERVER_URL] = url.trim()
        }
    }

    suspend fun setCloudServerStatus(status: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CLOUD_SERVER_STATUS] = status
        }
    }

    suspend fun saveRemoteAppUpdate(updateInfo: AppUpdateInfo?) {
        context.dataStore.edit { prefs ->
            if (updateInfo == null || !updateInfo.hasUpdate || (updateInfo.fileId.isBlank() && updateInfo.downloadUrl.isBlank())) {
                if (prefs.contains(KEY_REMOTE_APP_UPDATE_JSON)) {
                    prefs.remove(KEY_REMOTE_APP_UPDATE_JSON)
                    prefs.remove(KEY_INSTALLED_UPDATE_SIGNATURE)
                }
            } else {
                val newJson = serializeAppUpdateInfoJson(updateInfo)
                val currentJson = prefs[KEY_REMOTE_APP_UPDATE_JSON]
                if (currentJson != newJson) {
                    prefs[KEY_REMOTE_APP_UPDATE_JSON] = newJson
                }
            }
        }
    }

    suspend fun setInstalledUpdateSignature(signature: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_INSTALLED_UPDATE_SIGNATURE] = signature
        }
    }

    suspend fun setUpdateDriveFolderUrl(url: String) {
        lastLocalMutationMillis = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            prefs[KEY_UPDATE_DRIVE_FOLDER_URL] = url.trim()
        }
    }

    suspend fun saveAppDownloadUrl(url: String) {
        lastLocalMutationMillis = System.currentTimeMillis()
        val normalized = normalizeAppDownloadUrl(url)
        context.dataStore.edit { prefs ->
            prefs[KEY_APP_DOWNLOAD_URL] = normalized
        }
    }

    suspend fun savePendingReferralCode(code: String) {
        val clean = code.trim().filter { it.isDigit() }.take(6)
        if (clean.length == 6) {
            context.dataStore.edit { prefs ->
                prefs[KEY_PENDING_REFERRAL_CODE] = clean
            }
        }
    }

    suspend fun recordSharedReferralCode(code: String) {
        val clean = code.trim().filter { it.isDigit() }.take(6)
        if (clean.length == 6) {
            lastLocalMutationMillis = System.currentTimeMillis()
            context.dataStore.edit { prefs ->
                prefs[KEY_LAST_SHARED_REFERRAL_CODE] = clean
                prefs[KEY_PENDING_REFERRAL_CODE] = clean
            }
        }
    }

    private fun parseAppUpdateInfoJson(json: String): AppUpdateInfo? {
        if (json.isBlank()) return null
        return try {
            val obj = JSONObject(json)
            val hasUpdate = obj.optBoolean("hasUpdate", false)
            val fileId = obj.optString("fileId", "")
            val downloadUrl = obj.optString("downloadUrl", "")
            if (!hasUpdate || (fileId.isBlank() && downloadUrl.isBlank())) return null
            AppUpdateInfo(
                hasUpdate = true,
                fileId = fileId,
                fileName = obj.optString("fileName", "KingoKing_Update.apk").ifBlank { "KingoKing_Update.apk" },
                updatedAtMillis = obj.optLong("updatedAtMillis", 0L),
                fileSize = obj.optLong("fileSize", 0L),
                downloadUrl = downloadUrl.ifBlank {
                    "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
                }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun serializeAppUpdateInfoJson(info: AppUpdateInfo): String {
        return JSONObject().apply {
            put("hasUpdate", info.hasUpdate)
            put("fileId", info.fileId)
            put("fileName", info.fileName)
            put("updatedAtMillis", info.updatedAtMillis)
            put("fileSize", info.fileSize)
            put("downloadUrl", info.downloadUrl)
        }.toString()
    }

    suspend fun lockTask(taskId: String, durationMillis: Long = 6 * 60 * 60 * 1000L) {
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_VIDEO_TASKS]
            val currentList = if (json.isNullOrBlank()) getDefaultTasks().toMutableList() else parseVideoTasksJson(json).toMutableList()
            val index = currentList.indexOfFirst { it.id == taskId }
            val lockTime = System.currentTimeMillis() + durationMillis
            if (index != -1) {
                val t = currentList[index]
                currentList[index] = t.copy(
                    isCompleted = false,
                    watchedMillis = 0L,
                    lockedUntilMillis = lockTime
                )
                prefs[KEY_VIDEO_TASKS] = serializeVideoTasksJson(currentList)
            }
        }
    }

    suspend fun unlockTask(taskId: String) {
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_VIDEO_TASKS]
            val currentList = if (json.isNullOrBlank()) getDefaultTasks().toMutableList() else parseVideoTasksJson(json).toMutableList()
            val index = currentList.indexOfFirst { it.id == taskId }
            if (index != -1) {
                val t = currentList[index]
                currentList[index] = t.copy(
                    isCompleted = false,
                    watchedMillis = 0L,
                    lockedUntilMillis = 0L
                )
                prefs[KEY_VIDEO_TASKS] = serializeVideoTasksJson(currentList)
            }
        }
    }

    suspend fun unlockExpiredTasks() {
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_VIDEO_TASKS] ?: return@edit
            val now = System.currentTimeMillis()
            val rawArray = try { JSONArray(json) } catch (_: Exception) { return@edit }
            var anyExpired = false
            for (i in 0 until rawArray.length()) {
                val obj = rawArray.optJSONObject(i) ?: continue
                val lockedUntil = obj.optLong("lockedUntilMillis", 0L)
                if (lockedUntil in 1..now) {
                    anyExpired = true
                    break
                }
            }
            if (anyExpired) {
                val currentList = parseVideoTasksJson(json)
                prefs[KEY_VIDEO_TASKS] = serializeVideoTasksJson(currentList)
            }
        }
    }

    suspend fun unlockAllTasks() {
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_VIDEO_TASKS]
            val currentList = if (json.isNullOrBlank()) getDefaultTasks().toMutableList() else parseVideoTasksJson(json).toMutableList()
            val updated = currentList.map { it.copy(lockedUntilMillis = 0L) }
            prefs[KEY_VIDEO_TASKS] = serializeVideoTasksJson(updated)
        }
    }

    suspend fun recordTaskComment(taskId: String, taskTitle: String = "YouTube Video"): Pair<Boolean, String> {
        lastLocalMutationMillis = System.currentTimeMillis()
        var added = false
        var message = ""
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_COMMENT_COUNTS] ?: "{}"
            val obj = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val currentCount = obj.optInt(taskId, 0)
            if (currentCount >= 2) {
                message = "Maximum 2 comments reached for this task (+10 coins limit)."
                added = false
            } else {
                val newCount = currentCount + 1
                obj.put(taskId, newCount)
                prefs[KEY_COMMENT_COUNTS] = obj.toString()

                val currentBalance = prefs[KEY_WALLET_BALANCE] ?: 0
                prefs[KEY_WALLET_BALANCE] = currentBalance + 5

                val currentTxJson = prefs[KEY_TRANSACTIONS] ?: "[]"
                val list = parseTransactionsJson(currentTxJson).toMutableList()
                list.add(
                    0,
                    WalletTransaction(
                        id = UUID.randomUUID().toString(),
                        title = "💬 Video Comment #$newCount Bonus: $taskTitle",
                        coins = 5,
                        timestampMillis = System.currentTimeMillis()
                    )
                )
                val serializedTx = serializeTransactionsJson(list)
                prefs[KEY_TRANSACTIONS] = serializedTx
                syncActiveUserIntoUsersList(
                    prefs,
                    newBalanceOverride = currentBalance + 5,
                    newTxJsonOverride = serializedTx,
                    newCommentsJsonOverride = obj.toString()
                )
                added = true
                message = "🎉 +5 Coins added for Comment #$newCount on video!"
            }
        }
        return Pair(added, message)
    }

    suspend fun withdrawCoins(coins: Int, method: String, destination: String): Boolean {
        lastLocalMutationMillis = System.currentTimeMillis()
        var success = false
        context.dataStore.edit { prefs ->
            val currentBalance = prefs[KEY_WALLET_BALANCE] ?: 0
            if (currentBalance >= coins && coins > 0) {
                val newBalance = currentBalance - coins
                prefs[KEY_WALLET_BALANCE] = newBalance

                val inrAmount = coins.toDouble() / COINS_PER_INR.toDouble()
                val formattedInr = String.format(java.util.Locale.US, "%.2f", inrAmount)

                val currentJson = prefs[KEY_TRANSACTIONS] ?: "[]"
                val list = parseTransactionsJson(currentJson).toMutableList()
                val reqId = UUID.randomUUID().toString()
                list.add(
                    0,
                    WalletTransaction(
                        id = reqId,
                        title = "⏳ Withdrawal PENDING ($coins Coins = ₹$formattedInr) to $method ($destination)",
                        coins = -coins,
                        timestampMillis = System.currentTimeMillis()
                    )
                )
                val serializedTx = serializeTransactionsJson(list)
                prefs[KEY_TRANSACTIONS] = serializedTx

                // Add to payout requests queue for instant Admin Panel review!
                val currentEmail = prefs[KEY_CURRENT_USER_EMAIL] ?: "guest@watchearn.com"
                val payoutList = parsePayoutRequestsJson(prefs[KEY_PAYOUT_REQUESTS] ?: "[]").toMutableList()
                payoutList.add(
                    0,
                    PayoutRequest(
                        id = reqId,
                        userId = "usr_${Math.abs(currentEmail.hashCode()) % 100000}",
                        userEmail = currentEmail,
                        amountCoins = coins,
                        amountInr = inrAmount,
                        method = method,
                        destination = destination,
                        status = PayoutStatus.PENDING,
                        requestedAtMillis = System.currentTimeMillis()
                    )
                )
                prefs[KEY_PAYOUT_REQUESTS] = serializePayoutRequestsJson(payoutList)
                syncActiveUserIntoUsersList(
                    prefs,
                    newBalanceOverride = newBalance,
                    newTxJsonOverride = serializedTx
                )

                // Credit 10% Referral Withdrawal Bonus to Referrer (User A) when User B withdraws!
                val bonusCoins = (coins * 10) / 100
                if (bonusCoins > 0) {
                    val allUsers = parseUsersJson(prefs[KEY_USERS] ?: "[]").toMutableList()
                    val currentUserObj = allUsers.find { it.email.equals(currentEmail, ignoreCase = true) }
                    val refCode = currentUserObj?.referredByCode?.ifBlank {
                        extractReferredByCodeFromTransactions(serializedTx)
                    } ?: extractReferredByCodeFromTransactions(serializedTx)

                    if (refCode.isNotBlank()) {
                        val refIdx = allUsers.indexOfFirst {
                            !it.email.equals(currentEmail, ignoreCase = true) &&
                                (it.referralCode == refCode || generateSixDigitReferralCode(it.email) == refCode)
                        }
                        if (refIdx != -1) {
                            val referrer = allUsers[refIdx]
                            val bonusTxId = "ref_withdraw_bonus_$reqId"
                            val refTxList = parseTransactionsJson(referrer.transactionsJson).toMutableList()
                            if (refTxList.none { it.id == bonusTxId }) {
                                val nowMs = System.currentTimeMillis()
                                val withdrawerName = currentUserObj?.name?.ifBlank { currentUserObj.userId } ?: currentEmail.substringBefore("@")
                                refTxList.add(
                                    0,
                                    WalletTransaction(
                                        id = bonusTxId,
                                        title = "🤝 10% Referral Withdraw Bonus from $withdrawerName (${coins}c Withdraw)",
                                        coins = bonusCoins,
                                        timestampMillis = nowMs
                                    )
                                )
                                val newRefBal = referrer.coinsBalance + bonusCoins
                                allUsers[refIdx] = referrer.copy(
                                    coinsBalance = newRefBal,
                                    transactionsJson = serializeTransactionsJson(refTxList),
                                    lastUpdatedMillis = maxOf(nowMs + 60_000L, referrer.lastUpdatedMillis + 1000L)
                                )
                                prefs[KEY_USERS] = serializeUsersJson(allUsers)
                            }
                        }
                    }
                }

                success = true
            }
        }
        return success
    }

    suspend fun approvePayout(requestId: String, note: String = "Approved • Payment Processing"): Boolean {
        val now = System.currentTimeMillis()
        lastLocalMutationMillis = now
        var found = false
        context.dataStore.edit { prefs ->
            val payoutList = parsePayoutRequestsJson(prefs[KEY_PAYOUT_REQUESTS] ?: "[]").toMutableList()
            val index = payoutList.indexOfFirst { it.id == requestId }
            if (index != -1) {
                val req = payoutList[index]
                payoutList[index] = req.copy(
                    status = PayoutStatus.APPROVED,
                    processedAtMillis = now,
                    adminNote = note
                )
                prefs[KEY_PAYOUT_REQUESTS] = serializePayoutRequestsJson(payoutList)

                val formattedInr = String.format(java.util.Locale.US, "%.2f", req.amountInr)
                val approvedTitle = "✅ Withdrawal APPROVED (${req.amountCoins} Coins = ₹$formattedInr) • Processing via ${req.method}"

                // Update user's transaction in KEY_USERS so they see exact coins approved (-amountCoins, never 0)
                val users = parseUsersJson(prefs[KEY_USERS] ?: "[]").toMutableList()
                val uIdx = users.indexOfFirst { it.email.equals(req.userEmail, ignoreCase = true) }
                if (uIdx != -1) {
                    val u = users[uIdx]
                    val uTxList = parseTransactionsJson(u.transactionsJson).toMutableList()
                    val txIdx = uTxList.indexOfFirst { it.id == req.id }
                    if (txIdx != -1) {
                        uTxList[txIdx] = uTxList[txIdx].copy(
                            title = approvedTitle,
                            coins = -req.amountCoins,
                            timestampMillis = now
                        )
                    } else {
                        uTxList.add(
                            0,
                            WalletTransaction(
                                id = req.id,
                                title = approvedTitle,
                                coins = -req.amountCoins,
                                timestampMillis = now
                            )
                        )
                    }
                    users[uIdx] = u.copy(
                        transactionsJson = serializeTransactionsJson(uTxList),
                        lastUpdatedMillis = maxOf(now + 60_000L, u.lastUpdatedMillis + 1000L)
                    )
                    prefs[KEY_USERS] = serializeUsersJson(users)
                }

                val currentEmail = prefs[KEY_CURRENT_USER_EMAIL]
                if (currentEmail != null && currentEmail.equals(req.userEmail, ignoreCase = true)) {
                    val txList = parseTransactionsJson(prefs[KEY_TRANSACTIONS] ?: "[]").toMutableList()
                    val txIdx = txList.indexOfFirst { it.id == req.id }
                    if (txIdx != -1) {
                        txList[txIdx] = txList[txIdx].copy(
                            title = approvedTitle,
                            coins = -req.amountCoins,
                            timestampMillis = now
                        )
                    } else {
                        txList.add(
                            0,
                            WalletTransaction(
                                id = req.id,
                                title = approvedTitle,
                                coins = -req.amountCoins,
                                timestampMillis = now
                            )
                        )
                    }
                    prefs[KEY_TRANSACTIONS] = serializeTransactionsJson(txList)
                }
                found = true
            }
        }
        return found
    }

    suspend fun completePayout(requestId: String, note: String = "Payment Sent & Completed"): Boolean {
        val now = System.currentTimeMillis()
        lastLocalMutationMillis = now
        var found = false
        context.dataStore.edit { prefs ->
            val payoutList = parsePayoutRequestsJson(prefs[KEY_PAYOUT_REQUESTS] ?: "[]").toMutableList()
            val index = payoutList.indexOfFirst { it.id == requestId }
            if (index != -1) {
                val req = payoutList[index]
                payoutList[index] = req.copy(
                    status = PayoutStatus.COMPLETED,
                    processedAtMillis = now,
                    adminNote = note
                )
                prefs[KEY_PAYOUT_REQUESTS] = serializePayoutRequestsJson(payoutList)

                val formattedInr = String.format(java.util.Locale.US, "%.2f", req.amountInr)
                val doneTitle = "🎉 Payment DONE (${req.amountCoins} Coins = ₹$formattedInr) • Paid to ${req.method} (${req.destination})"

                val users = parseUsersJson(prefs[KEY_USERS] ?: "[]").toMutableList()
                val uIdx = users.indexOfFirst { it.email.equals(req.userEmail, ignoreCase = true) }
                if (uIdx != -1) {
                    val u = users[uIdx]
                    val uTxList = parseTransactionsJson(u.transactionsJson).toMutableList()
                    val txIdx = uTxList.indexOfFirst { it.id == req.id }
                    if (txIdx != -1) {
                        uTxList[txIdx] = uTxList[txIdx].copy(
                            title = doneTitle,
                            coins = -req.amountCoins,
                            timestampMillis = now
                        )
                    } else {
                        uTxList.add(
                            0,
                            WalletTransaction(
                                id = req.id,
                                title = doneTitle,
                                coins = -req.amountCoins,
                                timestampMillis = now
                            )
                        )
                    }
                    users[uIdx] = u.copy(
                        transactionsJson = serializeTransactionsJson(uTxList),
                        lastUpdatedMillis = maxOf(now + 60_000L, u.lastUpdatedMillis + 1000L)
                    )
                    prefs[KEY_USERS] = serializeUsersJson(users)
                }

                val currentEmail = prefs[KEY_CURRENT_USER_EMAIL]
                if (currentEmail != null && currentEmail.equals(req.userEmail, ignoreCase = true)) {
                    val txList = parseTransactionsJson(prefs[KEY_TRANSACTIONS] ?: "[]").toMutableList()
                    val txIdx = txList.indexOfFirst { it.id == req.id }
                    if (txIdx != -1) {
                        txList[txIdx] = txList[txIdx].copy(
                            title = doneTitle,
                            coins = -req.amountCoins,
                            timestampMillis = now
                        )
                    } else {
                        txList.add(
                            0,
                            WalletTransaction(
                                id = req.id,
                                title = doneTitle,
                                coins = -req.amountCoins,
                                timestampMillis = now
                            )
                        )
                    }
                    prefs[KEY_TRANSACTIONS] = serializeTransactionsJson(txList)
                }
                found = true
            }
        }
        return found
    }

    suspend fun rejectPayout(requestId: String, reason: String = "Declined"): Boolean {
        val now = System.currentTimeMillis()
        lastLocalMutationMillis = now
        var found = false
        context.dataStore.edit { prefs ->
            val payoutList = parsePayoutRequestsJson(prefs[KEY_PAYOUT_REQUESTS] ?: "[]").toMutableList()
            val index = payoutList.indexOfFirst { it.id == requestId }
            if (index != -1) {
                val req = payoutList[index]
                val cleanReason = reason.replace("by Admin", "", ignoreCase = true).trim().ifBlank { "Declined" }
                payoutList[index] = req.copy(
                    status = PayoutStatus.REJECTED,
                    processedAtMillis = now,
                    adminNote = cleanReason
                )
                prefs[KEY_PAYOUT_REQUESTS] = serializePayoutRequestsJson(payoutList)

                val refundTitle = "❌ Payout Declined (Refunded +${req.amountCoins} Coins)"

                // Refund coins to the user in KEY_USERS
                val users = parseUsersJson(prefs[KEY_USERS] ?: "[]").toMutableList()
                val uIdx = users.indexOfFirst { it.email.equals(req.userEmail, ignoreCase = true) }
                if (uIdx != -1) {
                    val u = users[uIdx]
                    val refundedBal = u.coinsBalance + req.amountCoins
                    pendingAdminCoinUpdates[u.email.trim().lowercase()] = Pair(refundedBal, now)
                    val uTxList = parseTransactionsJson(u.transactionsJson).toMutableList()
                    uTxList.add(
                        0,
                        WalletTransaction(
                            id = "refund_${req.id}",
                            title = refundTitle,
                            coins = req.amountCoins,
                            timestampMillis = now
                        )
                    )
                    users[uIdx] = u.copy(
                        coinsBalance = refundedBal,
                        transactionsJson = serializeTransactionsJson(uTxList),
                        lastUpdatedMillis = maxOf(now + 60_000L, u.lastUpdatedMillis + 1000L)
                    )
                    prefs[KEY_USERS] = serializeUsersJson(users)
                }

                val currentEmail = prefs[KEY_CURRENT_USER_EMAIL]
                if (currentEmail != null && currentEmail.equals(req.userEmail, ignoreCase = true)) {
                    val currentBalance = prefs[KEY_WALLET_BALANCE] ?: 0
                    prefs[KEY_WALLET_BALANCE] = currentBalance + req.amountCoins

                    val txList = parseTransactionsJson(prefs[KEY_TRANSACTIONS] ?: "[]").toMutableList()
                    txList.add(
                        0,
                        WalletTransaction(
                            id = "refund_${req.id}",
                            title = refundTitle,
                            coins = req.amountCoins,
                            timestampMillis = now
                        )
                    )
                    prefs[KEY_TRANSACTIONS] = serializeTransactionsJson(txList)
                }
                found = true
            }
        }
        return found
    }

    suspend fun signUpUser(
        email: String,
        password: String,
        name: String,
        referralCodeInput: String = ""
    ): Pair<Boolean, String> {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isEmpty() || !cleanEmail.contains("@")) {
            return Pair(false, "Please enter a valid email address.")
        }
        if (password.length < 4) {
            return Pair(false, "Password must be at least 4 characters.")
        }

        val cleanRefCode = referralCodeInput.trim()
        val myGeneratedRefCode = generateSixDigitReferralCode(cleanEmail)
        if (cleanRefCode.isNotEmpty()) {
            if (cleanRefCode.length != 6 || !cleanRefCode.all { it.isDigit() }) {
                return Pair(false, "Refer Key must be a 6-digit number (or leave it empty).")
            }
            if (cleanRefCode == myGeneratedRefCode) {
                return Pair(false, "You cannot use your own 6-digit Refer Key.")
            }
        }

        var result = Pair(true, "Account created! +50 Coins Welcome Bonus added!")
        lastLocalMutationMillis = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            val users = parseUsersJson(prefs[KEY_USERS] ?: "[]").toMutableList()
            if (users.any { it.email.equals(cleanEmail, ignoreCase = true) }) {
                result = Pair(false, "Account with this email already exists. Please sign in.")
                return@edit
            }
            val now = System.currentTimeMillis()
            val initialTransactions = mutableListOf<WalletTransaction>()

            // 1. Every new user gets +50 Coins First-Time Sign Up Bonus
            var startingCoins = 50
            initialTransactions.add(
                WalletTransaction(
                    id = "signup_welcome_bonus_${Math.abs(cleanEmail.hashCode())}",
                    title = "🎉 First-Time Sign Up Bonus",
                    coins = 50,
                    timestampMillis = now
                )
            )

            // 2. If 6-digit Refer Key was entered, User B also gets +50 Invite Coins!
            var appliedRefCode = ""
            if (cleanRefCode.length == 6 && cleanRefCode.all { it.isDigit() }) {
                appliedRefCode = cleanRefCode
                startingCoins += 50
                initialTransactions.add(
                    0,
                    WalletTransaction(
                        id = "signup_ref_$cleanRefCode",
                        title = "🎁 Referral Invite Bonus (Refer Key: $cleanRefCode)",
                        coins = 50,
                        timestampMillis = now + 1L
                    )
                )
                result = Pair(
                    true,
                    "Account created! +50 Sign Up Bonus & +50 Referral Invite Coins (+100 Coins Total) added!"
                )
            }

            val serializedInitialTx = serializeTransactionsJson(initialTransactions)
            val newUser = UserProfile(
                userId = "usr_${Math.abs(cleanEmail.hashCode()) % 100000}",
                email = cleanEmail,
                name = name.ifBlank { cleanEmail.substringBefore("@") },
                passwordHash = password,
                coinsBalance = startingCoins,
                completedTasksCount = 0,
                joinedAtMillis = now,
                transactionsJson = serializedInitialTx,
                likedTasksJson = "[]",
                commentCountsJson = "{}",
                taskLocksJson = "{}",
                lastUpdatedMillis = now,
                referralCode = myGeneratedRefCode,
                referredByCode = appliedRefCode
            )
            users.add(newUser)
            prefs[KEY_USERS] = serializeUsersJson(users)
            prefs[KEY_CURRENT_USER_EMAIL] = cleanEmail
            applyUserProfileToSessionPrefs(prefs, newUser)
        }
        return result
    }

    suspend fun loginUser(email: String, password: String): Pair<Boolean, String> {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isEmpty() || !cleanEmail.contains("@")) {
            return Pair(false, "Please enter a valid email address.")
        }
        if (password.isBlank()) {
            return Pair(false, "Please enter your password.")
        }
        var result = Pair(false, "Invalid email or password.")
        context.dataStore.edit { prefs ->
            val users = parseUsersJson(prefs[KEY_USERS] ?: "[]").toMutableList()
            val existing = users.find { it.email.equals(cleanEmail, ignoreCase = true) }
            if (existing != null) {
                if (existing.passwordHash == password || existing.passwordHash.isEmpty()) {
                    val updatedUser = if (existing.passwordHash.isEmpty()) {
                        existing.copy(passwordHash = password, lastUpdatedMillis = System.currentTimeMillis())
                    } else {
                        existing
                    }
                    val idx = users.indexOfFirst { it.email.equals(cleanEmail, ignoreCase = true) }
                    if (idx != -1) {
                        users[idx] = updatedUser
                        prefs[KEY_USERS] = serializeUsersJson(users)
                    }
                    prefs[KEY_CURRENT_USER_EMAIL] = cleanEmail
                    applyUserProfileToSessionPrefs(prefs, updatedUser)
                    result = Pair(true, "Welcome back, ${updatedUser.name.ifBlank { cleanEmail.substringBefore("@") }}!")
                } else {
                    result = Pair(false, "Incorrect password. Please try again.")
                }
            } else {
                result = Pair(false, "No account found with this email. Please Sign Up first.")
            }
        }
        return result
    }

    suspend fun logoutUser() {
        lastLocalMutationMillis = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            syncActiveUserIntoUsersList(prefs)
            prefs.remove(KEY_CURRENT_USER_EMAIL)
            prefs[KEY_WALLET_BALANCE] = 0
            prefs[KEY_TRANSACTIONS] = "[]"
            prefs[KEY_LIKED_TASKS] = "[]"
            prefs[KEY_COMMENT_COUNTS] = "{}"
            val currentList = parseVideoTasksJson(prefs[KEY_VIDEO_TASKS] ?: serializeVideoTasksJson(getDefaultTasks()))
            val unlocked = currentList.map { it.copy(isCompleted = false, watchedMillis = 0L, lockedUntilMillis = 0L) }
            prefs[KEY_VIDEO_TASKS] = serializeVideoTasksJson(unlocked)
        }
    }

    suspend fun adminDeleteVideoTask(taskId: String) {
        lastLocalMutationMillis = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_VIDEO_TASKS]
            val list = if (json == null) getDefaultTasks().toMutableList() else parseVideoTasksJson(json).toMutableList()
            list.removeAll { it.id == taskId }
            prefs[KEY_VIDEO_TASKS] = serializeVideoTasksJson(list)

            val deletedJson = prefs[KEY_DELETED_TASK_IDS] ?: "[]"
            val deletedArr = try { JSONArray(deletedJson) } catch (_: Exception) { JSONArray() }
            deletedArr.put(taskId)
            prefs[KEY_DELETED_TASK_IDS] = deletedArr.toString()

            if (prefs[KEY_SELECTED_TASK_ID] == taskId) {
                val nextTask = list.firstOrNull()
                if (nextTask != null) {
                    prefs[KEY_SELECTED_TASK_ID] = nextTask.id
                    prefs[KEY_ACTIVE_VIDEO_URL] = nextTask.videoUrl
                } else {
                    prefs.remove(KEY_SELECTED_TASK_ID)
                }
            }
        }
    }

    suspend fun doesUserEmailExist(email: String): Boolean {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isBlank()) return false
        var exists = false
        context.dataStore.edit { prefs ->
            val users = parseUsersJson(prefs[KEY_USERS] ?: "[]")
            exists = users.any { it.email.equals(cleanEmail, ignoreCase = true) }
        }
        return exists
    }

    suspend fun resetUserPassword(email: String, newPassword: String): Pair<Boolean, String> {
        val cleanEmail = email.trim().lowercase()
        if (cleanEmail.isEmpty() || !cleanEmail.contains("@")) {
            return Pair(false, "Please enter a valid email address.")
        }
        if (newPassword.length < 4) {
            return Pair(false, "New password must be at least 4 characters.")
        }
        val now = System.currentTimeMillis()
        lastLocalMutationMillis = now
        pendingPasswordResets[cleanEmail] = Pair(newPassword, now)
        var result = Pair(false, "No account found with this email address.")
        context.dataStore.edit { prefs ->
            val users = parseUsersJson(prefs[KEY_USERS] ?: "[]").toMutableList()
            val idx = users.indexOfFirst { it.email.equals(cleanEmail, ignoreCase = true) }
            if (idx != -1) {
                val updatedUser = users[idx].copy(
                    passwordHash = newPassword,
                    lastUpdatedMillis = now + 60_000L
                )
                users[idx] = updatedUser
                prefs[KEY_USERS] = serializeUsersJson(users)
                prefs[KEY_CURRENT_USER_EMAIL] = cleanEmail
                applyUserProfileToSessionPrefs(prefs, updatedUser)
                result = Pair(true, "Password reset successful! Welcome back, ${updatedUser.name.ifBlank { cleanEmail.substringBefore("@") }}!")
            }
        }
        return result
    }

    suspend fun adminUpdateUserCoins(userEmail: String, newCoins: Int) {
        val cleanEmail = userEmail.trim().lowercase()
        val safeCoins = newCoins.coerceAtLeast(0)
        val now = System.currentTimeMillis()
        val updatedTimestamp = now + 120_000L
        lastLocalMutationMillis = now
        if (cleanEmail.isNotBlank()) {
            pendingAdminCoinUpdates[cleanEmail] = Pair(safeCoins, now)
        }
        context.dataStore.edit { prefs ->
            val currentEmail = prefs[KEY_CURRENT_USER_EMAIL]
            if (currentEmail.equals(cleanEmail, ignoreCase = true) || cleanEmail.isBlank() || cleanEmail == "current") {
                prefs[KEY_WALLET_BALANCE] = safeCoins
            }
            val users = parseUsersJson(prefs[KEY_USERS] ?: "[]").toMutableList()
            val index = users.indexOfFirst { it.email.equals(cleanEmail, ignoreCase = true) }
            if (index != -1) {
                val existing = users[index]
                val diff = safeCoins - existing.coinsBalance
                val txList = parseTransactionsJson(existing.transactionsJson).toMutableList()
                if (diff != 0) {
                    val sign = if (diff > 0) "+$diff" else "$diff"
                    txList.add(
                        0,
                        WalletTransaction(
                            id = "admin_coin_${now}",
                            title = "🪙 Wallet Balance Updated ($sign Coins)",
                            coins = diff,
                            timestampMillis = now
                        )
                    )
                }
                val serializedTx = serializeTransactionsJson(txList)
                users[index] = existing.copy(
                    coinsBalance = safeCoins,
                    transactionsJson = serializedTx,
                    lastUpdatedMillis = updatedTimestamp
                )
                prefs[KEY_USERS] = serializeUsersJson(users)
                if (currentEmail.equals(cleanEmail, ignoreCase = true)) {
                    prefs[KEY_TRANSACTIONS] = serializedTx
                }
            }
        }
    }

    suspend fun resetAll() {
        context.dataStore.edit { prefs ->
            prefs[KEY_WALLET_BALANCE] = 0
            prefs[KEY_TASK_COMPLETED] = false
            prefs[KEY_WATCHED_MILLIS] = 0L
            prefs[KEY_TRANSACTIONS] = "[]"
            prefs[KEY_VIDEO_TASKS] = serializeVideoTasksJson(getDefaultTasks())
            prefs.remove(KEY_SELECTED_TASK_ID)
        }
    }

    suspend fun setSelectedTaskId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id != null) {
                prefs[KEY_SELECTED_TASK_ID] = id
            } else {
                prefs.remove(KEY_SELECTED_TASK_ID)
            }
        }
    }

    suspend fun addVideoTask(task: VideoTaskItem) {
        lastLocalMutationMillis = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_VIDEO_TASKS]
            val currentList = if (json.isNullOrBlank()) getDefaultTasks().toMutableList() else parseVideoTasksJson(json).toMutableList()
            currentList.removeAll { it.id == task.id }
            currentList.add(0, task)
            val sortedList = currentList.sortedWith(
                compareByDescending<VideoTaskItem> { it.isPinned }
                    .thenByDescending { if (it.isPinned) it.pinnedAt else 0L }
            )
            prefs[KEY_VIDEO_TASKS] = serializeVideoTasksJson(sortedList)

            // Also ensure it is removed from deletedTaskIds if re-added
            val deletedJson = prefs[KEY_DELETED_TASK_IDS] ?: "[]"
            val deletedArr = try { JSONArray(deletedJson) } catch (_: Exception) { JSONArray() }
            val newDeletedArr = JSONArray()
            for (i in 0 until deletedArr.length()) {
                val id = deletedArr.optString(i)
                if (id.isNotBlank() && id != task.id) newDeletedArr.put(id)
            }
            prefs[KEY_DELETED_TASK_IDS] = newDeletedArr.toString()
        }
    }

    suspend fun togglePinVideoTask(taskId: String): Boolean {
        lastLocalMutationMillis = System.currentTimeMillis()
        var newPinState = false
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_VIDEO_TASKS]
            val currentList = if (json.isNullOrBlank()) getDefaultTasks().toMutableList() else parseVideoTasksJson(json).toMutableList()
            val index = currentList.indexOfFirst { it.id == taskId }
            if (index != -1) {
                val item = currentList[index]
                newPinState = !item.isPinned
                val now = System.currentTimeMillis()
                currentList[index] = item.copy(
                    isPinned = newPinState,
                    pinnedAt = if (newPinState) now else 0L
                )
                val sortedList = currentList.sortedWith(
                    compareByDescending<VideoTaskItem> { it.isPinned }
                        .thenByDescending { if (it.isPinned) it.pinnedAt else 0L }
                )
                prefs[KEY_VIDEO_TASKS] = serializeVideoTasksJson(sortedList)
            }
        }
        return newPinState
    }

    suspend fun updateVideoTask(updatedTask: VideoTaskItem) {
        lastLocalMutationMillis = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_VIDEO_TASKS]
            val currentList = if (json.isNullOrBlank()) getDefaultTasks().toMutableList() else parseVideoTasksJson(json).toMutableList()
            val index = currentList.indexOfFirst { it.id == updatedTask.id }
            if (index != -1) {
                currentList[index] = updatedTask
            } else {
                currentList.add(0, updatedTask)
            }
            prefs[KEY_VIDEO_TASKS] = serializeVideoTasksJson(currentList)
        }
    }

    suspend fun markTaskCompleted(taskId: String, rewardCoins: Int) {
        lastLocalMutationMillis = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_VIDEO_TASKS]
            val currentList = if (json.isNullOrBlank()) getDefaultTasks().toMutableList() else parseVideoTasksJson(json).toMutableList()
            val index = currentList.indexOfFirst { it.id == taskId }
            if (index != -1) {
                val t = currentList[index]
                val lockDuration = 4 * 60 * 60 * 1000L
                val lockUntil = System.currentTimeMillis() + lockDuration
                val newCompletedCount = t.completedCount + 1
                currentList[index] = t.copy(
                    isCompleted = true,
                    watchedMillis = 0L,
                    rewardCoins = rewardCoins,
                    lockedUntilMillis = lockUntil,
                    completedCount = newCompletedCount
                )
                val serializedTasks = serializeVideoTasksJson(currentList)
                prefs[KEY_VIDEO_TASKS] = serializedTasks
                syncActiveUserIntoUsersList(
                    prefs,
                    updatedTasks = currentList,
                    incrementCompletedTasks = true,
                    completedTaskId = taskId
                )
            }
        }
    }

    fun getDefaultAdminPosts(): List<AdminPostItem> {
        return listOf(
            AdminPostItem(
                id = "default_welcome_banner",
                title = "🔥 Bonus Update: 1000 Coins = ₹10 INR!",
                message = "Watch tasks & earn: 3m=10c, 5m=17c, 10m=35c, 20m=72c, 30m=110c + Like (+5c) & Comment (+5c)!",
                targetTab = "HOME",
                postType = "BANNER",
                actionUrl = "",
                imageUrl = "",
                createdAt = 1700000000000L,
                isPinned = true,
                pinnedAt = 1700000000000L
            )
        )
    }

    suspend fun addAdminPost(post: AdminPostItem) {
        lastLocalMutationMillis = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_ADMIN_POSTS]
            val currentList = if (json == null) getDefaultAdminPosts().toMutableList() else parseAdminPostsJson(json).toMutableList()
            currentList.removeAll { it.id == post.id }
            currentList.add(0, post)
            val sortedList = currentList.sortedWith(
                compareByDescending<AdminPostItem> { it.isPinned }
                    .thenByDescending { if (it.isPinned) it.pinnedAt else 0L }
            )
            prefs[KEY_ADMIN_POSTS] = serializeAdminPostsJson(sortedList)
        }
    }

    suspend fun togglePinAdminPost(postId: String): Boolean {
        lastLocalMutationMillis = System.currentTimeMillis()
        var newPinState = false
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_ADMIN_POSTS]
            val currentList = if (json == null) getDefaultAdminPosts().toMutableList() else parseAdminPostsJson(json).toMutableList()
            val index = currentList.indexOfFirst { it.id == postId }
            if (index != -1) {
                val item = currentList[index]
                newPinState = !item.isPinned
                val now = System.currentTimeMillis()
                currentList[index] = item.copy(
                    isPinned = newPinState,
                    pinnedAt = if (newPinState) now else 0L
                )
                val sortedList = currentList.sortedWith(
                    compareByDescending<AdminPostItem> { it.isPinned }
                        .thenByDescending { if (it.isPinned) it.pinnedAt else 0L }
                )
                prefs[KEY_ADMIN_POSTS] = serializeAdminPostsJson(sortedList)
            }
        }
        return newPinState
    }

    suspend fun deleteAdminPost(postId: String) {
        lastLocalMutationMillis = System.currentTimeMillis()
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_ADMIN_POSTS]
            val currentList = if (json == null) getDefaultAdminPosts().toMutableList() else parseAdminPostsJson(json).toMutableList()
            currentList.removeAll { it.id == postId }
            prefs[KEY_ADMIN_POSTS] = serializeAdminPostsJson(currentList)

            val deletedJson = prefs[KEY_DELETED_POST_IDS] ?: "[]"
            val deletedArr = try { JSONArray(deletedJson) } catch (_: Exception) { JSONArray() }
            var exists = false
            for (i in 0 until deletedArr.length()) {
                if (deletedArr.optString(i) == postId) {
                    exists = true
                    break
                }
            }
            if (!exists) {
                deletedArr.put(postId)
                prefs[KEY_DELETED_POST_IDS] = deletedArr.toString()
            }
        }
    }

    suspend fun syncAdminPosts(posts: List<AdminPostItem>) {
        context.dataStore.edit { prefs ->
            // Extract any system config items carried in adminPosts
            posts.firstOrNull { it.id == SYSTEM_CONFIG_APP_LINK_ID || it.postType == "CONFIG_APP_LINK" }?.let { cfg ->
                val link = cfg.actionUrl.ifBlank { cfg.message }.trim()
                if (link.isNotBlank()) {
                    val normalized = normalizeAppDownloadUrl(link)
                    if (System.currentTimeMillis() - lastLocalMutationMillis > 30_000L || prefs[KEY_APP_DOWNLOAD_URL].isNullOrBlank()) {
                        prefs[KEY_APP_DOWNLOAD_URL] = normalized
                    }
                }
            }
            posts.firstOrNull { it.id == SYSTEM_CONFIG_REF_SHARE_ID || it.postType == "CONFIG_REF_SHARE" }?.let { refCfg ->
                val code = refCfg.actionUrl.ifBlank { refCfg.title }.trim().filter { it.isDigit() }.take(6)
                if (code.length == 6) {
                    prefs[KEY_LAST_SHARED_REFERRAL_CODE] = code
                    if (prefs[KEY_PENDING_REFERRAL_CODE].isNullOrBlank()) {
                        prefs[KEY_PENDING_REFERRAL_CODE] = code
                    }
                }
            }
            posts.firstOrNull { it.id == SYSTEM_CONFIG_FULL_SCREEN_OVERLAY_ID || it.postType == "CONFIG_FULL_SCREEN_OVERLAY" }?.let { fsCfg ->
                val str = fsCfg.actionUrl.ifBlank { fsCfg.message }.trim()
                val isFs = str.equals("true", ignoreCase = true)
                prefs[KEY_FULL_SCREEN_OPENING_OVERLAY] = isFs
                try {
                    val legacy = context.getSharedPreferences("watchearn_prefs", Context.MODE_PRIVATE)
                    legacy.edit().putBoolean("full_screen_opening_overlay", isFs).apply()
                } catch (_: Exception) {}
            }

            val deletedJson = prefs[KEY_DELETED_POST_IDS] ?: "[]"
            val deletedIds = mutableSetOf<String>()
            try {
                val arr = JSONArray(deletedJson)
                for (i in 0 until arr.length()) deletedIds.add(arr.optString(i))
            } catch (_: Exception) {}

            val currentJson = prefs[KEY_ADMIN_POSTS]
            val cleanRemote = posts.filter {
                !it.postType.startsWith("CONFIG_") &&
                    !it.id.startsWith("__system_config_") &&
                    (com.example.BuildConfig.APP_ROLE != "ADMIN" || !deletedIds.contains(it.id))
            }
            val localList = if (currentJson == null) {
                if (cleanRemote.isNotEmpty()) emptyList() else getDefaultAdminPosts()
            } else {
                parseAdminPostsJson(currentJson)
            }

            val remoteIds = cleanRemote.map { it.id }.toSet()
            val merged = cleanRemote.toMutableList()
            val now = System.currentTimeMillis()
            // Preserve local admin posts that were just created or when in Admin role and not deleted
            for (localPost in localList) {
                if (localPost.postType.startsWith("CONFIG_") || localPost.id.startsWith("__system_config_")) continue
                if (!remoteIds.contains(localPost.id) && !deletedIds.contains(localPost.id)) {
                    val isRecent = (now - localPost.createdAt) < 120_000L
                    if (com.example.BuildConfig.APP_ROLE == "ADMIN" || isRecent) {
                        merged.add(0, localPost)
                    }
                }
            }

            val sorted = merged.sortedWith(
                compareByDescending<AdminPostItem> { it.isPinned }
                    .thenByDescending { if (it.isPinned) it.pinnedAt else 0L }
                    .thenByDescending { it.createdAt }
            )
            prefs[KEY_ADMIN_POSTS] = serializeAdminPostsJson(sorted)
        }
    }

    suspend fun markItemsNotified(ids: Set<String>) {
        if (ids.isEmpty()) return
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_NOTIFIED_ITEM_IDS] ?: "[]"
            val arr = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
            val existing = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                existing.add(arr.getString(i))
            }
            for (id in ids) {
                if (existing.add(id)) {
                    arr.put(id)
                }
            }
            prefs[KEY_NOTIFIED_ITEM_IDS] = arr.toString()
        }
    }

    suspend fun dismissAdminPost(postId: String) {
        context.dataStore.edit { prefs ->
            val json = prefs[KEY_DISMISSED_POST_IDS] ?: "[]"
            val arr = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
            var found = false
            for (i in 0 until arr.length()) {
                if (arr.getString(i) == postId) {
                    found = true
                    break
                }
            }
            if (!found) {
                arr.put(postId)
                prefs[KEY_DISMISSED_POST_IDS] = arr.toString()
            }
        }
    }

    fun getDefaultTasks(): List<VideoTaskItem> {
        return listOf(
            VideoTaskItem(
                id = "default_rick",
                title = "Rick Astley - Never Gonna Give You Up (Official Music Video)",
                channelName = "Rick Astley",
                videoUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                thumbnailUrl = "https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                durationSeconds = 213, // 3 min 33 sec
                isLive = false,
                isCompleted = false,
                rewardCoins = 10,
                selectedDurationSeconds = 0
            ),
            VideoTaskItem(
                id = "default_android15",
                title = "Android 15 Developer Deep Dive: Private Spaces & System APIs",
                channelName = "Android Developers",
                videoUrl = "https://www.youtube.com/watch?v=jNQXAC9IVRw",
                thumbnailUrl = "https://img.youtube.com/vi/jNQXAC9IVRw/hqdefault.jpg",
                durationSeconds = 600, // 10 min 00 sec
                isLive = false,
                isCompleted = false,
                rewardCoins = 35,
                selectedDurationSeconds = 0
            ),
            VideoTaskItem(
                id = "default_kotlin_course",
                title = "Complete Kotlin Programming Course 2026 for Android",
                channelName = "freeCodeCamp.org",
                videoUrl = "https://www.youtube.com/watch?v=F9UC9DY-vIU",
                thumbnailUrl = "https://img.youtube.com/vi/F9UC9DY-vIU/hqdefault.jpg",
                durationSeconds = 1980, // 33 min
                isLive = false,
                isCompleted = false,
                rewardCoins = 110,
                selectedDurationSeconds = 0
            ),
            VideoTaskItem(
                id = "default_lofi_live",
                title = "lofi hip hop radio - beats to relax/study to [LIVE 24/7]",
                channelName = "Lofi Girl",
                videoUrl = "https://www.youtube.com/watch?v=jfKfPfyJRdk",
                thumbnailUrl = "https://img.youtube.com/vi/jfKfPfyJRdk/hqdefault.jpg",
                durationSeconds = 0, // 0 = LIVE STREAM!
                isLive = true,
                isCompleted = false,
                rewardCoins = 110,
                selectedDurationSeconds = 0
            )
        )
    }

    private fun parseVideoTasksJson(json: String): List<VideoTaskItem> {
        val list = mutableListOf<VideoTaskItem>()
        val now = System.currentTimeMillis()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val rawLockedUntil = obj.optLong("lockedUntilMillis", 0L)
                val lockExpired = rawLockedUntil in 1..now
                val effectiveLockedUntil = if (lockExpired) 0L else rawLockedUntil
                val rawCompleted = obj.optBoolean("isCompleted", false)
                val effectiveCompleted = if (lockExpired || effectiveLockedUntil == 0L) false else rawCompleted
                val effectiveWatched = if (lockExpired) 0L else obj.optLong("watchedMillis", 0L)
                val selSec = obj.optInt("selectedDurationSeconds", 0)
                val durSec = obj.optInt("durationSeconds", 600)
                val rawCoins = obj.optInt("rewardCoins", 10)
                val tierCoins = if (selSec > 0) {
                    WATCH_DURATION_TIERS.find { it.seconds == selSec }?.coins ?: calculateCoinsForDuration(selSec)
                } else {
                    if (rawCoins > 0) rawCoins else calculateCoinsForDuration(durSec)
                }

                list.add(
                    VideoTaskItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", "Video Task"),
                        channelName = obj.optString("channelName", "YouTube Creator"),
                        videoUrl = obj.optString("videoUrl", "https://www.youtube.com"),
                        thumbnailUrl = obj.optString("thumbnailUrl", ""),
                        durationSeconds = obj.optInt("durationSeconds", 600),
                        isLive = obj.optBoolean("isLive", false),
                        isCompleted = effectiveCompleted,
                        watchedMillis = effectiveWatched,
                        selectedDurationSeconds = selSec,
                        rewardCoins = tierCoins,
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        lockedUntilMillis = effectiveLockedUntil,
                        isPinned = obj.optBoolean("isPinned", false),
                        pinnedAt = obj.optLong("pinnedAt", 0L),
                        maxCompletions = obj.optInt("maxCompletions", 0),
                        completedCount = obj.optInt("completedCount", 0)
                    )
                )
            }
        } catch (_: Exception) {
            // fallback
        }
        return list
    }

    private fun serializeVideoTasksJson(tasks: List<VideoTaskItem>): String {
        val array = JSONArray()
        for (item in tasks) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("channelName", item.channelName)
                put("videoUrl", item.videoUrl)
                put("thumbnailUrl", item.thumbnailUrl)
                put("durationSeconds", item.durationSeconds)
                put("isLive", item.isLive)
                put("isCompleted", item.isCompleted)
                put("watchedMillis", item.watchedMillis)
                put("selectedDurationSeconds", item.selectedDurationSeconds)
                put("rewardCoins", item.rewardCoins)
                put("createdAt", item.createdAt)
                put("lockedUntilMillis", item.lockedUntilMillis)
                put("isPinned", item.isPinned)
                put("pinnedAt", item.pinnedAt)
                put("maxCompletions", item.maxCompletions)
                put("completedCount", item.completedCount)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseTransactionsJson(json: String): List<WalletTransaction> {
        val results = mutableListOf<WalletTransaction>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                results.add(
                    WalletTransaction(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", "Task Reward"),
                        coins = obj.optInt("coins", 0),
                        timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Exception) {
            // fallback to empty list
        }
        return results
    }

    private fun serializeTransactionsJson(transactions: List<WalletTransaction>): String {
        val array = JSONArray()
        for (item in transactions) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("coins", item.coins)
                put("timestampMillis", item.timestampMillis)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun extractReferredByCodeFromTransactions(txJson: String): String {
        if (txJson.isBlank() || txJson == "[]") return ""
        return try {
            val arr = JSONArray(txJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                if (id.startsWith("signup_ref_")) {
                    val code = id.removePrefix("signup_ref_").trim()
                    if (code.length == 6 && code.all { it.isDigit() }) return code
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseUsersJson(json: String): List<UserProfile> {
        val list = mutableListOf<UserProfile>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val emailVal = obj.optString("email", "")
                val txJsonVal = obj.optString("transactionsJson", "[]")
                val explicitRefBy = obj.optString("referredByCode", "")
                val effectiveRefBy = explicitRefBy.ifBlank { extractReferredByCodeFromTransactions(txJsonVal) }
                val explicitRefCode = obj.optString("referralCode", "")
                val effectiveRefCode = explicitRefCode.ifBlank { generateSixDigitReferralCode(emailVal) }
                list.add(
                    UserProfile(
                        userId = obj.optString("userId", UUID.randomUUID().toString()),
                        email = emailVal,
                        name = obj.optString("name", ""),
                        passwordHash = obj.optString("passwordHash", ""),
                        coinsBalance = obj.optInt("coinsBalance", 0),
                        completedTasksCount = obj.optInt("completedTasksCount", 0),
                        joinedAtMillis = obj.optLong("joinedAtMillis", System.currentTimeMillis()),
                        transactionsJson = txJsonVal,
                        likedTasksJson = obj.optString("likedTasksJson", "[]"),
                        commentCountsJson = obj.optString("commentCountsJson", "{}"),
                        taskLocksJson = obj.optString("taskLocksJson", "{}"),
                        completedTaskIdsJson = obj.optString("completedTaskIdsJson", "[]"),
                        lastUpdatedMillis = obj.optLong("lastUpdatedMillis", 0L),
                        referralCode = effectiveRefCode,
                        referredByCode = effectiveRefBy
                    )
                )
            }
        } catch (_: Exception) {
            // fallback
        }
        return list
    }

    private fun serializeUsersJson(users: List<UserProfile>): String {
        val array = JSONArray()
        for (u in users) {
            val obj = JSONObject().apply {
                put("userId", u.userId)
                put("email", u.email)
                put("name", u.name)
                put("passwordHash", u.passwordHash)
                put("coinsBalance", u.coinsBalance)
                put("completedTasksCount", u.completedTasksCount)
                put("joinedAtMillis", u.joinedAtMillis)
                put("transactionsJson", u.transactionsJson)
                put("likedTasksJson", u.likedTasksJson)
                put("commentCountsJson", u.commentCountsJson)
                put("taskLocksJson", u.taskLocksJson)
                put("completedTaskIdsJson", u.completedTaskIdsJson)
                put("lastUpdatedMillis", u.lastUpdatedMillis)
                put("referralCode", u.referralCode.ifBlank { generateSixDigitReferralCode(u.email) })
                put("referredByCode", u.referredByCode.ifBlank { extractReferredByCodeFromTransactions(u.transactionsJson) })
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun extractTaskLocksJson(tasks: List<VideoTaskItem>): String {
        val obj = JSONObject()
        val now = System.currentTimeMillis()
        for (t in tasks) {
            if (t.lockedUntilMillis > now) {
                obj.put(t.id, t.lockedUntilMillis)
            }
        }
        return obj.toString()
    }

    private fun applyUserProfileToSessionPrefs(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        user: UserProfile
    ) {
        prefs[KEY_WALLET_BALANCE] = user.coinsBalance
        prefs[KEY_TRANSACTIONS] = user.transactionsJson.ifBlank { "[]" }
        prefs[KEY_LIKED_TASKS] = user.likedTasksJson.ifBlank { "[]" }
        prefs[KEY_COMMENT_COUNTS] = user.commentCountsJson.ifBlank { "{}" }

        val locksObj = try { JSONObject(user.taskLocksJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        val now = System.currentTimeMillis()
        val currentTasks = parseVideoTasksJson(prefs[KEY_VIDEO_TASKS] ?: serializeVideoTasksJson(getDefaultTasks()))
        val updatedTasks = currentTasks.map { task ->
            val lockUntil = locksObj.optLong(task.id, 0L)
            val isLockedNow = lockUntil > now
            task.copy(
                isCompleted = isLockedNow,
                watchedMillis = 0L,
                lockedUntilMillis = if (isLockedNow) lockUntil else 0L
            )
        }
        prefs[KEY_VIDEO_TASKS] = serializeVideoTasksJson(updatedTasks)
    }

    private fun mergeJsonStringSets(jsonA: String, jsonB: String, extraId: String? = null): String {
        val set = linkedSetOf<String>()
        for (src in listOf(jsonA, jsonB)) {
            try {
                val arr = JSONArray(src)
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i)
                    if (v.isNotBlank()) set.add(v)
                }
            } catch (_: Exception) {}
        }
        if (!extraId.isNullOrBlank()) set.add(extraId)
        val out = JSONArray()
        for (item in set) out.put(item)
        return out.toString()
    }

    private fun syncActiveUserIntoUsersList(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        newBalanceOverride: Int? = null,
        newTxJsonOverride: String? = null,
        newLikedJsonOverride: String? = null,
        newCommentsJsonOverride: String? = null,
        updatedTasks: List<VideoTaskItem>? = null,
        incrementCompletedTasks: Boolean = false,
        completedTaskId: String? = null
    ) {
        val currentEmail = prefs[KEY_CURRENT_USER_EMAIL] ?: return
        if (currentEmail.isBlank()) return
        val users = parseUsersJson(prefs[KEY_USERS] ?: "[]").toMutableList()
        val idx = users.indexOfFirst { it.email.equals(currentEmail, ignoreCase = true) }
        val balance = newBalanceOverride ?: (prefs[KEY_WALLET_BALANCE] ?: 0)
        val txJson = newTxJsonOverride ?: (prefs[KEY_TRANSACTIONS] ?: "[]")
        val likedJson = newLikedJsonOverride ?: (prefs[KEY_LIKED_TASKS] ?: "[]")
        val commentsJson = newCommentsJsonOverride ?: (prefs[KEY_COMMENT_COUNTS] ?: "{}")
        val tasksList = updatedTasks ?: parseVideoTasksJson(prefs[KEY_VIDEO_TASKS] ?: serializeVideoTasksJson(getDefaultTasks()))
        val locksJson = extractTaskLocksJson(tasksList)
        val now = System.currentTimeMillis()
        val currentlyCompletedIdsJson = JSONArray().apply {
            tasksList.filter { it.isCompleted }.forEach { put(it.id) }
        }.toString()

        if (idx != -1) {
            val existing = users[idx]
            val mergedCompletedIdsJson = mergeJsonStringSets(existing.completedTaskIdsJson, currentlyCompletedIdsJson, completedTaskId)
            val newCompletedCount = if (incrementCompletedTasks) {
                existing.completedTasksCount + 1
            } else {
                maxOf(existing.completedTasksCount, tasksList.count { it.isCompleted })
            }
            // Ensure local user mutation always has a strictly newer timestamp than any previous state
            val monotonicUpdatedMillis = maxOf(now, existing.lastUpdatedMillis + 1000L)
            users[idx] = existing.copy(
                coinsBalance = balance,
                completedTasksCount = newCompletedCount,
                transactionsJson = txJson,
                likedTasksJson = likedJson,
                commentCountsJson = commentsJson,
                taskLocksJson = locksJson,
                completedTaskIdsJson = mergedCompletedIdsJson,
                lastUpdatedMillis = monotonicUpdatedMillis
            )
        } else {
            val mergedCompletedIdsJson = mergeJsonStringSets("[]", currentlyCompletedIdsJson, completedTaskId)
            users.add(
                UserProfile(
                    userId = "usr_${Math.abs(currentEmail.hashCode()) % 100000}",
                    email = currentEmail,
                    name = currentEmail.substringBefore("@"),
                    coinsBalance = balance,
                    completedTasksCount = if (incrementCompletedTasks) 1 else tasksList.count { it.isCompleted },
                    joinedAtMillis = now,
                    transactionsJson = txJson,
                    likedTasksJson = likedJson,
                    commentCountsJson = commentsJson,
                    taskLocksJson = locksJson,
                    completedTaskIdsJson = mergedCompletedIdsJson,
                    lastUpdatedMillis = now
                )
            )
        }
        prefs[KEY_USERS] = serializeUsersJson(users)
    }

    suspend fun syncRemoteTasksFromServer(remoteTasks: List<VideoTaskItem>) {
        context.dataStore.edit { prefs ->
            val currentJson = prefs[KEY_VIDEO_TASKS]
            val localList = if (currentJson.isNullOrBlank()) {
                if (remoteTasks.isNotEmpty()) emptyList() else getDefaultTasks()
            } else {
                parseVideoTasksJson(currentJson)
            }
            val localMap = localList.associateBy { it.id }

            val deletedJson = prefs[KEY_DELETED_TASK_IDS] ?: "[]"
            val deletedIds = mutableSetOf<String>()
            try {
                val arr = JSONArray(deletedJson)
                for (i in 0 until arr.length()) deletedIds.add(arr.optString(i))
            } catch (_: Exception) {}

            // Merge remote authoritative task metadata with local user's personal lock/completion state
            val merged = remoteTasks
                .filter { com.example.BuildConfig.APP_ROLE != "ADMIN" || !deletedIds.contains(it.id) }
                .map { remote ->
                    val local = localMap[remote.id]
                    if (local != null) {
                        remote.copy(
                            isCompleted = local.isCompleted,
                            watchedMillis = local.watchedMillis,
                            lockedUntilMillis = local.lockedUntilMillis,
                            selectedDurationSeconds = remote.selectedDurationSeconds,
                            maxCompletions = if (remote.maxCompletions > 0) remote.maxCompletions else local.maxCompletions,
                            completedCount = maxOf(remote.completedCount, local.completedCount)
                        )
                    } else {
                        remote
                    }
                }.toMutableList()

            // In Admin app, preserve local admin-added tasks if they haven't been pushed or deleted yet
            if (com.example.BuildConfig.APP_ROLE == "ADMIN") {
                val remoteIds = merged.map { it.id }.toSet()
                for (localTask in localList) {
                    if (!remoteIds.contains(localTask.id) && !deletedIds.contains(localTask.id)) {
                        merged.add(0, localTask)
                    }
                }
            }

            val finalTasks = merged.filter { !it.isCompletionLimitReached }.sortedWith(
                compareByDescending<VideoTaskItem> { it.isPinned }
                    .thenByDescending { if (it.isPinned) it.pinnedAt else 0L }
            )
            prefs[KEY_VIDEO_TASKS] = serializeVideoTasksJson(finalTasks)
        }
    }

    suspend fun addSupportMessage(
        userId: String,
        userEmail: String,
        userName: String,
        senderRole: String,
        messageText: String
    ): SupportMessage {
        val now = System.currentTimeMillis()
        lastLocalMutationMillis = now
        val msg = SupportMessage(
            id = "chat_${now}_${(100..999).random()}",
            userId = userId,
            userEmail = userEmail.trim().lowercase(),
            userName = userName.trim().ifBlank { userEmail.substringBefore("@") },
            senderRole = senderRole.uppercase(),
            message = messageText.trim(),
            timestampMillis = now
        )
        context.dataStore.edit { prefs ->
            val currentList = parseSupportMessagesJson(prefs[KEY_SUPPORT_MESSAGES] ?: "[]").toMutableList()
            currentList.add(msg)
            val sorted = currentList.sortedBy { it.timestampMillis }.takeLast(300)
            prefs[KEY_SUPPORT_MESSAGES] = serializeSupportMessagesJson(sorted)
        }
        return msg
    }

    suspend fun syncRemoteSupportMessagesFromServer(remoteMessages: List<SupportMessage>) {
        if (remoteMessages.isEmpty()) return
        context.dataStore.edit { prefs ->
            val localList = parseSupportMessagesJson(prefs[KEY_SUPPORT_MESSAGES] ?: "[]")
            val combinedMap = linkedMapOf<String, SupportMessage>()
            for (m in (localList + remoteMessages)) {
                if (m.id.isNotBlank() && m.message.isNotBlank()) {
                    combinedMap[m.id] = m
                }
            }
            val merged = combinedMap.values.sortedBy { it.timestampMillis }.takeLast(300)
            prefs[KEY_SUPPORT_MESSAGES] = serializeSupportMessagesJson(merged)
        }
    }

    private fun parseSupportMessagesJson(json: String): List<SupportMessage> {
        val list = mutableListOf<SupportMessage>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    SupportMessage(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        userId = obj.optString("userId", ""),
                        userEmail = obj.optString("userEmail", ""),
                        userName = obj.optString("userName", ""),
                        senderRole = obj.optString("senderRole", "USER"),
                        message = obj.optString("message", ""),
                        timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Exception) {}
        return list.sortedBy { it.timestampMillis }
    }

    private fun serializeSupportMessagesJson(messages: List<SupportMessage>): String {
        val array = JSONArray()
        for (m in messages) {
            val obj = JSONObject().apply {
                put("id", m.id)
                put("userId", m.userId)
                put("userEmail", m.userEmail)
                put("userName", m.userName)
                put("senderRole", m.senderRole)
                put("message", m.message)
                put("timestampMillis", m.timestampMillis)
            }
            array.put(obj)
        }
        return array.toString()
    }

    suspend fun syncRemoteUsersFromServer(remoteUsers: List<UserProfile>, isAdmin: Boolean) {
        context.dataStore.edit { prefs ->
            val localUsers = parseUsersJson(prefs[KEY_USERS] ?: "[]").toMutableList()
            val currentEmail = prefs[KEY_CURRENT_USER_EMAIL]
            val now = System.currentTimeMillis()

            for (remote in remoteUsers) {
                val emailKey = remote.email.trim().lowercase()
                if (emailKey.isBlank()) continue
                val idx = localUsers.indexOfFirst { it.email.equals(emailKey, ignoreCase = true) }
                if (idx == -1) {
                    localUsers.add(remote)
                    if (!isAdmin && currentEmail != null && currentEmail.equals(emailKey, ignoreCase = true)) {
                        applyUserProfileToSessionPrefs(prefs, remote)
                    }
                } else {
                    val local = localUsers[idx]
                    val isCurrentLoggedUser = !isAdmin && currentEmail != null && currentEmail.equals(emailKey, ignoreCase = true)

                    // 1. Check if Admin recently updated this user's coins locally (prevents 2-3s revert on Admin App!)
                    val pendingCoinUpdate = pendingAdminCoinUpdates[emailKey]
                    if (pendingCoinUpdate != null) {
                        val (expectedCoins, updatedAt) = pendingCoinUpdate
                        if (remote.coinsBalance == expectedCoins) {
                            // Server now reflects Admin's coin update!
                            pendingAdminCoinUpdates.remove(emailKey)
                        } else if (now - updatedAt < 60_000L) {
                            // Keep Admin's updated coin balance & transactions; do not let stale GET overwrite it
                            continue
                        } else {
                            pendingAdminCoinUpdates.remove(emailKey)
                        }
                    }

                    // 2. Check if User recently reset their password locally
                    val pendingPw = pendingPasswordResets[emailKey]
                    val effectivePasswordHash = if (pendingPw != null && (now - pendingPw.second < 60_000L)) {
                        if (remote.passwordHash == pendingPw.first) {
                            pendingPasswordResets.remove(emailKey)
                        }
                        pendingPw.first
                    } else {
                        remote.passwordHash.ifBlank { local.passwordHash }
                    }

                    // 3. Detect if remote has any new Admin coin update, payout refund, or 10% referral withdrawal bonus transaction not in local
                    val remoteTxList = parseTransactionsJson(remote.transactionsJson)
                    val localTxList = parseTransactionsJson(local.transactionsJson)
                    val localTxIds = localTxList.map { it.id }.toSet()
                    val remoteTxMap = remoteTxList.associateBy { it.id }
                    val hasNewAdminTransaction = remoteTxList.any { tx ->
                        (tx.id.startsWith("admin_coin_") ||
                            tx.id.startsWith("refund_") ||
                            tx.id.startsWith("ref_withdraw_bonus_") ||
                            tx.title.contains("Admin Balance Update") ||
                            tx.title.contains("Referral Withdraw Bonus")) &&
                                !localTxIds.contains(tx.id)
                    }

                    // 4. Merge transactions, preferring remote's updated status title if ID matches (e.g., PENDING -> APPROVED -> DONE)
                    val combinedTxMap = linkedMapOf<String, WalletTransaction>()
                    for (tx in (remoteTxList + localTxList)) {
                        val existingTx = combinedTxMap[tx.id]
                        if (existingTx == null) {
                            val remoteVer = remoteTxMap[tx.id]
                            combinedTxMap[tx.id] = if (remoteVer != null && remoteVer.timestampMillis >= tx.timestampMillis) remoteVer else tx
                        }
                    }
                    val mergedTxList = combinedTxMap.values.sortedByDescending { it.timestampMillis }
                    val mergedTxJson = if (mergedTxList.isNotEmpty()) serializeTransactionsJson(mergedTxList) else "[]"
                    val mergedCompletedIdsJson = mergeJsonStringSets(remote.completedTaskIdsJson, local.completedTaskIdsJson)
                    val mergedLikedJson = mergeJsonStringSets(remote.likedTasksJson, local.likedTasksJson)

                    // Never overwrite a logged-in user's local coin balance right after they withdrew or earned coins unless Admin just sent a new coin update!
                    val userMutatedRecently = isCurrentLoggedUser && (now - lastLocalMutationMillis) < 15_000L
                    val shouldAcceptRemoteBalance = isAdmin ||
                            hasNewAdminTransaction ||
                            (!userMutatedRecently && (
                                    remote.lastUpdatedMillis > local.lastUpdatedMillis ||
                                    local.lastUpdatedMillis == 0L
                            ))

                    val effectiveCoinsBalance = if (shouldAcceptRemoteBalance) {
                        remote.coinsBalance
                    } else {
                        if (isCurrentLoggedUser) (prefs[KEY_WALLET_BALANCE] ?: local.coinsBalance) else local.coinsBalance
                    }

                    val mergedUser = remote.copy(
                        passwordHash = effectivePasswordHash,
                        coinsBalance = effectiveCoinsBalance,
                        completedTasksCount = maxOf(remote.completedTasksCount, local.completedTasksCount),
                        transactionsJson = mergedTxJson,
                        likedTasksJson = mergedLikedJson,
                        commentCountsJson = if (remote.commentCountsJson != "{}" || local.commentCountsJson == "{}") remote.commentCountsJson else local.commentCountsJson,
                        taskLocksJson = if (remote.taskLocksJson != "{}" || local.taskLocksJson == "{}") remote.taskLocksJson else local.taskLocksJson,
                        completedTaskIdsJson = mergedCompletedIdsJson,
                        lastUpdatedMillis = maxOf(remote.lastUpdatedMillis, local.lastUpdatedMillis),
                        referralCode = remote.referralCode.ifBlank { local.referralCode.ifBlank { generateSixDigitReferralCode(emailKey) } },
                        referredByCode = remote.referredByCode.ifBlank { local.referredByCode.ifBlank { extractReferredByCodeFromTransactions(mergedTxJson) } }
                    )
                    localUsers[idx] = mergedUser
                    if (isCurrentLoggedUser) {
                        val currentSessionBal = prefs[KEY_WALLET_BALANCE] ?: 0
                        if (shouldAcceptRemoteBalance && (hasNewAdminTransaction || mergedUser.coinsBalance != currentSessionBal)) {
                            applyUserProfileToSessionPrefs(prefs, mergedUser)
                        } else {
                            prefs[KEY_TRANSACTIONS] = mergedTxJson
                            prefs[KEY_LIKED_TASKS] = mergedLikedJson
                        }
                    }
                }
            }
            prefs[KEY_USERS] = serializeUsersJson(localUsers)
        }
    }

    suspend fun syncRemotePayoutsFromServer(remotePayouts: List<PayoutRequest>) {
        context.dataStore.edit { prefs ->
            val localPayouts = parsePayoutRequestsJson(prefs[KEY_PAYOUT_REQUESTS] ?: "[]").toMutableList()
            val currentEmail = prefs[KEY_CURRENT_USER_EMAIL]
            var stateChanged = false
            val now = System.currentTimeMillis()

            for (remote in remotePayouts) {
                val idx = localPayouts.indexOfFirst { it.id == remote.id }
                if (idx == -1) {
                    localPayouts.add(remote)
                } else {
                    val local = localPayouts[idx]
                    if (local.status != remote.status || local.adminNote != remote.adminNote) {
                        val previousStatus = local.status
                        localPayouts[idx] = remote
                        if (currentEmail != null && currentEmail.equals(remote.userEmail, ignoreCase = true)) {
                            val txList = parseTransactionsJson(prefs[KEY_TRANSACTIONS] ?: "[]").toMutableList()
                            val safeCoins = if (remote.amountCoins > 0) remote.amountCoins else (remote.amountInr * COINS_PER_INR).toInt().coerceAtLeast(1000)
                            val safeInr = if (remote.amountInr > 0.0) remote.amountInr else (safeCoins.toDouble() / COINS_PER_INR)
                            val formattedInr = String.format(java.util.Locale.US, "%.2f", safeInr)
                            when (remote.status) {
                                PayoutStatus.APPROVED -> {
                                    val approvedTitle = "✅ Withdrawal APPROVED ($safeCoins Coins = ₹$formattedInr) • Processing via ${remote.method}"
                                    val txIdx = txList.indexOfFirst { it.id == remote.id }
                                    if (txIdx != -1) {
                                        txList[txIdx] = txList[txIdx].copy(
                                            title = approvedTitle,
                                            coins = -safeCoins,
                                            timestampMillis = now
                                        )
                                    } else {
                                        txList.add(
                                            0,
                                            WalletTransaction(
                                                id = remote.id,
                                                title = approvedTitle,
                                                coins = -safeCoins,
                                                timestampMillis = now
                                            )
                                        )
                                    }
                                    prefs[KEY_TRANSACTIONS] = serializeTransactionsJson(txList)
                                    stateChanged = true
                                }
                                PayoutStatus.COMPLETED -> {
                                    val doneTitle = "🎉 Payment DONE ($safeCoins Coins = ₹$formattedInr) • Paid to ${remote.method} (${remote.destination})"
                                    val txIdx = txList.indexOfFirst { it.id == remote.id }
                                    if (txIdx != -1) {
                                        txList[txIdx] = txList[txIdx].copy(
                                            title = doneTitle,
                                            coins = -safeCoins,
                                            timestampMillis = now
                                        )
                                    } else {
                                        txList.add(
                                            0,
                                            WalletTransaction(
                                                id = remote.id,
                                                title = doneTitle,
                                                coins = -safeCoins,
                                                timestampMillis = now
                                            )
                                        )
                                    }
                                    prefs[KEY_TRANSACTIONS] = serializeTransactionsJson(txList)
                                    stateChanged = true
                                }
                                PayoutStatus.REJECTED -> {
                                    if (previousStatus != PayoutStatus.REJECTED) {
                                        val refundId = "refund_${remote.id}"
                                        if (txList.none { it.id == refundId }) {
                                            val curBal = prefs[KEY_WALLET_BALANCE] ?: 0
                                            prefs[KEY_WALLET_BALANCE] = curBal + safeCoins
                                            txList.add(
                                                0,
                                                WalletTransaction(
                                                    id = refundId,
                                                    title = "❌ Payout REJECTED (Refunded +$safeCoins Coins): ${remote.adminNote ?: "Declined"}",
                                                    coins = safeCoins,
                                                    timestampMillis = now
                                                )
                                            )
                                            prefs[KEY_TRANSACTIONS] = serializeTransactionsJson(txList)
                                            stateChanged = true
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
            localPayouts.sortByDescending { it.requestedAtMillis }
            prefs[KEY_PAYOUT_REQUESTS] = serializePayoutRequestsJson(localPayouts)

            // Ensure 10% Referral Withdrawal Bonus is credited to Referrer (User A) across devices
            val allUsers = parseUsersJson(prefs[KEY_USERS] ?: "[]").toMutableList()
            var usersModified = false
            for (payout in localPayouts) {
                if (payout.status == PayoutStatus.REJECTED || payout.amountCoins <= 0) continue
                val bonusCoins = (payout.amountCoins * 10) / 100
                if (bonusCoins <= 0) continue
                val withdrawer = allUsers.find { it.email.equals(payout.userEmail, ignoreCase = true) } ?: continue
                val refCode = withdrawer.referredByCode.ifBlank {
                    extractReferredByCodeFromTransactions(withdrawer.transactionsJson)
                }
                if (refCode.isBlank()) continue
                val refIdx = allUsers.indexOfFirst {
                    !it.email.equals(withdrawer.email, ignoreCase = true) &&
                        (it.referralCode == refCode || generateSixDigitReferralCode(it.email) == refCode)
                }
                if (refIdx != -1) {
                    val referrer = allUsers[refIdx]
                    val bonusTxId = "ref_withdraw_bonus_${payout.id}"
                    val refTxList = parseTransactionsJson(referrer.transactionsJson).toMutableList()
                    if (refTxList.none { it.id == bonusTxId }) {
                        val withdrawerName = withdrawer.name.ifBlank { withdrawer.userId }
                        val bonusTx = WalletTransaction(
                            id = bonusTxId,
                            title = "🤝 10% Referral Withdraw Bonus from $withdrawerName (${payout.amountCoins}c Withdraw)",
                            coins = bonusCoins,
                            timestampMillis = maxOf(payout.requestedAtMillis, now)
                        )
                        refTxList.add(0, bonusTx)
                        val newRefBal = referrer.coinsBalance + bonusCoins
                        allUsers[refIdx] = referrer.copy(
                            coinsBalance = newRefBal,
                            transactionsJson = serializeTransactionsJson(refTxList),
                            lastUpdatedMillis = maxOf(now + 60_000L, referrer.lastUpdatedMillis + 1000L)
                        )
                        usersModified = true

                        if (currentEmail != null && currentEmail.equals(referrer.email, ignoreCase = true)) {
                            val sessionTxList = parseTransactionsJson(prefs[KEY_TRANSACTIONS] ?: "[]").toMutableList()
                            if (sessionTxList.none { it.id == bonusTxId }) {
                                sessionTxList.add(0, bonusTx)
                                prefs[KEY_TRANSACTIONS] = serializeTransactionsJson(sessionTxList)
                                val curSessionBal = prefs[KEY_WALLET_BALANCE] ?: 0
                                prefs[KEY_WALLET_BALANCE] = curSessionBal + bonusCoins
                                lastLocalMutationMillis = now
                            }
                        }
                    }
                }
            }
            if (usersModified) {
                prefs[KEY_USERS] = serializeUsersJson(allUsers)
            }

            if (stateChanged) {
                syncActiveUserIntoUsersList(prefs)
            }
        }
    }

    private fun parsePayoutRequestsJson(json: String): List<PayoutRequest> {
        val list = mutableListOf<PayoutRequest>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val statusStr = obj.optString("status", PayoutStatus.PENDING.name)
                val status = try {
                    PayoutStatus.valueOf(statusStr)
                } catch (_: Exception) {
                    PayoutStatus.PENDING
                }
                val rawCoins = obj.optInt("amountCoins", obj.optInt("coins", 0))
                val rawInr = obj.optDouble("amountInr", obj.optDouble("inr", 0.0))
                val safeCoins = if (rawCoins > 0) rawCoins else (rawInr * COINS_PER_INR).toInt()
                val safeInr = if (rawInr > 0.0) rawInr else (safeCoins.toDouble() / COINS_PER_INR)
                list.add(
                    PayoutRequest(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        userId = obj.optString("userId", ""),
                        userEmail = obj.optString("userEmail", ""),
                        amountCoins = safeCoins,
                        amountInr = safeInr,
                        method = obj.optString("method", "UPI"),
                        destination = obj.optString("destination", ""),
                        status = status,
                        requestedAtMillis = obj.optLong("requestedAtMillis", System.currentTimeMillis()),
                        processedAtMillis = if (obj.has("processedAtMillis")) obj.optLong("processedAtMillis") else null,
                        adminNote = if (obj.has("adminNote")) obj.optString("adminNote") else null
                    )
                )
            }
        } catch (_: Exception) {
            // fallback
        }
        return list
    }

    private fun serializePayoutRequestsJson(requests: List<PayoutRequest>): String {
        val array = JSONArray()
        for (r in requests) {
            val obj = JSONObject().apply {
                put("id", r.id)
                put("userId", r.userId)
                put("userEmail", r.userEmail)
                put("amountCoins", r.amountCoins)
                put("amountInr", r.amountInr)
                put("method", r.method)
                put("destination", r.destination)
                put("status", r.status.name)
                put("requestedAtMillis", r.requestedAtMillis)
                r.processedAtMillis?.let { put("processedAtMillis", it) }
                r.adminNote?.let { put("adminNote", it) }
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseAdminPostsJson(json: String): List<AdminPostItem> {
        val list = mutableListOf<AdminPostItem>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val rawId = obj.optString("id", UUID.randomUUID().toString())
                val rawTitle = obj.optString("title", "Announcement")
                    .replace("200 Coins = ₹10 INR", "1000 Coins = ₹10 INR")
                    .replace("5000 Coins = ₹10 INR", "1000 Coins = ₹10 INR")
                val rawMessage = obj.optString("message", "")
                    .replace("200 Coins = ₹10 INR", "1000 Coins = ₹10 INR")
                    .replace("5000 Coins = ₹10 INR", "1000 Coins = ₹10 INR")
                    .replace("3m=5c, 5m=10c, 10m=20c, 20m=45c, 30m=80c", "3m=10c, 5m=17c, 10m=35c, 20m=72c, 30m=110c")
                val rawTab = obj.optString("targetTab", "HOME").uppercase()
                val effectiveTab = if (rawTab == "ALL" || rawTab.isBlank()) "HOME" else rawTab
                list.add(
                    AdminPostItem(
                        id = rawId,
                        title = rawTitle,
                        message = rawMessage,
                        targetTab = effectiveTab,
                        postType = obj.optString("postType", "BANNER"),
                        actionUrl = obj.optString("actionUrl", ""),
                        imageUrl = obj.optString("imageUrl", ""),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        isPinned = obj.optBoolean("isPinned", rawId == "default_welcome_banner"),
                        pinnedAt = obj.optLong("pinnedAt", if (rawId == "default_welcome_banner") 1700000000000L else 0L)
                    )
                )
            }
        } catch (_: Exception) {
            // fallback
        }
        return list
    }

    private fun serializeAdminPostsJson(posts: List<AdminPostItem>): String {
        val array = JSONArray()
        for (p in posts) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("title", p.title)
                put("message", p.message)
                put("targetTab", p.targetTab)
                put("postType", p.postType)
                put("actionUrl", p.actionUrl)
                put("imageUrl", p.imageUrl)
                put("createdAt", p.createdAt)
                put("isPinned", p.isPinned)
                put("pinnedAt", p.pinnedAt)
            }
            array.put(obj)
        }
        return array.toString()
    }
}
