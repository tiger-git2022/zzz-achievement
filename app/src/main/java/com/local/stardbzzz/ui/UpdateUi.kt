package com.local.stardbzzz.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.stardbzzz.data.update.AppUpdater
import com.local.stardbzzz.data.update.UpdateState
import com.local.stardbzzz.ui.glass.GlassAlertDialog
import com.local.stardbzzz.ui.glass.GlassScaffold
import com.local.stardbzzz.ui.glass.GlassSettings
import com.local.stardbzzz.ui.glass.glassContent
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(updater: AppUpdater, onBack: () -> Unit, onOpenScan: () -> Unit) {
    val updateState by updater.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)

    val glassEnabled by GlassSettings.enabled.collectAsStateWithLifecycle()
    GlassScaffold(
        topBar = { barModifier, barColors ->
            TopAppBar(
                modifier = barModifier,
                colors = barColors,
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding, page ->
        Column(
            modifier = Modifier.fillMaxSize().glassContent(page).verticalScroll(rememberScrollState()).padding(padding),
        ) {
            ListItem(
                headlineContent = { Text("当前版本") },
                supportingContent = { Text(updater.currentVersionName) },
            )
            ListItem(
                headlineContent = { Text("检查更新") },
                supportingContent = { Text("从 GitHub Releases 获取最新版本") },
                trailingContent = {
                    if (updateState == UpdateState.Checking) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                },
                modifier = Modifier.clickable(enabled = updateState == UpdateState.Idle) {
                    updater.checkManually()
                },
            )
            ListItem(
                headlineContent = { Text("液态玻璃") },
                supportingContent = { Text("顶栏、筛选栏、分类抽屉、对话框和菜单用玻璃效果（安卓 13 起有折射，12 起有模糊）") },
                trailingContent = {
                    Switch(checked = glassEnabled, onCheckedChange = GlassSettings::setEnabled)
                },
                modifier = Modifier.clickable { GlassSettings.setEnabled(!glassEnabled) },
            )
            ListItem(
                headlineContent = { Text("游戏成就扫描") },
                supportingContent = { Text("自动识别游戏成就页，把完成状态同步到 stardb") },
                modifier = Modifier.clickable(onClick = onOpenScan),
            )
        }
    }
}

/** Hosts the update prompt, download progress and install hand-off; render once at the app root. */
@Composable
fun UpdateDialogs(updater: AppUpdater) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by updater.state.collectAsStateWithLifecycle()
    var waitingForInstallPermission by remember { mutableStateOf(false) }

    LaunchedEffect(updater) {
        updater.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val currentState by rememberUpdatedState(state)
    DisposableEffect(lifecycleOwner, updater) {
        // Returning from the "install unknown apps" settings page: continue the install if it was granted.
        val observer = LifecycleEventObserver { _, event ->
            val ready = currentState as? UpdateState.ReadyToInstall
            if (event == Lifecycle.Event.ON_RESUME && ready != null && waitingForInstallPermission &&
                updater.canInstallPackages()
            ) {
                waitingForInstallPermission = false
                launchInstaller(context, updater, ready.apk)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (val current = state) {
        is UpdateState.Available -> GlassAlertDialog(
            onDismissRequest = updater::postpone,
            title = { Text("发现新版本 ${current.release.tagName}") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "当前版本 ${updater.currentVersionName}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(current.release.notes.ifBlank { "暂无更新说明" })
                }
            },
            confirmButton = {
                TextButton(onClick = { updater.download(current.release) }) { Text("更新") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { updater.ignore(current.release) }) { Text("忽略此版本") }
                    TextButton(onClick = updater::postpone) { Text("稍后") }
                }
            },
        )

        is UpdateState.Downloading -> GlassAlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("正在下载 ${current.release.tagName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (current.totalBytes > 0) {
                        val fraction = (current.downloadedBytes.toFloat() / current.totalBytes).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text("${(fraction * 100).toInt()}%  ·  ${formatMegabytes(current.downloadedBytes)} / ${formatMegabytes(current.totalBytes)}")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(formatMegabytes(current.downloadedBytes))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = updater::cancelDownload) { Text("取消") }
            },
        )

        is UpdateState.ReadyToInstall -> {
            LaunchedEffect(current.apk) {
                if (updater.canInstallPackages()) {
                    launchInstaller(context, updater, current.apk)
                } else {
                    waitingForInstallPermission = true
                }
            }
            GlassAlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnClickOutside = false),
                title = { Text(if (waitingForInstallPermission) "需要安装权限" else "下载完成") },
                text = {
                    Text(
                        if (waitingForInstallPermission) {
                            "请在系统设置中允许本应用「安装未知应用」，返回后会自动继续安装。"
                        } else {
                            "安装包已下载，点击「安装」打开系统安装程序。"
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (updater.canInstallPackages()) {
                                waitingForInstallPermission = false
                                launchInstaller(context, updater, current.apk)
                            } else {
                                waitingForInstallPermission = true
                                startActivitySafely(context, updater.unknownSourcesSettingsIntent())
                            }
                        },
                    ) {
                        Text(if (waitingForInstallPermission) "去设置" else "安装")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            waitingForInstallPermission = false
                            updater.dismissInstall()
                        },
                    ) { Text("关闭") }
                },
            )
        }

        UpdateState.Idle, UpdateState.Checking -> Unit
    }
}

private fun launchInstaller(context: Context, updater: AppUpdater, apk: File) {
    startActivitySafely(context, updater.installIntent(apk))
}

private fun startActivitySafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "无法打开系统界面", Toast.LENGTH_SHORT).show()
    }
}

private fun formatMegabytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
