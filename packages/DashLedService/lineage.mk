# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

# DashLED owns the ring; disable Lineage's standard light controls.
DEVICE_PACKAGE_OVERLAYS += $(DASH_HARDWARE_PATH)/packages/DashLedService/overlay-lineage
