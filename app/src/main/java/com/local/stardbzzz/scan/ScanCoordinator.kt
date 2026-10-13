package com.local.stardbzzz.scan

import android.content.Context
import android.graphics.Bitmap
import com.local.stardbzzz.data.repository.AchievementListInfo
import com.local.stardbzzz.data.repository.AchievementRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ScanState {
    /** No capture session. */
    data object Idle : ScanState

    /** Screen capture granted and the accessibility service connected; waiting for 开始 on the overlay. */
    data object Ready : ScanState
    data class Running(val progress: ScanProgress) : ScanState
    data class Finished(val report: ScanReport) : ScanState
    data class Failed(val message: String) : ScanState
}

/**
 * Process-wide owner of a scan session: the screen capture (from [ScreenCaptureService]) and
 * gesture input (from [ScanAccessibilityService]) attach here, and the overlay and app UI observe [state].
 */
class ScanCoordinator(
    context: Context,
    private val repository: AchievementRepository,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("scan", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var capture: ProjectionScreenCapture? = null
    private var input: GestureInput? = null
    private var job: Job? = null

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _accessibilityConnected = MutableStateFlow(false)
    val accessibilityConnected: StateFlow<Boolean> = _accessibilityConnected.asStateFlow()

    private val _lastReport = MutableStateFlow<ScanReport?>(null)
    val lastReport: StateFlow<ScanReport?> = _lastReport.asStateFlow()

    private val _openScanScreen = MutableStateFlow(false)
    val openScanScreen: StateFlow<Boolean> = _openScanScreen.asStateFlow()

    private val _saveDebugFrames = MutableStateFlow(prefs.getBoolean(KeySaveDebugFrames, false))
    val saveDebugFrames: StateFlow<Boolean> = _saveDebugFrames.asStateFlow()

    val debugDirectory: File? get() = appContext.getExternalFilesDir("scan-debug")

    @Synchronized
    fun attachInput(gestureInput: GestureInput) {
        input = gestureInput
        _accessibilityConnected.value = true
        markReadyIfPossible()
    }

    @Synchronized
    fun detachInput(gestureInput: GestureInput) {
        if (input !== gestureInput) return
        input = null
        _accessibilityConnected.value = false
        job?.cancel()
        if (_state.value == ScanState.Ready || _state.value is ScanState.Running) _state.value = ScanState.Idle
    }

    @Synchronized
    fun attachCapture(screenCapture: ProjectionScreenCapture) {
        capture = screenCapture
        markReadyIfPossible()
    }

    @Synchronized
    fun detachCapture(screenCapture: ProjectionScreenCapture) {
        if (capture !== screenCapture) return
        capture = null
        job?.cancel()
        if (_state.value == ScanState.Ready || _state.value is ScanState.Running) _state.value = ScanState.Idle
    }

    @Synchronized
    fun start() {
        val screen = capture
        val gestures = input
        if (screen == null || gestures == null) {
            _state.value = ScanState.Failed("屏幕录制已结束，请回到 App 重新开始")
            return
        }
        if (job?.isActive == true) return
        // A report from an earlier run must not be applied as if it came from this one.
        _lastReport.value = null
        _state.value = ScanState.Running(ScanProgress(null, null, 0, 0))
        job = scope.launch {
            val recognizer = MlKitTextRecognizer()
            try {
                val candidates = repository.scanCandidates()
                require(candidates.isNotEmpty()) { "本地还没有成就数据，请先在 App 里登录并刷新" }
                // A panel dragged over the tab bar while Ready moves back to its spot once Running; let it.
                delay(OverlaySettleMillis)
                val debug = if (_saveDebugFrames.value) DebugFrameWriter(debugDirectory) else null
                debug?.writeCandidates(candidates)
                val listInfo = repository.achievementListInfo()
                var progress = ScanProgress(null, null, 0, 0)
                val reader = ScreenReader {
                    val frame = screen.capture()
                    val scaled = scaleForOcr(frame)
                    try {
                        recognizer.recognize(scaled).also { boxes -> debug?.write(scaled, boxes, progress) }
                    } finally {
                        if (scaled !== frame) scaled.recycle()
                    }
                }
                val result = AchievementPageScanner(reader, gestures) { update ->
                    progress = update
                    _state.value = ScanState.Running(update)
                }.scan()
                val report = ScanReportBuilder.build(result.rows, candidates, result.gameCompletedCount)
                debug?.writeReport(report, listInfo)
                _lastReport.value = report
                _state.value = ScanState.Finished(report)
            } catch (cancelled: CancellationException) {
                // Stopped from the overlay: back to Ready. endSession()/detach already set Idle; keep that.
                _state.update { current ->
                    if (current !is ScanState.Running) current
                    else if (capture != null && input != null) ScanState.Ready
                    else ScanState.Idle
                }
                throw cancelled
            } catch (failure: Exception) {
                _state.value = ScanState.Failed(failure.message ?: failure.javaClass.simpleName)
            } finally {
                recognizer.close()
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** Stops scanning, releases the screen capture and hides the overlay. */
    fun endSession() {
        job?.cancel()
        ScreenCaptureService.stop(appContext)
        _state.value = ScanState.Idle
    }

    suspend fun achievementListInfo(): AchievementListInfo = repository.achievementListInfo()

    /** Re-downloads the achievement list from stardb, so newly added achievements can be matched. */
    suspend fun refreshAchievementList() {
        repository.refreshAchievements()
    }

    fun requestOpenScanScreen() {
        _openScanScreen.value = true
    }

    fun consumeOpenScanScreen() {
        _openScanScreen.value = false
    }

    fun setSaveDebugFrames(enabled: Boolean) {
        prefs.edit().putBoolean(KeySaveDebugFrames, enabled).apply()
        _saveDebugFrames.value = enabled
    }

    suspend fun apply(diffs: List<ScanDiff>) {
        repository.applyScannedCompletion(diffs.associate { it.id to it.markCompleted })
        val applied = diffs.map { it.id }.toSet()
        // One diff can update several linked records, so recount instead of adjusting by the diff count.
        val candidates = repository.scanCandidates()
        val updated = _lastReport.value?.let { report ->
            report.copy(
                localCompletedCount = ScanReportBuilder.completedAchievements(candidates),
                localCompletedRecords = candidates.count { it.isCompleted },
                localCompletedNotSeen = report.localCompletedNotSeen.filter { notSeen ->
                    candidates.any { it.linkKey == notSeen.linkKey && it.isCompleted }
                },
                diffs = report.diffs.filterNot { it.id in applied },
            )
        } ?: return
        _lastReport.value = updated
        // The overlay shows the difference count from the finished state; keep it in step.
        _state.update { current -> if (current is ScanState.Finished) ScanState.Finished(updated) else current }
    }

    @Synchronized
    private fun markReadyIfPossible() {
        if (capture != null && input != null && job?.isActive != true) _state.value = ScanState.Ready
    }

    /**
     * Only very large frames are shrunk. On a 3168px-wide phone, shrinking to 1600px left achievement names
     * about 20px tall and ML Kit misread characters (叩 → D/号) on every other frame.
     */
    private fun scaleForOcr(frame: Bitmap): Bitmap {
        if (frame.width <= OcrMaxWidth) return frame
        val height = (frame.height * OcrMaxWidth.toFloat() / frame.width).toInt()
        return Bitmap.createScaledBitmap(frame, OcrMaxWidth, height, true)
    }

    /**
     * Saves each scanned frame and its OCR lines (same TSV layout as the unit-test fixtures) under
     * Android/data/com.local.stardbzzz/files/scan-debug/<session>/, plus what an offline replay needs
     * (ScanReplayTest): the local achievement list, which tab/category each frame belongs to, and the report.
     */
    private class DebugFrameWriter(root: File?) {
        private val directory = root?.let {
            File(it, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())).apply { mkdirs() }
        }
        private var index = 0

        fun write(frame: Bitmap, boxes: List<OcrBox>, progress: ScanProgress) {
            val dir = directory ?: return
            if (index >= MaxDebugFrames) return
            index++
            val name = "frame_%04d".format(Locale.ROOT, index)
            runCatching {
                // High quality so another OCR engine can be compared on these frames fairly.
                File(dir, "$name.jpg").outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                File(dir, "$name.tsv").writeText(ScanDebugFiles.frameTsv(boxes))
                File(dir, ScanDebugFiles.FrameIndex).appendText(
                    ScanDebugFiles.frameIndexLine(name, progress.tab.orEmpty(), progress.category.orEmpty(), progress.collecting),
                )
            }
        }

        fun writeCandidates(candidates: List<ScanCandidate>) {
            val dir = directory ?: return
            runCatching { File(dir, ScanDebugFiles.Candidates).writeText(ScanDebugFiles.candidatesTsv(candidates)) }
        }

        fun writeReport(report: ScanReport, list: AchievementListInfo) {
            val dir = directory ?: return
            val listHeader = buildString {
                appendLine("本地成就列表\t${list.total} 条，最新版本 ${list.latestVersion ?: "未知"}")
                appendLine("列表更新时间\t${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(list.refreshedAtEpochMillis))}")
                appendLine("stardb 同组连带标记已完成\t${list.completedViaGroup}")
            }
            runCatching { File(dir, ScanDebugFiles.Report).writeText(listHeader + ScanDebugFiles.reportText(report)) }
        }
    }

    private companion object {
        const val KeySaveDebugFrames = "save_debug_frames"
        const val OcrMaxWidth = 3200
        const val MaxDebugFrames = 1500
        const val OverlaySettleMillis = 600L
    }
}
