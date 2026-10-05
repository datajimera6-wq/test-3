package com.example.ui.components

import android.app.Activity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.SecurityUpdateGood
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.data.AppUpdateInfo
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.SuccessGreen
import com.example.util.ApkCompatibilityReport
import com.example.util.ApkUpdateInstaller
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val AmberGold = AmberPrimary
private val DarkCard = Slate900
private val DarkSurfaceVariant = Slate800
private val EmeraldGreen = SuccessGreen
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)

object ApkDownloadSession {
    var isDownloading by mutableStateOf(false)
    var progressPercent by mutableIntStateOf(0)
    var downloadedMb by mutableFloatStateOf(0f)
    var totalMb by mutableFloatStateOf(0f)
    var errorMessage by mutableStateOf<String?>(null)
    var downloadedApkFile by mutableStateOf<File?>(null)
    private var downloadJob: Job? = null
    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun startDownload(context: android.content.Context, updateInfo: AppUpdateInfo, onComplete: (File) -> Unit) {
        if (isDownloading) return
        val existing = downloadedApkFile
        if (existing != null && existing.exists() && existing.length() > 50_000L) {
            onComplete(existing)
            return
        }
        isDownloading = true
        errorMessage = null
        progressPercent = 2
        downloadJob?.cancel()
        downloadJob = sessionScope.launch {
            val result = ApkUpdateInstaller.downloadUpdateApk(
                context = context.applicationContext,
                updateInfo = updateInfo,
                onProgress = { pct, dlMb, totMb ->
                    progressPercent = pct
                    downloadedMb = dlMb
                    totalMb = totMb
                }
            )
            isDownloading = false
            result.onSuccess { apkFile ->
                downloadedApkFile = apkFile
                onComplete(apkFile)
            }.onFailure { _ ->
                errorMessage = "Update failed. Please try again."
            }
        }
    }
}

