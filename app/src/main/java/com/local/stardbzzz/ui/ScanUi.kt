package com.local.stardbzzz.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.local.stardbzzz.scan.ObservedRow
import com.local.stardbzzz.scan.QuickSyncSummary
import com.local.stardbzzz.scan.RowStatus
import com.local.stardbzzz.scan.ScanCandidate
import com.local.stardbzzz.scan.ScanCoordinator
import com.local.stardbzzz.scan.ScanDiff
import com.local.stardbzzz.scan.ScanReport
import com.local.stardbzzz.scan.ScanState
import com.local.stardbzzz.scan.ScreenCaptureService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** Everything the 游戏成就扫描 part of the settings page shows, read once per composition of the page. */
class ScanSection internal constructor(
    val coordinator: ScanCoordinator,
    val state: ScanState,
    val accessibilityOn: Boolean,
    val report: ScanReport?,
    val laterMatches: List<ScanDiff>,
    val saveDebug: Boolean,
    val quickReady: Boolean,
    val listInfo: AchievementListInfo?,
    val refreshingList: Boolean,
    val applying: Boolean,
    val onRefreshList: () -> Unit,
    val onAuthorizeCapture: () -> Unit,
    val onApply: (List<ScanDiff>) -> Unit,
) {
    val listStale: Boolean
        get() = listInfo.let { it == null || it.total == 0 || System.currentTimeMillis() - it.refreshedAtEpochMillis > ListMaxAgeMillis }
}

@Composable
fun rememberScanSection(coordinator: ScanCoordinator): ScanSection {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by coordinator.state.collectAsStateWithLifecycle()
    val accessibilityOn by coordinator.accessibilityConnected.collectAsStateWithLifecycle()
    val report by coordinator.lastReport.collectAsStateWithLifecycle()
    val laterMatches by coordinator.laterMatches.collectAsStateWithLifecycle()
    val saveDebug by coordinator.saveDebugFrames.collectAsStateWithLifecycle()
    val quickReady by coordinator.quickSyncReady.collectAsStateWithLifecycle()
    // Follows the list as the main screen refreshes it too, not only the 刷新 here.
    val listInfo by remember(coordinator) { coordinator.observeAchievementListInfo() }.collectAsStateWithLifecycle(null)
    var applying by remember { mutableStateOf(false) }
    var refreshingList by remember { mutableStateOf(false) }

    // Rows earlier scans couldn't match may match the refreshed list: stardb added them since.
    LaunchedEffect(coordinator, listInfo?.refreshedAtEpochMillis) {
        if (listInfo != null) coordinator.rematchUnmatched()
    }

    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(context, result.resultCode, data)
            Toast.makeText(context, "已就绪：切换到游戏的成就页，点击悬浮窗的「快速」或「全盘」", Toast.LENGTH_LONG).show()
        }
    }

    return ScanSection(
        coordinator = coordinator,
        state = state,
        accessibilityOn = accessibilityOn,
        report = report,
        laterMatches = laterMatches,
        saveDebug = saveDebug,
        quickReady = quickReady,
        listInfo = listInfo,
        refreshingList = refreshingList,
        applying = applying,
        onRefreshList = {
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
                    refreshingList = false
                }
            }
        },
        onAuthorizeCapture = {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Whole-screen capture keeps pixels aligned with the gesture coordinates.
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
            captureLauncher.launch(intent)
        },
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

