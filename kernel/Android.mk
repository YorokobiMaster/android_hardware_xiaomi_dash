# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

LOCAL_PATH := $(call my-dir)
ifeq ($(TARGET_DEVICE),dash)

.PHONY: kernel
kernel: $(PRODUCT_OUT)/kernel

# Native module installers export staging rules through installs.mk, not AndroidMk entries.
# Include their primary files so Make also includes the attached module ZIP contents.
ALL_DEFAULT_INSTALLED_MODULES += \
    $(TARGET_VENDOR_RAMDISK_OUT)/lib/modules/modules.load \
    $(TARGET_OUT_VENDOR_DLKM)/lib/modules/modules.load \
    $(TARGET_OUT_SYSTEM_DLKM)/lib/modules/modules.load

.PHONY: ufs-wlun-bootstrap
ufs-wlun-bootstrap: PRIVATE_KERNEL_BUILD_SCRIPT := $(LOCAL_PATH)/build-kleaf.sh
ufs-wlun-bootstrap:
	$(hide) bash $(PRIVATE_KERNEL_BUILD_SCRIPT) --workspace $(DASH_KERNEL_WORKSPACE) \
	    --//build/kernel/kleaf:kmi_symbol_list_violations_check=true \
	    //kernel_device_modules-6.6:ufs_wlun_bootstrap

.PHONY: adsp-modules
adsp-modules: PRIVATE_KERNEL_BUILD_SCRIPT := $(LOCAL_PATH)/build-kleaf.sh
adsp-modules:
	$(hide) bash $(PRIVATE_KERNEL_BUILD_SCRIPT) --workspace $(DASH_KERNEL_WORKSPACE) \
	    //kernel_device_modules-6.6:mgk_64_k66_modules.user/drivers/misc/mediatek/adsp/adsp.ko \
	    //kernel_device_modules-6.6:mgk_64_k66_modules.user/drivers/misc/mediatek/adsp/v2/adsp-v2.ko

endif
