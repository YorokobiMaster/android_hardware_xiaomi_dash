# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

# From the ROM root, prime the official GKI cache outside Ninja:
# python3 hardware/xiaomi/dash/kernel/export-kleaf.py --workspace <DASH_KERNEL_WORKSPACE> --fetch-official
DASH_KERNEL_PROFILE := $(if $(filter true,$(DASH_OFFICIAL_GKI)),official,normal)