/** The 游戏成就扫描 part of the settings page: its steps, then the last scan's results. */
fun LazyListScope.scanSection(section: ScanSection) {
    item(key = ScanSectionKey) {
        Column {
            HorizontalDivider()
            Text(
                "游戏成就扫描",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
            Text(
                text = "自动切换游戏成就页的各个分类并滑动列表，识别每条成就的完成状态，与本地数据对比后由你确认同步。" +
                    "截图期间不要触碰屏幕；悬浮窗显示「截图完成」后就可以切到其他 App，识别会在后台继续。\n\n" +
                    "悬浮窗有两种扫描：「全盘」把每个分类从头翻到底；「快速」只用于日常同步，先比对「完成数」，" +
                    "没变就直接结束，变了再只看各分类顶部的未完成部分（游戏把未完成的排在最前面），按全服完成率从高到低找，凑够数就停。" +
                    "快速同步以上一次全盘扫描为基准，第一次请先做全盘扫描。小游戏（「其他」里的 25马赫 等）的成就不在成就页上，扫描不看。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    item {
        ListItem(
            headlineContent = { Text("0. 确认成就列表是最新的") },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(section.listInfo?.describe() ?: "读取中…")
                    if (section.listStale) {
                        Text(
                            "扫描是拿游戏里的成就和本地列表比对，列表旧了，新成就会全部显示为「stardb 里没有」。超过一天没更新，请先刷新。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            trailingContent = {
                TextButton(enabled = !section.refreshingList, onClick = section.onRefreshList) {
                    Text(if (section.refreshingList) "刷新中…" else "刷新")
                }
            },
        )
    }
    item {
        val context = LocalContext.current
        ListItem(
            headlineContent = { Text("1. 开启无障碍服务") },
            supportingContent = {
                Text(
                    if (section.accessibilityOn) {
                        "已开启"
                    } else {
                        "在 无障碍 → 已下载的应用 中开启「绝区零成就 · 自动扫描」，用于自动点击和滑动。" +
                            "如果开关是灰色的，先到 应用信息 → 右上角 ⋮ → 允许受限制的设置。" +
                            "如果系统里已显示开启、这里仍未开启（例如重新安装后），把开关关掉再打开一次。"
                    },
                )
            },
            trailingContent = {
                if (!section.accessibilityOn) {
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
                supportingContent = {
                    Column {
                        Text(section.state.description())
                        Text(
                            if (section.quickReady) "快速同步：已有全盘扫描的基准，可以直接用「快速」。"
                            else "快速同步：还没有基准，请先做一次「全盘」扫描。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = section.accessibilityOn && section.state == ScanState.Idle && !section.listStale && !section.refreshingList,
                    onClick = section.onAuthorizeCapture,
                ) { Text("授权屏幕录制") }
                if (section.state != ScanState.Idle) {
                    OutlinedButton(onClick = section.coordinator::endSession) { Text("结束扫描") }
                }
            }
        }
    }
    item {
        ListItem(
            headlineContent = { Text("保存调试数据") },
            supportingContent = {
                Text("保存每一屏的截图和识别文字，便于排查识别问题：${section.coordinator.debugDirectory?.path ?: "不可用"}")
            },
            trailingContent = {
                Switch(checked = section.saveDebug, onCheckedChange = section.coordinator::setSaveDebugFrames)
            },
        )
    }
    if (section.laterMatches.isNotEmpty()) {
        item {
            Column {
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("刷新列表后新匹配上的成就（${section.laterMatches.size}）") },
                    supportingContent = {
                        Text("之前扫描时 stardb 还没收录、现在收录了的成就，游戏里的状态和本地不一样。确认后同步。")
                    },
                )
                DiffList(section.laterMatches, section.applying, section.onApply)
            }
        }
    }
    section.report?.let { report -> reportItems(report, section.applying, section.onApply) }
}

/** The key of the section's first item, to scroll there when the overlay's 查看 opens the settings page. */
const val ScanSectionKey = "scan-section"

private fun LazyListScope.reportItems(
    report: ScanReport,
    applying: Boolean,
    onApply: (List<ScanDiff>) -> Unit,
) {
    item {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (report.quick != null) "快速同步结果" else "扫描结果", style = MaterialTheme.typography.titleMedium)
                report.quick?.let { quick -> QuickSummaryText(report.gameCompletedCount, quick) }
                SummaryLines(report)
            }
        }
    }
    if (report.diffs.isEmpty()) {
        item {
            Text(
                text = "游戏里的状态和本地一致，没有要同步的成就。",
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    } else {
        item {
            ListItem(
                headlineContent = { Text("要同步的成就（${report.diffs.size}）") },
                supportingContent = {
                    Text("游戏里的状态和 stardb 不一样的成就。游戏已完成的默认勾选；游戏里没完成、stardb 却勾了的默认不勾，先核对清楚。")
                },
            )
        }
        item { DiffList(report.diffs, applying, onApply) }
    }
    if (report.unmatched.isNotEmpty()) {
        item {
            ExpandableList(
                title = "stardb 里没有的成就（${report.unmatched.size}）",
                subtitle = "游戏里读到、但 stardb 列表里找不到：多半是 stardb 还没收录的新成就，也可能是识别错字。" +
                    "以后刷新成就列表时会自动重新匹配，不用再扫。点开看详情。",
            ) {
                report.unmatched.forEach { row -> UnmatchedRow(row) }
            }
        }
    }
    if (report.localCompletedNotSeen.isNotEmpty()) {
        item {
            ExpandableList(
                title = "stardb 勾了、这次没扫到（${report.localCompletedNotSeen.size}）",
                subtitle = "stardb 里已完成、这次扫描在游戏列表里没找到：要么是漏扫，要么游戏列表里没有这一条" +
                    "（还没完成的隐藏成就不显示）。点开看详情。",
            ) {
                report.localCompletedNotSeen.groupBy { it.place }.forEach { (place, candidates) ->
                    Text(
                        "$place（${candidates.size}）",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    candidates.forEach { NotSeenRow(it) }
                }
            }
        }
    }
}

/**
 * The scan's numbers as one account: the game's 完成数 against what the scan saw, stardb against what the scan saw,
 * and what the difference is made of.
 */
@Composable
private fun SummaryLines(report: ScanReport) {
    val game = report.gameCompletedCount
    val seen = report.gameCompletedSeen
    val local = report.localCompletedCount
    val afterSync = report.localCompletedAfterSync
    val notSeen = report.localCompletedNotSeen.size
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val quick = report.quick != null

    // 快速同步 reads only part of the page: only its own summary and the local count apply.
    if (!quick) {
        Text("游戏：完成数 ${game ?: "没读到"}，这次扫到已完成 $seen 条")
        if (game != null && game != seen) {
            Text(
                if (game > seen) "扫到的比完成数少 ${game - seen} 条，说明有漏扫（翻页时漏了几行，或者有字认错了），可以再扫一次。"
                else "扫到的比完成数多 ${seen - game} 条，可能有成就被认错、算重了，请看下面的列表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    Text(
        buildString {
            append("stardb：已完成 $local 条")
            if (report.arcadeCompleted > 0) append("（不含小游戏 ${report.arcadeCompleted} 条，扫描不看小游戏）")
            if (afterSync != local) append("；同步下面勾选的成就后是 $afterSync 条")
        },
    )
    if (report.localCompletedRecords != local) {
        Text(
            "stardb 把一些成就重复收录了，按记录数是 ${report.localCompletedRecords} 条，这里一条成就只算一次。",
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
    }
    if (!quick && notSeen > 0) {
        val missed = if (game != null) (game - seen).coerceIn(0, notSeen) else 0
        Text(
            buildString {
                append("stardb 比扫到的多 $notSeen 条，见下面「stardb 勾了、这次没扫到」")
                if (missed > 0) {
                    append("：其中 $missed 条是漏扫的（就是完成数比扫到的多出来的那几条）")
                    if (notSeen > missed) append("，其余 ${notSeen - missed} 条游戏列表里没有，多半是还没完成的隐藏成就")
                } else {
                    append("：游戏列表里没有这几条，多半是还没完成的隐藏成就")
                }
                append("。")
            },
        )
    }
    Text(
        buildString {
            append("识别：这次读到 ${report.observedCount} 条成就，${report.matchedCount} 条在 stardb 里对上了")
            if (report.unmatched.isNotEmpty()) append("，${report.unmatched.size} 条 stardb 里没有")
            append("。")
            if (report.rematched > 0) append("其中 ${report.rematched} 条是刷新列表后才对上的。")
        },
        style = MaterialTheme.typography.bodySmall,
        color = secondary,
    )
    if (report.clippedReads > 0) {
        Text(
            "另有 ${report.clippedReads} 条是滑到屏幕边缘、只露出半截名字的行，已在相邻一屏读到，不重复计算。",
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
    }
}

@Composable
private fun QuickSummaryText(gameCompletedCount: Int?, quick: QuickSyncSummary) {
    when {
        quick.expectedNew == 0 -> Text("游戏完成数 ${gameCompletedCount ?: "未识别"}，和上次扫描后一样，没有新完成的成就。")
        quick.expectedNew < 0 -> Text(
            "游戏完成数 ${gameCompletedCount ?: "未识别"}，比按本地记录算出来的少 ${-quick.expectedNew} 个。" +
                "可能本地多勾了成就，建议做一次全盘扫描。",
            color = MaterialTheme.colorScheme.error,
        )
        else -> {
            Text("游戏完成数 ${gameCompletedCount ?: "未识别"}，比上次扫描后多 ${quick.expectedNew} 个；找到 ${quick.found} 个。")
            Text("读了 ${quick.categoriesRead}/${quick.categoriesPlanned} 个分类" + if (quick.found >= quick.expectedNew) "，找够了就停下。" else "。")
            if (!quick.consistent) {
                Text(
                    "找到的数量和完成数对不上，推断出来的项默认不勾选，请逐个确认；也可以做一次全盘扫描。",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (quick.notFound.isNotEmpty()) {
                Text("侧栏里没找到的分类：${quick.notFound.joinToString("、")}", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ExpandableList(title: String, subtitle: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(subtitle) },
            trailingContent = { Text(if (expanded) "收起" else "展开", color = MaterialTheme.colorScheme.primary) },
            modifier = Modifier.clickable { expanded = !expanded },
        )
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}

@Composable
private fun UnmatchedRow(row: ObservedRow) {
    Column {
        Text(row.name, style = MaterialTheme.typography.bodyLarge)
        DetailLine(listOf(row.tab, row.category).filter { it.isNotBlank() }.joinToString(" / "))
        DetailLine(row.description)
        DetailLine("游戏：${row.status.text()}")
    }
}

@Composable
private fun NotSeenRow(candidate: ScanCandidate) {
    Column {
        Text(
            candidate.name + if (candidate.version.isNotBlank()) " · ${candidate.version}" else "",
            style = MaterialTheme.typography.bodyLarge,
        )
        DetailLine(candidate.description)
        DetailLine("stardb：已完成 · 游戏：这次没扫到")
    }
}

@Composable
private fun DetailLine(text: String) {
    if (text.isBlank()) return
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DiffList(diffs: List<ScanDiff>, applying: Boolean, onApply: (List<ScanDiff>) -> Unit) {
    // Marking completions is selected by default; un-completing (stardb ahead of the game) and doubtful inferences
    // are opt-in.
    val selected = remember(diffs) {
        mutableStateMapOf<String, Boolean>().apply { diffs.forEach { put(it.id, it.markCompleted && !it.uncertain) } }
    }
    Column {
        diffs.forEach { diff ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selected[diff.id] = selected[diff.id] != true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Checkbox(checked = selected[diff.id] == true, onCheckedChange = { selected[diff.id] = it })
                Column(modifier = Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        diff.name + if (diff.version.isNotBlank()) " · ${diff.version}" else "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    DetailLine(diff.place)
                    DetailLine(diff.description)
                    Text(
                        text = "游戏：${diff.gameStatus} · stardb：${if (diff.markCompleted) "未完成" else "已完成"} → " +
                            (if (diff.markCompleted) "标记完成" else "取消完成") +
                            if (diff.uncertain) "（推断，请确认）" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (diff.namesakes.isNotEmpty()) {
                        Text(
                            "注意：还有同名成就在 ${diff.namesakes.joinToString("、")}，确认是这一条再同步。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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

/** The scan compares against the local list; older than this and the scan section asks for a refresh first. */
private const val ListMaxAgeMillis = 24 * 60 * 60 * 1000L

private fun AchievementListInfo.describe(): String {
    if (total == 0) return "本地还没有成就数据，请先刷新"
    val refreshed = if (refreshedAtEpochMillis > 0) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(refreshedAtEpochMillis))
    } else {
        "未知"
    }
    val arcade = total - pageTotal
    return "成就页 $pageTotal 条，已完成 $pageCompleted 条" +
        (if (arcade > 0) "（另有小游戏成就 $arcade 条，扫描不看）" else "") +
        "；最新版本 ${latestVersion ?: "未知"}，更新于 $refreshed"
}

private fun RowStatus.text(): String = when (this) {
    is RowStatus.Completed -> date?.let { "已完成（$it）" } ?: "已完成"
    is RowStatus.InProgress -> "进行中 $current/$target"
}

private fun ScanState.description(): String = when (this) {
    ScanState.Idle -> "授权屏幕录制后，切换到游戏的成就页，点击悬浮窗的「快速」或「全盘」。"
    ScanState.Ready -> "已就绪：切换到游戏的成就页，点击悬浮窗的「快速」或「全盘」。"
    is ScanState.Running -> buildString {
        if (progress.quick) {
            append("快速同步中")
            progress.tab?.let { append(" · ").append(it) }
            progress.category?.let { append(" / ").append(it) }
        } else if (progress.capturing) {
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
