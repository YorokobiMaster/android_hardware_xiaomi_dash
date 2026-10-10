# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

DASH_HARDWARE_PATH ?= hardware/xiaomi/dash

PRODUCT_SOONG_NAMESPACES += $(DASH_HARDWARE_PATH)

PRODUCT_PACKAGES += privapp-permissions-dash-hardware
