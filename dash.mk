# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

DASH_HARDWARE_PATH ?= hardware/xiaomi/dash

PRODUCT_SOONG_NAMESPACES += $(DASH_HARDWARE_PATH)

$(call inherit-product, $(DASH_HARDWARE_PATH)/storage/device.mk)

# Audio
PRODUCT_PACKAGES += \
    dash-audio-parameter-parser \
    libaudiopolicymanagercustom

PRODUCT_SYSTEM_EXT_PROPERTIES += \
    ro.audio.ihaladaptervendorextension_enabled=true

# Device apps
PRODUCT_PACKAGES += \
    DashCharging \
    DashDolby \
    PowerOffAlarm

$(call inherit-product, $(DASH_HARDWARE_PATH)/packages/DashLedService/led.mk)
$(call inherit-product, $(DASH_HARDWARE_PATH)/packages/DashWake/wake.mk)

# Runtime configuration
PRODUCT_PACKAGES += \
    privapp-permissions-dash-hardware
