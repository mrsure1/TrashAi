#!/usr/bin/env python3
"""
Train Ultralytics YOLO (default: YOLO26n, released 2026-01-14) on the TACO
trash dataset and export a TFLite model that the Android app can load
(assets/taco_yolo.tflite).

Workflow (one-shot):
  pip install -U ultralytics roboflow         # YOLO26 needs ultralytics ≥ 8.4.x
  python scripts/train_taco_yolov8.py         # train + export
  cp runs/detect/train/weights/best_saved_model/best_float32.tflite \\
     app/src/main/assets/taco_yolo.tflite

Notes
-----
- TACO official repo (pedropro/TACO) ships annotations in COCO JSON format.
  Easiest YOLO-ready re-export is via Roboflow Universe ("TACO Trash"),
  which gives a YOLO data.yaml + image zip.  Pass that yaml to --data, or
  drop it at ./datasets/taco/data.yaml (the default).
- Default model = YOLO26n with the end2end head: TFLite output is
  [1, 300, 6] (NMS-free).  Pass --legacy-head to export the YOLOv8-style
  [1, 4+nc, 8400] head instead.  The Android Kotlin detector auto-detects
  either format — no client change needed.
- License: YOLO26 weights are AGPL-3.0 (research/internal-use friendly).
  For closed-source distribution see Ultralytics Enterprise license.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=None,
                    help="Path to YOLO-format data.yaml. Default: ./datasets/taco/data.yaml")
    ap.add_argument("--epochs", type=int, default=50)
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--model", default="yolo26n.pt",
                    help="Base weights. yolo26n.pt = nano (recommended). "
                         "yolo26s.pt for higher accuracy. yolov8n.pt also works "
                         "(legacy head, identical Kotlin path).")
    ap.add_argument("--legacy-head", action="store_true",
                    help="Export with YOLOv8-style head instead of YOLO26's "
                         "default end2end head. Only relevant for YOLO26+.")
    ap.add_argument("--export-only", action="store_true",
                    help="Skip training; just export the latest best.pt to TFLite.")
    args = ap.parse_args()

    try:
        from ultralytics import YOLO
    except ImportError:
        sys.stderr.write("ultralytics not installed. Run: pip install -U ultralytics\n")
        sys.exit(1)

    data_yaml = Path(args.data) if args.data else Path("datasets/taco/data.yaml")
    if not args.export_only and not data_yaml.exists():
        sys.stderr.write(
            f"Dataset YAML not found at {data_yaml}.\n"
            "Get TACO-YOLO export from Roboflow Universe, or convert TACO COCO\n"
            "annotations with `pyodi coco-to-yolo`, then point --data at the YAML.\n"
        )
        sys.exit(2)

    if not args.export_only:
        model = YOLO(args.model)
        model.train(
            data=str(data_yaml),
            epochs=args.epochs,
            imgsz=args.imgsz,
            batch=16,
            patience=15,
            cache=True,
        )
        best_pt = Path("runs/detect/train/weights/best.pt")
    else:
        candidates = sorted(
            Path("runs/detect").glob("train*/weights/best.pt"),
            key=lambda p: p.stat().st_mtime,
        )
        if not candidates:
            sys.stderr.write("No runs/detect/train*/weights/best.pt found.\n")
            sys.exit(3)
        best_pt = candidates[-1]
        print(f"Using {best_pt} for export.")

    export_kwargs: dict = dict(format="tflite", imgsz=args.imgsz, int8=False)
    # YOLO26: end2end is the default. Setting end2end=False produces the
    # legacy head if user passed --legacy-head.
    if args.legacy_head:
        export_kwargs["end2end"] = False
    YOLO(str(best_pt)).export(**export_kwargs)

    print(
        "\nDone.\n"
        "Copy the exported model into the app:\n"
        "  cp runs/detect/train/weights/best_saved_model/best_float32.tflite \\\n"
        "     app/src/main/assets/taco_yolo.tflite\n"
        "and rebuild the app.\n"
    )


if __name__ == "__main__":
    main()
