# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

PRODUCT_PACKAGES += \
    DashWake \
    default-permissions-dashwake \
    privapp-permissions-dashwake

DEVICE_PACKAGE_OVERLAYS += $(DASH_HARDWARE_PATH)/packages/DashWake/overlay
