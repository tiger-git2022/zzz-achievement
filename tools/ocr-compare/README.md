# 扫描离线回放与 OCR 引擎对比 / Offline scan replay and OCR engine comparison

## 中文

真机扫描时打开「保存调试数据」，每次扫描会在 `Android/data/com.local.stardbzzz/files/scan-debug/<时间>/` 下保存：

| 文件 | 内容 |
|---|---|
| `frame_NNNN.jpg` | 每一屏截图（**含 UID，不要上传或提交**） |
| `frame_NNNN.tsv` | App 的识别结果：`left top right bottom text`，坐标按截图尺寸归一化（v1.1.19 及以前是 ML Kit，之后是 PaddleOCR） |
| `frames.tsv` | 每一帧属于哪个页签、哪个分类 |
| `candidates.tsv` | 本地成就列表（开始扫描时写入，扫描失败也会有） |
| `report.txt` | 扫描报告：差异、未匹配、本地已完成但本次没看到 |

用 `adb pull /sdcard/Android/data/com.local.stardbzzz/files/scan-debug/ .` 拉到电脑上。

### 1. 用当前代码重跑一次扫描（不需要手机）

```bat
set SCAN_REPLAY_DIR=C:\...\scan-debug\20261014-032011
gradlew testReleaseUnitTest --tests "*ScanReplayTest" --rerun
```

结果写在该文件夹的 `replay-report.txt`。它在报告之外，还会为每条未匹配的识别结果列出最接近的 3 个本地成就名，方便判断是哪个字认错了，再补进 `AchievementMatcher` 的易混字表。

旧的扫描文件夹如果没有 `candidates.tsv`：打开「保存调试数据」后在 App 里开始一次扫描（不在成就页、直接失败也可以），把新文件夹里的 `candidates.tsv` 复制过去。没有 `frames.tsv` 也能回放，只是匹配时没有分类提示。

### 2. 用 App 现在的 OCR 重读旧扫描的截图

旧版本（ML Kit）保存的扫描，可以在电脑上用 App 里同一套 PaddleOCR 代码和模型重新识别，不需要 Python：

```bat
set SCAN_OCR_DIR=C:\...\scan-debug\20261014-032011
gradlew testReleaseUnitTest --tests "*PaddleOcrTest" --rerun
```

每帧写出 `frame_NNNN.paddle.tsv`，并打印每帧耗时（电脑上的速度不能直接代表手机）。然后对比两个引擎：

```bat
set SCAN_REPLAY_DIR=C:\...\scan-debug\20261014-032011
set SCAN_REPLAY_COMPARE=.paddle.tsv
gradlew testReleaseUnitTest --tests "*ScanReplayTest" --rerun
set SCAN_REPLAY_SUFFIX=.paddle.tsv
set SCAN_REPLAY_COMPARE=
gradlew testReleaseUnitTest --tests "*ScanReplayTest" --rerun
```

- `replay-compare.txt`：两个引擎在同一帧上逐行比较。看「能匹配本地成就的行」两列谁高，以及「读法不同的行」里谁读对了。
- `replay-report.txt` / `replay-report.paddle.txt`：两个引擎各自的完整报告，重点比较未匹配条数和对账结果。

注意：截图是 JPEG（质量 90；第二轮是 70），手机上识别的是未压缩的原图，所以电脑上重读的结果会略差一点。

### 3. 试其他模型（可选）

`paddle_ocr.py` 用 RapidOCR 在电脑上跑任意 PaddleOCR ONNX 模型，例如 `onnxocr` pip 包里的 PP-OCRv6：

```bat
pip install "rapidocr>=3.4" onnxruntime pillow
python tools\ocr-compare\paddle_ocr.py C:\...\scan-debug\20261014-032011 --models <onnxocr>\models\ppocrv6 --suffix .v6.tsv
```

`--models` 指向含 det/rec 的 `.onnx` 和字典 `.txt` 的文件夹；不给时 RapidOCR 从 ModelScope 下载默认模型。输出的 TSV 同样可以用上面的 `SCAN_REPLAY_COMPARE` / `SCAN_REPLAY_SUFFIX` 对比。

## English

With 「保存调试数据」 on, each scan saves its frames (`frame_NNNN.jpg`, which **contain the UID: never upload or commit them**), the app's OCR (`frame_NNNN.tsv`; ML Kit up to v1.1.19, PaddleOCR after), the tab/category of each frame (`frames.tsv`), the local achievement list (`candidates.tsv`) and the report (`report.txt`) under `Android/data/com.local.stardbzzz/files/scan-debug/<session>/`.

- Replay a session with the current code: set `SCAN_REPLAY_DIR` to the folder and run `gradlew testReleaseUnitTest --tests "*ScanReplayTest" --rerun`. It writes `replay-report.txt`, including the three nearest local names for every unmatched OCR read.
- Re-read an older session's frames with the app's current OCR: set `SCAN_OCR_DIR` and run `gradlew testReleaseUnitTest --tests "*PaddleOcrTest" --rerun`. It writes `frame_NNNN.paddle.tsv` using the same Kotlin code and bundled models as the app.
- Compare engines: set `SCAN_REPLAY_COMPARE=.paddle.tsv` for a row-by-row comparison in `replay-compare.txt`, or `SCAN_REPLAY_SUFFIX=.paddle.tsv` for a full report from those reads.
- Try other models: `python tools/ocr-compare/paddle_ocr.py <folder> --models <dir>` runs any PaddleOCR ONNX models through RapidOCR (e.g. PP-OCRv6 from the `onnxocr` pip package).
