// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0

#include <cstdlib>
#include <cstring>

// Android's property_get ABI: callers provide PROPERTY_VALUE_MAX (92) bytes.
extern "C" int mtk_property_get(const char* key, char* value, const char* fallback) {
    static const struct {
        const char* property;
        const char* environment;
    } kProperties[] = {
        {"ro.vendor.mtk_thin_md_telephony_support_mode", "DASH_ESIM_THIN_MD"},
        {"ro.vendor.md.state.path1", "DASH_ESIM_MD_STATE_PATH1"},
        {"ro.vendor.md.state.path2", "DASH_ESIM_MD_STATE_PATH2"},
        {"ro.vendor.md.state.ready", "DASH_ESIM_MD_STATE_READY"},
        {"ro.vendor.md.state.ready.key", "DASH_ESIM_MD_STATE_READY_KEY"},
        {"ro.vendor.md.flag.ready.key", "DASH_ESIM_MD_FLAG_READY_KEY"},
        {"ro.vendor.md.ctrl.ready.key", "DASH_ESIM_MD_CTRL_READY_KEY"},
        {"ro.vendor.md.ctrl.path1", "DASH_ESIM_MD_CTRL_PATH1"},
        {"ro.vendor.md.ctrl.path2", "DASH_ESIM_MD_CTRL_PATH2"},
    };
    for (const auto& entry : kProperties) {
        if (std::strcmp(key, entry.property) != 0) continue;
        const char* source = std::getenv(entry.environment);
        if (source == nullptr || *source == '\0' || std::strcmp(source, "@unset") == 0) {
            source = fallback;
        }
        if (source == nullptr) source = "";
        const auto length = std::strlen(source);
        if (length >= 92) {
            value[0] = '\0';
            return 0;
        }
        std::memcpy(value, source, length + 1);
        return static_cast<int>(length);
    }
    // No unrestricted vendor-property access from this system-side client.
    value[0] = '\0';
    return 0;
}
