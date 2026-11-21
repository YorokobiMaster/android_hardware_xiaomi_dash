# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

PRODUCT_PACKAGES += \
    ImsService \
    MtkGbaService \
    dash-ims-appcompat \
    ims-compat-migrate \
    init.dash-ims.rc \
    mediatek-common \
    mediatek-ims-base \
    libmtk_vt_wrapper \
    libvcodec_cap \
    libvcodec_capenc \
    privapp-permissions-mediatek-ims \
    sysconfig-com.mediatek.ims \
    vendor.mediatek.hardware.videotelephony-V1-ndk \
    vendor.mediatek.hardware.videotelephony@1.0 \
    vtservice

# IMS and GBA services load these classes from the boot class path.
PRODUCT_BOOT_JARS_EXTRA += \
    system_ext:mediatek-common \
    system_ext:mediatek-ims-base

DEVICE_PACKAGE_OVERLAYS += $(DASH_HARDWARE_PATH)/ims/overlay
