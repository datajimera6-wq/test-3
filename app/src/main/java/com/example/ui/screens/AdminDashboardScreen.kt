package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.AdminPostItem
import com.example.data.PayoutRequest
import com.example.data.PayoutStatus
import com.example.data.SupportMessage
import com.example.data.UserProfile
import com.example.data.VideoTaskItem
import com.example.ui.components.AddVideoTaskDialog
import com.example.ui.components.SupportChatDialog
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AmberDark
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.Slate800
import com.example.ui.theme.SuccessGreen
import com.example.util.TimeFormatter
import com.example.viewmodel.MainViewModel
import java.util.Locale

private val PrimaryBlue = Color(0xFF3B82F6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    if (com.example.BuildConfig.APP_ROLE != "ADMIN") {
        BackHandler { viewModel.navigateBack() }
    }

    val videoTasks by viewModel.videoTasks.collectAsState()
    val adminPosts by viewModel.adminPosts.collectAsState()
    val payoutRequests by viewModel.payoutRequests.collectAsState()
    val supportMessages by viewModel.supportMessages.collectAsState()
    val allUsers by viewModel.allUsers.collectAsState()
    val serverRunning by viewModel.adminServerRunning.collectAsState()
    val serverUrl by viewModel.adminServerUrl.collectAsState()
    val cloudServerUrl by viewModel.cloudServerUrl.collectAsState()
    val cloudServerStatus by viewModel.cloudServerStatus.collectAsState()
    val remoteAppUpdate by viewModel.remoteAppUpdate.collectAsState()
    val updateDriveFolderUrl by viewModel.updateDriveFolderUrl.collectAsState()
    val appDownloadUrl by viewModel.appDownloadUrl.collectAsState()
    val fullScreenOpeningOverlay by viewModel.fullScreenOpeningOverlay.collectAsState()
    val walletBalance by viewModel.walletBalance.collectAsState()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showAddTaskDialog by remember { mutableIntStateOf(0).let { mutableStateOf(false) } }

    // Dialogs for Admin actions
    var selectedDetailUserEmail by remember { mutableStateOf<String?>(null) }
    var adjustCoinsUser by remember { mutableStateOf<UserProfile?>(null) }
    var adjustCoinsInput by remember { mutableStateOf("") }
    var activeChatUser by remember { mutableStateOf<Triple<String, String, String>?>(null) } // (userId, userEmail, userName)

    val pendingPayoutsCount = payoutRequests.count { it.status == PayoutStatus.PENDING }
    val supportThreadsCount = remember(supportMessages) {
        supportMessages.map { it.userId.ifBlank { it.userEmail.lowercase() } }.distinct().size
    }
    val unAnsweredSupportCount = remember(supportMessages) {
        supportMessages
            .groupBy { it.userId.ifBlank { it.userEmail.lowercase() } }
            .count { (_, msgs) -> msgs.maxByOrNull { it.timestampMillis }?.senderRole == "USER" }
    }

    LaunchedEffect(Unit) {
        if (!serverRunning) {
            com.example.admin.AdminWebServer.startServer(context, viewModel.getDataStoreManager()) { running, url ->
                viewModel.updateAdminServerState(running, url)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.example.ui.components.KingoLogoBadge(
                            isAdmin = true,
                            size = 36.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "KINGO ADMIN",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 17.sp,
                                letterSpacing = 0.6.sp
                            )
                            Text(
                                text = "Realtime Cloud & Task Control",
                                style = MaterialTheme.typography.labelSmall,
                                color = AmberPrimary,
                                fontSize = 10.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (com.example.BuildConfig.APP_ROLE != "ADMIN") {
                        IconButton(
                            onClick = { viewModel.navigateBack() },
                            modifier = Modifier.testTag("admin_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAddTaskDialog = true },
                        modifier = Modifier.testTag("admin_add_task_topbar_btn")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add Task", tint = AmberPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier.testTag("admin_dashboard_screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Quick Stat Header Cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AdminStatCard(
                    title = "Pending Payouts",
                    value = "$pendingPayoutsCount",
                    color = if (pendingPayoutsCount > 0) AlertRed else SuccessGreen,
                    modifier = Modifier.weight(1f)
                )
                AdminStatCard(
                    title = "Active Tasks",
                    value = "${videoTasks.size}",
                    color = AmberPrimary,
                    modifier = Modifier.weight(1f)
                )
                AdminStatCard(
                    title = "Users",
                    value = "${maxOf(1, allUsers.size)}",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }

            // Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Tasks (${videoTasks.size})", fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Posts & Alerts (${adminPosts.size})", fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Payouts", fontWeight = FontWeight.Bold)
                            if (pendingPayoutsCount > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .background(AlertRed, CircleShape)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$pendingPayoutsCount",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTabIndex == 3,
                    onClick = { selectedTabIndex = 3 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Support Chat", fontWeight = FontWeight.Bold)
                            if (unAnsweredSupportCount > 0 || supportThreadsCount > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (unAnsweredSupportCount > 0) AlertRed else PrimaryBlue,
                                            CircleShape
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (unAnsweredSupportCount > 0) "$unAnsweredSupportCount new" else "$supportThreadsCount",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.SupportAgent, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTabIndex == 4,
                    onClick = { selectedTabIndex = 4 },
                    text = { Text("Users", fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTabIndex == 5,
                    onClick = { selectedTabIndex = 5 },
                    text = { Text("PC / Laptop", fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.Laptop, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTabIndex == 6,
                    onClick = { selectedTabIndex = 6 },
                    text = { Text("Google Drive Server", fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            // Tab Content
            when (selectedTabIndex) {
                0 -> TasksTabContent(
                    tasks = videoTasks,
                    onAddTask = { showAddTaskDialog = true },
                    onTogglePinTask = { taskId ->
                        viewModel.togglePinVideoTask(taskId)
                        Toast.makeText(context, "Updated task pin status!", Toast.LENGTH_SHORT).show()
                    },
                    onDeleteTask = { viewModel.adminDeleteTask(it) }
                )
                1 -> AdminPostsTabContent(
                    posts = adminPosts,
                    onPublishPost = { title, msg, tab, type, actionUrl, imgUrl, isPinned ->
                        viewModel.addAdminPost(title, msg, tab, type, actionUrl, imgUrl, isPinned)
                        Toast.makeText(context, "Published to $tab tab & sent instant notification!", Toast.LENGTH_SHORT).show()
                    },
                    onTogglePinPost = { postId ->
                        viewModel.togglePinAdminPost(postId)
                        Toast.makeText(context, "Updated post pin status!", Toast.LENGTH_SHORT).show()
                    },
                    onDeletePost = { viewModel.deleteAdminPost(it) }
                )
                2 -> PayoutsTabContent(
                    requests = payoutRequests,
                    onApprove = { id, note ->
                        viewModel.approvePayout(id, note)
                        Toast.makeText(context, "Payout Approved! Tap 'Done' after sending payment.", Toast.LENGTH_SHORT).show()
                    },
                    onComplete = { id, note ->
                        viewModel.completePayout(id, note)
                        Toast.makeText(context, "Payment marked as DONE! User notified.", Toast.LENGTH_SHORT).show()
                    },
                    onReject = { id, reason ->
                        viewModel.rejectPayout(id, reason)
                        Toast.makeText(context, "Payout Rejected & Coins Refunded.", Toast.LENGTH_SHORT).show()
                    }
                )
                3 -> SupportChatTabContent(
                    supportMessages = supportMessages,
                    allUsers = allUsers,
                    onOpenUserChat = { userId, userEmail, userName ->
                        activeChatUser = Triple(userId, userEmail, userName)
                    }
                )
                4 -> UsersTabContent(
                    users = allUsers,
                    tasks = videoTasks,
                    currentBalance = walletBalance,
                    onOpenUserDetail = { user ->
                        selectedDetailUserEmail = user.email
                    },
                    onChatWithUser = { user ->
                        activeChatUser = Triple(user.userId, user.email, user.name.ifBlank { user.userId })
                    }
                )
                5 -> LaptopAccessTabContent(
                    context = context,
                    serverRunning = serverRunning,
                    serverUrl = serverUrl,
                    onToggleServer = { enabled ->
                        if (enabled) {
                            com.example.admin.AdminWebServer.startServer(context, viewModel.getDataStoreManager()) { running, url ->
                                viewModel.updateAdminServerState(running, url)
                            }
                        } else {
                            com.example.admin.AdminWebServer.stopServer { running, url ->
                                viewModel.updateAdminServerState(running, url)
                            }
                        }
                    }
                )
                6 -> GoogleDriveServerTabContent(
                    context = context,
                    cloudServerUrl = cloudServerUrl,
                    cloudServerStatus = cloudServerStatus,
                    remoteAppUpdate = remoteAppUpdate,
                    updateDriveFolderUrl = updateDriveFolderUrl,
                    appDownloadUrl = appDownloadUrl,
                    fullScreenOpeningOverlay = fullScreenOpeningOverlay,
                    onToggleFullScreenOpeningOverlay = { viewModel.toggleFullScreenOpeningOverlay(it) },
                    onSaveUpdateFolderUrl = { folderUrl ->
                        viewModel.saveUpdateDriveFolderUrl(folderUrl)
                        Toast.makeText(context, "Google Drive 'update' folder synced!", Toast.LENGTH_SHORT).show()
                    },
                    onSaveAppDownloadUrl = { downloadUrl ->
                        viewModel.saveAppDownloadUrl(downloadUrl)
                        Toast.makeText(context, "App Download Link saved & synced!", Toast.LENGTH_SHORT).show()
                    },
                    onSaveUrl = { viewModel.saveCloudServerUrl(it) },
                    onTestConnection = { onResult -> viewModel.testGoogleDriveConnection(onResult) },
                    onSyncNow = { onResult -> viewModel.syncWithGoogleDriveServer(onResult) }
                )
            }
        }
    }

    // Add Video Task Dialog
    if (showAddTaskDialog) {
        AddVideoTaskDialog(
            onDismiss = { showAddTaskDialog = false },
            onTaskAdded = { newTask ->
                viewModel.addVideoTask(newTask)
                showAddTaskDialog = false
            }
        )
    }

    // Active Admin Support Chat Dialog with User
    activeChatUser?.let { (targetUserId, targetEmail, targetName) ->
        val threadMessages = remember(supportMessages, targetUserId, targetEmail) {
            supportMessages.filter {
                (targetEmail.isNotBlank() && it.userEmail.equals(targetEmail, ignoreCase = true)) ||
                    (targetUserId.isNotBlank() && it.userId == targetUserId)
            }.sortedBy { it.timestampMillis }
        }
        SupportChatDialog(
            title = "Chat: ${targetName.ifBlank { targetUserId }}",
            subtitle = "User ID: $targetUserId • $targetEmail",
            messages = threadMessages,
            isAdminViewer = true,
            onSendMessage = { replyText ->
                viewModel.sendSupportMessage(
                    messageText = replyText,
                    targetUserEmail = targetEmail,
                    targetUserId = targetUserId,
                    targetUserName = targetName
                )
            },
            onDismiss = { activeChatUser = null }
        )
    }

    // User Detail Popup Dialog (shows Coin Balance + Edit Coins button + Task Stats)
    selectedDetailUserEmail?.let { detailEmail ->
        val detailUser = allUsers.find { it.email.equals(detailEmail, ignoreCase = true) }
            ?: UserProfile("user_app", detailEmail, "VIP Watcher", coinsBalance = walletBalance)
        UserDetailDialog(
            user = detailUser,
            tasks = videoTasks,
            onEditCoins = {
                adjustCoinsUser = detailUser
                adjustCoinsInput = detailUser.coinsBalance.toString()
            },
            onChatWithUser = {
                selectedDetailUserEmail = null
                activeChatUser = Triple(detailUser.userId, detailUser.email, detailUser.name.ifBlank { detailUser.userId })
            },
            onDismiss = { selectedDetailUserEmail = null }
        )
    }

    // Adjust Coins Dialog
    if (adjustCoinsUser != null) {
        val user = adjustCoinsUser!!
        AlertDialog(
            onDismissRequest = { adjustCoinsUser = null },
            title = { Text("Adjust Coins for ${user.email}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter new total coin balance for this user account:")
                    OutlinedTextField(
                        value = adjustCoinsInput,
                        onValueChange = { adjustCoinsInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Coins Balance") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.MonetizationOn, null, tint = AmberPrimary) }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newCoins = adjustCoinsInput.toIntOrNull() ?: 0
                        viewModel.adminUpdateUserCoins(user.email, newCoins)
                        adjustCoinsUser = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AmberPrimary)
                ) {
                    Text("Save Coins", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { adjustCoinsUser = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AdminStatCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = color)
        }
    }
}

@Composable
private fun TasksTabContent(
    tasks: List<VideoTaskItem>,
    onAddTask: () -> Unit,
    onTogglePinTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Button(
                onClick = onAddTask,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("admin_add_task_btn"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AmberPrimary)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black)
                Spacer(modifier = Modifier.width(8.dp))
                Text("+ Add New YouTube Task in Realtime", fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }

        if (tasks.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No video tasks configured yet. Tap above to add one.")
                    }
                }
            }
        } else {
            items(tasks, key = { it.id }) { task ->
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = if (task.isPinned) 1.5.dp else 0.dp,
                            color = if (task.isPinned) AmberPrimary else Color.Transparent,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .testTag("admin_task_item_${task.id}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(70.dp, 44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            if (task.thumbnailUrl.isNotBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(task.thumbnailUrl).crossfade(true).build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Default.PlayArrow, null, tint = Color.Gray)
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (task.isPinned) {
                                    Box(
                                        modifier = Modifier
                                            .background(AmberPrimary, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = "📌 PINNED",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color.Black
                                        )
                                    }
                                }
                                if (task.maxCompletions > 0) {
                                    val limitHit = task.isCompletionLimitReached
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (limitHit) AlertRed.copy(alpha = 0.15f) else PrimaryBlue.copy(alpha = 0.15f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = if (limitHit) "🎯 LIMIT FULL (${task.completedCount}/${task.maxCompletions})"
                                            else "🎯 Clicks: ${task.completedCount}/${task.maxCompletions}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (limitHit) AlertRed else PrimaryBlue
                                        )
                                    }
                                }
                            }
                            if (task.isPinned || task.maxCompletions > 0) {
                                Spacer(modifier = Modifier.height(2.dp))
                            }
                            Text(
                                text = task.title,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "${task.channelName} • ${task.selectedDurationSeconds / 60} min",
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "+${task.rewardCoins} Coins",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = AmberPrimary
                            )
                        }

                        OutlinedButton(
                            onClick = { onTogglePinTask(task.id) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("admin_pin_task_${task.id}")
                        ) {
                            Text(
                                text = if (task.isPinned) "📌 Unpin" else "📌 Pin",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (task.isPinned) AmberDark else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        IconButton(
                            onClick = { onDeleteTask(task.id) },
                            modifier = Modifier.testTag("admin_delete_task_${task.id}")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = AlertRed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PayoutsTabContent(
    requests: List<PayoutRequest>,
    onApprove: (String, String) -> Unit,
    onComplete: (String, String) -> Unit,
    onReject: (String, String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (requests.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Box(modifier = Modifier.padding(30.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Payments, contentDescription = null, tint = AmberPrimary, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("No payout requests yet.", fontWeight = FontWeight.Bold)
                            Text("When users request cash withdrawal, it appears here instantly.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            items(requests, key = { it.id }) { req ->
                val safeCoins = if (req.amountCoins > 0) req.amountCoins else (req.amountInr * com.example.data.COINS_PER_INR).toInt().coerceAtLeast(1000)
                val safeInr = if (req.amountInr > 0.0) req.amountInr else (safeCoins.toDouble() / com.example.data.COINS_PER_INR)
                val formattedInr = String.format(Locale.US, "%.2f", safeInr)

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().testTag("admin_payout_item_${req.id}")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = req.userEmail,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Box(
                                modifier = Modifier
                                    .background(
                                        when (req.status) {
                                            PayoutStatus.PENDING -> AmberPrimary.copy(alpha = 0.2f)
                                            PayoutStatus.APPROVED -> PrimaryBlue.copy(alpha = 0.2f)
                                            PayoutStatus.COMPLETED -> SuccessGreen.copy(alpha = 0.2f)
                                            PayoutStatus.REJECTED -> AlertRed.copy(alpha = 0.2f)
                                        },
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = when (req.status) {
                                        PayoutStatus.PENDING -> "PENDING"
                                        PayoutStatus.APPROVED -> "APPROVED • PAYING"
                                        PayoutStatus.COMPLETED -> "DONE (PAID ✓)"
                                        PayoutStatus.REJECTED -> "REJECTED"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = when (req.status) {
                                        PayoutStatus.PENDING -> AmberDark
                                        PayoutStatus.APPROVED -> PrimaryBlue
                                        PayoutStatus.COMPLETED -> SuccessGreen
                                        PayoutStatus.REJECTED -> AlertRed
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$safeCoins Coins → ₹$formattedInr INR",
                                fontWeight = FontWeight.ExtraBold,
                                color = SuccessGreen,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "${req.method}: ${req.destination}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (!req.adminNote.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Status Note: ${req.adminNote}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        when (req.status) {
                            PayoutStatus.PENDING -> {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            onApprove(
                                                req.id,
                                                "Approved $safeCoins Coins (₹$formattedInr) via ${req.method}"
                                            )
                                        },
                                        modifier = Modifier.weight(1f).height(42.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = AmberPrimary)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Approve ($safeCoins c)", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }

                                    OutlinedButton(
                                        onClick = { onReject(req.id, "Declined by Admin") },
                                        modifier = Modifier.weight(1f).height(42.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = null, tint = AlertRed, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Reject & Refund", color = AlertRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                            PayoutStatus.APPROVED -> {
                                Spacer(modifier = Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(PrimaryBlue.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = "Step 2: Send ₹$formattedInr ($safeCoins Coins) to ${req.method} (${req.destination}), then tap 'Done (Payment Sent)' below so the user knows payment is completed!",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        onComplete(
                                            req.id,
                                            "Payment of ₹$formattedInr ($safeCoins Coins) sent to ${req.method}: ${req.destination}"
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Done • Payment Sent ($safeCoins Coins = ₹$formattedInr)",
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                            PayoutStatus.COMPLETED -> {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Payment of $safeCoins Coins (₹$formattedInr) completed & user notified.",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = SuccessGreen
                                    )
                                }
                            }
                            PayoutStatus.REJECTED -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportChatTabContent(
    supportMessages: List<SupportMessage>,
    allUsers: List<UserProfile>,
    onOpenUserChat: (userId: String, userEmail: String, userName: String) -> Unit
) {
    val groupedThreads = remember(supportMessages, allUsers) {
        val map = supportMessages.groupBy { msg ->
            msg.userId.ifBlank { msg.userEmail.lowercase() }
        }
        map.entries.map { (key, msgs) ->
            val sorted = msgs.sortedBy { it.timestampMillis }
            val latest = sorted.last()
            val matchedUser = allUsers.find {
                it.userId == latest.userId || it.email.equals(latest.userEmail, ignoreCase = true)
            }
            val displayId = latest.userId.ifBlank { matchedUser?.userId ?: key }
            val displayEmail = latest.userEmail.ifBlank { matchedUser?.email ?: "" }
            val displayName = latest.userName.ifBlank { matchedUser?.name?.ifBlank { displayId } ?: displayId }
            val waitingForAdmin = latest.senderRole == "USER"
            Triple(Triple(displayId, displayEmail, displayName), sorted, waitingForAdmin)
        }.sortedWith(
            compareByDescending<Triple<Triple<String, String, String>, List<SupportMessage>, Boolean>> { it.third }
                .thenByDescending { it.second.lastOrNull()?.timestampMillis ?: 0L }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (groupedThreads.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Box(modifier = Modifier.padding(32.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.SupportAgent,
                                contentDescription = null,
                                tint = PrimaryBlue,
                                modifier = Modifier.size(42.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("No Support Messages Yet", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "When any user sends a query from Support Chat, it will appear here under their User ID.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            items(groupedThreads, key = { it.first.first + "_" + it.first.second }) { (userInfo, msgs, waitingForAdmin) ->
                val (userId, userEmail, userName) = userInfo
                val lastMsg = msgs.last()
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = if (waitingForAdmin) 1.5.dp else 1.dp,
                            color = if (waitingForAdmin) AmberPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { onOpenUserChat(userId, userEmail, userName) }
                        .testTag("admin_support_thread_$userId")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .background(
                                    if (waitingForAdmin) AmberPrimary.copy(alpha = 0.2f) else PrimaryBlue.copy(alpha = 0.15f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Chat,
                                contentDescription = null,
                                tint = if (waitingForAdmin) AmberDark else PrimaryBlue,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ID: $userId • $userName",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (waitingForAdmin) {
                                    Box(
                                        modifier = Modifier
                                            .background(AlertRed, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "NEEDS REPLY",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .background(SuccessGreen.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "REPLIED",
                                            color = SuccessGreen,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            if (userEmail.isNotBlank()) {
                                Text(
                                    text = userEmail,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = (if (lastMsg.senderRole == "ADMIN") "You: " else "User: ") + lastMsg.message,
                                fontSize = 13.sp,
                                fontWeight = if (waitingForAdmin) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { onOpenUserChat(userId, userEmail, userName) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Reply", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsersTabContent(
    users: List<UserProfile>,
    tasks: List<VideoTaskItem>,
    currentBalance: Int,
    onOpenUserDetail: (UserProfile) -> Unit,
    onChatWithUser: (UserProfile) -> Unit
) {
    val effectiveUsers = if (users.isEmpty()) {
        listOf(UserProfile("user_app", "guest@watchearn.com", "VIP Watcher", coinsBalance = currentBalance))
    } else users

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(effectiveUsers, key = { it.email }) { user ->
            val completedIdsSet = remember(user.completedTaskIdsJson, user.taskLocksJson, user.transactionsJson, tasks) {
                val set = mutableSetOf<String>()
                try {
                    val arr = org.json.JSONArray(user.completedTaskIdsJson)
                    for (i in 0 until arr.length()) {
                        val id = arr.optString(i)
                        if (id.isNotBlank()) set.add(id)
                    }
                } catch (_: Exception) {}
                try {
                    val locks = org.json.JSONObject(user.taskLocksJson)
                    val keys = locks.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (locks.optLong(k, 0L) > 0L) set.add(k)
                    }
                } catch (_: Exception) {}
                set
            }
            val totalCompletedStat = maxOf(user.completedTasksCount, completedIdsSet.size)

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenUserDetail(user) }
                    .testTag("admin_user_${user.email}")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(AmberPrimary.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.People, contentDescription = null, tint = AmberDark, modifier = Modifier.size(22.dp))
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (user.name.isNotBlank()) "${user.name} (${user.email})" else user.email,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append("ID: ${user.userId}")
                                if (user.referralCode.isNotBlank()) {
                                    append(" • Refer Key: ${user.referralCode}")
                                }
                                append(" • Tasks Done: $totalCompletedStat")
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Tap to view User Details & Edit Coins",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AmberPrimary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { onChatWithUser(user) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Chat", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PrimaryBlue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserDetailDialog(
    user: UserProfile,
    tasks: List<VideoTaskItem>,
    onEditCoins: () -> Unit,
    onChatWithUser: () -> Unit,
    onDismiss: () -> Unit
) {
    val completedIdsSet = remember(user.completedTaskIdsJson, user.taskLocksJson, user.transactionsJson, tasks) {
        val set = mutableSetOf<String>()
        try {
            val arr = org.json.JSONArray(user.completedTaskIdsJson)
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id.isNotBlank()) set.add(id)
            }
        } catch (_: Exception) {}
        try {
            val locks = org.json.JSONObject(user.taskLocksJson)
            val keys = locks.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (locks.optLong(k, 0L) > 0L) set.add(k)
            }
        } catch (_: Exception) {}
        try {
            val txArr = org.json.JSONArray(user.transactionsJson)
            for (i in 0 until txArr.length()) {
                val txObj = txArr.optJSONObject(i) ?: continue
                val txTitle = txObj.optString("title", "")
                if (txTitle.contains("Continuous Watch", ignoreCase = true)) {
                    tasks.forEach { t ->
                        if (txTitle.contains(t.title, ignoreCase = true)) {
                            set.add(t.id)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        set
    }

    val likedTaskIdsSet = remember(user.likedTasksJson) {
        val set = mutableSetOf<String>()
        try {
            val arr = org.json.JSONArray(user.likedTasksJson)
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id.isNotBlank()) set.add(id)
            }
        } catch (_: Exception) {}
        set
    }

    val commentCountsMap = remember(user.commentCountsJson) {
        val map = mutableMapOf<String, Int>()
        try {
            val obj = org.json.JSONObject(user.commentCountsJson)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val count = obj.optInt(k, 0)
                if (count > 0) map[k] = count
            }
        } catch (_: Exception) {}
        map
    }

    val recentTransactions = remember(user.transactionsJson) {
        val list = mutableListOf<Triple<String, Int, Long>>()
        try {
            val arr = org.json.JSONArray(user.transactionsJson)
            for (i in 0 until minOf(arr.length(), 8)) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    Triple(
                        obj.optString("title", "Activity"),
                        obj.optInt("coins", 0),
                        obj.optLong("timestampMillis", 0L)
                    )
                )
            }
        } catch (_: Exception) {}
        list
    }

    val completedTasksList = tasks.filter { completedIdsSet.contains(it.id) }
    val pendingTasksList = tasks.filter { !completedIdsSet.contains(it.id) }
    val totalCommentsCount = commentCountsMap.values.sum()
    val totalCompletedStat = maxOf(user.completedTasksCount, completedTasksList.size)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = if (user.name.isNotBlank()) user.name else user.email,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "${user.email} • ID: ${user.userId}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Coin Balance & Edit Coins Header inside Popup
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AmberPrimary.copy(alpha = 0.14f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Wallet Balance",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${user.coinsBalance} Coins (≈ ₹${String.format(Locale.US, "%.2f", user.coinsBalance / com.example.data.COINS_PER_INR.toDouble())})",
                                fontWeight = FontWeight.ExtraBold,
                                color = AmberDark,
                                fontSize = 15.sp
                            )
                        }
                        Button(
                            onClick = onEditCoins,
                            colors = ButtonDefaults.buttonColors(containerColor = AmberPrimary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Default.MonetizationOn, contentDescription = null, tint = Color.Black, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit Coins", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }

                if (user.referralCode.isNotBlank() || user.referredByCode.isNotBlank()) {
                    Text(
                        text = buildString {
                            if (user.referralCode.isNotBlank()) append("Refer Key: ${user.referralCode}")
                            if (user.referredByCode.isNotBlank()) append(" • Invited by: ${user.referredByCode}")
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Summary Statistics Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("✅ Done", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$totalCompletedStat", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = SuccessGreen)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⏳ Pending", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${pendingTasksList.size}", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = AmberPrimary)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("👍 Liked", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${likedTaskIdsSet.size}", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = PrimaryBlue)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("💬 Comments", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$totalCommentsCount", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = AmberDark)
                    }
                }

                if (tasks.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "📊 User Task-by-Task Statistics:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = AmberPrimary
                        )
                        tasks.forEach { task ->
                            val isDone = completedIdsSet.contains(task.id)
                            val isLiked = likedTaskIdsSet.contains(task.id)
                            val commentCnt = commentCountsMap[task.id] ?: 0
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "• ${task.title}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isDone) "✓ Done" else "✗ Pending",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDone) SuccessGreen else AlertRed
                                    )
                                    Text(
                                        text = if (isLiked) "👍" else "",
                                        fontSize = 9.sp
                                    )
                                    Text(
                                        text = "💬$commentCnt/2",
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                if (recentTransactions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "🕒 Recent User Actions & Coin Log:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        recentTransactions.forEach { (txTitle, txCoins, _) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = txTitle,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (txCoins >= 0) "+${txCoins}c" else "${txCoins}c",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (txCoins >= 0) SuccessGreen else AlertRed
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onChatWithUser,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Chat with User", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun LaptopAccessTabContent(
    context: Context,
    serverRunning: Boolean,
    serverUrl: String,
    onToggleServer: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Remote PC / Laptop Web Dashboard", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Control Kingo King from any laptop browser", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Switch(
                        checked = serverRunning,
                        onCheckedChange = { onToggleServer(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = SuccessGreen)
                    )
                }

                if (serverRunning && serverUrl.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SuccessGreen.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .border(1.dp, SuccessGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Text("Open this URL on your laptop/computer:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SuccessGreen)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = serverUrl,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 17.sp,
                                    color = Color.White
                                )

                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Admin URL", serverUrl))
                                        Toast.makeText(context, "URL copied to clipboard!", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL", tint = SuccessGreen)
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "Turn ON the switch above to start the embedded web server. Your phone and laptop should be on the same Wi-Fi or hotspot network.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What can you do from your Laptop?", fontWeight = FontWeight.Bold)
                Text("• 🎬 Add and delete video tasks with instant real-time sync into user's app.", fontSize = 13.sp)
                Text("• 💸 View live incoming payout requests and approve / pay with 1 click.", fontSize = 13.sp)
                Text("• 👥 View all registered user accounts and modify coin balances.", fontSize = 13.sp)
                Text("• ⏱️ Realtime WebSocket/Polling automatically keeps phone and laptop in sync.", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun GoogleDriveServerTabContent(
    context: Context,
    cloudServerUrl: String,
    cloudServerStatus: String,
    remoteAppUpdate: com.example.data.AppUpdateInfo?,
    updateDriveFolderUrl: String,
    appDownloadUrl: String,
    fullScreenOpeningOverlay: Boolean = true,
    onToggleFullScreenOpeningOverlay: (Boolean) -> Unit = {},
    onSaveUpdateFolderUrl: (String) -> Unit,
    onSaveAppDownloadUrl: (String) -> Unit,
    onSaveUrl: (String) -> Unit,
    onTestConnection: ((Boolean, String) -> Unit) -> Unit,
    onSyncNow: ((Boolean, String) -> Unit) -> Unit
) {
    var urlInput by remember(cloudServerUrl) {
        mutableStateOf(cloudServerUrl.ifBlank { com.example.data.DataStoreManager.DEFAULT_ADMIN_CLOUD_SERVER_URL })
    }
    var updateFolderInput by remember(updateDriveFolderUrl) { mutableStateOf(updateDriveFolderUrl) }
    var appDownloadInput by remember(appDownloadUrl) { mutableStateOf(appDownloadUrl) }
    var isTesting by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isSuccessStatus by remember { mutableStateOf(true) }

    val isConnected = cloudServerStatus.contains("Connected", ignoreCase = true) ||
            cloudServerStatus.contains("Synced", ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Slate800)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    if (isConnected) SuccessGreen.copy(alpha = 0.2f) else AmberPrimary.copy(alpha = 0.2f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = if (isConnected) SuccessGreen else AmberPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Google Drive 24/7 Cloud Server",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 15.sp
                            )
                            Text(
                                text = if (isConnected) "Online • Synced" else "Setup / Disconnected",
                                fontSize = 11.sp,
                                color = if (isConnected) SuccessGreen else AmberPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .background(
                                if (isConnected) SuccessGreen.copy(alpha = 0.15f) else AmberPrimary.copy(alpha = 0.15f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isConnected) "ACTIVE" else "LOCAL ONLY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isConnected) SuccessGreen else AmberPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Status: $cloudServerStatus",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
            }
        }

        // URL Input & Configuration Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Google Drive Web App URL",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )

                Text(
                    text = "User app kisi bhi internet connection se aapke Google Drive mein data save karegi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text("https://script.google.com/macros/s/.../exec", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = false,
                    maxLines = 3,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val text = clip.getItemAt(0).text?.toString() ?: ""
                                    if (text.isNotBlank()) {
                                        urlInput = text.trim()
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = AmberPrimary)
                        }
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveUrl(urlInput.trim())
                            Toast.makeText(context, "Server URL Saved!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save URL", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            isTesting = true
                            statusMessage = "Testing Google Drive connection..."
                            onTestConnection { success, msg ->
                                isTesting = false
                                isSuccessStatus = success
                                statusMessage = msg
                            }
                        },
                        enabled = !isTesting,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test Link")
                    }
                }

                // Two-way sync button
                Button(
                    onClick = {
                        isSyncing = true
                        statusMessage = "Syncing with Google Drive..."
                        onSyncNow { success, msg ->
                            isSyncing = false
                            isSuccessStatus = success
                            statusMessage = msg
                        }
                    },
                    enabled = !isSyncing && urlInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null, tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sync Now (Two-Way Sync)", color = Color.White, fontWeight = FontWeight.Bold)
                }

                statusMessage?.let { msg ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSuccessStatus) SuccessGreen.copy(alpha = 0.12f) else AlertRed.copy(alpha = 0.12f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Text(
                            text = msg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSuccessStatus) SuccessGreen else AlertRed
                        )
                    }
                }
            }
        }

        // Full Screen YouTube Opening Overlay Toggle Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Full Screen Opening / Floating Pill Toggle",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = PrimaryBlue
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "ON: Video play time par upar ka chhota floating pill hide rahega, sirf neeche ka suggested lock bar active rahega. OFF: Video ke upar chhota floating timer pill bhi dikhega.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = fullScreenOpeningOverlay,
                    onCheckedChange = { onToggleFullScreenOpeningOverlay(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = PrimaryBlue)
                )
            }
        }

        // Mandatory App Update ("update" Google Drive Folder) Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Google Drive 'update' Folder (Mandatory App Update)",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    color = PrimaryBlue
                )
                Text(
                    text = "Apne Google Drive par 'update' naam ka folder banayein. Jab bhi aap us folder mein naya APK dalenge ya purana APK hata kar naya APK dalenge, sabhi users ke app khulte hi 'Please Update' ka mandatory popup aayega (Update ya Cancel).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                if (remoteAppUpdate != null && remoteAppUpdate.hasUpdate) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SuccessGreen.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "✅ Active Update APK Detected in 'update' Folder:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                            Text(
                                text = "File: ${remoteAppUpdate.fileName} (${String.format(Locale.US, "%.1f MB", remoteAppUpdate.fileSize / (1024.0 * 1024.0))})",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "Status: Currently no APK inside 'update' folder (Normal app launch active).",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = updateFolderInput,
                    onValueChange = { updateFolderInput = it },
                    label = { Text("Optional: Direct 'update' Folder or APK Link") },
                    placeholder = { Text("https://drive.google.com/drive/folders/...", fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = { onSaveUpdateFolderUrl(updateFolderInput.trim()) },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save 'update' Folder Link & Check APK", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        // App Download Link (Included in Refer & Share messages) Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "App Download Link (For Refer & Share)",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    color = AmberPrimary
                )
                Text(
                    text = "Yahan apne Kingo King User APK ka download link (Google Drive / Website link) dalein. Jab bhi koi user apna 6-Digit Refer Key share karega, yeh download link aur unka Refer Key automatic message ke saath jayega.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                OutlinedTextField(
                    value = appDownloadInput,
                    onValueChange = { appDownloadInput = it },
                    label = { Text("User App Download Link (APK / Drive URL)") },
                    placeholder = { Text("https://drive.google.com/file/d/...", fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val cleanLink = com.example.data.DataStoreManager.normalizeAppDownloadUrl(appDownloadInput)
                            appDownloadInput = cleanLink
                            onSaveAppDownloadUrl(cleanLink)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save App Link", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            val cleanLink = com.example.data.DataStoreManager.normalizeAppDownloadUrl(appDownloadInput)
                            appDownloadInput = cleanLink
                            try {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(cleanLink)
                                ).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open link: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = "Test App Link", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test Link", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 1-Click Copy Apps Script Code Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Google Apps Script Server Code",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )

                    Button(
                        onClick = {
                            val script = com.example.admin.CloudDriveServerManager.getGoogleAppsScriptTemplate()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Google Apps Script", script))
                            Toast.makeText(context, "Full Server Script Copied to Clipboard!", Toast.LENGTH_LONG).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberPrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Script (1-Click)", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }

                Text(
                    text = "Yeh Google Apps Script aapke Google Drive mein 'KingoKing_Server' folder banayega jisme tasks.json, users.json, aur payouts.json auto-store honge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }

        // Step by Step Setup Instructions Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Google Drive Server Setup Guide (2 Minutes):",
                    fontWeight = FontWeight.Bold,
                    color = AmberPrimary
                )
                Text(
                    text = "1. Apne phone ya computer mein https://script.google.com open karein.\n" +
                            "2. 'New project' par click karein.\n" +
                            "3. Upar diye gaye 'Copy Script (1-Click)' button se poora code copy karke wahan paste karein.\n" +
                            "4. Top-right mein 'Deploy' -> 'New deployment' par click karein.\n" +
                            "5. Gear icon ⚙️ par click karke 'Web app' choose karein.\n" +
                            "6. 'Execute as': 'Me' aur 'Who has access': 'Anyone' rakhein.\n" +
                            "7. 'Deploy' par click karein aur permission allow karein.\n" +
                            "8. Jo Web App URL milega, use copy karke yahan paste karein aur 'Save URL' dabayein!\n\n" +
                            "🎉 Ab aapka Google Drive ek 24/7 free cloud server ban gaya hai! Normal users jo bhi task karenge ya payout mangenge, wo automatically aapke drive par folder banakar sync hota rahega!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun AdminPostsTabContent(
    posts: List<AdminPostItem>,
    onPublishPost: (title: String, message: String, targetTab: String, postType: String, actionUrl: String, imageUrl: String, isPinned: Boolean) -> Unit,
    onTogglePinPost: (String) -> Unit,
    onDeletePost: (String) -> Unit
) {
    var pushTitleInput by remember { mutableStateOf("") }
    var pushMessageInput by remember { mutableStateOf("") }
    var pushSuccessStatus by remember { mutableStateOf<String?>(null) }

    var titleInput by remember { mutableStateOf("") }
    var messageInput by remember { mutableStateOf("") }
    var selectedTargetTab by remember { mutableStateOf("HOME") }
    var selectedPostType by remember { mutableStateOf("BANNER") }
    var actionUrlInput by remember { mutableStateOf("") }
    var imageUrlInput by remember { mutableStateOf("") }
    var isPinnedInput by remember { mutableStateOf(true) }

    val targetTabs = listOf(
        "HOME" to "Home Tab",
        "TASKS" to "Tasks Tab",
        "WALLET" to "Wallet Tab",
        "ME" to "Me Tab"
    )
    val postTypes = listOf(
        "BANNER" to "📢 Banner",
        "ALERT" to "🚨 Popup Alert",
        "POST" to "📌 Post Card"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Dedicated Push Notification Only Card (No Banner / No Post in App)
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, AmberPrimary.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = AmberPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Send User Push Notification Only",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                    Text(
                        text = "Send a direct push notification to users without adding any banner or post inside the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )

                    OutlinedTextField(
                        value = pushTitleInput,
                        onValueChange = {
                            pushTitleInput = it
                            pushSuccessStatus = null
                        },
                        label = { Text("Notification Title *") },
                        placeholder = { Text("e.g. 🎉 Bonus Tasks Are Live Now!") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_push_only_title_input")
                    )

                    OutlinedTextField(
                        value = pushMessageInput,
                        onValueChange = {
                            pushMessageInput = it
                            pushSuccessStatus = null
                        },
                        label = { Text("Notification Message *") },
                        placeholder = { Text("Enter message for user push notification...") },
                        minLines = 2,
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_push_only_message_input")
                    )

                    if (!pushSuccessStatus.isNullOrBlank()) {
                        Text(
                            text = pushSuccessStatus!!,
                            color = SuccessGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            if (pushTitleInput.isNotBlank()) {
                                onPublishPost(
                                    pushTitleInput.trim(),
                                    pushMessageInput.trim(),
                                    "NONE",
                                    "PUSH_ONLY",
                                    "",
                                    "",
                                    false
                                )
                                pushTitleInput = ""
                                pushMessageInput = ""
                                pushSuccessStatus = "✅ Push notification sent to all users!"
                            }
                        },
                        enabled = pushTitleInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("admin_send_push_only_button")
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Send Push Notification Only",
                            color = Color.Black,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Publish Banner, Post or Alert to User App",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        text = "Select any tab in the User App to display a custom banner, post, or popup alert. Users receive an instant push notification as soon as you publish!",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )

                    Text(
                        text = "1. Select User App Tab:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AmberPrimary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        targetTabs.forEach { (key, label) ->
                            val isSelected = selectedTargetTab == key
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) AmberPrimary else Color.White.copy(alpha = 0.08f))
                                    .clickable { selectedTargetTab = key }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label.substringBefore(" "),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.Black else Color.White
                                )
                            }
                        }
                    }

                    Text(
                        text = "2. Select Display Type:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AmberPrimary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        postTypes.forEach { (key, label) ->
                            val isSelected = selectedPostType == key
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isSelected && key == "ALERT" -> AlertRed
                                            isSelected -> AmberPrimary
                                            else -> Color.White.copy(alpha = 0.08f)
                                        }
                                    )
                                    .clickable { selectedPostType = key }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected && key != "ALERT") Color.Black else Color.White
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Banner / Alert Heading *") },
                        placeholder = { Text("e.g. 🔥 Special Weekend Bonus Live!") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        label = { Text("Message / Announcement Text *") },
                        placeholder = { Text("Enter message to show on the selected User App tab...") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = imageUrlInput,
                        onValueChange = { imageUrlInput = it },
                        label = { Text("Banner Image URL (Optional)") },
                        placeholder = { Text("https://example.com/banner.jpg") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = actionUrlInput,
                        onValueChange = { actionUrlInput = it },
                        label = { Text("Click Action Link URL (Optional)") },
                        placeholder = { Text("https://t.me/yourchannel or YouTube link") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "📌 Pin Banner / Post to Top",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Keep this item fixed at the top even when new posts are published",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = isPinnedInput,
                            onCheckedChange = { isPinnedInput = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = AmberPrimary
                            )
                        )
                    }

                    Button(
                        onClick = {
                            if (titleInput.isNotBlank()) {
                                onPublishPost(
                                    titleInput,
                                    messageInput,
                                    selectedTargetTab,
                                    selectedPostType,
                                    actionUrlInput,
                                    imageUrlInput,
                                    isPinnedInput
                                )
                                titleInput = ""
                                messageInput = ""
                                actionUrlInput = ""
                                imageUrlInput = ""
                            }
                        },
                        enabled = titleInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AmberPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Publish to $selectedTargetTab & Notify Users",
                            color = Color.Black,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "Active Banners, Posts & Alerts (${posts.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }

        items(posts, key = { it.id }) { post ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = if (post.isPinned) 1.5.dp else 0.dp,
                        color = if (post.isPinned) AmberPrimary else Color.Transparent,
                        shape = RoundedCornerShape(14.dp)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (post.isPinned) {
                                Box(
                                    modifier = Modifier
                                        .background(AmberPrimary, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "📌 PINNED",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.Black
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (post.postType == "ALERT") AlertRed.copy(alpha = 0.2f) else AmberPrimary.copy(alpha = 0.2f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${post.postType} • Tab: ${post.targetTab}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (post.postType == "ALERT") AlertRed else AmberPrimary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = post.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (post.message.isNotBlank()) {
                            Text(
                                text = post.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { onTogglePinPost(post.id) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("admin_pin_post_${post.id}")
                    ) {
                        Text(
                            text = if (post.isPinned) "📌 Unpin" else "📌 Pin",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (post.isPinned) AmberDark else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(onClick = { onDeletePost(post.id) }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Post",
                            tint = AlertRed
                        )
                    }
                }
            }
        }
    }
}
