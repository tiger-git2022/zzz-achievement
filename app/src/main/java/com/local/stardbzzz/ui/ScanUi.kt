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
import com.local.stardbzzz.scan.ScanCoordinator
import com.local.stardbzzz.scan.ScanDiff
import com.local.stardbzzz.scan.ScanReport
import com.local.stardbzzz.scan.ScanState
import com.local.stardbzzz.scan.ScreenCaptureService
import kotlinx.coroutines.launch

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
                        "扫描期间不要触碰屏幕；成就较多时可能需要 10 分钟左右。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
                                    "如果开关是灰色的，先到 应用信息 → 右上角 ⋮ → 允许受限制的设置。"
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
                            enabled = accessibilityOn && state == ScanState.Idle,
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
                Text("游戏完成数 ${report.gameCompletedCount ?: "未识别"} · 本地已完成 ${report.localCompletedCount}")
                Text("识别 ${report.observedCount} 条 · 匹配 ${report.matchedCount} 条 · 未匹配 ${report.unmatched.size} 条")
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
            var expanded by remember { mutableStateOf(false) }
            Column {
                ListItem(
                    headlineContent = { Text("未匹配的成就（${report.unmatched.size}）") },
                    supportingContent = { Text("游戏里识别到、但本地找不到的名称，可能是识别错误或 stardb 未收录") },
                    modifier = Modifier.clickable { expanded = !expanded },
                )
                if (expanded) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        report.unmatched.forEach { row ->
                            Text("${row.tab} / ${row.category}：${row.name}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
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

private fun ScanState.description(): String = when (this) {
    ScanState.Idle -> "授权屏幕录制后，切换到游戏的成就页，点击悬浮窗的「开始」。"
    ScanState.Ready -> "已就绪：切换到游戏的成就页，点击悬浮窗的「开始」。"
    is ScanState.Running -> buildString {
        append("扫描中")
        progress.tab?.let { append(" · ").append(it) }
        progress.category?.let { append(" / ").append(it) }
        append(" · 已读 ${progress.rows} 条")
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
