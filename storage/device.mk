# Copyright (C) 2026 YorokobiMaster
# SPDX-License-Identifier: Apache-2.0

# Second-stage init loads this property after first-stage mounts.
PRODUCT_SYSTEM_PROPERTIES += ro.boot.fstab_suffix=dash
PRODUCT_PACKAGES += fstab.dash
