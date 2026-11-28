#!/usr/bin/env python3
# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

import argparse
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory


def extract_inputs(image_dir, unpacker, work):
    vbmeta = (image_dir / "vbmeta_vendor.img").read_bytes()
    if not vbmeta.startswith(b"AVB0"):
        raise ValueError("vbmeta_vendor.img has no AVB header")

    packed = work / "vendor_boot"
    raw = subprocess.check_output([
        sys.executable, unpacker, "--boot_img", image_dir / "vendor_boot.img",
        "--out", packed, "--format=mkbootimg", "--null",
    ])
    args = raw.decode().split("\0")[:-1]
    options = list(zip(args[::2], args[1::2]))
    if ("--header_version", "4") not in options:
        raise ValueError("expected a version 4 vendor_boot image")

    # AOSP names the symlink for the stock unnamed fragment "ramdisk_".
    fragment = packed / "vendor-ramdisk-by-name/ramdisk_"
    fragment_path = str(fragment.resolve(strict=True))
    ramdisk = {}
    for name, value in options:
        if name == "--ramdisk_type":
            ramdisk = {name: value}
        elif name == "--ramdisk_name":
            ramdisk[name] = value
        elif name == "--vendor_ramdisk_fragment" and value == fragment_path:
            if ramdisk == {"--ramdisk_type": "1", "--ramdisk_name": ""}:
                break
    else:
        raise ValueError("expected the unnamed platform ramdisk (type 1)")

    dtb = (packed / "dtb").read_bytes()
    if not dtb.startswith(b"\xd7\xb7\xab\x1e"):
        raise ValueError("vendor_boot does not contain the platform DTB table")

    fstab_path = "first_stage_ramdisk/fstab.mt6991"
    with subprocess.Popen(["lz4", "-dc", fragment], stdout=subprocess.PIPE) as archive:
        fstab = subprocess.check_output(
            ["cpio", "--quiet", "--extract", "--to-stdout", fstab_path, "./" + fstab_path],
            stdin=archive.stdout,
        )
        archive.stdout.close()
        if archive.wait():
            raise ValueError("could not decompress the platform ramdisk")
    if not fstab:
        raise ValueError(f"platform ramdisk does not contain {fstab_path}")

    return {
        "kernel-inputs/platform.dtb": dtb,
        f"kernel-inputs/platform/{fstab_path}": fstab,
        "avb/vbmeta_vendor.img": vbmeta,
    }


def extract_vendor_fstab(image_dir):
    path = "/etc/fstab.mt6991"
    fstab = subprocess.check_output(["dump.erofs", "--cat", f"--path={path}",
                                     image_dir / "vendor.img"])
    data_rows = [fields for line in fstab.split(b"\n")
                 if (fields := line.split()) and not fields[0].startswith(b"#")
                 and fields[1:2] == [b"/data"]]
    if (len(data_rows) != 1 or len(data_rows[0]) < 5 or
            data_rows[0][:3] != [b"/dev/block/by-name/userdata", b"/data", b"f2fs"]):
        raise ValueError(f"vendor.img:{path} does not contain a unique userdata /data entry")
    return fstab


def main():
    parser = argparse.ArgumentParser(
        description="Extract dash OS4 boot inputs. Run from an Android source root; "
                    "requires lz4 and cpio.",
    )
    parser.add_argument("image_dir", type=Path,
                        help="factory image directory containing vendor_boot.img and vbmeta_vendor.img")
    parser.add_argument("vendor_dir", type=Path, nargs="?", default=Path("vendor/xiaomi/dash"),
                        help="destination (default: vendor/xiaomi/dash)")
    parser.add_argument("--with-storage", action="store_true",
                        help="also extract the runtime fstab from vendor.img (requires dump.erofs)")
    args = parser.parse_args()

    unpacker = Path("system/tools/mkbootimg/unpack_bootimg.py")
    if not unpacker.is_file():
        parser.error("run from an Android source root with system/tools/mkbootimg/unpack_bootimg.py")
    try:
        with TemporaryDirectory(prefix="dash-boot-") as work:
            inputs = extract_inputs(args.image_dir, unpacker, Path(work))
            if args.with_storage:
                inputs["storage/fstab.mt6991"] = extract_vendor_fstab(args.image_dir)

        # Extract and validate everything before touching any existing input.
        for relative, data in inputs.items():
            target = (args.vendor_dir / relative).resolve()
            target.parent.mkdir(parents=True, exist_ok=True)
            with TemporaryDirectory(dir=target.parent) as work:
                staged = Path(work) / target.name
                staged.write_bytes(data)
                staged.replace(target)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{parser.prog}: {error}\n")


if __name__ == "__main__":
    main()
