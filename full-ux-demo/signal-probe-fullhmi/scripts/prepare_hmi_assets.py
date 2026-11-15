#!/usr/bin/env python3
"""Validate and install externally generated RE:SAFETY HMI PNG assets.

Changed from the intake-kit original (kept beside this file as prepare_hmi_assets.kit_original.py.txt), 2026-11-14:
- HMI_BG_01_NORMAL.png and HMI_BG_02_ACCIDENT.png are REQUIRED runtime assets: both must exist and pass, otherwise nothing is installed.
- HMI_REF_03_VEHICLE_DETAIL.png and HMI_REF_04_NIGHT_ENVIRONMENT.png are OPTIONAL references: a missing one is a warning only;
  a present one is still checked, and a bad optional file is reported as a warning (it is never packaged anyway).
Exit codes: 0 = runtime assets verified (and installed unless --verify-only), 1 = a runtime asset failed, 2 = a runtime asset is missing.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import struct
import sys
from datetime import datetime, timezone
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
RUNTIME_FILES = {
    "HMI_BG_01_NORMAL.png": "hmi_bg_01_normal.png",
    "HMI_BG_02_ACCIDENT.png": "hmi_bg_02_accident.png",
}
REFERENCE_FILES = (
    "HMI_REF_03_VEHICLE_DETAIL.png",
    "HMI_REF_04_NIGHT_ENVIRONMENT.png",
)
MIN_WIDTH = 1600
MIN_HEIGHT = 2000


def png_dimensions(path: Path) -> tuple[int, int]:
    with path.open("rb") as file:
        signature = file.read(8)
        if signature != PNG_SIGNATURE:
            raise ValueError("PNG signature가 아닙니다")
        length = struct.unpack(">I", file.read(4))[0]
        chunk_type = file.read(4)
        if chunk_type != b"IHDR" or length < 8:
            raise ValueError("유효한 PNG IHDR을 찾을 수 없습니다")
        width, height = struct.unpack(">II", file.read(8))
    return width, height


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def copy_file(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def check(path: Path) -> tuple[dict | None, list[str]]:
    """Return (manifest entry, problems) for one PNG."""
    try:
        width, height = png_dimensions(path)
    except (OSError, ValueError, struct.error) as error:
        return None, [f"{path.name}: {error}"]
    problems = []
    if width < MIN_WIDTH or height < MIN_HEIGHT:
        problems.append(f"{path.name}: {width}x{height}, 최소 {MIN_WIDTH}x{MIN_HEIGHT}보다 작습니다")
    if width * 5 != height * 4:
        problems.append(f"{path.name}: {width}x{height}, 정확한 4:5 비율이 아닙니다")
    entry = {"fileName": path.name, "width": width, "height": height, "sizeBytes": path.stat().st_size, "sha256": sha256(path)}
    return entry, problems


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("hmi-assets/incoming"))
    parser.add_argument("--web-dir", type=Path, default=Path("public/assets/hmi"))
    parser.add_argument("--archive-dir", type=Path, default=Path("artifacts/hmi-visuals"))
    parser.add_argument("--android-web-assets-dir", type=Path)
    parser.add_argument("--android-res-dir", type=Path)
    parser.add_argument("--verify-only", action="store_true")
    return parser.parse_args()


def main() -> int:
    for stream in (sys.stdout, sys.stderr):                      # Korean messages on a Windows console
        try:
            stream.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass
    args = parse_args()

    missing = [name for name in RUNTIME_FILES if not (args.source / name).is_file()]
    if missing:
        print("필수 런타임 HMI PNG가 없습니다 (01·02는 둘 다 필요):", file=sys.stderr)
        for name in missing:
            print(f"  - {args.source / name}", file=sys.stderr)
        print("임시 placeholder는 허용되지 않습니다. 아무것도 설치하지 않았습니다.", file=sys.stderr)
        return 2

    manifest = {
        "verifiedAtUtc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "sourceDirectory": args.source.as_posix(),
        "assets": [],
        "warnings": [],
    }
    errors: list[str] = []
    for name in RUNTIME_FILES:
        entry, problems = check(args.source / name)
        errors.extend(problems)
        if entry:
            manifest["assets"].append({**entry, "runtime": True, "optional": False})

    references_ok: list[str] = []
    for name in REFERENCE_FILES:
        path = args.source / name
        if not path.is_file():
            manifest["warnings"].append(f"{name}: 선택 참고자료 없음 (런타임에는 영향 없음)")
            continue
        entry, problems = check(path)
        if problems or not entry:
            manifest["warnings"].extend(f"선택 참고자료 규격 미달 - {p}" for p in problems)
            continue
        manifest["assets"].append({**entry, "runtime": False, "optional": True})
        references_ok.append(name)

    if errors:
        print("HMI 런타임 이미지 검증 실패:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print("HMI 런타임 이미지 검증 통과:")
    for asset in manifest["assets"]:
        kind = "런타임 필수" if asset["runtime"] else "선택 참고"
        print(f"  - [{kind}] {asset['fileName']}: {asset['width']}x{asset['height']}, {asset['sizeBytes']} bytes")
    for warning in manifest["warnings"]:
        print(f"  경고: {warning}")

    if args.verify_only:
        return 0

    for name in RUNTIME_FILES:
        source = args.source / name
        copy_file(source, args.web_dir / name)
        if args.android_web_assets_dir:
            copy_file(source, args.android_web_assets_dir / name)
        if args.android_res_dir:
            copy_file(source, args.android_res_dir / RUNTIME_FILES[name])

    for name in (*RUNTIME_FILES, *references_ok):                # references are archived only, never packaged
        copy_file(args.source / name, args.archive_dir / name)

    args.archive_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = args.archive_dir / "HMI_ASSET_MANIFEST.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"검증 매니페스트: {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
