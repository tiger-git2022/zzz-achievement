# 扫描离线回放与 OCR 引擎对比 / Offline scan replay and OCR engine comparison

## 中文

真机扫描时打开「保存调试数据」，每次扫描会在 `Android/data/com.local.stardbzzz/files/scan-debug/<时间>/` 下保存：

| 文件 | 内容 |
|---|---|
| `frame_NNNN.jpg` | 每一屏截图（**含 UID，不要上传或提交**） |
| `frame_NNNN.tsv` | ML Kit 识别结果：`left top right bottom text`，坐标按截图尺寸归一化 |
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

### 2. 对比 ML Kit 和 PaddleOCR（PP-OCRv5 mobile）

```bat
pip install "rapidocr>=3.4" onnxruntime pillow
python tools\ocr-compare\paddle_ocr.py C:\...\scan-debug\20261014-032011
```

脚本为每帧写出 `frame_NNNN.paddle.tsv`（格式同上），并打印每帧耗时。模型首次运行时从 ModelScope 下载；也可以用 `--models` 指向本地的 PP-OCRv5 模型文件夹（例如 `onnxocr` 这个 pip 包里的 `models/ppocrv5`）。`--max-width 1600` 可以测缩小后的速度和效果。

然后：

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

注意：截图是 JPEG（质量 90；第二轮是 70），ML Kit 在手机上识别的是未压缩的原图，所以这个对比对 PaddleOCR 略为不利。电脑上的速度也不能直接代表手机速度。

## English

With 「保存调试数据」 on, each scan saves its frames (`frame_NNNN.jpg`, which **contain the UID: never upload or commit them**), ML Kit's OCR (`frame_NNNN.tsv`), the tab/category of each frame (`frames.tsv`), the local achievement list (`candidates.tsv`) and the report (`report.txt`) under `Android/data/com.local.stardbzzz/files/scan-debug/<session>/`.

- Replay a session with the current code: set `SCAN_REPLAY_DIR` to the folder and run `gradlew testReleaseUnitTest --tests "*ScanReplayTest" --rerun`. It writes `replay-report.txt`, including the three nearest local names for every unmatched OCR read.
- Compare engines: `python tools/ocr-compare/paddle_ocr.py <folder>` writes `frame_NNNN.paddle.tsv` with PP-OCRv5 mobile (ONNX Runtime via RapidOCR) and prints timings. Then set `SCAN_REPLAY_COMPARE=.paddle.tsv` for a row-by-row comparison in `replay-compare.txt`, or `SCAN_REPLAY_SUFFIX=.paddle.tsv` for a full report from PaddleOCR's reads.
