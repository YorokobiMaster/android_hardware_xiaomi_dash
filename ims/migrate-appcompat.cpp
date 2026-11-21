/*
 * Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/unique_fd.h>
#include <tinyxml2.h>

namespace {

constexpr char kAppCompatDir[] = "/data/misc/appcompat";
constexpr char kOverridesFile[] = "compat_framework_overrides.xml";
constexpr char kMigrated[] = "persist.sys.dash.ims_appcompat_migrated";

bool RemoveLegacyOverride(tinyxml2::XMLDocument& overrides) {
    bool changed = false;
    auto* root = overrides.FirstChildElement("overrides");
    if (!root) return false;

    for (auto* change = root->FirstChildElement("change-overrides"); change;
         change = change->NextSiblingElement("change-overrides")) {
        if (change->Int64Attribute("changeId") != 161145287) continue;
        auto* raw = change->FirstChildElement("raw");
        if (!raw) continue;

        for (auto* entry = raw->FirstChildElement("raw-override-value"); entry;) {
            auto* next = entry->NextSiblingElement("raw-override-value");
            const char* name = entry->Attribute("packageName");
            bool enabled;
            int64_t min, max;
            if (name && std::strcmp(name, "com.mediatek.ims") == 0 &&
                entry->QueryBoolAttribute("enabled", &enabled) == tinyxml2::XML_SUCCESS &&
                !enabled &&
                entry->QueryInt64Attribute("minVersionCode", &min) == tinyxml2::XML_SUCCESS &&
                entry->QueryInt64Attribute("maxVersionCode", &max) == tinyxml2::XML_SUCCESS &&
                min == max) {
                // The product supplies an unbounded override; only the saved old pin masks it.
                raw->DeleteChild(entry);
                changed = true;
            }
            entry = next;
        }
    }
    return changed;
}

bool Migrate(const std::string& path) {
    android::base::unique_fd input(open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (input < 0) {
        if (errno == ENOENT) return true;
        PLOG(ERROR) << "Cannot open " << path;
        return false;
    }
    struct stat info;
    std::string text;
    if (fstat(input, &info) < 0 || !android::base::ReadFdToString(input, &text)) {
        PLOG(ERROR) << "Cannot read " << path;
        return false;
    }
    tinyxml2::XMLDocument overrides;
    if (overrides.Parse(text.data(), text.size()) != tinyxml2::XML_SUCCESS) {
        LOG(ERROR) << "Cannot parse " << path << ": " << overrides.ErrorStr();
        return false;
    }
    if (!RemoveLegacyOverride(overrides)) return true;

    tinyxml2::XMLPrinter printer;
    overrides.Print(&printer);
    std::string temporary = path + ".XXXXXX";
    android::base::unique_fd output(mkostemp(temporary.data(), O_CLOEXEC));
    if (output < 0) {
        PLOG(ERROR) << "Cannot stage " << path;
        return false;
    }
    // Both files are owned by system:system and inherit the appcompat directory label.
    struct stat staged;
    if (fstat(output, &staged) < 0) {
        PLOG(ERROR) << "Cannot inspect " << temporary;
        unlink(temporary.c_str());
        return false;
    }
    if (staged.st_uid != info.st_uid || staged.st_gid != info.st_gid) {
        LOG(ERROR) << "Unexpected ownership of " << path;
        unlink(temporary.c_str());
        return false;
    }
    if (fchmod(output, info.st_mode & 07777) < 0 ||
        !android::base::WriteStringToFd(printer.CStr(), output) || fsync(output) < 0 ||
        rename(temporary.c_str(), path.c_str()) < 0) {
        PLOG(ERROR) << "Cannot update " << path;
        unlink(temporary.c_str());
        return false;
    }
    return true;
}

}  // namespace

int main() {
    // CompatConfig restores .bak before loading the saved override, if present.
    const std::string path = std::string(kAppCompatDir) + "/" + kOverridesFile;
    if (!Migrate(path + ".bak") || !Migrate(path)) return 1;
    android::base::unique_fd dir(open(kAppCompatDir, O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (dir < 0 || fsync(dir) < 0) {
        PLOG(ERROR) << "Cannot sync " << kAppCompatDir;
        return 1;
    }
    if (!android::base::SetProperty(kMigrated, "1")) {
        LOG(ERROR) << "Cannot mark IMS appcompat migration complete";
        return 1;
    }
    return 0;
}
