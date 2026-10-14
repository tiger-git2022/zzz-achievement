package com.local.stardbzzz.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.stardbzzz.data.repository.AchievementListInfo
import com.local.stardbzzz.data.repository.AuthenticationExpiredException
import com.local.stardbzzz.scan.ScanCoordinator
import com.local.stardbzzz.scan.ScanDiff
import com.local.stardbzzz.scan.ScanReport
import com.local.stardbzzz.scan.ScanState
import com.local.stardbzzz.scan.ScreenCaptureService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(coordinator: ScanCoordinator, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by coordinator.state.collectAsStateWithLifecycle()
    val accessibilityOn by coordinator.accessibilityConnected.collectAsStateWithLifecycle()
    val report by coordinator.lastReport.collectAsStateWithLifecycle()
    val saveDebug by coordinator.saveDebugFrames.collectAsStateWithLifecycle()
    var applying by remember { mutableStateOf(false) }
    var listInfo by remember { mutableStateOf<AchievementListInfo?>(null) }
    var refreshingList by remember { mutableStateOf(false) }
    LaunchedEffect(coordinator) { listInfo = coordinator.achievementListInfo() }
    val listStale = listInfo.let { it == null || it.total == 0 || System.currentTimeMillis() - it.refreshedAtEpochMillis > ListMaxAgeMillis }

    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(context, result.resultCode, data)
            Toast.makeText(context, "已就绪：切换到游戏的成就页，点击悬浮窗的「开始」", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("游戏成就扫描") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    text = "自动切换游戏成就页的各个分类并滑动列表，识别每条成就的完成状态，与本地数据对比后由你确认同步。" +
                        "截图期间不要触碰屏幕；悬浮窗显示「截图完成」后就可以切到其他 App，识别会在后台继续。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("0. 确认成就列表是最新的") },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(listInfo?.describe() ?: "读取中…")
                            if (listStale) {
                                Text(
                                    "扫描是拿游戏里的成就和本地列表比对，列表旧了，新成就会全部显示为「未匹配」。超过一天没更新，请先刷新。",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    trailingContent = {
                        TextButton(
                            enabled = !refreshingList,
                            onClick = {
                                scope.launch {
                                    refreshingList = true
                                    try {
                                        coordinator.refreshAchievementList()
                                        Toast.makeText(context, "成就列表已更新", Toast.LENGTH_SHORT).show()
                                    } catch (failure: Exception) {
                                        val message = if (failure is AuthenticationExpiredException) {
                                            "登录已失效，请回到主页重新登录后再刷新"
                                        } else {
                                            "刷新失败：${failure.message}"
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    } finally {
                                        listInfo = coordinator.achievementListInfo()
                                        refreshingList = false
                                    }
                                }
                            },
                        ) { Text(if (refreshingList) "刷新中…" else "刷新") }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("1. 开启无障碍服务") },
                    supportingContent = {
                        Text(
                            if (accessibilityOn) {
                                "已开启"
                            } else {
                                "在 无障碍 → 已下载的应用 中开启「绝区零成就 · 自动扫描」，用于自动点击和滑动。" +
                                    "如果开关是灰色的，先到 应用信息 → 右上角 ⋮ → 允许受限制的设置。" +
                                    "如果系统里已显示开启、这里仍未开启（例如重新安装后），把开关关掉再打开一次。"
                            },
                        )
                    },
                    trailingContent = {
                        if (!accessibilityOn) {
                            TextButton(onClick = {
                                startActivitySafely(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }) { Text("去开启") }
                        }
                    },
                )
            }
            item {
                Column {
                    ListItem(
                        headlineContent = { Text("2. 开始扫描") },
                        supportingContent = { Text(state.description()) },
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            enabled = accessibilityOn && state == ScanState.Idle && !listStale && !refreshingList,
                            onClick = {
                                val manager = context.getSystemService(MediaProjectionManager::class.java)
                                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                    // Whole-screen capture keeps pixels aligned with the gesture coordinates.
                                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                                } else {
                                    manager.createScreenCaptureIntent()
                                }
                                captureLauncher.launch(intent)
                            },
                        ) { Text("授权屏幕录制") }
                        if (state != ScanState.Idle) {
                            OutlinedButton(onClick = coordinator::endSession) { Text("结束扫描") }
                        }
                    }
                }
            }
            item {
                Column {
                    ListItem(
                        headlineContent = { Text("保存调试数据") },
                        supportingContent = {
                            Text("保存每一屏的截图和识别文字，便于排查识别问题：${coordinator.debugDirectory?.path ?: "不可用"}")
                        },
                        trailingContent = {
                            Switch(checked = saveDebug, onCheckedChange = coordinator::setSaveDebugFrames)
                        },
                    )
                    HorizontalDivider()
                }
            }
            report?.let { current ->
                reportItems(
                    report = current,
                    applying = applying,
                    onApply = { diffs ->
                        scope.launch {
                            applying = true
                            try {
                                coordinator.apply(diffs)
                                Toast.makeText(context, "已同步 ${diffs.size} 项", Toast.LENGTH_SHORT).show()
                            } catch (failure: Exception) {
                                Toast.makeText(context, "同步失败：${failure.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                applying = false
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.reportItems(
    report: ScanReport,
    applying: Boolean,
    onApply: (List<ScanDiff>) -> Unit,
) {
    item {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("扫描结果", style = MaterialTheme.typography.titleMedium)
                Text("游戏完成数 ${report.gameCompletedCount ?: "未识别"} · 本次看到已完成 ${report.gameCompletedSeen} 条")
                Text(
                    buildString {
                        append("本地已完成 ${report.localCompletedCount} 个")
                        if (report.localCompletedRecords != report.localCompletedCount) {
                            append("（${report.localCompletedRecords} 条记录，有成就被 stardb 重复收录）")
                        }
                        append(" · 同步全部差异后 ${report.localCompletedAfterSync} 个")
                    },
                )
                Text(
                    "识别 ${report.observedCount} 条 · 匹配 ${report.matchedCount} 条 · 未匹配 ${report.unmatched.size} 条" +
                        if (report.clippedReads > 0) " · 忽略半截名字 ${report.clippedReads} 条" else "",
                )
                if (report.gameCompletedCount != null && report.localCompletedAfterSync != report.gameCompletedCount) {
                    Text(
                        "同步后的本地完成数与游戏不一致，请先查看下方「未匹配」和「本地已完成但本次没看到」再同步。",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    if (report.diffs.isEmpty()) {
        item {
            Text(
                text = "已识别的成就与本地数据一致，没有需要同步的差异。",
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    } else {
        item {
            DiffList(report.diffs, applying, onApply)
        }
    }
    if (report.unmatched.isNotEmpty()) {
        item {
            ExpandableList(
                title = "未匹配的成就（${report.unmatched.size}）",
                subtitle = "游戏里识别到、但本地找不到的名称，可能是识别错误或 stardb 未收录",
                lines = report.unmatched.map { row -> "${row.tab} / ${row.category}：${row.name}" },
            )
        }
    }
    if (report.localCompletedNotSeen.isNotEmpty()) {
        item {
            ExpandableList(
                title = "本地已完成但本次没看到（${report.localCompletedNotSeen.size}）",
                subtitle = "本地标记已完成、这次扫描没有对上的成就：可能被漏扫、识别错，或游戏里没有这一项",
                lines = report.localCompletedNotSeen.map { "${it.category}：${it.name}" },
            )
        }
    }
}

@Composable
private fun ExpandableList(title: String, subtitle: String, lines: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            modifier = Modifier.clickable { expanded = !expanded },
        )
        if (expanded) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun DiffList(diffs: List<ScanDiff>, applying: Boolean, onApply: (List<ScanDiff>) -> Unit) {
    // Marking completions is selected by default; un-completing (stardb ahead of the game) is opt-in.
    val selected = remember(diffs) { mutableStateMapOf<String, Boolean>().apply { diffs.forEach { put(it.id, it.markCompleted) } } }
    Column {
        diffs.forEach { diff ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selected[diff.id] = selected[diff.id] != true }
                    .padding(horizontal = 8.dp),
            ) {
                Checkbox(checked = selected[diff.id] == true, onCheckedChange = { selected[diff.id] = it })
                Column(modifier = Modifier.weight(1f)) {
                    Text(diff.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "${diff.category} · 游戏：${diff.gameStatus} → ${if (diff.markCompleted) "标记完成" else "取消完成"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val chosen = diffs.filter { selected[it.id] == true }
        Button(
            enabled = chosen.isNotEmpty() && !applying,
            onClick = { onApply(chosen) },
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
        ) {
            Text(if (applying) "同步中…" else "同步选中的 ${chosen.size} 项到 stardb")
        }
    }
}

/** The scan compares against the local list; older than this and the scan screen asks for a refresh first. */
private const val ListMaxAgeMillis = 24 * 60 * 60 * 1000L

private fun AchievementListInfo.describe(): String {
    if (total == 0) return "本地还没有成就数据，请先刷新"
    val refreshed = if (refreshedAtEpochMillis > 0) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(refreshedAtEpochMillis))
    } else {
        "未知"
    }
    return "本地 $total 条，已完成 $completed 条，最新版本 ${latestVersion ?: "未知"}，更新于 $refreshed"
}

private fun ScanState.description(): String = when (this) {
    ScanState.Idle -> "授权屏幕录制后，切换到游戏的成就页，点击悬浮窗的「开始」。"
    ScanState.Ready -> "已就绪：切换到游戏的成就页，点击悬浮窗的「开始」。"
    is ScanState.Running -> buildString {
        if (progress.capturing) {
            append("扫描中")
            progress.tab?.let { append(" · ").append(it) }
            progress.category?.let { append(" / ").append(it) }
        } else {
            append("截图完成，可以切到其他 App；正在识别")
        }
        append(" · 已识别 ${progress.read}/${progress.queued} 屏，读到 ${progress.rows} 条")
    }
    is ScanState.Finished -> "扫描完成，结果见下方。"
    is ScanState.Failed -> "扫描失败：$message"
}

private fun startActivitySafely(context: android.content.Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "无法打开系统设置", Toast.LENGTH_SHORT).show()
    }
}
