package com.example.util

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.MainActivity
import com.example.data.AppUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ApkCompatibilityReport(
    val isValidApk: Boolean,
    val archivePackageName: String = "",
    val archiveVersionCode: Long = 0L,
    val archiveVersionName: String = "",
    val installedVersionCode: Long = 0L,
    val hasSignatureConflict: Boolean = false,
    val hasVersionDowngrade: Boolean = false,
    val publicDownloadsPath: String = "Downloads/KingoKing_Update.apk"
) {
    val requiresUninstallToReplace: Boolean
        get() = hasSignatureConflict || hasVersionDowngrade
}

object ApkUpdateInstaller {

    private const val PREFS_NAME = "kingo_apk_update_tracker"
    private const val KEY_PRE_INSTALL_UPDATE_TIME = "pre_install_update_time"
    private const val KEY_LAST_RECORDED_APP_UPDATE_TIME = "last_recorded_app_update_time"
    private const val KEY_PENDING_SIGNATURE = "pending_update_signature"
    private const val KEY_ATTEMPTED_SIGNATURE = "attempted_update_signature"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostList = cookieStore.getOrPut(url.host) { mutableListOf() }
                synchronized(hostList) {
                    cookies.forEach { newCookie ->
                        hostList.removeAll { it.name == newCookie.name }
                        hostList.add(newCookie)
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val all = mutableListOf<Cookie>()
                cookieStore.forEach { (host, list) ->
                    if (url.host.endsWith(host) || host.endsWith(url.host)) {
                        synchronized(list) {
                            all.addAll(list)
                        }
                    }
                }
                return all
            }
        })
        .build()

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallUnknownAppsSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                val fallback = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }
        }
    }

    /**
     * Checks whether the current running app was ALREADY updated after the install was triggered
     * (or if the app's lastUpdateTime is newer than the Drive APK's timestamp).
     */
    fun didAppUpdateComplete(context: Context, updateInfo: AppUpdateInfo): Boolean {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentLastUpdateTime = pkgInfo.lastUpdateTime
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val preInstallTime = prefs.getLong(KEY_PRE_INSTALL_UPDATE_TIME, 0L)
            val pendingSig = prefs.getString(KEY_PENDING_SIGNATURE, "") ?: ""

            // 1. If we triggered an in-app install for this signature and Android package was updated
            if (pendingSig.isNotBlank() && pendingSig == updateInfo.signature && preInstallTime > 0L && currentLastUpdateTime > preInstallTime) {
                return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Determines whether the currently running app is already up-to-date with respect to the remote update.
     * Compares remote upload timestamp against this app's package install/update time:
     * - If remote APK was uploaded AFTER user installed this app -> update is required!
     * - If user installed this app AFTER or at the release timestamp -> already up to date!
     */
    fun isAppAlreadyUpToDate(
        context: Context,
        updateInfo: AppUpdateInfo,
        installedSignature: String = ""
    ): Boolean {
        if (!updateInfo.hasUpdate) return true

        if (didAppUpdateComplete(context, updateInfo)) {
            return true
        }

        try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val appInstallTime = Math.max(pkgInfo.firstInstallTime, pkgInfo.lastUpdateTime)

            // When a remote upload timestamp is available, compare directly with the app's install timestamp
            if (updateInfo.updatedAtMillis > 0L) {
                // If remote file was uploaded after this app was installed/updated on device:
                if (updateInfo.updatedAtMillis > appInstallTime) {
                    return false
                } else {
                    // App was installed after this release was published -> user already has latest version!
                    return true
                }
            }
        } catch (_: Exception) {}

        if (installedSignature.isNotBlank() && installedSignature == updateInfo.signature) {
            return true
        }

        return false
    }

    fun wasInstallAttemptedForSignature(context: Context, signature: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ATTEMPTED_SIGNATURE, "") == signature
    }

    fun recordInstallAttempt(context: Context, signature: String) {
        try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_PRE_INSTALL_UPDATE_TIME, pkgInfo.lastUpdateTime)
                .putString(KEY_PENDING_SIGNATURE, signature)
                .putString(KEY_ATTEMPTED_SIGNATURE, signature)
                .apply()
        } catch (_: Exception) {}
    }

    suspend fun downloadUpdateApk(
        context: Context,
        updateInfo: AppUpdateInfo,
        onProgress: (percent: Int, downloadedMb: Float, totalMb: Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // Use persistent externalFilesDir / filesDir instead of volatile cacheDir so OS never purges the APK mid-install
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val updatesDir = File(baseDir, "updates")
            if (!updatesDir.exists()) {
                updatesDir.mkdirs()
            }
            val targetFile = File(updatesDir, "KingoKing_Update.apk")
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val candidateUrls = mutableListOf<String>()
            // Always prioritize direct download URL (e.g. GitHub Releases asset or direct APK host)
            if (updateInfo.downloadUrl.isNotBlank()) {
                candidateUrls.add(updateInfo.downloadUrl)
            }
            if (updateInfo.fileId.isNotBlank() && !updateInfo.fileId.startsWith("apk_") && !updateInfo.fileId.startsWith("gh_")) {
                candidateUrls.add("https://drive.usercontent.google.com/download?id=${updateInfo.fileId}&export=download&confirm=t")
                candidateUrls.add("https://drive.google.com/uc?export=download&id=${updateInfo.fileId}&confirm=t")
            }

            var lastError = "Update failed. Please try again."

            for (url in candidateUrls) {
                val success = tryDownloadUrlToFile(context, url, targetFile, updateInfo.fileSize, onProgress)
                if (success.isSuccess) {
                    // Mirror the verified APK to public Downloads/KingoKing_Update.apk
                    // so it remains accessible even if the old conflicting app is uninstalled!
                    mirrorApkToPublicDownloads(context, targetFile)
                    return@withContext Result.success(targetFile)
                } else {
                    lastError = "Update failed. Please try again."
                }
            }

            Result.failure(Exception("Update failed. Please try again."))
        } catch (e: Exception) {
            Result.failure(Exception("Update failed. Please try again."))
        }
    }

    private fun tryDownloadUrlToFile(
        context: Context,
        initialUrl: String,
        targetFile: File,
        knownFileSize: Long,
        onProgress: (percent: Int, downloadedMb: Float, totalMb: Float) -> Unit
    ): Result<File> {
        var currentUrl = initialUrl
        for (attempt in 1..3) {
            val req = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .get()
                .build()

            val response = try {
                downloadClient.newCall(req).execute()
            } catch (_: Exception) {
                continue
            }
            if (!response.isSuccessful) {
                response.close()
                continue
            }

            val body = response.body ?: run {
                response.close()
                continue
            }

            val contentType = response.header("Content-Type")?.lowercase() ?: ""
            if (contentType.contains("text/html")) {
                val html = body.string()
                response.close()
                if (attempt < 3) {
                    val nextUrl = extractGoogleDriveConfirmUrl(html, currentUrl)
                    if (nextUrl != null) {
                        currentUrl = nextUrl
                        continue
                    }
                }
                continue
            }

            val totalBytes = if (body.contentLength() > 0) body.contentLength() else knownFileSize
            val totalMb = if (totalBytes > 0) totalBytes.toFloat() / (1024f * 1024f) else 0f

            try {
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var downloadedBytes = 0L
                        var firstChunkChecked = false

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (!firstChunkChecked && bytesRead >= 2) {
                                firstChunkChecked = true
                                // APK files are ZIP archives and always start with 'P' (0x50) 'K' (0x4B)
                                if (buffer[0] != 0x50.toByte() || buffer[1] != 0x4B.toByte()) {
                                    output.close()
                                    response.close()
                                    targetFile.delete()
                                    return Result.failure(
                                        Exception("Update failed. Please try again.")
                                    )
                                }
                            }
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            val dlMb = downloadedBytes.toFloat() / (1024f * 1024f)
                            val pct = if (totalBytes > 0) {
                                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(1, 99)
                            } else {
                                50
                            }
                            onProgress(pct, dlMb, totalMb)
                        }
                        output.flush()
                    }
                }
            } catch (_: Exception) {
                response.close()
                targetFile.delete()
                continue
            }
            response.close()

            if (targetFile.exists() && targetFile.length() > 10_000L) {
                // Verify Android PackageManager can parse the downloaded APK archive
                val archiveInfo = try {
                    context.packageManager.getPackageArchiveInfo(targetFile.absolutePath, 0)
                } catch (_: Exception) {
                    null
                }
                if (archiveInfo == null) {
                    targetFile.delete()
                    return Result.failure(
                        Exception("Update failed. Please try again.")
                    )
                }

                val finalMb = targetFile.length().toFloat() / (1024f * 1024f)
                onProgress(100, finalMb, finalMb)
                return Result.success(targetFile)
            } else {
                targetFile.delete()
            }
        }
        return Result.failure(Exception("Update failed. Please try again."))
    }

    private fun extractGoogleDriveConfirmUrl(html: String, fallbackCurrentUrl: String): String? {
        val actionMatch = Regex("""action=["']([^"']+)["'][^>]*id=["']download-form["']""").find(html)
            ?: Regex("""id=["']download-form["'][^>]*action=["']([^"']+)["']""").find(html)
            ?: Regex("""action=["'](https://drive\.usercontent\.google\.com/download[^"']*)["']""").find(html)

        val hiddenInputs = mutableMapOf<String, String>()
        val inputRegex1 = Regex("""<input[^>]*name=["']([^"']+)["'][^>]*value=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val inputRegex2 = Regex("""<input[^>]*value=["']([^"']*)["'][^>]*name=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        inputRegex1.findAll(html).forEach { m ->
            hiddenInputs[m.groupValues[1]] = m.groupValues[2]
        }
        inputRegex2.findAll(html).forEach { m ->
            hiddenInputs[m.groupValues[2]] = m.groupValues[1]
        }

        if (hiddenInputs.isNotEmpty() || actionMatch != null) {
            val baseAction = actionMatch?.groupValues?.get(1)?.replace("&amp;", "&")
                ?: "https://drive.usercontent.google.com/download"
            if (!hiddenInputs.containsKey("confirm")) {
                hiddenInputs["confirm"] = "t"
            }
            if (!hiddenInputs.containsKey("export")) {
                hiddenInputs["export"] = "download"
            }
            if (!hiddenInputs.containsKey("id")) {
                val idFromUrl = Regex("""[?&]id=([a-zA-Z0-9_-]+)""").find(fallbackCurrentUrl)?.groupValues?.getOrNull(1)
                if (!idFromUrl.isNullOrBlank()) {
                    hiddenInputs["id"] = idFromUrl
                }
            }
            val queryParams = hiddenInputs.entries.joinToString("&") { (k, v) ->
                "${Uri.encode(k)}=${Uri.encode(v)}"
            }
            return if (baseAction.contains("?")) {
                "$baseAction&$queryParams"
            } else {
                "$baseAction?$queryParams"
            }
        }
        return null
    }

    /**
     * Inspects the downloaded APK and compares its package name, versionCode, and signing certificate
     * against the currently installed app to detect why Android might say "App not installed"
     * (e.g. signature mismatch from a different build environment or version downgrade).
     */
    @Suppress("DEPRECATION")
    fun inspectApkCompatibility(context: Context, apkFile: File): ApkCompatibilityReport {
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }

            val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
                ?: return ApkCompatibilityReport(isValidApk = false)

            val archivePkg = archiveInfo.packageName ?: ""
            val archiveVerCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.longVersionCode
            } else {
                archiveInfo.versionCode.toLong()
            }
            val archiveVerName = archiveInfo.versionName ?: "1.0"

            val installedInfo = try {
                pm.getPackageInfo(archivePkg, flags)
            } catch (_: Exception) {
                null
            }

            if (installedInfo == null) {
                return ApkCompatibilityReport(
                    isValidApk = true,
                    archivePackageName = archivePkg,
                    archiveVersionCode = archiveVerCode,
                    archiveVersionName = archiveVerName,
                    installedVersionCode = 0L,
                    hasSignatureConflict = false,
                    hasVersionDowngrade = false
                )
            }

            val installedVerCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                installedInfo.longVersionCode
            } else {
                installedInfo.versionCode.toLong()
            }

            val archiveCertHashes = extractSignatureHashes(archiveInfo)
            val installedCertHashes = extractSignatureHashes(installedInfo)

            val sigConflict = archiveCertHashes.isNotEmpty() &&
                    installedCertHashes.isNotEmpty() &&
                    archiveCertHashes.intersect(installedCertHashes).isEmpty()

            val verDowngrade = archiveVerCode < installedVerCode

            ApkCompatibilityReport(
                isValidApk = true,
                archivePackageName = archivePkg,
                archiveVersionCode = archiveVerCode,
                archiveVersionName = archiveVerName,
                installedVersionCode = installedVerCode,
                hasSignatureConflict = sigConflict,
                hasVersionDowngrade = verDowngrade
            )
        } catch (_: Exception) {
            ApkCompatibilityReport(isValidApk = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSignatureHashes(pkgInfo: PackageInfo): Set<String> {
        val hashes = mutableSetOf<String>()
        try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = pkgInfo.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                } else {
                    pkgInfo.signatures
                }
            } else {
                pkgInfo.signatures
            }
            signatures?.forEach { sig ->
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(sig.toByteArray())
                hashes.add(digest.joinToString("") { "%02x".format(it) })
            }
        } catch (_: Exception) {}
        return hashes
    }

    /**
     * Copies the downloaded APK into the phone's public Downloads folder ("KingoKing_Update.apk")
     * so that even if the user uninstalls the old conflicting app, the new APK stays in Downloads!
     */
    private fun mirrorApkToPublicDownloads(context: Context, sourceApk: File): Uri? {
        return try {
            val fileName = "KingoKing_Update.apk"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                try {
                    resolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                        arrayOf(fileName)
                    )
                } catch (_: Exception) {}

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceApk).use { input ->
                            input.copyTo(out, 32 * 1024)
                        }
                    }
                    val doneValues = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, doneValues, null, null)
                    return uri
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val destFile = File(downloadsDir, fileName)
                if (destFile.exists()) destFile.delete()
                sourceApk.copyTo(destFile, overwrite = true)
                return Uri.fromFile(destFile)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Launches the Android system APK update installer configured to replace the existing app in-place.
     */
    @Suppress("DEPRECATION")
    fun launchApkInstaller(context: Context, apkFile: File, signature: String = ""): Boolean {
        if (signature.isNotBlank()) {
            recordInstallAttempt(context, signature)
        }
        return try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = apkUri
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
                putExtra("android.intent.extra.REPLACE_UNKNOWN_SOURCES", true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            true
        } catch (_: Exception) {
            try {
                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(viewIntent)
                true
            } catch (_: Exception) {
                installViaPackageInstallerSession(context, apkFile)
            }
        }
    }

    /**
     * Streams the APK into Android's PackageInstaller.Session with MODE_FULL_INSTALL and INSTALL_REPLACE_EXISTING.
     */
    private fun installViaPackageInstallerSession(context: Context, apkFile: File): Boolean {
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (archiveInfo?.packageName != null) {
                params.setAppPackageName(archiveInfo.packageName)
            }
            // Enable INSTALL_REPLACE_EXISTING (0x00000002) via reflection if present on SessionParams
            try {
                val field = PackageInstaller.SessionParams::class.java.getDeclaredField("installFlags")
                field.isAccessible = true
                val currentFlags = field.getInt(params)
                field.setInt(params, currentFlags or 0x00000002)
            } catch (_: Exception) {}

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            session.openWrite("KingoKing_Update.apk", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { input ->
                    input.copyTo(out, 32 * 1024)
                }
                session.fsync(out)
            }

            val callbackIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(context, sessionId, callbackIntent, pendingFlags)
            session.commit(pendingIntent.intentSender)
            session.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * When the existing installed app has a different signing key or version conflict (causing Android
     * to say "App not installed"), this method:
     * 1. Saves the new APK into the phone's public Downloads folder ("Downloads/KingoKing_Update.apk")
     * 2. Opens the phone's Downloads screen in a separate background task so KingoKing_Update.apk is right on top
     * 3. Launches the system Uninstall dialog for the conflicting old app so the user taps "OK" to remove
     *    the old app and lands straight on KingoKing_Update.apk in Downloads to install cleanly!
     */
    fun replaceConflictingAppAndInstall(context: Context, apkFile: File, targetPackageName: String = context.packageName) {
        try {
            mirrorApkToPublicDownloads(context, apkFile)
            Toast.makeText(
                context,
                "New APK saved to Downloads/KingoKing_Update.apk! Tap OK to uninstall the old conflicting version, then tap KingoKing_Update.apk in Downloads.",
                Toast.LENGTH_LONG
            ).show()

            // 1. Open system Downloads screen in a separate task so after uninstall completes,
            // the user lands directly on the Downloads list with KingoKing_Update.apk at the top!
            try {
                val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(downloadsIntent)
            } catch (_: Exception) {}

            // 2. Prompt Android to uninstall the conflicting old package
            val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$targetPackageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(uninstallIntent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Please uninstall the old app first, then install KingoKing_Update.apk from your Downloads folder.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
