# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

include $(DASH_HARDWARE_PATH)/kernel/profile.mk

$(call soong_config_set,kleaf,profile,$(DASH_KERNEL_PROFILE))
$(call soong_config_set,kleaf,workspace,$(DASH_KERNEL_WORKSPACE))
$(call soong_config_set_bool,kleaf,system_dlkm,true)
$(foreach partition,vendor_ramdisk vendor_dlkm,$(call soong_config_set,kleaf,$(partition)_modules,$(strip $(file <$(DASH_HARDWARE_PATH)/kernel/modules.$(partition)))))
$(call soong_config_set,kleaf,vendor_ramdisk_load,$(strip $(file <$(DASH_HARDWARE_PATH)/kernel/modules.load.platform)))
$(call soong_config_set,kleaf,vendor_dlkm_load,$(strip $(file <$(DASH_HARDWARE_PATH)/kernel/modules.load.vendor_dlkm)))
$(call soong_config_set,kleaf,system_dlkm_modules,$(strip $(file <$(DASH_HARDWARE_PATH)/kernel/modules.load.system_dlkm)))
$(call soong_config_set,kleaf,system_dlkm_load,$(SOONG_CONFIG_kleaf_system_dlkm_modules))

# Soong installs the kernel and partition modules; skip legacy ROM builders.
TARGET_NO_KERNEL := false
TARGET_NO_KERNEL_OVERRIDE := true
TARGET_KERNEL_VERSION := 6.6
BOARD_KERNEL_IMAGE_NAME := Image.lz4
BUILD_BROKEN_SRC_DIR_RW_ALLOWLIST += $(realpath $(DASH_KERNEL_WORKSPACE))

BOARD_DO_NOT_STRIP_VENDOR_RAMDISK_MODULES := true
BOARD_DO_NOT_STRIP_VENDOR_MODULES := true
