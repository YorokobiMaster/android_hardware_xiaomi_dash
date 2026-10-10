# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

DASH_HARDWARE_PATH ?= hardware/xiaomi/dash

PRODUCT_SOONG_NAMESPACES += $(DASH_HARDWARE_PATH)

# Audio
PRODUCT_PACKAGES += \
    dash-audio-parameter-parser \
    libaudiopolicymanagercustom

PRODUCT_SYSTEM_EXT_PROPERTIES += \
    ro.audio.ihaladaptervendorextension_enabled=true

# Device apps
PRODUCT_PACKAGES += \
    DashCharging \
    DashDolby

$(call inherit-product, $(DASH_HARDWARE_PATH)/packages/DashLedService/led.mk)
$(call inherit-product, $(DASH_HARDWARE_PATH)/packages/DashWake/wake.mk)

# Vibrator
PRODUCT_PACKAGES += \
    dash-vibrator-adapter

# Runtime configuration
PRODUCT_PACKAGES += \
    privapp-permissions-dash-hardware
