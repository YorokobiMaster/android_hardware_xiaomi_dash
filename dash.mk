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

PRODUCT_PACKAGES += DashCharging

PRODUCT_PACKAGES += DashDolby

$(call inherit-product, $(DASH_HARDWARE_PATH)/packages/DashLedService/led.mk)

$(call inherit-product, $(DASH_HARDWARE_PATH)/packages/DashWake/wake.mk)

PRODUCT_PACKAGES += dash-vibrator-adapter

PRODUCT_PACKAGES += privapp-permissions-dash-hardware
