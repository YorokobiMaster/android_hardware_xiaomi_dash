# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

ifeq ($(TARGET_DEVICE),dash)

dash_fstab := $(DASH_KERNEL_INPUTS)/platform/first_stage_ramdisk/fstab.mt6991
dash_fstab_out := $(TARGET_VENDOR_RAMDISK_OUT)/first_stage_ramdisk/fstab.mt6991
dash_fstab_filter := $(DASH_KERNEL_WORKSPACE)/kernel_device_modules-6.6/kernel/boot-compat/fstab-no-avb.awk

$(dash_fstab_out): PRIVATE_FSTAB_FILTER := $(dash_fstab_filter)
$(dash_fstab_out): $(dash_fstab) $(dash_fstab_filter)
	$(hide) mkdir -p $(dir $@)
	$(hide) set -o pipefail; \
	    awk -f $(PRIVATE_FSTAB_FILTER) $< | \
	    awk '$$1 == "/dev/block/by-name/vbmeta" { $$5 = "first_stage_mount,nofail,slotselect" } { print }' > $@
ALL_DEFAULT_INSTALLED_MODULES += $(dash_fstab_out)

# mkbootfs reads the executable's mode from the generated system table.
$(call intermediates-dir-for,PACKAGING,vendor_boot)/vendor_ramdisk.cpio.lz4: $(TARGET_OUT)/etc/fs_config_files

ifdef BUILDING_VBMETA_IMAGE
$(PRODUCT_OUT)/vbmeta.img: $(DASH_AVB_INPUTS)/vbmeta_vendor.img
endif

endif
