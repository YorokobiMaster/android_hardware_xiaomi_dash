# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

import re
import sys
from pathlib import Path

WEAR_FLAG = b",sysfs_path=/sys/devices/platform/soc/16810000.ufshci"


def add_storage_wear(source):
    table = source.read_bytes()
    if b"\r" in table or b"\0" in table:
        raise ValueError("expected a text fstab with LF line endings")

    lines = table.split(b"\n")
    userdata = None
    for index, line in enumerate(lines):
        row = line.lstrip(b" \t")
        if not row or row.startswith(b"#"):
            continue
        fields = list(re.finditer(rb"[^ \t]+", row))
        if len(fields) < 5:
            raise ValueError(f"line {index + 1}: expected five fstab fields")
        block, mount, fs_type, _, flags = [field.group() for field in fields[:5]]
        if any(flag.startswith(b"sysfs_path=") for flag in flags.split(b",")):
            raise ValueError(f"line {index + 1}: existing sysfs_path")
        if mount != b"/data":
            continue
        if (userdata is not None or block != b"/dev/block/by-name/userdata" or
                fs_type != b"f2fs"):
            raise ValueError(f"line {index + 1}: unexpected /data entry")
        userdata = index
        end = len(line) - len(row) + fields[4].end()
        lines[index] = line[:end] + WEAR_FLAG + line[end:]
    if userdata is None:
        raise ValueError("missing userdata /data entry")
    return b"\n".join(lines)


if __name__ == "__main__":
    source, output = map(Path, sys.argv[1:])
    try:
        output.write_bytes(add_storage_wear(source))
    except ValueError as error:
        sys.exit(f"{source}: {error}")
