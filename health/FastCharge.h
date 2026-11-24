/* Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
#pragma once

#include <aidl/vendor/lineage/health/BnFastCharge.h>
#include <android-base/unique_fd.h>

#include <mutex>
#include <optional>
#include <string>
#include <string_view>

namespace dash {

class FastCharge : public aidl::vendor::lineage::health::BnFastCharge {
 public:
  FastCharge(const char* stateDir, const char* stateFilename);
  void initialize();

  ndk::ScopedAStatus getSupportedFastChargeModes(int64_t* modes) override;
  ndk::ScopedAStatus getFastChargeMode(
      aidl::vendor::lineage::health::FastChargeMode* mode) override;
  ndk::ScopedAStatus setFastChargeMode(
      aidl::vendor::lineage::health::FastChargeMode mode,
      aidl::vendor::lineage::health::FastChargeMode* applied) override;
  binder_status_t dump(int fd, const char** args, uint32_t numArgs) override;

 private:
  bool restoreMode();
  bool saveMode(aidl::vendor::lineage::health::FastChargeMode mode);
  bool applySicMode(int mode);
  void handleUevent(std::string_view event);
  void listen();

  const std::string mStateDir;
  const std::string mStatePath;
  const std::string mStateTempPath;
  std::mutex mMutex;
  bool mReady = false;
  bool mTopSpeed = false;
  std::optional<bool> mUsbOnline;
  android::base::unique_fd mUeventFd;
};

}  // namespace dash
