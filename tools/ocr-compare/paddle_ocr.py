"""Re-OCR a debug scan session's frames with PaddleOCR (PP-OCRv5 mobile on ONNX Runtime, via RapidOCR).

For every frame_NNNN.jpg in the session folder this writes frame_NNNN.paddle.tsv in the same format as the
app's ML Kit output (frame_NNNN.tsv), so ScanReplayTest can build a report from either engine:

    pip install "rapidocr>=3.4" onnxruntime pillow
    python paddle_ocr.py "C:\\...\\scan-debug\\20261014-032011"

RapidOCR downloads the PP-OCRv5 mobile models from ModelScope on first use. To use local copies instead
(det.onnx / rec.onnx / ppocrv5_dict.txt, e.g. from the `onnxocr` wheel), pass --models DIR.
The frames contain the player's UID: keep them and the output on this computer.
"""
import argparse
import statistics
import sys
import time
from pathlib import Path

from PIL import Image


def build_engine(models: Path | None, threads: int):
    from rapidocr import ModelType, OCRVersion, RapidOCR

    params = {
        "Det.ocr_version": OCRVersion.PPOCRV5,
        "Det.model_type": ModelType.MOBILE,
        "Rec.ocr_version": OCRVersion.PPOCRV5,
        "Rec.model_type": ModelType.MOBILE,
        # Achievement rows are never rotated; the phone app wouldn't run the classifier either.
        "Global.use_cls": False,
        "EngineConfig.onnxruntime.intra_op_num_threads": threads,
    }
    if models:
        params.update({
            "Det.model_path": str(find_model(models, "det", "*.onnx")),
            "Rec.model_path": str(find_model(models, "rec", "*.onnx")),
            "Rec.rec_keys_path": str(find_model(models, "dict", "*.txt")),
        })
    return RapidOCR(params=params)


def find_model(models: Path, kind: str, pattern: str) -> Path:
    """The first file under [models] matching [pattern] with [kind] in its path (det/det.onnx or det.onnx)."""
    for path in sorted(models.rglob(pattern)):
        if kind in str(path.relative_to(models)).lower():
            return path
    raise FileNotFoundError(f"no {kind} {pattern} under {models}")


def to_tsv(result, width: int, height: int) -> str:
    lines = []
    if result.boxes is not None:
        for box, text in zip(result.boxes, result.txts):
            xs = [p[0] for p in box]
            ys = [p[1] for p in box]
            text = text.replace("\t", " ").replace("\n", " ")
            lines.append(f"{min(xs) / width:.4f}\t{min(ys) / height:.4f}\t{max(xs) / width:.4f}\t{max(ys) / height:.4f}\t{text}")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("session", type=Path, help="debug scan session folder with frame_NNNN.jpg")
    parser.add_argument("--models", type=Path, help="folder containing the det and rec .onnx models and the dict .txt")
    parser.add_argument("--suffix", default=".paddle.tsv", help="output suffix (default .paddle.tsv)")
    parser.add_argument("--max-width", type=int, default=0,
                        help="shrink wider frames to this width first (0 = full size, like the app)")
    parser.add_argument("--threads", type=int, default=4, help="ONNX Runtime threads (a phone has ~4 big cores)")
    args = parser.parse_args()

    frames = sorted(args.session.glob("frame_*.jpg"))
    if not frames:
        print(f"no frame_*.jpg in {args.session}", file=sys.stderr)
        return 1

    engine = build_engine(args.models, args.threads)
    timings = []
    for frame in frames:
        image = Image.open(frame).convert("RGB")
        if args.max_width and image.width > args.max_width:
            image = image.resize((args.max_width, round(image.height * args.max_width / image.width)))
        started = time.perf_counter()
        result = engine(image)
        timings.append(time.perf_counter() - started)
        out = frame.with_name(frame.stem + args.suffix)
        out.write_text(to_tsv(result, image.width, image.height), encoding="utf-8")
        print(f"{frame.name}: {timings[-1]:.2f}s, {0 if result.txts is None else len(result.txts)} lines")

    print(f"\n{len(frames)} frames, {sum(timings):.1f}s total, "
          f"median {statistics.median(timings):.2f}s, max {max(timings):.2f}s per frame "
          f"({args.threads} threads, {image.width}px wide)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
