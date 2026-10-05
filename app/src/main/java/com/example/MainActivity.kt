package com.example

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.admin.AdminWebServer
import com.example.data.DataStoreManager
import com.example.service.NotificationChannels
import com.example.ui.components.AppBottomNavBar
import com.example.ui.components.NoInternetDialog
import com.example.ui.components.SuccessDialog
import com.example.ui.components.SupportChatDialog
import com.example.ui.components.TaskIncompleteDialog
import com.example.ui.components.isInternetAvailable
import com.example.ui.screens.AdminDashboardScreen
import com.example.ui.screens.AuthGateScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MeScreen
import com.example.ui.screens.SetupScreen
import com.example.ui.screens.TaskScreen
import com.example.ui.screens.TasksListScreen
import com.example.ui.screens.WalletScreen
import com.example.ui.theme.WatchEarnTheme
import com.example.viewmodel.AppScreen
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val currentIntentState = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentIntentState.value = intent
        enableEdgeToEdge()
        NotificationChannels.createChannels(this)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val isAdmin = BuildConfig.APP_ROLE == "ADMIN"
        if (isAdmin) {
            AdminWebServer.startServer(this, DataStoreManager(this)) { _, _ -> }
        }

        setContent {
            WatchEarnTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: MainViewModel = viewModel()
                    val context = LocalContext.current
                    val activeIntent by currentIntentState

                    LaunchedEffect(activeIntent) {
                        val currIntent = activeIntent
                        if (currIntent != null) {
                            val refParam = currIntent.data?.getQueryParameter("ref")?.trim()
                                ?: currIntent.data?.getQueryParameter("code")?.trim() ?: ""
                            if (refParam.length == 6 && refParam.all { it.isDigit() }) {
                                viewModel.savePendingReferralCode(refParam)
                            } else {
                                viewModel.scanForPendingReferralCode(context)
                            }
                            viewModel.handleIncomingIntent(currIntent)
                        }
                    }
                    var isOnline by remember { mutableStateOf(isInternetAvailable(context)) }
                    var forceNoInternetPopup by remember { mutableStateOf(!isOnline) }

                    // Real-time Network Callback + periodic check
                    DisposableEffect(context) {
                        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                        val callback = object : ConnectivityManager.NetworkCallback() {
                            override fun onAvailable(network: Network) {
                                isOnline = true
                                forceNoInternetPopup = false
                                viewModel.syncWithGoogleDriveServer { _, _ -> }
                            }

                            override fun onLost(network: Network) {
                                val stillConnected = isInternetAvailable(context)
                                isOnline = stillConnected
                                if (!stillConnected) {
                                    forceNoInternetPopup = true
                                }
                            }
                        }
                        try {
                            val req = NetworkRequest.Builder()
                                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                .build()
                            cm?.registerNetworkCallback(req, callback)
                        } catch (_: Exception) {}

                        onDispose {
                            try {
                                cm?.unregisterNetworkCallback(callback)
                            } catch (_: Exception) {}
                        }
                    }

                    LaunchedEffect(Unit) {
                        if (isInternetAvailable(context)) {
                            viewModel.syncWithGoogleDriveServer { _, _ -> }
                        }
                        while (true) {
                            val connected = isInternetAvailable(context)
                            if (connected && !isOnline) {
                                isOnline = true
                                forceNoInternetPopup = false
                                viewModel.syncWithGoogleDriveServer { _, _ -> }
                            } else if (!connected) {
                                isOnline = false
                                forceNoInternetPopup = true
                            }
                            delay(2500L)
                        }
                    }

                    if (isAdmin) {
                        AdminDashboardScreen(viewModel = viewModel)
                    } else {
                        WatchEarnApp(
                            viewModel = viewModel,
                            onRequireInternetPopup = { forceNoInternetPopup = true }
                        )
                    }

                    if (!isOnline || forceNoInternetPopup) {
                        NoInternetDialog(
                            onRetry = {
                                val connected = isInternetAvailable(context)
                                isOnline = connected
                                if (connected) {
                                    forceNoInternetPopup = false
                                    viewModel.syncWithGoogleDriveServer { _, _ -> }
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Mobile Data / Wi-Fi is still off. Please turn on your internet connection.",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentIntentState.value = intent
    }

    override fun onResume() {
        super.onResume()
        com.example.repository.WatchSessionRepository.setAppInForeground(true)
    }

    override fun onPause() {
        super.onPause()
        com.example.repository.WatchSessionRepository.setAppInForeground(false)
    }
}

@Composable
fun WatchEarnApp(
    viewModel: MainViewModel = viewModel(),
    onRequireInternetPopup: () -> Unit = {}
) {
    val currentUser by viewModel.currentUser.collectAsState()
    val currentScreen by viewModel.currentScreen.collectAsState()
    val showSuccessDialog by viewModel.showSuccessDialog.collectAsState()
    val showGlobalSupportChat by viewModel.showGlobalSupportChat.collectAsState()
    val activeRewardCoins by viewModel.activeRewardCoins.collectAsState()
    val videoTasks by viewModel.videoTasks.collectAsState()
    val taskIncompleteMessage by viewModel.taskIncompleteMessage.collectAsState()
    val remoteAppUpdate by viewModel.remoteAppUpdate.collectAsState()
    val installedUpdateSignature by viewModel.installedUpdateSignature.collectAsState()

    val pendingUpdate = remoteAppUpdate
    val context = androidx.compose.ui.platform.LocalContext.current
    val isAlreadyUpToDate = remember(pendingUpdate, installedUpdateSignature) {
        if (pendingUpdate == null || !pendingUpdate.hasUpdate) {
            true
        } else {
            com.example.util.ApkUpdateInstaller.isAppAlreadyUpToDate(
                context = context,
                updateInfo = pendingUpdate,
                installedSignature = installedUpdateSignature
            )
        }
    }

    LaunchedEffect(pendingUpdate?.signature, isAlreadyUpToDate) {
        if (pendingUpdate != null && pendingUpdate.hasUpdate) {
            if (isAlreadyUpToDate && installedUpdateSignature != pendingUpdate.signature) {
                viewModel.markAppUpdateInstalled(pendingUpdate.signature)
            }
        }
    }

    if (BuildConfig.APP_ROLE != "ADMIN" &&
        pendingUpdate != null &&
        pendingUpdate.hasUpdate &&
        !isAlreadyUpToDate &&
        (pendingUpdate.fileId.isNotBlank() || pendingUpdate.downloadUrl.isNotBlank())
    ) {
        com.example.ui.components.MandatoryUpdateDialog(
            updateInfo = pendingUpdate,
            onMarkUpdateInstalled = { sig ->
                viewModel.markAppUpdateInstalled(sig)
            }
        )
    }

    // Mandatory Login Gate: User must sign in or sign up before using Kingo King
    if (currentUser == null) {
        AuthGateScreen(
            viewModel = viewModel,
            onRequireInternetPopup = onRequireInternetPopup
        )
        return
    }

    val isPrimaryTab = currentScreen == AppScreen.TASKS ||
            currentScreen == AppScreen.HOME ||
            currentScreen == AppScreen.WALLET ||
            currentScreen == AppScreen.ME

    // Handle back button for secondary screens
    if (!isPrimaryTab) {
        BackHandler {
            viewModel.navigateBack()
        }
    }

    val activeTaskCount = videoTasks.count { !it.isCompleted }

    Scaffold(
        bottomBar = {
            if (isPrimaryTab) {
                AppBottomNavBar(
                    currentScreen = currentScreen,
                    onTabSelected = { selectedScreen ->
                        viewModel.switchTab(selectedScreen)
                    },
                    activeTaskCount = activeTaskCount
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            when (currentScreen) {
                AppScreen.HOME -> HomeScreen(viewModel = viewModel)
                AppScreen.TASKS -> TasksListScreen(viewModel = viewModel)
                AppScreen.WALLET -> WalletScreen(viewModel = viewModel)
                AppScreen.ME -> MeScreen(viewModel = viewModel)
                AppScreen.TASK, AppScreen.TASK_DETAIL -> HomeScreen(viewModel = viewModel)
                AppScreen.SETUP -> SetupScreen(viewModel = viewModel)
                AppScreen.DIAGNOSTICS -> DiagnosticsScreen(viewModel = viewModel)
                AppScreen.ADMIN -> {
                    if (BuildConfig.APP_ROLE == "ADMIN") {
                        AdminDashboardScreen(viewModel = viewModel)
                    } else {
                        HomeScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }

    if (showSuccessDialog) {
        SuccessDialog(
            rewardCoins = activeRewardCoins,
            onDismiss = { viewModel.dismissSuccessDialog() }
        )
    }

    if (showGlobalSupportChat) {
        val supportMessages by viewModel.supportMessages.collectAsState()
        SupportChatDialog(
            currentUser = currentUser,
            allMessages = supportMessages,
            onSendMessage = { msg -> viewModel.sendSupportMessage(msg) },
            onDismiss = { viewModel.dismissSupportChat() }
        )
    }

    taskIncompleteMessage?.let { msg ->
        TaskIncompleteDialog(
            message = msg,
            onDismiss = { viewModel.dismissTaskIncompleteMessage() }
        )
    }
}
