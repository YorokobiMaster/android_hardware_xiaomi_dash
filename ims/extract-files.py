#!/usr/bin/env python3
# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

import argparse
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory


RELEASE = "OS4.0.4.0.XPLCNXM"
OTA = f"dash-ota_full-{RELEASE}-user-17.0-81a5c2e9c7.zip"
# Shared MIUI system_ext identifies itself by its own build, not the dash OTA version.
SYSTEM_EXT_BUILD = "17OS4.0.261028.233904816.MTPECN.S"
APK_FILES = ("ImsService/ImsService.apk", "MtkGbaService/MtkGbaService.apk")
FILES = {
    **{f"priv-app/{apk}": apk for apk in APK_FILES},
    **{path: path for path in (
        "framework/mediatek-common.jar",
        "framework/mediatek-ims-base.jar",
        "bin/vtservice",
        "lib64/libcomutils.so",
        "lib64/libimsma.so",
        "lib64/libimsma_adapt.so",
        "lib64/libimsma_rtp.so",
        "lib64/libimsma_socketwrapper.so",
        "lib64/libmtk_vt_service.so",
        "lib64/libmtk_vt_wrapper.so",
        "lib64/libsignal.so",
        "lib64/libsink.so",
        "lib64/libsource.so",
        "lib64/libvcodec_cap.so",
        "lib64/libvcodec_capenc.so",
        "lib64/libvt_avsync.so",
        "lib64/vendor.mediatek.hardware.videotelephony-V1-ndk.so",
        "lib64/vendor.mediatek.hardware.videotelephony@1.0.so",
    )},
}
BLUEPRINTS = {"Android.bp.in": "Android.bp", "lib64.Android.bp.in": "lib64/Android.bp"}


def read_erofs(image, path, **kwargs):
    return subprocess.run(
        ["dump.erofs", "--cat", f"--path=/{path}", str(image)], check=True, **kwargs
    ).stdout


def extract(image, vendor):
    props = read_erofs(image, "etc/build.prop", capture_output=True, text=True)
    if f"ro.system_ext.build.version.incremental={SYSTEM_EXT_BUILD}" not in props.splitlines():
        raise ValueError(f"Expected system_ext.img from {OTA}")

    vendor.mkdir(parents=True, exist_ok=True)
    templates = Path(__file__).resolve().parent / "prebuilts"
    ims = vendor / "ims"
    with TemporaryDirectory(dir=vendor) as staging:
        staging = Path(staging)
        for origin, target in FILES.items():
            out = staging / target
            out.parent.mkdir(parents=True, exist_ok=True)
            with out.open("wb") as stream:
                read_erofs(image, origin, stdout=stream)
            if not out.stat().st_size:
                raise ValueError(f"Empty file: /{origin}")
            if target == "bin/vtservice":
                out.chmod(0o755)
        for template, target in BLUEPRINTS.items():
            (staging / target).write_bytes((templates / template).read_bytes())
        for target in (*FILES.values(), *BLUEPRINTS.values()):
            # Follow the same destination if a source view already links to these files.
            out = (ims / target).resolve()
            out.parent.mkdir(parents=True, exist_ok=True)
            (staging / target).replace(out)
        namespace = vendor / "Android.bp"
        if not namespace.exists():
            namespace.write_text("soong_namespace {\n}\n")


def main():
    parser = argparse.ArgumentParser(
        description=f"Extract dash IMS and video calling prebuilts from {RELEASE}.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=f"""From the Android source root:
  Get the stock full OTA at https://bigota.d.miui.com/{RELEASE}/{OTA}
  payload-dumper-go -p system_ext -o ota-images {OTA}
  python3 hardware/xiaomi/dash/ims/extract-files.py ota-images/system_ext.img

The extractor requires dump.erofs with --cat support. Keep the extracted
vendor inputs local; run the same command to replace old IMS inputs.""",
    )
    parser.add_argument("image", type=Path, help="raw EROFS system_ext.img")
    parser.add_argument("--vendor-dir", type=Path, default=Path("vendor/xiaomi/dash"),
                        help="dash vendor directory (default: vendor/xiaomi/dash)")
    args = parser.parse_args()
    try:
        extract(args.image.resolve(strict=True), args.vendor_dir)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{parser.prog}: {error}\n")


if __name__ == "__main__":
    main()
