# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

PRODUCT_PACKAGES += \
    DashEsim \
    DashEsimGlobalOverlay \
    DashEsimIndiaOverlay \
    dash-esim-control \
    dash-esim-modem \
    init.dash-esim.rc

PRODUCT_COPY_FILES += \
    $(DASH_HARDWARE_PATH)/esim/config/features-disabled.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/dash-esim.xml \
    $(DASH_HARDWARE_PATH)/esim/config/features.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/dash-esim/features.xml \
    $(DASH_HARDWARE_PATH)/esim/config/privapp-permissions-dash-esim.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-dash-esim.xml
