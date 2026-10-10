# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

DASH_HARDWARE_PATH ?= hardware/xiaomi/dash

$(call inherit-product, $(DASH_HARDWARE_PATH)/packages/DashLedService/lineage.mk)

$(call inherit-product, $(DASH_HARDWARE_PATH)/packages/DashWake/lineage.mk)