@Composable
fun MandatoryUpdateDialog(
    updateInfo: AppUpdateInfo,
    onMarkUpdateInstalled: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val isDownloading = ApkDownloadSession.isDownloading
    val progressPercent = ApkDownloadSession.progressPercent
    val downloadedMb = ApkDownloadSession.downloadedMb
    val totalMb = ApkDownloadSession.totalMb
    val errorMessage = ApkDownloadSession.errorMessage
    val downloadedApkFile = ApkDownloadSession.downloadedApkFile
    var compatibilityReport by remember { mutableStateOf<ApkCompatibilityReport?>(null) }
    var waitingForInstallPermission by remember { mutableStateOf(false) }
    var installAttemptedInSession by remember { mutableStateOf(false) }
    var showReplaceExistingHelper by remember { mutableStateOf(false) }

    val animatedProgress by animateFloatAsState(
        targetValue = (progressPercent / 100f).coerceIn(0f, 1f),
        label = "apk_download_progress"
    )

    // If this app instance was already updated after the Drive APK was uploaded, mark installed immediately
    LaunchedEffect(updateInfo.signature) {
        if (ApkUpdateInstaller.isAppAlreadyUpToDate(context, updateInfo)) {
            onMarkUpdateInstalled(updateInfo.signature)
        }
    }

    // Handle returning from "Install Unknown Apps" settings OR returning from system PackageInstaller
    DisposableEffect(lifecycleOwner, waitingForInstallPermission, downloadedApkFile, installAttemptedInSession) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (ApkUpdateInstaller.isAppAlreadyUpToDate(context, updateInfo)) {
                    onMarkUpdateInstalled(updateInfo.signature)
                    return@LifecycleEventObserver
                }
                val apkFile = downloadedApkFile
                if (waitingForInstallPermission && apkFile != null && apkFile.exists() && ApkUpdateInstaller.canRequestPackageInstalls(context)) {
                    waitingForInstallPermission = false
                    installAttemptedInSession = true
                    val report = compatibilityReport ?: ApkUpdateInstaller.inspectApkCompatibility(context, apkFile)
                    compatibilityReport = report
                    if (report.requiresUninstallToReplace) {
                        showReplaceExistingHelper = true
                        ApkDownloadSession.errorMessage = "Existing app conflict detected. Tap 'Replace & Install Update' below to install cleanly."
                        ApkUpdateInstaller.replaceConflictingAppAndInstall(
                            context = context,
                            apkFile = apkFile,
                            targetPackageName = report.archivePackageName.ifBlank { context.packageName }
                        )
                    } else {
                        ApkUpdateInstaller.launchApkInstaller(context, apkFile, updateInfo.signature)
                    }
                } else if (installAttemptedInSession && apkFile != null && apkFile.exists()) {
                    // User returned from PackageInstaller and the package wasn't replaced yet
                    showReplaceExistingHelper = true
                    ApkDownloadSession.errorMessage = "Update failed. Please try again."
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun closeAppImmediately() {
        val activity = context as? Activity
        activity?.finishAffinity()
        exitProcess(0)
    }

    fun executeInstallForDownloadedApk(apkFile: File) {
        if (!ApkUpdateInstaller.canRequestPackageInstalls(context)) {
            waitingForInstallPermission = true
            ApkDownloadSession.errorMessage = "Please allow 'Install unknown apps' permission on the next screen to install the update."
            ApkUpdateInstaller.openInstallUnknownAppsSettings(context)
            return
        }
        val report = ApkUpdateInstaller.inspectApkCompatibility(context, apkFile)
        compatibilityReport = report

        installAttemptedInSession = true
        if (report.requiresUninstallToReplace) {
            showReplaceExistingHelper = true
            ApkDownloadSession.errorMessage = "Existing app conflict detected. Uninstalling old version first — your update is saved in Downloads!"
            ApkUpdateInstaller.replaceConflictingAppAndInstall(
                context = context,
                apkFile = apkFile,
                targetPackageName = report.archivePackageName.ifBlank { context.packageName }
            )
        } else {
            val launched = ApkUpdateInstaller.launchApkInstaller(context, apkFile, updateInfo.signature)
            if (!launched) {
                showReplaceExistingHelper = true
                ApkDownloadSession.errorMessage = "Tap 'Replace & Install Update' below to install cleanly."
                ApkUpdateInstaller.replaceConflictingAppAndInstall(
                    context = context,
                    apkFile = apkFile,
                    targetPackageName = report.archivePackageName.ifBlank { context.packageName }
                )
            }
        }
    }

    fun triggerInstallOrDownload() {
        val existingFile = downloadedApkFile
        if (existingFile != null && existingFile.exists() && existingFile.length() > 50_000L) {
            executeInstallForDownloadedApk(existingFile)
            return
        }

        ApkDownloadSession.startDownload(
            context = context,
            updateInfo = updateInfo,
            onComplete = { apkFile ->
                executeInstallForDownloadedApk(apkFile)
            }
        )
    }

    Dialog(
        onDismissRequest = { /* Mandatory update cannot be dismissed by tapping outside */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .widthIn(max = 400.dp)
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            AmberGold,
                            Color(0xFFFBBF24).copy(alpha = 0.5f),
                            AmberGold.copy(alpha = 0.8f)
                        )
                    ),
                    shape = RoundedCornerShape(26.dp)
                )
                .testTag("mandatory_update_dialog"),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF090E17)),
            elevation = CardDefaults.cardElevation(defaultElevation = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Glowing App Icon Badge
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    AmberGold.copy(alpha = 0.25f),
                                    AmberGold.copy(alpha = 0.05f)
                                )
                            )
                        )
                        .border(1.5.dp, AmberGold.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (downloadedApkFile != null) Icons.Default.SecurityUpdateGood else Icons.Default.SystemUpdate,
                        contentDescription = "App Update",
                        tint = AmberGold,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Update Status Pill
                Surface(
                    shape = RoundedCornerShape(50),
                    color = AmberGold.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AmberGold.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "NEW VERSION READY",
                        color = AmberGold,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "App Update Available",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "A new update is available with faster video tracking, new tasks, and improved security. Please update now to continue enjoying Kingo King.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8),
                    fontSize = 12.5.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Clean Feature Highlights Card (No raw APK or filename)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF111827),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F2937)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        UpdateFeatureRow(
                            icon = "⚡",
                            title = "Performance & Speed",
                            description = "Instant YouTube video detection & smooth tracking"
                        )
                        HorizontalDivider(color = Color(0xFF1F2937))
                        UpdateFeatureRow(
                            icon = "🎁",
                            title = "New Tasks & Rewards",
                            description = "More videos available with higher coin earnings"
                        )
                        HorizontalDivider(color = Color(0xFF1F2937))
                        UpdateFeatureRow(
                            icon = "🛡️",
                            title = "Security & Stability",
                            description = "Account protection and bug fixes included"
                        )
                    }
                }

                if (isDownloading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = AmberGold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Downloading Update...",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "$progressPercent%",
                                style = MaterialTheme.typography.labelMedium,
                                color = AmberGold,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(50)),
                            color = AmberGold,
                            trackColor = Color(0xFF1E293B)
                        )

                        if (downloadedMb > 0f) {
                            Spacer(modifier = Modifier.height(6.dp))
                            val mbStr = if (totalMb > 0f) {
                                String.format(Locale.US, "%.1f MB of %.1f MB", downloadedMb, totalMb)
                            } else {
                                String.format(Locale.US, "%.1f MB downloaded", downloadedMb)
                            }
                            Text(
                                text = mbStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AlertRed.copy(alpha = 0.14f))
                            .border(1.dp, AlertRed.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = AlertRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            lineHeight = 16.sp
                        )
                    }
                }

                val readyApk = downloadedApkFile
                if (readyApk != null && readyApk.exists() && (showReplaceExistingHelper || compatibilityReport?.requiresUninstallToReplace == true)) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val targetPkg = compatibilityReport?.archivePackageName?.ifBlank { context.packageName } ?: context.packageName
                            ApkUpdateInstaller.replaceConflictingAppAndInstall(
                                context = context,
                                apkFile = readyApk,
                                targetPackageName = targetPkg
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("replace_existing_app_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldGreen,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Autorenew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Replace & Install Update",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Buttons: Exit App & Update Now
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { closeAppImmediately() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("mandatory_update_cancel_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF1E293B),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Exit",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                    }

                    Button(
                        onClick = { triggerInstallOrDownload() },
                        enabled = !isDownloading,
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp)
                            .testTag("mandatory_update_confirm_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AmberGold,
                            contentColor = Color.Black,
                            disabledContainerColor = AmberGold.copy(alpha = 0.4f),
                            disabledContentColor = Color.Black.copy(alpha = 0.6f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                isDownloading -> "Updating..."
                                downloadedApkFile != null -> "Install Now"
                                errorMessage != null -> "Try Again"
                                else -> "Update Now"
                            },
                            fontWeight = FontWeight.Black,
                            fontSize = 13.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Update is required to access tasks and earn rewards.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun UpdateFeatureRow(
    icon: String,
    title: String,
    description: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = icon,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 12.sp
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )
        }
    }
}
