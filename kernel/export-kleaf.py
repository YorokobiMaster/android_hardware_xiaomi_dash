#!/usr/bin/env python3
# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

import argparse
import filecmp
import fcntl
from pathlib import Path
import shutil
import subprocess
import tempfile


DEVICE = Path(__file__).resolve().parent
DIST = "//kernel_device_modules-6.6:kernel_dist"
CORE = "//kernel-6.6:kernel_aarch64"
BOOTSTRAP = "//kernel_device_modules-6.6:ufs_wlun_bootstrap"
EARLY_CORE = {"bluetooth.ko", "libarc4.ko", "rfkill.ko"}


def read_list(filename):
    return (DEVICE / filename).read_text().split()


def replace_changed(source, target):
    if not target.is_symlink() and target.is_file() and filecmp.cmp(source, target, shallow=False):
        return
    with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as pending:
        temp = Path(pending.name)
    try:
        shutil.copyfile(source, temp)
        temp.chmod(0o644)
        temp.replace(target)
    finally:
        temp.unlink(missing_ok=True)


def produce(dist, stage, workspace, profile, system_dlkm):
    def kleaf(*args, capture=False):
        return subprocess.run(
            ["bash", str(DEVICE / "build-kleaf.sh"), "--workspace", str(workspace), *args],
            check=True, stdout=subprocess.PIPE if capture else None, text=True,
        ).stdout

    flags = ("--cpu=arm64", "--experimental_convenience_symlinks=ignore")
    # The dist packages MTK modules and UAPI under the relaxed KMI policy.
    kleaf("--run", *flags, DIST, "--", f"--destdir={dist}")

    # Enforce KMI on the common core and the bootstrap tied to it. Ask Bazel
    # for this configuration's physical bin path, not the workspace bazel-bin.
    strict = (*flags, "--//build/kernel/kleaf:kmi_symbol_list_violations_check=true")
    modules = set()
    if profile == "normal":
        modules.update(EARLY_CORE)
        if system_dlkm:
            modules.update(read_list("modules.load.system_dlkm"))
    elif system_dlkm:
        modules.add("tls.ko")
    kleaf(*strict, CORE, CORE + "_config", BOOTSTRAP,
          *(f"{CORE}/{name}" for name in sorted(modules)))
    bin_path = Path(kleaf("--info", *strict, "bazel-bin", capture=True).strip())
    core = bin_path / "kernel-6.6/kernel_aarch64"
    config = core.with_name(core.name + "_config") / "out_dir/.config"
    bootstrap = bin_path / "kernel_device_modules-6.6/ufs_wlun_bootstrap/ufs_wlun_bootstrap.ko"
    constants = (workspace / "kernel-6.6/build.config.constants").read_text().splitlines()
    clang_version = next(line.split("=", 1)[1] for line in constants if line.startswith("CLANG_VERSION="))
    strip = workspace / "prebuilts/clang/host/linux-x86" / f"clang-{clang_version}/bin/llvm-strip"
    stripped_bootstrap = stage / bootstrap.name
    subprocess.run([str(strip), "--strip-debug", "-o", str(stripped_bootstrap), str(bootstrap)], check=True)
    return core, config, stripped_bootstrap


def official_core(workspace):
    release = "android15-6.6-2026-10_r3"
    build = 16867777
    cache = workspace / "out/bazel/repositories/dash_official_gki" / release
    url = f"https://ci.android.com/builds/submitted/{build}/kernel_aarch64/latest/raw"
    cache.mkdir(parents=True, exist_ok=True)

    def available(name):
        path = cache / name
        return path.is_file() and path.stat().st_size > 0

    def fetch(name):
        if not available(name):
            pending = cache / (name + ".part")
            subprocess.run(
                ["curl", "--fail", "--location", "--connect-timeout", "15",
                 "--max-time", "180", "--continue-at", "-", "--output",
                 str(pending), f"{url}/{name}"],
                check=True,
            )
            pending.replace(cache / name)
        return cache / name

    image = fetch("Image.lz4")
    if not available(".config"):
        pending = cache / ".config.part"
        with pending.open("wb") as output:
            subprocess.run(
                [str(workspace / "kernel-6.6/scripts/extract-ikconfig"), str(image)],
                stdout=output, check=True,
            )
        if not pending.stat().st_size:
            raise ValueError(f"official GKI {release} ({build}): Image.lz4 has no configuration")
        pending.replace(cache / ".config")

    signed = set(read_list("modules.load.system_dlkm")) - {"tls.ko"}
    if not all(available(name) for name in signed):
        image = fetch("system_dlkm.flatten.erofs.img")
        with tempfile.TemporaryDirectory(dir=cache, prefix="extract.") as temp:
            subprocess.run(["fsck.erofs", f"--extract={temp}", str(image)], check=True)
            for module in Path(temp).rglob("*.ko"):
                if module.name in signed and not available(module.name):
                    module.replace(cache / module.name)
    missing = sorted(name for name in signed if not available(name))
    if missing:
        raise ValueError(f"official GKI {release} ({build}) lacks {missing}")
    return cache


