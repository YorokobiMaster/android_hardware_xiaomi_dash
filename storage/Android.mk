# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

LOCAL_PATH := $(call my-dir)

ifeq ($(TARGET_DEVICE),dash)
include $(CLEAR_VARS)
LOCAL_MODULE := fstab.dash
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_PATH := $(TARGET_ROOT_OUT)

# Use the runtime table extracted from vendor.img by boot/extract-inputs.py.
dash_storage_fstab := vendor/xiaomi/dash/storage/fstab.mt6991
dash_storage_fstab_generator := $(LOCAL_PATH)/add-storage-wear.py
dash_storage_fstab_out := $(call intermediates-dir-for,ETC,$(LOCAL_MODULE))/fstab
$(dash_storage_fstab_out): PRIVATE_GENERATOR := $(dash_storage_fstab_generator)
$(dash_storage_fstab_out): $(dash_storage_fstab) $(dash_storage_fstab_generator)
	$(hide) mkdir -p $(dir $@)
	$(hide) python3 $(PRIVATE_GENERATOR) $< $@

LOCAL_PREBUILT_MODULE_FILE := $(dash_storage_fstab_out)
include $(BUILD_PREBUILT)
endif
