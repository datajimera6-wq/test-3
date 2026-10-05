package com.example.admin

import android.util.Log
import com.example.BuildConfig
import com.example.data.AdminPostItem
import com.example.data.AppUpdateInfo
import com.example.data.DataStoreManager
import com.example.data.PayoutRequest
import com.example.data.PayoutStatus
import com.example.data.SupportMessage
import com.example.data.UserProfile
import com.example.data.VideoTaskItem
import com.example.data.generateSixDigitReferralCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object CloudDriveServerManager {

    private const val TAG = "CloudDriveServer"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val syncMutex = Mutex()
    private var lastGithubCheckTime = 0L
    private var cachedGithubUpdateInfo: AppUpdateInfo? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val noRedirectClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Tests connectivity to the Google Drive Apps Script Web App.
     */
    suspend fun testConnection(serverUrl: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanUrl = serverUrl.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return@withContext Pair(false, "Invalid URL. Please enter a valid HTTPS Google Script Web App URL.")
        }

        try {
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: ""
            val finalUrl = response.request.url.toString()

            if (finalUrl.contains("accounts.google.com")) {
                return@withContext Pair(
                    false,
                    "Google Script Access Locked (403): Please open script.google.com -> Deploy -> Manage deployments -> Edit (✏️) -> Set 'Who has access' to 'Anyone' and click Deploy."
                )
            }

            if (code in 200..299 || code == 302) {
                val json = try { JSONObject(body) } catch (_: Exception) { null }
                val folderName = json?.optString("folderName", "KingoKing_Server") ?: "KingoKing_Server"
                Pair(true, "Connected! Google Drive folder '$folderName' is live.")
            } else if (code == 403 || code == 401) {
                Pair(
                    false,
                    "HTTP 403 Permission Denied: Google Script mein 'Deploy -> Manage deployments -> Edit' par jaakar 'Who has access' ko 'Anyone' set karein!"
                )
            } else {
                Pair(false, "Server returned HTTP $code: ${body.take(120)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Test connection error: ${e.message}")
            Pair(false, "Connection error: ${e.message ?: "Failed to reach server"}")
        }
    }

    /**
     * Real-time two-way sync with Google Drive Server:
     * - Pulls latest tasks, posts, users, and payouts via GET first so no data is ever overwritten.
     * - In USER role: pulls Admin's tasks & posts in real time, and pushes only the user's own profile & payouts.
     * - In ADMIN role: pulls all users' live stats & payout requests, and pushes Admin's tasks, posts, and approvals.
     */
    suspend fun syncData(
        serverUrl: String,
        dataStoreManager: DataStoreManager,
        pushAdminContent: Boolean = (BuildConfig.APP_ROLE == "ADMIN"),
        pushLocalChanges: Boolean = true,
        pullRemoteFirst: Boolean = true
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanUrl = serverUrl.trim()
        if (cleanUrl.isBlank()) {
            return@withContext Pair(false, "Server URL not configured.")
        }

        // Background read-only polls should never queue up or block user-initiated sync/login
        if (!pushLocalChanges && syncMutex.isLocked) {
            return@withContext Pair(true, "Sync already in progress")
        }

        syncMutex.withLock {
            try {
                val isAdminRole = BuildConfig.APP_ROLE == "ADMIN"
                var getCode = 200
                var getSucceeded = false
                var remoteJson: JSONObject? = null
                val pollStartMillis = System.currentTimeMillis()

                if (pullRemoteFirst || !pushLocalChanges) {
                    // STEP 1: Fast cache-busted GET to fetch latest server state immediately
                    val getUrl = if (cleanUrl.contains("?")) {
                        "$cleanUrl&nocache=${System.currentTimeMillis()}"
                    } else {
                        "$cleanUrl?nocache=${System.currentTimeMillis()}"
                    }
                    val getReq = Request.Builder()
                        .url(getUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Cache-Control", "no-cache, no-store, must-revalidate")
                        .header("Pragma", "no-cache")
                        .get()
                        .build()
                    val getRes = httpClient.newCall(getReq).execute()
                    getCode = getRes.code
                    val getBody = getRes.body?.string() ?: ""
                    val getFinalUrl = getRes.request.url.toString()
                    getRes.close()
                    val getSucceededFlag = getRes.isSuccessful && !getFinalUrl.contains("accounts.google.com")
                    getSucceeded = getSucceededFlag
                    // If a local write occurred recently or while this background GET was in flight, discard stale GET payload
                    val staleDueToConcurrentWrite = !pushLocalChanges &&
                        (DataStoreManager.lastLocalMutationMillis > (pollStartMillis - 8_000L))
                    remoteJson = if (getSucceeded && !staleDueToConcurrentWrite) {
                        try { JSONObject(getBody) } catch (_: Exception) { null }
                    } else null
                }

                val deletedTaskIds = dataStoreManager.deletedTaskIdsFlow.first()
                val deletedPostIds = dataStoreManager.deletedPostIdsFlow.first()
                var remoteTasksArr: JSONArray? = null
                var remotePostsArr: JSONArray? = null

                if (remoteJson != null) {
                    // 1A. Parse Remote Users FIRST so we can accurately count per-task completions across all users
                    val remoteUsersArr = remoteJson.optJSONArray("users")
                    val parsedUsers = mutableListOf<UserProfile>()
                    if (remoteUsersArr != null) {
                        for (i in 0 until remoteUsersArr.length()) {
                            val obj = remoteUsersArr.optJSONObject(i) ?: continue
                            val email = obj.optString("email", "").trim().lowercase()
                            if (email.isNotBlank()) {
                                parsedUsers.add(
                                    UserProfile(
                                        userId = obj.optString("userId", "usr_${Math.abs(email.hashCode()) % 100000}"),
                                        email = email,
                                        name = obj.optString("name", email.substringBefore("@")),
                                        passwordHash = obj.optString("passwordHash", ""),
                                        coinsBalance = obj.optInt("coinsBalance", 0),
                                        completedTasksCount = obj.optInt("completedTasksCount", 0),
                                        joinedAtMillis = obj.optLong("joinedAtMillis", System.currentTimeMillis()),
                                        transactionsJson = obj.optString("transactionsJson", "[]"),
                                        likedTasksJson = obj.optString("likedTasksJson", "[]"),
                                        commentCountsJson = obj.optString("commentCountsJson", "{}"),
                                        taskLocksJson = obj.optString("taskLocksJson", "{}"),
                                        completedTaskIdsJson = obj.optString("completedTaskIdsJson", "[]"),
                                        lastUpdatedMillis = obj.optLong("lastUpdatedMillis", 0L),
                                        referralCode = obj.optString("referralCode", "").ifBlank { generateSixDigitReferralCode(email) },
                                        referredByCode = obj.optString("referredByCode", "")
                                    )
                                )
                            }
                        }
                        if (parsedUsers.isNotEmpty()) {
                            dataStoreManager.syncRemoteUsersFromServer(parsedUsers, isAdmin = isAdminRole)
                        }
                    }

                    // Count how many distinct users have completed each taskId
                    val allKnownUsers = dataStoreManager.usersFlow.first()
                    val taskCompletionMap = mutableMapOf<String, MutableSet<String>>()
                    for (u in allKnownUsers) {
                        val userKey = u.email.lowercase()
                        try {
                            val arr = JSONArray(u.completedTaskIdsJson)
                            for (idx in 0 until arr.length()) {
                                val tId = arr.optString(idx)
                                if (tId.isNotBlank()) {
                                    taskCompletionMap.getOrPut(tId) { mutableSetOf() }.add(userKey)
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    // Extract authoritative Admin state from payouts ("cfg_admin_state_v1") if present
                    val remotePayoutsArr = remoteJson.optJSONArray("payouts")
                    var adminStateObj: JSONObject? = null
                    val effectiveDeletedTaskIds = deletedTaskIds.toMutableSet()
                    val effectiveDeletedPostIds = deletedPostIds.toMutableSet()
                    if (remotePayoutsArr != null) {
                        for (i in 0 until remotePayoutsArr.length()) {
                            val pObj = remotePayoutsArr.optJSONObject(i) ?: continue
                            if (pObj.optString("id") == "cfg_admin_state_v1") {
                                val rawNote = pObj.optString("adminNote", "")
                                if (rawNote.isNotBlank()) {
                                    adminStateObj = try { JSONObject(rawNote) } catch (_: Exception) { null }
                                }
                                break
                            }
                        }
                    }
                    adminStateObj?.optJSONArray("deletedTaskIds")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val dId = arr.optString(i)
                            if (dId.isNotBlank()) effectiveDeletedTaskIds.add(dId)
                        }
                    }
                    adminStateObj?.optJSONArray("deletedPostIds")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val dId = arr.optString(i)
                            if (dId.isNotBlank()) effectiveDeletedPostIds.add(dId)
                        }
                    }

                    // 1B. Parse Remote Tasks (with maxCompletions & live completedCount)
                    remoteTasksArr = adminStateObj?.optJSONArray("tasks") ?: remoteJson.optJSONArray("tasks")
                    if (remoteTasksArr != null) {
                        val parsedTasks = mutableListOf<VideoTaskItem>()
                        for (i in 0 until remoteTasksArr.length()) {
                            val obj = remoteTasksArr.optJSONObject(i) ?: continue
                            val taskId = obj.optString("id")
                            if (taskId.isNotBlank() && !effectiveDeletedTaskIds.contains(taskId)) {
                                val maxComp = obj.optInt("maxCompletions", 0)
                                val remoteCompCount = obj.optInt("completedCount", 0)
                                val usersCompCount = taskCompletionMap[taskId]?.size ?: 0
                                val effectiveCompletedCount = maxOf(remoteCompCount, usersCompCount)
                                parsedTasks.add(
                                    VideoTaskItem(
                                        id = taskId,
                                        title = obj.optString("title", "YouTube Video Task"),
                                        channelName = obj.optString("channelName", "YouTube Creator"),
                                        videoUrl = obj.optString("videoUrl", "https://www.youtube.com"),
                                        thumbnailUrl = obj.optString("thumbnailUrl", ""),
                                        durationSeconds = obj.optInt("durationSeconds", 600),
                                        isLive = obj.optBoolean("isLive", false),
                                        rewardCoins = obj.optInt("rewardCoins", 10),
                                        selectedDurationSeconds = obj.optInt("selectedDurationSeconds", 180),
                                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                                        lockedUntilMillis = 0L,
                                        isPinned = obj.optBoolean("isPinned", false),
                                        pinnedAt = obj.optLong("pinnedAt", 0L),
                                        maxCompletions = maxComp,
                                        completedCount = effectiveCompletedCount
                                    )
                                )
                            }
                        }
                        dataStoreManager.syncRemoteTasksFromServer(parsedTasks)
                    } else if (taskCompletionMap.isNotEmpty()) {
                        // Even if tasks array wasn't in response, refresh local task completion counts from users
                        val currentLocalTasks = dataStoreManager.videoTasksFlow.first()
                        val updatedLocalTasks = currentLocalTasks.map { t ->
                            val usersCompCount = taskCompletionMap[t.id]?.size ?: 0
                            t.copy(completedCount = maxOf(t.completedCount, usersCompCount))
                        }
                        dataStoreManager.syncRemoteTasksFromServer(updatedLocalTasks)
                    }

                    // 1C. Parse Remote Admin Posts / Banners
                    remotePostsArr = adminStateObj?.optJSONArray("posts") ?: remoteJson.optJSONArray("posts")
                    if (remotePostsArr != null) {
                        val parsedPosts = mutableListOf<AdminPostItem>()
                        for (i in 0 until remotePostsArr.length()) {
                            val obj = remotePostsArr.optJSONObject(i) ?: continue
                            val postId = obj.optString("id")
                            if (postId.isNotBlank() && !effectiveDeletedPostIds.contains(postId)) {
                                val rawTab = obj.optString("targetTab", "HOME").uppercase()
                                val remoteTab = if (rawTab == "ALL" || rawTab.isBlank()) "HOME" else rawTab
                                parsedPosts.add(
                                    AdminPostItem(
                                        id = postId,
                                        title = obj.optString("title", "Announcement"),
                                        message = obj.optString("message", ""),
                                        targetTab = remoteTab,
                                        postType = obj.optString("postType", "BANNER"),
                                        actionUrl = obj.optString("actionUrl", ""),
                                        imageUrl = obj.optString("imageUrl", ""),
                                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                                        isPinned = obj.optBoolean("isPinned", false),
                                        pinnedAt = obj.optLong("pinnedAt", 0L)
                                    )
                                )
                            }
                        }
                        dataStoreManager.syncAdminPosts(parsedPosts)
                    }

                    // 1D. Parse Remote Payout Requests & Support Chat Messages
                    val parsedSupportMessages = mutableListOf<SupportMessage>()
                    val remoteSupportArr = remoteJson.optJSONArray("supportMessages")
                    if (remoteSupportArr != null) {
                        for (i in 0 until remoteSupportArr.length()) {
                            val obj = remoteSupportArr.optJSONObject(i) ?: continue
                            val id = obj.optString("id")
                            val msgText = obj.optString("message")
                            if (id.isNotBlank() && msgText.isNotBlank()) {
                                parsedSupportMessages.add(
                                    SupportMessage(
                                        id = id,
                                        userId = obj.optString("userId", ""),
                                        userEmail = obj.optString("userEmail", ""),
                                        userName = obj.optString("userName", ""),
                                        senderRole = obj.optString("senderRole", "USER"),
                                        message = msgText,
                                        timestampMillis = obj.optLong("timestampMillis", System.currentTimeMillis())
                                    )
                                )
                            }
                        }
                    }

                    var remoteConfiguredUpdateUrl: String? = null
                    var remoteConfiguredAppDownloadUrl: String? = adminStateObj?.optString("appDownloadUrl", "")?.trim()?.takeIf { it.isNotBlank() }
                    var remoteSharedReferralCode: String? = null
                    if (remotePayoutsArr != null) {
                        val parsedPayouts = mutableListOf<PayoutRequest>()
                        for (i in 0 until remotePayoutsArr.length()) {
                            val obj = remotePayoutsArr.optJSONObject(i) ?: continue
                            val id = obj.optString("id")
                            if (id == "cfg_admin_state_v1") {
                                continue
                            } else if (id == "cfg_update_folder") {
                                remoteConfiguredUpdateUrl = obj.optString("adminNote", "").trim()
                            } else if (id == "cfg_app_download_url") {
                                val rawDl = obj.optString("adminNote", "").trim().ifBlank { obj.optString("destination", "").trim() }
                                if (rawDl.isNotBlank()) {
                                    remoteConfiguredAppDownloadUrl = rawDl
                                }
                            } else if (id == "cfg_ref_share") {
                                val ref = obj.optString("adminNote", "").trim().filter { it.isDigit() }.take(6)
                                if (ref.length == 6) {
                                    remoteSharedReferralCode = ref
                                }
                            } else if (id.startsWith("chat_")) {
                                val msgText = obj.optString("adminNote", "")
                                if (msgText.isNotBlank()) {
                                    parsedSupportMessages.add(
                                        SupportMessage(
                                            id = id,
                                            userId = obj.optString("userId", ""),
                                            userEmail = obj.optString("userEmail", ""),
                                            userName = obj.optString("method", "").removePrefix("CHAT:"),
                                            senderRole = obj.optString("destination", "USER"),
                                            message = msgText,
                                            timestampMillis = obj.optLong("requestedAtMillis", System.currentTimeMillis())
                                        )
                                    )
                                }
                            } else if (id.isNotBlank()) {
                                val statusStr = obj.optString("status", PayoutStatus.PENDING.name)
                                val status = try { PayoutStatus.valueOf(statusStr) } catch (_: Exception) { PayoutStatus.PENDING }
                                val rawCoins = obj.optInt("amountCoins", obj.optInt("coins", 0))
                                val rawInr = obj.optDouble("amountInr", obj.optDouble("inr", 0.0))
                                val safeCoins = if (rawCoins > 0) rawCoins else (rawInr * 100.0).toInt()
                                val safeInr = if (rawInr > 0.0) rawInr else (safeCoins / 100.0)
                                parsedPayouts.add(
                                    PayoutRequest(
                                        id = id,
                                        userId = obj.optString("userId", ""),
                                        userEmail = obj.optString("userEmail", ""),
                                        amountCoins = safeCoins,
                                        amountInr = safeInr,
                                        method = obj.optString("method", "UPI"),
                                        destination = obj.optString("destination", ""),
                                        status = status,
                                        requestedAtMillis = obj.optLong("requestedAtMillis", System.currentTimeMillis()),
                                        processedAtMillis = if (obj.has("processedAtMillis")) obj.optLong("processedAtMillis") else null,
                                        adminNote = obj.optString("adminNote", "")
                                    )
                                )
                            }
                        }
                        if (parsedPayouts.isNotEmpty()) {
                            dataStoreManager.syncRemotePayoutsFromServer(parsedPayouts)
                        }
                    }

                    if (parsedSupportMessages.isNotEmpty()) {
                        dataStoreManager.syncRemoteSupportMessagesFromServer(parsedSupportMessages)
                    }

                    if (remoteConfiguredUpdateUrl != null && !isAdminRole) {
                        dataStoreManager.setUpdateDriveFolderUrl(remoteConfiguredUpdateUrl)
                    }
                    if (!remoteConfiguredAppDownloadUrl.isNullOrBlank() &&
                        !isAdminRole &&
                        (System.currentTimeMillis() - DataStoreManager.lastLocalMutationMillis > 30_000L)
                    ) {
                        dataStoreManager.saveAppDownloadUrl(
                            DataStoreManager.normalizeAppDownloadUrl(remoteConfiguredAppDownloadUrl!!)
                        )
                    }
                    if (!remoteSharedReferralCode.isNullOrBlank()) {
                        val existingPending = dataStoreManager.pendingReferralCodeFlow.first()
                        if (existingPending.isBlank()) {
                            dataStoreManager.savePendingReferralCode(remoteSharedReferralCode!!)
                        }
                    }

                    // 1E. Parse Remote App Update from Google Drive "update" folder
                    var resolvedUpdate: AppUpdateInfo? = null
                    val appUpdateObj = remoteJson.optJSONObject("appUpdate")
                    if (appUpdateObj != null) {
                        val hasUpd = appUpdateObj.optBoolean("hasUpdate", false)
                        val fId = appUpdateObj.optString("fileId", "").trim()
                        val dlUrl = appUpdateObj.optString("downloadUrl", "").trim()
                        if (hasUpd && (fId.isNotBlank() || dlUrl.isNotBlank())) {
                            resolvedUpdate = AppUpdateInfo(
                                hasUpdate = true,
                                fileId = fId,
                                fileName = appUpdateObj.optString("fileName", "KingoKing_Update.apk").ifBlank { "KingoKing_Update.apk" },
                                updatedAtMillis = appUpdateObj.optLong("updatedAtMillis", 0L),
                                fileSize = appUpdateObj.optLong("fileSize", 0L),
                                downloadUrl = dlUrl.ifBlank {
                                    "https://drive.usercontent.google.com/download?id=$fId&export=download&confirm=t"
                                }
                            )
                        }
                    }

                    // Priority 1: Check Google Drive update folder
                    val effectiveFolderUrl = (remoteConfiguredUpdateUrl ?: dataStoreManager.updateDriveFolderUrlFlow.first()).trim()
                    val effectiveAppDlUrl = (remoteConfiguredAppDownloadUrl ?: dataStoreManager.appDownloadUrlFlow.first()).trim().ifBlank { DataStoreManager.DEFAULT_APP_DOWNLOAD_URL }

                    if ((resolvedUpdate == null || !resolvedUpdate.hasUpdate) && effectiveFolderUrl.isNotBlank()) {
                        val folderUpdate = inspectPublicDriveUpdateLink(effectiveFolderUrl)
                        if (folderUpdate != null && folderUpdate.hasUpdate) {
                            resolvedUpdate = folderUpdate
                        }
                    }

                    // Priority 2: Fallback to direct app download URL if Google Drive folder didn't yield an update
                    if ((resolvedUpdate == null || !resolvedUpdate.hasUpdate) && effectiveAppDlUrl.isNotBlank() && !effectiveAppDlUrl.contains("drive.google.com/drive/folders")) {
                        val dlUpdate = inspectPublicDriveUpdateLink(effectiveAppDlUrl)
                        if (dlUpdate != null && dlUpdate.hasUpdate) {
                            resolvedUpdate = dlUpdate
                        }
                    }

                    if (resolvedUpdate != null && resolvedUpdate.hasUpdate) {
                        dataStoreManager.saveRemoteAppUpdate(resolvedUpdate)
                    }
                } else {
                    // When remoteJson is null, inspect Google Drive folder first, then fallback
                    val effectiveFolderUrl = dataStoreManager.updateDriveFolderUrlFlow.first().trim()
                    var resolvedUpdate: AppUpdateInfo? = null
                    if (effectiveFolderUrl.isNotBlank()) {
                        val folderUpdate = inspectPublicDriveUpdateLink(effectiveFolderUrl)
                        if (folderUpdate != null && folderUpdate.hasUpdate) {
                            resolvedUpdate = folderUpdate
                        }
                    }
                    if (resolvedUpdate == null || !resolvedUpdate.hasUpdate) {
                        val effectiveAppDlUrl = dataStoreManager.appDownloadUrlFlow.first().ifBlank { DataStoreManager.DEFAULT_APP_DOWNLOAD_URL }
                        if (effectiveAppDlUrl.isNotBlank() && !effectiveAppDlUrl.contains("drive.google.com/drive/folders")) {
                            val dlUpdate = inspectPublicDriveUpdateLink(effectiveAppDlUrl)
                            if (dlUpdate != null && dlUpdate.hasUpdate) {
                                resolvedUpdate = dlUpdate
                            }
                        }
                    }
                    if (resolvedUpdate != null && resolvedUpdate.hasUpdate) {
                        dataStoreManager.saveRemoteAppUpdate(resolvedUpdate)
                    }
                }

                val updatedTasks = dataStoreManager.videoTasksFlow.first()
                val updatedPosts = dataStoreManager.rawAdminPostsWithConfigFlow.first()
                val updatedUsers = dataStoreManager.usersFlow.first()
                val updatedPayouts = dataStoreManager.payoutRequestsFlow.first()
                val updatedSupportMessages = dataStoreManager.supportMessagesFlow.first()

                // Fast path: if only pulling updates (e.g., background poll or initial login fetch), return immediately after GET!
                if (!pushLocalChanges) {
                    if (getSucceeded) {
                        com.example.service.NotificationChannels.checkAndDispatchAdminNotifications(
                            context = dataStoreManager.appContext,
                            dataStoreManager = dataStoreManager
                        )
                    }
                    return@withLock if (getSucceeded) {
                        dataStoreManager.setCloudServerStatus(
                            "Live Connected (${updatedTasks.size} tasks, ${updatedUsers.size} users)"
                        )
                        Pair(true, "Connected & Synced with Google Drive!")
                    } else if (getCode == 403 || getCode == 401) {
                        dataStoreManager.setCloudServerStatus("Permission Needed (Set 'Anyone' in Script)")
                        Pair(
                            false,
                            "HTTP 403: Google Script mein 'Deploy -> Manage deployments -> Edit (✏️)' par jaakar 'Who has access' ko 'Anyone' karein!"
                        )
                    } else {
                        Pair(false, "Server HTTP error $getCode")
                    }
                }

                // STEP 2: Push merged state back to Google Drive
                val effectiveAdminPush = isAdminRole || pushAdminContent
                val syncPayload = JSONObject().apply {
                    put("action", "sync_all")
                    put("role", if (effectiveAdminPush) "ADMIN" else BuildConfig.APP_ROLE)

                    // Only ADMIN (or explicit Admin Dashboard action) pushes tasks and posts so User App never overwrites Admin updates
                    var pushedTasksArr: JSONArray? = null
                    var pushedPostsArr: JSONArray? = null
                    if (effectiveAdminPush && pushAdminContent) {
                        val localHasRealTasks = updatedTasks.any { !it.id.startsWith("task_") }
                        val remoteHasRealTasks = (remoteTasksArr != null && remoteTasksArr.length() > 0)
                        if (localHasRealTasks || !remoteHasRealTasks) {
                            val tasksArr = JSONArray()
                            for (t in updatedTasks) {
                                tasksArr.put(JSONObject().apply {
                                    put("id", t.id)
                                    put("title", t.title)
                                    put("channelName", t.channelName)
                                    put("videoUrl", t.videoUrl)
                                    put("thumbnailUrl", t.thumbnailUrl)
                                    put("rewardCoins", t.rewardCoins)
                                    put("durationSeconds", t.durationSeconds)
                                    put("isLive", t.isLive)
                                    put("selectedDurationSeconds", t.selectedDurationSeconds)
                                    put("isCompleted", false)
                                    put("lockedUntilMillis", 0L)
                                    put("createdAt", t.createdAt)
                                    put("isPinned", t.isPinned)
                                    put("pinnedAt", t.pinnedAt)
                                    put("maxCompletions", t.maxCompletions)
                                    put("completedCount", t.completedCount)
                                })
                            }
                            put("tasks", tasksArr)
                            pushedTasksArr = tasksArr
                        } else if (remoteTasksArr != null) {
                            put("tasks", remoteTasksArr)
                            pushedTasksArr = remoteTasksArr
                        }

                        val localHasRealPosts = updatedPosts.any { !it.id.startsWith("default_") && !it.postType.startsWith("CONFIG_") }
                        val remoteHasRealPosts = (remotePostsArr != null && remotePostsArr.length() > 0)
                        if (localHasRealPosts || !remoteHasRealPosts) {
                            val postsArr = JSONArray()
                            for (post in updatedPosts) {
                                postsArr.put(JSONObject().apply {
                                    put("id", post.id)
                                    put("title", post.title)
                                    put("message", post.message)
                                    put("targetTab", post.targetTab)
                                    put("postType", post.postType)
                                    put("actionUrl", post.actionUrl)
                                    put("imageUrl", post.imageUrl)
                                    put("createdAt", post.createdAt)
                                    put("isPinned", post.isPinned)
                                    put("pinnedAt", post.pinnedAt)
                                })
                            }
                            put("posts", postsArr)
                            pushedPostsArr = postsArr
                        } else if (remotePostsArr != null) {
                            put("posts", remotePostsArr)
                            pushedPostsArr = remotePostsArr
                        }
                    }

                    val usersArr = JSONArray()
                    for (u in updatedUsers) {
                        usersArr.put(JSONObject().apply {
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
                            put("referredByCode", u.referredByCode)
                        })
                    }
                    put("users", usersArr)

                    val configuredUpdateUrl = dataStoreManager.updateDriveFolderUrlFlow.first()
                    val configuredAppDownloadUrl = DataStoreManager.normalizeAppDownloadUrl(dataStoreManager.appDownloadUrlFlow.first())
                    val pendingRefShareCode = dataStoreManager.pendingReferralCodeFlow.first()
                    val payoutsArr = JSONArray()
                    for (p in updatedPayouts) {
                        payoutsArr.put(JSONObject().apply {
                            put("id", p.id)
                            put("userId", p.userId)
                            put("userEmail", p.userEmail)
                            put("amountCoins", p.amountCoins)
                            put("amountInr", p.amountInr)
                            put("method", p.method)
                            put("destination", p.destination)
                            put("status", p.status.name)
                            put("requestedAtMillis", p.requestedAtMillis)
                            p.processedAtMillis?.let { put("processedAtMillis", it) }
                            put("adminNote", p.adminNote ?: "")
                        })
                    }
                    if (effectiveAdminPush && pushAdminContent && pushedTasksArr != null && pushedPostsArr != null) {
                        val adminStateJson = JSONObject().apply {
                            put("versionMillis", System.currentTimeMillis())
                            put("appDownloadUrl", configuredAppDownloadUrl)
                            put("updateFolderUrl", configuredUpdateUrl)
                            put("tasks", pushedTasksArr)
                            put("posts", pushedPostsArr)
                            put("deletedTaskIds", JSONArray(deletedTaskIds.toList()))
                            put("deletedPostIds", JSONArray(deletedPostIds.toList()))
                        }.toString()
                        payoutsArr.put(JSONObject().apply {
                            put("id", "cfg_admin_state_v1")
                            put("userId", "system")
                            put("userEmail", "admin@system")
                            put("amountCoins", 0)
                            put("amountInr", 0.0)
                            put("method", "ADMIN_STATE")
                            put("destination", "DRIVE")
                            put("status", "PENDING")
                            put("requestedAtMillis", System.currentTimeMillis())
                            put("adminNote", adminStateJson)
                        })
                    }
                    if (effectiveAdminPush && configuredUpdateUrl.isNotBlank()) {
                        payoutsArr.put(JSONObject().apply {
                            put("id", "cfg_update_folder")
                            put("userId", "system")
                            put("userEmail", "admin@system")
                            put("amountCoins", 0)
                            put("amountInr", 0.0)
                            put("method", "UPDATE_FOLDER")
                            put("destination", "DRIVE")
                            put("status", "PENDING")
                            put("requestedAtMillis", System.currentTimeMillis())
                            put("adminNote", configuredUpdateUrl)
                        })
                    }
                    if (effectiveAdminPush && configuredAppDownloadUrl.isNotBlank()) {
                        payoutsArr.put(JSONObject().apply {
                            put("id", "cfg_app_download_url")
                            put("userId", "system")
                            put("userEmail", "admin@system")
                            put("amountCoins", 0)
                            put("amountInr", 0.0)
                            put("method", "APP_DOWNLOAD_URL")
                            put("destination", configuredAppDownloadUrl)
                            put("status", "PENDING")
                            put("requestedAtMillis", System.currentTimeMillis())
                            put("adminNote", configuredAppDownloadUrl)
                        })
                    }
                    if (pendingRefShareCode.length == 6 && pendingRefShareCode.all { it.isDigit() }) {
                        payoutsArr.put(JSONObject().apply {
                            put("id", "cfg_ref_share")
                            put("userId", "system")
                            put("userEmail", "ref@system")
                            put("amountCoins", 0)
                            put("amountInr", 0.0)
                            put("method", "REF_SHARE")
                            put("destination", "REF")
                            put("status", "PENDING")
                            put("requestedAtMillis", System.currentTimeMillis())
                            put("adminNote", pendingRefShareCode)
                        })
                    }
                    // Also include support messages in payoutsArr with "chat_" prefix so existing deployed Apps Script merges & persists them automatically
                    for (m in updatedSupportMessages) {
                        payoutsArr.put(JSONObject().apply {
                            put("id", m.id)
                            put("userId", m.userId)
                            put("userEmail", m.userEmail)
                            put("amountCoins", 0)
                            put("amountInr", 0.0)
                            put("method", "CHAT:${m.userName}")
                            put("destination", m.senderRole)
                            put("status", "PENDING")
                            put("requestedAtMillis", m.timestampMillis)
                            put("adminNote", m.message)
                        })
                    }
                    put("payouts", payoutsArr)

                    val supportArr = JSONArray()
                    for (m in updatedSupportMessages) {
                        supportArr.put(JSONObject().apply {
                            put("id", m.id)
                            put("userId", m.userId)
                            put("userEmail", m.userEmail)
                            put("userName", m.userName)
                            put("senderRole", m.senderRole)
                            put("message", m.message)
                            put("timestampMillis", m.timestampMillis)
                        })
                    }
                    put("supportMessages", supportArr)
                }

                // Use text/plain; charset=utf-8 as recommended by Google Apps Script Web Apps
                // and handle 302 redirect manually so OkHttp does not fail on script.googleusercontent.com
                val requestBody = syncPayload.toString().toRequestBody("text/plain; charset=utf-8".toMediaType())
                val postReq = Request.Builder()
                    .url(cleanUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .post(requestBody)
                    .build()

                val initialPostRes = noRedirectClient.newCall(postReq).execute()
                val initialCode = initialPostRes.code
                val redirectLocation = initialPostRes.header("Location")

                var postSucceeded = false
                var postBody = ""
                var finalPostCode = initialCode

                if (initialCode in 301..308 && !redirectLocation.isNullOrBlank()) {
                    initialPostRes.close()
                    if (redirectLocation.contains("script.googleusercontent.com")) {
                        // Google Apps Script executes doPost(e) synchronously BEFORE returning 302 to script.googleusercontent.com!
                        // Skipping the second GET to the echo URL cuts POST latency in half.
                        postSucceeded = true
                    } else if (redirectLocation.contains("accounts.google.com")) {
                        finalPostCode = 403
                    } else {
                        val followReq = Request.Builder()
                            .url(redirectLocation)
                            .header("User-Agent", USER_AGENT)
                            .get()
                            .build()
                        val followRes = httpClient.newCall(followReq).execute()
                        finalPostCode = followRes.code
                        postBody = followRes.body?.string() ?: ""
                        postSucceeded = followRes.isSuccessful && !followRes.request.url.toString().contains("accounts.google.com")
                        followRes.close()
                    }
                } else {
                    postBody = initialPostRes.body?.string() ?: ""
                    postSucceeded = initialPostRes.isSuccessful
                    initialPostRes.close()
                }

                if (postSucceeded || getSucceeded) {
                    val json = try { JSONObject(postBody) } catch (_: Exception) { null }
                    if (json == null || json.optBoolean("success", true)) {
                        dataStoreManager.setCloudServerStatus(
                            "Live Connected (${updatedTasks.size} tasks, ${updatedUsers.size} users)"
                        )
                        Pair(true, "Synced with Google Drive Server!")
                    } else {
                        val errMsg = json.optString("error", "Unknown error from Drive server")
                        Pair(false, "Drive response: $errMsg")
                    }
                } else if (finalPostCode == 403 || getCode == 403 || finalPostCode == 401 || getCode == 401) {
                    dataStoreManager.setCloudServerStatus("Permission Needed (Set 'Anyone' in Script)")
                    Pair(
                        false,
                        "HTTP 403: Google Script mein 'Deploy -> Manage deployments -> Edit (✏️)' par jaakar 'Who has access' ko 'Anyone' karein!"
                    )
                } else {
                    Pair(false, "Server HTTP error $finalPostCode")
                }
            } catch (e: Exception) {
                Pair(false, "Sync failed: ${e.message}")
            }
        }
    }

    /**
     * Sends a 6-digit OTP email via the connected Google Drive Apps Script (MailApp.sendEmail).
     */
    suspend fun sendOtpEmail(
        serverUrl: String,
        email: String,
        otpCode: String,
        purpose: String
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanUrl = serverUrl.trim()
        if (cleanUrl.isBlank()) return@withContext false
        try {
            val payload = JSONObject().apply {
                put("action", "send_otp")
                put("email", email.trim())
                put("otp", otpCode)
                put("purpose", purpose)
            }
            val separator = if (cleanUrl.contains("?")) "&" else "?"
            val encodedEmail = java.net.URLEncoder.encode(email.trim(), "UTF-8")
            val encodedPurpose = java.net.URLEncoder.encode(purpose, "UTF-8")
            val urlWithParams = "${cleanUrl}${separator}action=send_otp&email=${encodedEmail}&otp=${otpCode}&purpose=${encodedPurpose}"

            val body = payload.toString().toRequestBody("text/plain; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url(urlWithParams)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .post(body)
                .build()
            val res = noRedirectClient.newCall(req).execute()
            val code = res.code
            val redirectLocation = res.header("Location")
            var responseText = ""
            var ok = false

            if (code in 301..308 && !redirectLocation.isNullOrBlank()) {
                res.close()
                if (!redirectLocation.contains("accounts.google.com")) {
                    val followReq = Request.Builder()
                        .url(redirectLocation)
                        .header("User-Agent", USER_AGENT)
                        .get()
                        .build()
                    val followRes = httpClient.newCall(followReq).execute()
                    responseText = followRes.body?.string() ?: ""
                    ok = followRes.isSuccessful
                    followRes.close()
                }
            } else {
                responseText = res.body?.string() ?: ""
                ok = res.isSuccessful
                res.close()
            }

            // If the script didn't handle send_otp in doPost, also trigger doGet with action=send_otp
            if (responseText.isNotBlank() && !responseText.contains("otpSent")) {
                val getReq = Request.Builder()
                    .url(urlWithParams)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                httpClient.newCall(getReq).execute().close()
            }
            ok
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Inspects a public Google Drive "update" folder URL or direct APK file link to detect the newest APK file.
     * Works even if the Google Apps Script hasn't been redeployed yet!
     */
    fun inspectPublicDriveUpdateLink(rawUrl: String): AppUpdateInfo? {
        var clean = rawUrl.trim()
        if (clean.isBlank()) return AppUpdateInfo(hasUpdate = false)

        // Resolve shortened or redirect URLs (e.g. tinyurl.com, bit.ly, t.co, goo.gl)
        if (clean.contains("tinyurl.com", ignoreCase = true) ||
            clean.contains("bit.ly", ignoreCase = true) ||
            clean.contains("t.co", ignoreCase = true) ||
            clean.contains("goo.gl", ignoreCase = true)
        ) {
            try {
                val headReq = Request.Builder()
                    .url(clean)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                val headRes = httpClient.newCall(headReq).execute()
                val finalUrl = headRes.request.url.toString()
                headRes.close()
                if (finalUrl.isNotBlank() && finalUrl != clean) {
                    clean = finalUrl
                }
            } catch (_: Exception) {}
        }

        // 1. Check if it's a Google Drive Folder URL: /folders/FOLDER_ID
        val folderRegex = Regex("folders/([a-zA-Z0-9_-]{15,})")
        val folderMatch = folderRegex.find(clean)
        if (folderMatch != null) {
            val folderId = folderMatch.groupValues[1]
            return try {
                val folderUrl = "https://drive.google.com/drive/folders/$folderId"
                val req = Request.Builder()
                    .url(folderUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
                val res = httpClient.newCall(req).execute()
                val html = res.body?.string() ?: ""
                res.close()

                var parsedInfo: AppUpdateInfo? = null

                // Method A: Parse window['_DRIVE_ivd'] initial data callback
                val ivdMatch = Regex("""window\['_DRIVE_ivd'\]\s*=\s*'([^']+)'""").find(html)
                if (ivdMatch != null) {
                    try {
                        val unescaped = unescapeDriveHex(ivdMatch.groupValues[1])
                        val rootArr = JSONArray(unescaped)
                        val filesArr = rootArr.optJSONArray(0)
                        if (filesArr != null) {
                            for (i in 0 until filesArr.length()) {
                                val item = filesArr.optJSONArray(i) ?: continue
                                val fId = item.optString(0, "")
                                val fName = item.optString(2, "")
                                if (fId.isNotBlank() && fName.endsWith(".apk", ignoreCase = true)) {
                                    val t1 = item.optLong(9, 0L)
                                    val t2 = item.optLong(10, 0L)
                                    val modTime = maxOf(t1, t2)
                                    val size = item.optLong(13, 0L)
                                    val effectiveTime = if (modTime > 0L) modTime else 0L
                                    parsedInfo = AppUpdateInfo(
                                        hasUpdate = true,
                                        fileId = fId,
                                        fileName = fName,
                                        updatedAtMillis = effectiveTime,
                                        fileSize = size,
                                        downloadUrl = "https://drive.usercontent.google.com/download?id=$fId&export=download&confirm=t"
                                    )
                                    break
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Method B: Regex extraction on folder HTML (unescaped or raw)
                if (parsedInfo == null) {
                    val unescapedHtml = unescapeDriveHex(html)
                    val regexWithTimes = Regex(
                        """\["([a-zA-Z0-9_-]{25,})",\s*\["([a-zA-Z0-9_-]+)"\],\s*"([^"]+\.apk)"[^\d]*(\d{12,13})[^\d]*(\d{12,13})?""",
                        RegexOption.IGNORE_CASE
                    )
                    val mTimes = regexWithTimes.find(unescapedHtml) ?: regexWithTimes.find(html)
                    if (mTimes != null) {
                        val fId = mTimes.groupValues[1]
                        val fName = mTimes.groupValues[3]
                        val t1 = mTimes.groupValues[4].toLongOrNull() ?: 0L
                        val t2 = mTimes.groupValues.getOrNull(5)?.toLongOrNull() ?: 0L
                        val effectiveTime = maxOf(t1, t2)
                        parsedInfo = AppUpdateInfo(
                            hasUpdate = true,
                            fileId = fId,
                            fileName = fName,
                            updatedAtMillis = effectiveTime,
                            fileSize = 0L,
                            downloadUrl = "https://drive.usercontent.google.com/download?id=$fId&export=download&confirm=t"
                        )
                    } else {
                        val regexApk = Regex("""\["([a-zA-Z0-9_-]{25,})"[^\]]*?"([^"]+\.apk)"""", RegexOption.IGNORE_CASE)
                        val m = regexApk.find(unescapedHtml) ?: regexApk.find(html)
                        if (m != null) {
                            val fId = m.groupValues[1]
                            val fName = m.groupValues[2]
                            parsedInfo = AppUpdateInfo(
                                hasUpdate = true,
                                fileId = fId,
                                fileName = fName,
                                updatedAtMillis = 0L,
                                fileSize = 0L,
                                downloadUrl = "https://drive.usercontent.google.com/download?id=$fId&export=download&confirm=t"
                            )
                        }
                    }
                }

                // Method C: Legacy embeddedfolderview fallback
                if (parsedInfo == null) {
                    try {
                        val embedUrl = "https://drive.google.com/embeddedfolderview?id=$folderId#list"
                        val embedReq = Request.Builder().url(embedUrl).header("User-Agent", USER_AGENT).get().build()
                        val embedRes = httpClient.newCall(embedReq).execute()
                        val embedHtml = embedRes.body?.string() ?: ""
                        embedRes.close()
                        val entryRegex = Regex(
                            """id="entry-([a-zA-Z0-9_-]{15,})"[\s\S]*?<div class="flip-entry-title">([^<]+)</div>[\s\S]*?<div class="flip-entry-last-modified">\s*<div>([^<]*)</div>""",
                            RegexOption.IGNORE_CASE
                        )
                        val match = entryRegex.findAll(embedHtml).firstOrNull { it.groupValues[2].trim().endsWith(".apk", ignoreCase = true) }
                        if (match != null) {
                            val fId = match.groupValues[1].trim()
                            val fName = match.groupValues[2].trim()
                            parsedInfo = AppUpdateInfo(
                                hasUpdate = true,
                                fileId = fId,
                                fileName = fName,
                                updatedAtMillis = 0L,
                                fileSize = 0L,
                                downloadUrl = "https://drive.usercontent.google.com/download?id=$fId&export=download&confirm=t"
                            )
                        }
                    } catch (_: Exception) {}
                }

                parsedInfo ?: AppUpdateInfo(hasUpdate = false)
            } catch (_: Exception) {
                null
            }
        }

        // 2. Check if it's a GitHub Release / Repo link
        if (clean.contains("github.com", ignoreCase = true)) {
            val ghInfo = inspectGitHubReleaseUpdate(clean)
            if (ghInfo != null) {
                return ghInfo
            }
        }

        // 3. Check if it's a direct Google Drive File link: /file/d/FILE_ID or id=FILE_ID
        val fileRegex = Regex("""(?:/file/d/|id=)([a-zA-Z0-9_-]{15,})""")
        val fileMatch = fileRegex.find(clean)
        if (fileMatch != null) {
            val fileId = fileMatch.groupValues[1]
            val dlUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
            val fileTimestamp = try {
                val headReq = Request.Builder()
                    .url(dlUrl)
                    .header("Range", "bytes=0-0")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                val headRes = httpClient.newCall(headReq).execute()
                val lastMod = headRes.header("Last-Modified")
                headRes.close()
                if (!lastMod.isNullOrBlank()) {
                    java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US).parse(lastMod)?.time
                } else null
            } catch (_: Exception) {
                null
            } ?: Math.abs(fileId.hashCode().toLong()).coerceAtLeast(1L)
            return AppUpdateInfo(
                hasUpdate = true,
                fileId = fileId,
                fileName = "KingoKing_Update",
                updatedAtMillis = fileTimestamp,
                fileSize = 0L,
                downloadUrl = dlUrl
            )
        }

        // 4. Direct APK HTTP link
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            val directHeaders = try {
                val headReq = Request.Builder()
                    .url(clean)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
                val headRes = httpClient.newCall(headReq).execute()
                val lastMod = headRes.header("Last-Modified")
                val etag = headRes.header("ETag") ?: ""
                val len = headRes.header("Content-Length")?.toLongOrNull() ?: 0L
                val creation = headRes.header("x-ms-creation-time")
                headRes.close()
                var parsedTime = 0L
                val timeHeader = lastMod ?: creation
                if (!timeHeader.isNullOrBlank()) {
                    try {
                        parsedTime = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US).parse(timeHeader)?.time ?: 0L
                    } catch (_: Exception) {}
                }
                Triple(parsedTime, etag, len)
            } catch (_: Exception) {
                Triple(0L, "", 0L)
            }

            val safeTime = if (directHeaders.first > 0L) directHeaders.first else Math.abs(clean.hashCode().toLong()).coerceAtLeast(1L)
            val syntheticId = "apk_${Math.abs("${clean}_${directHeaders.second}_${directHeaders.third}_${safeTime}".hashCode())}"
            return AppUpdateInfo(
                hasUpdate = true,
                fileId = syntheticId,
                fileName = "KingoKing_Update.apk",
                updatedAtMillis = safeTime,
                fileSize = directHeaders.third,
                downloadUrl = clean
            )
        }

        return null
    }

    private fun inspectGitHubReleaseUpdate(rawGithubUrl: String): AppUpdateInfo? {
        val now = System.currentTimeMillis()
        if (now - lastGithubCheckTime < 8_000L && cachedGithubUpdateInfo != null) {
            return cachedGithubUpdateInfo
        }

        try {
            // Extract owner and repo: github.com/{owner}/{repo}
            val match = Regex("""github\.com/([^/]+)/([^/\s#?]+)""", RegexOption.IGNORE_CASE).find(rawGithubUrl)
                ?: return null
            val owner = match.groupValues[1]
            val repo = match.groupValues[2].removeSuffix(".git")

            // 1. Try GitHub Releases API (latest release first, then all releases)
            val apiEndpoints = listOf(
                "https://api.github.com/repos/$owner/$repo/releases/latest",
                "https://api.github.com/repos/$owner/$repo/releases"
            )

            for (apiUrl in apiEndpoints) {
                try {
                    val req = Request.Builder()
                        .url(apiUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .get()
                        .build()
                    val res = httpClient.newCall(req).execute()
                    if (res.isSuccessful) {
                        val body = res.body?.string()?.trim() ?: ""
                        res.close()
                        if (body.startsWith("{")) {
                            val releaseObj = JSONObject(body)
                            val assets = releaseObj.optJSONArray("assets") ?: JSONArray()
                            val info = extractApkFromAssets(owner, repo, assets)
                            if (info != null) {
                                cachedGithubUpdateInfo = info
                                lastGithubCheckTime = System.currentTimeMillis()
                                return info
                            }
                        } else if (body.startsWith("[")) {
                            val array = JSONArray(body)
                            for (i in 0 until array.length()) {
                                val releaseObj = array.getJSONObject(i)
                                val assets = releaseObj.optJSONArray("assets") ?: JSONArray()
                                val info = extractApkFromAssets(owner, repo, assets)
                                if (info != null) {
                                    cachedGithubUpdateInfo = info
                                    lastGithubCheckTime = System.currentTimeMillis()
                                    return info
                                }
                            }
                        }
                    } else {
                        res.close()
                    }
                } catch (_: Exception) {}
            }

            // 2. Direct HTTP check on the direct release asset download URL & releases web page
            val directDownloadUrl = DataStoreManager.normalizeAppDownloadUrl(rawGithubUrl)
            var extractedTime = 0L
            var contentLength = 0L
            var etag = ""

            try {
                val headReq = Request.Builder()
                    .url(directDownloadUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Cache-Control", "no-cache, no-store")
                    .header("Pragma", "no-cache")
                    .get()
                    .build()
                val headRes = httpClient.newCall(headReq).execute()
                val lastMod = headRes.header("Last-Modified")
                val creation = headRes.header("x-ms-creation-time")
                etag = headRes.header("ETag") ?: ""
                contentLength = headRes.header("Content-Length")?.toLongOrNull() ?: 0L
                headRes.close()

                val timeHeader = lastMod ?: creation
                if (!timeHeader.isNullOrBlank()) {
                    try {
                        extractedTime = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US).parse(timeHeader)?.time ?: 0L
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            // 3. Fallback: Parse GitHub public releases HTML web page (not subject to API rate limits!)
            if (extractedTime == 0L) {
                try {
                    val pageUrl = "https://github.com/$owner/$repo/releases"
                    val pageReq = Request.Builder()
                        .url(pageUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html")
                        .header("Cache-Control", "no-cache, no-store")
                        .header("Pragma", "no-cache")
                        .get()
                        .build()
                    val pageRes = httpClient.newCall(pageReq).execute()
                    if (pageRes.isSuccessful) {
                        val html = pageRes.body?.string() ?: ""
                        pageRes.close()
                        val timeRegex = Regex("""(?:datetime|data-date)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        val matchTime = timeRegex.find(html)
                        if (matchTime != null) {
                            val timeStr = matchTime.groupValues[1]
                            extractedTime = try {
                                java.time.Instant.parse(timeStr).toEpochMilli()
                            } catch (_: Exception) {
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                sdf.parse(timeStr.replace("Z", ""))?.time ?: 0L
                            }
                        }
                    } else {
                        pageRes.close()
                    }
                } catch (_: Exception) {}
            }

            val hashKey = "${directDownloadUrl}_${etag}_${contentLength}_${extractedTime}"
            val safeTime = if (extractedTime > 0L) extractedTime else Math.abs(hashKey.hashCode().toLong()).coerceAtLeast(1L)
            val stableFileId = "gh_${owner}_${repo}_${etag.take(12)}_${contentLength}_${safeTime}"
            val info = AppUpdateInfo(
                hasUpdate = true,
                fileId = stableFileId,
                fileName = "KingoKing_Update.apk",
                updatedAtMillis = safeTime,
                fileSize = contentLength,
                downloadUrl = directDownloadUrl
            )
            cachedGithubUpdateInfo = info
            lastGithubCheckTime = System.currentTimeMillis()
            return info
        } catch (_: Exception) {
            return null
        }
    }

    private fun unescapeDriveHex(str: String): String {
        return try {
            val sb = java.lang.StringBuilder(str.length)
            var i = 0
            while (i < str.length) {
                if (i + 3 < str.length && str[i] == '\\' && str[i + 1] == 'x') {
                    val hex = str.substring(i + 2, i + 4)
                    sb.append(hex.toInt(16).toChar())
                    i += 4
                } else if (i + 1 < str.length && str[i] == '\\' && str[i + 1] == '/') {
                    sb.append('/')
                    i += 2
                } else {
                    sb.append(str[i])
                    i++
                }
            }
            sb.toString()
        } catch (_: Exception) {
            str
        }
    }

    private fun extractApkFromAssets(owner: String, repo: String, assets: JSONArray): AppUpdateInfo? {
        for (j in 0 until assets.length()) {
            val asset = assets.getJSONObject(j)
            val aName = asset.optString("name", "")
            val aDownloadUrl = asset.optString("browser_download_url", "")
            val aSize = asset.optLong("size", 0L)
            val aUpdated = asset.optString("updated_at", "").ifBlank { asset.optString("created_at", "") }
            val aId = asset.optLong("id", 0L)
            if (aName.endsWith(".apk", ignoreCase = true) || aDownloadUrl.endsWith(".apk", ignoreCase = true) || aName.contains("app", ignoreCase = true)) {
                val timeMillis = try {
                    java.time.Instant.parse(aUpdated).toEpochMilli()
                } catch (_: Exception) {
                    try {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        sdf.parse(aUpdated.replace("Z", ""))?.time ?: 0L
                    } catch (_: Exception) {
                        0L
                    }
                }
                val safeTime = if (timeMillis > 0L) timeMillis else Math.abs("gh_${aId}_${aUpdated}_${aSize}".hashCode().toLong()).coerceAtLeast(1L)
                val stableFileId = "gh_${owner}_${repo}_${aId}_${aSize}_${safeTime}"
                return AppUpdateInfo(
                    hasUpdate = true,
                    fileId = stableFileId,
                    fileName = aName.ifBlank { "KingoKing_Update.apk" },
                    updatedAtMillis = safeTime,
                    fileSize = aSize,
                    downloadUrl = aDownloadUrl
                )
            }
        }
        return null
    }

    /**
     * Complete, copy-paste ready Google Apps Script that turns Google Drive
     * into a free 24/7 real-time cloud server with per-user data, Email OTP, "update" folder APK detector & task sync.
     */
    fun getGoogleAppsScriptTemplate(): String {
        return """
// =========================================================================
// KINGO KING - GOOGLE DRIVE 24/7 REAL-TIME CLOUD SERVER + OTP + APK UPDATE
// =========================================================================
// HOW TO UPDATE YOUR EXISTING SCRIPT (KEEPING THE SAME URL!):
// 1. Open https://script.google.com/ and open your existing Kingo King project.
// 2. Replace all code in Code.gs with this updated script and press Ctrl+S (Save).
// 3. Select "authorizeEmailPermission" in the top toolbar dropdown and click "Run"
//    -> Click "Review permissions" -> Select your Google Account -> "Allow"
//    (This enables sending verification OTP emails from your Gmail!).
// 4. Click "Deploy" -> "Manage deployments" -> Click Edit (✏️ icon)
//    -> Under "Version", select "New version" -> Click "Deploy".
//    (Your Web App /exec URL stays 100% the same!)
//
// MANDATORY APP UPDATE FOLDER ("update"):
// - A folder named "update" is automatically created in your Google Drive.
// - Whenever you upload a new .apk file into the "update" folder (or replace
//   the old .apk with a new .apk), every user who opens the app will
//   immediately get a Mandatory Update popup to download & install it!
// =========================================================================

var FOLDER_NAME = "KingoKing_Server";
var UPDATE_FOLDER_NAME = "update";

// Run this function once in the Apps Script editor to authorize Gmail/MailApp OTP sending!
function authorizeEmailPermission() {
  var remaining = MailApp.getRemainingDailyQuota();
  Logger.log("Email permission authorized! Daily quota remaining: " + remaining);
}

function sendOtpVerificationEmail(targetEmail, otpCode, purpose) {
  var cleanPurpose = purpose || "Account Verification";
  var subject = "Kingo King - " + otpCode + " is your verification code";
  var plainBody = "Hello,\n\nYour 6-digit verification OTP for " + cleanPurpose + " on Kingo King is:\n\n" +
    otpCode + "\n\nThis code is valid for 10 minutes. Please do not share this code with anyone.\n\n- Team Kingo King";
  var htmlBody = "<div style='font-family:Arial,sans-serif;max-width:480px;margin:0 auto;padding:24px;border:1px solid #e5e7eb;border-radius:12px;background:#ffffff;'>" +
    "<h2 style='color:#111827;margin-top:0;'>Kingo King Verification</h2>" +
    "<p style='color:#374151;font-size:15px;'>Use the 6-digit verification code below for <b>" + cleanPurpose + "</b>:</p>" +
    "<div style='background:#f3f4f6;border-radius:10px;padding:18px;text-align:center;margin:20px 0;'>" +
    "<span style='font-size:32px;font-weight:bold;letter-spacing:8px;color:#d97706;'>" + otpCode + "</span>" +
    "</div>" +
    "<p style='color:#6b7280;font-size:13px;'>This code expires in 10 minutes. If you did not request this, you can safely ignore this email.</p>" +
    "</div>";
  MailApp.sendEmail({
    to: targetEmail,
    subject: subject,
    body: plainBody,
    htmlBody: htmlBody,
    name: "Kingo King Security"
  });
}

function getOrCreateFolder() {
  var folders = DriveApp.getFoldersByName(FOLDER_NAME);
  if (folders.hasNext()) {
    return folders.next();
  }
  return DriveApp.createFolder(FOLDER_NAME);
}

function getUpdateApkInfo() {
  try {
    var candidateFolders = [];
    var namesToCheck = ["update", "Update", "UPDATE"];
    for (var n = 0; n < namesToCheck.length; n++) {
      var rootFolders = DriveApp.getFoldersByName(namesToCheck[n]);
      while (rootFolders.hasNext()) {
        candidateFolders.push(rootFolders.next());
      }
      var srvFolder = getOrCreateFolder();
      var subFolders = srvFolder.getFoldersByName(namesToCheck[n]);
      while (subFolders.hasNext()) {
        candidateFolders.push(subFolders.next());
      }
    }
    if (candidateFolders.length === 0) {
      candidateFolders.push(DriveApp.createFolder(UPDATE_FOLDER_NAME));
    }

    var newestFile = null;
    var newestMillis = 0;
    for (var i = 0; i < candidateFolders.length; i++) {
      var files = candidateFolders[i].getFiles();
      while (files.hasNext()) {
        var f = files.next();
        if (f.isTrashed()) continue;
        var fName = f.getName() || "";
        var fMime = f.getMimeType() || "";
        if (fName.toLowerCase().indexOf(".apk") !== -1 || fMime.indexOf("android.package-archive") !== -1 || f.getSize() > 100000) {
          var updatedMs = f.getLastUpdated() ? f.getLastUpdated().getTime() : 1;
          if (newestFile === null || updatedMs >= newestMillis) {
            newestFile = f;
            newestMillis = updatedMs;
          }
        }
      }
    }

    if (newestFile !== null) {
      try {
        newestFile.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
      } catch (shareErr) {}
      var fId = newestFile.getId();
      return {
        "hasUpdate": true,
        "fileId": fId,
        "fileName": newestFile.getName(),
        "updatedAtMillis": newestMillis,
        "fileSize": newestFile.getSize(),
        "downloadUrl": "https://drive.usercontent.google.com/download?id=" + fId + "&export=download&confirm=t"
      };
    }
  } catch (e) {}
  return {
    "hasUpdate": false,
    "fileId": "",
    "fileName": "",
    "updatedAtMillis": 0,
    "fileSize": 0,
    "downloadUrl": ""
  };
}

function getFileContent(fileName, defaultContent) {
  var folder = getOrCreateFolder();
  var files = folder.getFilesByName(fileName);
  if (files.hasNext()) {
    return files.next().getBlob().getDataAsString();
  }
  folder.createFile(fileName, defaultContent);
  return defaultContent;
}

function saveFileContent(fileName, content) {
  var folder = getOrCreateFolder();
  var files = folder.getFilesByName(fileName);
  if (files.hasNext()) {
    var file = files.next();
    file.setContent(content);
    return file;
  }
  return folder.createFile(fileName, content);
}

function doGet(e) {
  try {
    if (e && e.parameter && e.parameter.action === "send_otp" && e.parameter.email && e.parameter.otp) {
      try {
        sendOtpVerificationEmail(e.parameter.email, e.parameter.otp, e.parameter.purpose);
        return ContentService.createTextOutput(JSON.stringify({ "success": true, "otpSent": true }))
          .setMimeType(ContentService.MimeType.JSON);
      } catch (mailErr) {
        return ContentService.createTextOutput(JSON.stringify({ "success": false, "otpSent": false, "error": mailErr.toString() }))
          .setMimeType(ContentService.MimeType.JSON);
      }
    }

    var tasks = JSON.parse(getFileContent("tasks.json", "[]"));
    var posts = JSON.parse(getFileContent("posts.json", "[]"));
    var users = JSON.parse(getFileContent("users.json", "[]"));
    var payouts = JSON.parse(getFileContent("payouts.json", "[]"));
    var appUpdate = getUpdateApkInfo();
    
    var response = {
      "status": "online",
      "server": "Kingo King Google Drive Server",
      "folderName": FOLDER_NAME,
      "tasks": tasks,
      "posts": posts,
      "users": users,
      "payouts": payouts,
      "appUpdate": appUpdate,
      "timestamp": new Date().toISOString()
    };
    
    return ContentService.createTextOutput(JSON.stringify(response))
      .setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({ "status": "error", "error": err.toString() }))
      .setMimeType(ContentService.MimeType.JSON);
  }
}

function doPost(e) {
  var lock = LockService.getScriptLock();
  try {
    lock.waitLock(10000);
    var postData = JSON.parse(e.postData.contents);
    var action = postData.action || "sync_all";
    
    if (action === "send_otp" && postData.email && postData.otp) {
      try {
        sendOtpVerificationEmail(postData.email, postData.otp, postData.purpose);
        return ContentService.createTextOutput(JSON.stringify({ "success": true, "otpSent": true }))
          .setMimeType(ContentService.MimeType.JSON);
      } catch (mailErr) {
        return ContentService.createTextOutput(JSON.stringify({ "success": false, "otpSent": false, "error": mailErr.toString() }))
          .setMimeType(ContentService.MimeType.JSON);
      }
    }

    if (action === "sync_all") {
      if (postData.tasks) {
        saveFileContent("tasks.json", JSON.stringify(postData.tasks));
      }
      if (postData.posts) {
        saveFileContent("posts.json", JSON.stringify(postData.posts));
      }
      if (postData.users) {
        var existingUsers = JSON.parse(getFileContent("users.json", "[]"));
        var userMap = {};
        for (var i = 0; i < existingUsers.length; i++) {
          var u = existingUsers[i];
          if (u.email) userMap[u.email.toLowerCase()] = u;
        }
        for (var j = 0; j < postData.users.length; j++) {
          var incoming = postData.users[j];
          if (!incoming.email) continue;
          var key = incoming.email.toLowerCase();
          var prev = userMap[key];
          if (!prev || (incoming.lastUpdatedMillis || 0) >= (prev.lastUpdatedMillis || 0) || postData.role === "ADMIN") {
            userMap[key] = incoming;
          }
        }
        var mergedUsers = [];
        for (var k in userMap) {
          mergedUsers.push(userMap[k]);
        }
        saveFileContent("users.json", JSON.stringify(mergedUsers));
      }
      if (postData.payouts) {
        var existingPayouts = JSON.parse(getFileContent("payouts.json", "[]"));
        var payoutMap = {};
        for (var pIdx = 0; pIdx < existingPayouts.length; pIdx++) {
          var ep = existingPayouts[pIdx];
          if (ep.id) payoutMap[ep.id] = ep;
        }
        for (var qIdx = 0; qIdx < postData.payouts.length; qIdx++) {
          var ip = postData.payouts[qIdx];
          if (!ip.id) continue;
          var oldP = payoutMap[ip.id];
          if (!oldP || oldP.status === "PENDING" || oldP.status === "APPROVED" || postData.role === "ADMIN") {
            payoutMap[ip.id] = ip;
          }
        }
        var mergedPayouts = [];
        for (var pk in payoutMap) {
          mergedPayouts.push(payoutMap[pk]);
        }
        saveFileContent("payouts.json", JSON.stringify(mergedPayouts));
      }
      
      var currentTasks = JSON.parse(getFileContent("tasks.json", "[]"));
      var currentPosts = JSON.parse(getFileContent("posts.json", "[]"));
      var currentUsers = JSON.parse(getFileContent("users.json", "[]"));
      var currentPayouts = JSON.parse(getFileContent("payouts.json", "[]"));
      var currentAppUpdate = getUpdateApkInfo();
      return ContentService.createTextOutput(JSON.stringify({
        "success": true,
        "message": "Synced with Google Drive",
        "tasks": currentTasks,
        "posts": currentPosts,
        "users": currentUsers,
        "payouts": currentPayouts,
        "appUpdate": currentAppUpdate
      })).setMimeType(ContentService.MimeType.JSON);
    }
    
    return ContentService.createTextOutput(JSON.stringify({ "success": true }))
      .setMimeType(ContentService.MimeType.JSON);
  } catch (err) {
    return ContentService.createTextOutput(JSON.stringify({ "success": false, "error": err.toString() }))
      .setMimeType(ContentService.MimeType.JSON);
  } finally {
    try { lock.releaseLock(); } catch (ignore) {}
  }
}
""".trimIndent()
    }
}