def export(out, dist, stage, core, config, bootstrap, official, system_dlkm):
    ramdisk = read_list("modules.vendor_ramdisk")
    vendor = read_list("modules.vendor_dlkm")
    system = read_list("modules.load.system_dlkm")
    recovery = read_list("modules.load.recovery")
    for load, install in [("platform", ramdisk), ("recovery", ramdisk), ("vendor_dlkm", vendor)]:
        missing = set(read_list("modules.load." + load)).difference(install)
        if missing:
            raise ValueError(f"{load}: load list contains uninstalled modules: {sorted(missing)}")
    if system != (dist / "system_dlkm.modules.load").read_text().split():
        raise ValueError("system_dlkm: shared load list differs from kernel_dist")

    selected_core = official if official else core
    if official:
        config = official / ".config"
    exports = {
        "Image.lz4": selected_core / "Image.lz4",
        ".config": config,
        "kernel-uapi-headers.tar.gz": dist / "kernel-uapi-headers.tar.gz",
    }
    for name in ramdisk:
        if name == bootstrap.name:
            source = bootstrap
        elif name in EARLY_CORE:
            source = selected_core / name
        else:
            source = dist / "vendor_ramdisk" / name
        exports[f"modules/vendor_ramdisk/{name}"] = source
    for name in vendor:
        exports[f"modules/vendor_dlkm/{name}"] = dist / name
    if system_dlkm:
        for name in system:
            # Official GKI omits TLS; it uses this build's audited vendor-KMI module.
            source_core = core if name == "tls.ko" else selected_core
            exports[f"modules/system_dlkm/{name}"] = source_core / name

    staged_load = stage / "modules.load.recovery"
    staged_load.write_text("\n".join(recovery) + "\n")
    exports[staged_load.name] = staged_load

    for source in exports.values():
        if not source.is_file():
            raise FileNotFoundError(f"missing kernel output: {source}")
    for target, source in exports.items():
        path = out / target
        path.parent.mkdir(parents=True, exist_ok=True)
        replace_changed(source, path)

    # The partition directories are producer-owned. A removed module must not
    # remain visible after a list or system_dlkm change.
    for stale in (out / "modules").glob("*/*.ko"):
        if str(stale.relative_to(out)) not in exports:
            stale.unlink()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--profile", choices=["normal", "official"], required=True)
    parser.add_argument("--system-dlkm", choices=["true", "false"], required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    workspace = args.workspace.resolve(strict=True)
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    # Both ROMs reuse the same Bazel cache. Relaxed and strict actions can
    # publish to the same physical output paths, so snapshot before another
    # ROM's producer can switch the flags.
    lock = workspace / "out/bazel/dash-kernel.lock"
    lock.parent.mkdir(parents=True, exist_ok=True)
    system_dlkm = args.system_dlkm == "true"
    with lock.open("a") as fd, tempfile.TemporaryDirectory(dir=out, prefix=".kleaf-") as temp:
        fcntl.flock(fd, fcntl.LOCK_EX)
        stage = Path(temp)
        dist = stage / "dist"
        core, config, bootstrap = produce(dist, stage, workspace, args.profile, system_dlkm)
        official = official_core(workspace) if args.profile == "official" else None
        export(out, dist, stage, core, config, bootstrap, official, system_dlkm)


if __name__ == "__main__":
    main()
