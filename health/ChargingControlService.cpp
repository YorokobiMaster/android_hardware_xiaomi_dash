/*
 * Copyright (C) 2026 YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "ChargingControlService.h"

#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>

#include <aidl/vendor/lineage/health/BnChargingControl.h>
#include <aidl/vendor/lineage/health/ChargingControlSupportedMode.h>

#include <fcntl.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <mutex>
#include <string>

namespace {

using aidl::vendor::lineage::health::BnChargingControl;
using aidl::vendor::lineage::health::ChargingLimitInfo;

constexpr char kSmartChargePath[] = "/sys/class/power_supply/battery/smart_chg";
constexpr char kStateDir[] = "/data/misc/dash-health";
constexpr char kStatePath[] = "/data/misc/dash-health/limit";
constexpr char kStateTempPath[] = "/data/misc/dash-health/limit.tmp";
constexpr int32_t kNavigationBit = 1 << 1;
constexpr int32_t kCommandErrorBit = 1;
constexpr int32_t kRechargeMargin = 5;

bool readInt(const char* path, int32_t* value) {
  std::string content;
  if (!android::base::ReadFileToString(path, &content, true)) {
    PLOG(ERROR) << "read " << path;
    return false;
  }
  if (!android::base::ParseInt(android::base::Trim(content), value)) {
    LOG(ERROR) << "Invalid integer in " << path;
    return false;
  }
  return true;
}

bool writeInt(const char* path, int32_t value) {
  if (!android::base::WriteStringToFile(std::to_string(value), path, true)) {
    PLOG(ERROR) << "write " << path;
    return false;
  }
  return true;
}

class ChargingControl : public BnChargingControl {
 public:
  ChargingControl() {
    std::string content;
    if (!android::base::ReadFileToString(kStatePath, &content)) {
      if (errno != ENOENT) {
        PLOG(ERROR) << "read " << kStatePath;
      }
      return;
    }
    auto fields = android::base::Split(android::base::Trim(content), " ");
    if (fields.size() != 2 ||
        (fields[0] != "pending" && fields[0] != "applied") ||
        !android::base::ParseInt(fields[1], &mTarget, kRechargeMargin, 100)) {
      LOG(ERROR) << "Invalid charging limit state";
      return;
    }
    mHasState = true;
    mPending = fields[0] == "pending";
  }

  ndk::ScopedAStatus getChargingEnabled(bool*) override {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
  }

  ndk::ScopedAStatus setChargingEnabled(bool) override {
    return ndk::ScopedAStatus::fromExceptionCode(EX_UNSUPPORTED_OPERATION);
  }

  ndk::ScopedAStatus getSupportedMode(int32_t* mode) override {
    *mode = static_cast<int32_t>(
        aidl::vendor::lineage::health::ChargingControlSupportedMode::LIMIT);
    return ndk::ScopedAStatus::ok();
  }

  ndk::ScopedAStatus setChargingDeadline(int64_t) override {
    return ndk::ScopedAStatus::fromServiceSpecificError(-1);
  }

  ndk::ScopedAStatus getChargingDeadline(int64_t*) override {
    return ndk::ScopedAStatus::fromServiceSpecificError(-1);
  }

  ndk::ScopedAStatus getChargingLimit(ChargingLimitInfo* limit) override {
    std::lock_guard<std::mutex> lock(mMutex);
    int32_t flags;
    if (!readInt(kSmartChargePath, &flags)) {
      return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }

    // This HAL owns Navigation's threshold; other smart_chg selectors remain
    // independent. Readback contains enable bits, not that threshold's value.
    // A disabled Navigation function always represents an unrestricted limit.
    if (!(flags & kNavigationBit)) {
      if ((mPending || (mHasState && mTarget != 100)) && !saveState(100, false)) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
      }
      limit->min = 0;
      limit->max = 100;
      return ndk::ScopedAStatus::ok();
    }
    if (!mHasState || (!mPending && mTarget == 100)) {
      // Limit reads before every set, including reset. Establish a known
      // unrestricted state so missing records cannot prevent it from repairing
      // or disabling an old Navigation request. Other feature bits stay intact.
      LOG(WARNING) << "Recovering an untracked Navigation charging limit";
      if (!saveState(100, true)) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
      }
    }
    // Finish an interrupted command before reporting its target as applied.
    if (mPending && !applyLimit()) {
      return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
    }
    limit->min = mTarget == 100 ? 0 : mTarget - kRechargeMargin;
    limit->max = mTarget;
    return ndk::ScopedAStatus::ok();
  }

  ndk::ScopedAStatus setChargingLimit(const ChargingLimitInfo& limit) override {
    if (limit.max < kRechargeMargin || limit.max > 100 ||
        limit.min != (limit.max == 100 ? 0 : limit.max - kRechargeMargin)) {
      return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }
    std::lock_guard<std::mutex> lock(mMutex);
    // Persist intent before the write-only command; an interrupted request can
    // then be replayed instead of guessing the threshold from status flags.
    if (!saveState(limit.max, true) || !applyLimit()) {
      return ndk::ScopedAStatus::fromServiceSpecificError(-1);
    }
    return ndk::ScopedAStatus::ok();
  }

 private:
  bool saveState(int32_t target, bool pending) {
    android::base::unique_fd fd(open(kStateTempPath,
        O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600));
    const std::string content =
        std::string(pending ? "pending " : "applied ") + std::to_string(target) + "\n";
    if (fd.get() < 0 || !android::base::WriteStringToFd(content, fd) ||
        fsync(fd.get()) != 0 || rename(kStateTempPath, kStatePath) != 0) {
      PLOG(ERROR) << "persist charging limit";
      return false;
    }
    android::base::unique_fd dir(open(kStateDir, O_RDONLY | O_DIRECTORY | O_CLOEXEC));
    if (dir.get() < 0 || fsync(dir.get()) != 0) {
      PLOG(ERROR) << "sync charging limit directory";
      return false;
    }
    mTarget = target;
    mPending = pending;
    mHasState = true;
    return true;
  }

  bool applyLimit() {
    const bool enabled = mTarget < 100;
    const int32_t command = (mTarget << 16) | kNavigationBit | enabled;
    if (!writeInt(kSmartChargePath, command)) {
      return false;
    }
    int32_t flags;
    if (!readInt(kSmartChargePath, &flags) || (flags & kCommandErrorBit) ||
        static_cast<bool>(flags & kNavigationBit) != enabled) {
      LOG(ERROR) << "Kernel did not confirm the charging limit command";
      return false;
    }
    return saveState(mTarget, false);
  }

  std::mutex mMutex;
  int32_t mTarget = 100;
  bool mHasState = false;
  bool mPending = false;
};

}  // namespace

int dash::runChargingControlService(const char* instance) {
  ABinderProcess_setThreadPoolMaxThreadCount(0);
  auto service = ndk::SharedRefBase::make<ChargingControl>();

  binder_status_t status =
      AServiceManager_addService(service->asBinder().get(), instance);
  if (status != STATUS_OK) {
    LOG(ERROR) << "Failed to publish " << instance << ": " << status;
    return 1;
  }

  ABinderProcess_startThreadPool();
  ABinderProcess_joinThreadPool();
  return 0;
}
