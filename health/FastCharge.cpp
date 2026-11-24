/* Copyright (C) 2026 YorokobiMaster
 * SPDX-License-Identifier: Apache-2.0
 */
#include "FastCharge.h"
#include "FastChargeService.h"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <cutils/uevent.h>

#include <fcntl.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <string>
#include <thread>

namespace dash {
namespace {

using aidl::vendor::lineage::health::FastChargeMode;

constexpr char kSicPath[] = "/sys/class/thermal/thermal_message/charger_temp";
constexpr char kSmartSicPath[] = "/sys/class/power_supply/usb/smart_sic_mode";
constexpr char kUsbOnlinePath[] = "/sys/class/power_supply/usb/online";

bool readInt(const char* path, int* value) {
  std::string content;
  if (!android::base::ReadFileToString(path, &content, true) ||
      !android::base::ParseInt(android::base::Trim(content), value)) {
    LOG(ERROR) << "Cannot read integer from " << path;
    return false;
  }
  return true;
}

bool isSicMode(int mode) {
  return mode == 0 || mode == 2 || mode == 4 || mode == 8;
}

}  // namespace

FastCharge::FastCharge(const char* stateDir, const char* stateFilename)
    : mStateDir(stateDir),
      mStatePath(mStateDir + "/" + stateFilename),
      mStateTempPath(mStatePath + ".tmp") {}

bool FastCharge::saveMode(FastChargeMode mode) {
  android::base::unique_fd fd(open(mStateTempPath.c_str(),
      O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600));
  if (fd.get() < 0 ||
      !android::base::WriteStringToFd(std::to_string(static_cast<int>(mode)) + "\n", fd) ||
      fsync(fd.get()) != 0 || rename(mStateTempPath.c_str(), mStatePath.c_str()) != 0) {
    PLOG(ERROR) << "Cannot persist charging speed";
    return false;
  }
  android::base::unique_fd dir(open(mStateDir.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC));
  return dir.get() >= 0 && fsync(dir.get()) == 0;
}

void FastCharge::initialize() {
  // Subscribe before applying the startup mode so its feedback is queued.
  mReady = restoreMode();
  if (mReady) {
    std::thread([this] { listen(); }).detach();
  } else {
    mUeventFd.reset();
    LOG(ERROR) << "Fast charge unavailable; leaving charging control active";
  }
}

bool FastCharge::restoreMode() {
  mUeventFd.reset(uevent_open_socket(256 * 1024, true));
  if (mUeventFd.get() < 0) {
    PLOG(ERROR) << "Cannot listen for Xiaomi sensing-charge feedback";
    return false;
  }
  std::string state;
  if (android::base::ReadFileToString(mStatePath, &state)) {
    int mode;
    if (!android::base::ParseInt(android::base::Trim(state), &mode) ||
        (mode != static_cast<int>(FastChargeMode::FAST_CHARGE) &&
         mode != static_cast<int>(FastChargeMode::SUPER_FAST_CHARGE))) {
      LOG(ERROR) << "Invalid saved charging speed";
    } else {
      mTopSpeed = mode == static_cast<int>(FastChargeMode::SUPER_FAST_CHARGE);
    }
  } else if (errno != ENOENT) {
    PLOG(ERROR) << "Cannot read charging speed";
    return false;
  }
  int online;
  if (!readInt(kUsbOnlinePath, &online)) return false;
  mUsbOnline = online != 0;
  return applySicMode(mTopSpeed ? 8 : 0);
}

ndk::ScopedAStatus FastCharge::getSupportedFastChargeModes(int64_t* modes) {
  *modes = mReady ? static_cast<int64_t>(FastChargeMode::FAST_CHARGE) |
                        static_cast<int64_t>(FastChargeMode::SUPER_FAST_CHARGE)
                  : 0;
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus FastCharge::getFastChargeMode(FastChargeMode* mode) {
  if (!mReady) return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
  std::lock_guard<std::mutex> lock(mMutex);
  int sic;
  if (!readInt(kSicPath, &sic) || !isSicMode(sic) || (!mTopSpeed && sic != 0)) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
  }
  // A sensing override of 0/2/4 still belongs to the user's top-speed choice.
  *mode = mTopSpeed ? FastChargeMode::SUPER_FAST_CHARGE : FastChargeMode::FAST_CHARGE;
  return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus FastCharge::setFastChargeMode(FastChargeMode mode,
                                              FastChargeMode* applied) {
  if (!mReady) return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
  if (mode != FastChargeMode::FAST_CHARGE && mode != FastChargeMode::SUPER_FAST_CHARGE) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
  }
  std::lock_guard<std::mutex> lock(mMutex);
  const bool topSpeed = mode == FastChargeMode::SUPER_FAST_CHARGE;
  if (!applySicMode(topSpeed ? 8 : 0)) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
  }
  mTopSpeed = topSpeed;
  *applied = mode;
  // Framework owns the setting; retain its last applied choice across HAL
  // restarts, when the framework has no new settings change to replay.
  if (!saveMode(mode)) {
    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
  }
  return ndk::ScopedAStatus::ok();
}

bool FastCharge::applySicMode(int mode) {
  if (!android::base::WriteStringToFile(std::to_string(mode), kSicPath, true)) {
    PLOG(ERROR) << "Cannot set Xiaomi SIC mode " << mode;
    return false;
  }
  int actual;
  if (!readInt(kSicPath, &actual) || actual != mode) {
    LOG(ERROR) << "Xiaomi SIC mode readback mismatch";
    return false;
  }
  return true;
}

void FastCharge::handleUevent(std::string_view event) {
  std::optional<int> sensingMode;
  std::optional<bool> online;
  bool usb = false;
  while (!event.empty()) {
    const auto end = event.find('\0');
    if (end == std::string_view::npos) break;
    const auto field = event.substr(0, end);
    event.remove_prefix(end + 1);
    if (field == "POWER_SUPPLY_NAME=usb") usb = true;
    if (field == "POWER_SUPPLY_ONLINE=0") online = false;
    if (field == "POWER_SUPPLY_ONLINE=1") online = true;
    constexpr std::string_view prefix = "POWER_SUPPLY_SMART_SIC_MODE=";
    if (field.substr(0, prefix.size()) == prefix) {
      const auto value = field.substr(prefix.size());
      if (value.size() == 1 && isSicMode(value[0] - '0')) {
        sensingMode = value[0] - '0';
      }
    }
  }

  bool connectionChanged = false;
  if (usb && online) {
    connectionChanged = mUsbOnline && mUsbOnline != online;
    mUsbOnline = online;
  }
  // Stock resets sensing overrides on disconnect. Ordinary USB status
  // updates must not replace the active sensing mode with the base mode.
  if (connectionChanged && !*online) {
    applySicMode(mTopSpeed ? 8 : 0);
  } else if (mTopSpeed && sensingMode) {
    applySicMode(*sensingMode);
  } else if (connectionChanged) {
    applySicMode(mTopSpeed ? 8 : 0);
  }
}

void FastCharge::listen() {
  char buffer[64 * 1024];
  while (true) {
    const auto size = uevent_kernel_multicast_recv(mUeventFd.get(), buffer, sizeof(buffer));
    if (size < 0) {
      if (errno == EIO) continue;  // A message rejected by libcutils.
      if (errno == ENOBUFS) {
        std::lock_guard<std::mutex> lock(mMutex);
        int mode;
        if (mTopSpeed && readInt(kSmartSicPath, &mode) && isSicMode(mode)) {
          applySicMode(mode);
        }
        continue;
      }
      PLOG(FATAL) << "Lost Xiaomi sensing-charge event socket";
    }
    std::lock_guard<std::mutex> lock(mMutex);
    handleUevent(std::string_view(buffer, size));
  }
}

binder_status_t FastCharge::dump(int fd, const char**, uint32_t) {
  if (!mReady) {
    dprintf(fd, "Charging speed unavailable\n");
    return STATUS_OK;
  }
  std::lock_guard<std::mutex> lock(mMutex);
  int sic = -1;
  readInt(kSicPath, &sic);
  dprintf(fd, "Charging speed: %s\nSIC mode: %d\n",
          mTopSpeed ? "top speed" : "balanced", sic);
  return STATUS_OK;
}

}  // namespace dash

int dash::runFastChargeService(const char* instance, const char* stateDir,
                               const char* stateFilename) {
  ABinderProcess_setThreadPoolMaxThreadCount(0);
  auto service = ndk::SharedRefBase::make<FastCharge>(stateDir, stateFilename);
  const binder_status_t status = AServiceManager_addService(service->asBinder().get(), instance);
  if (status != STATUS_OK) {
    LOG(ERROR) << "Failed to publish " << instance << ": " << status;
    return 1;
  }

  // The framework waits for this instance before publishing HealthInterface.
  // Serve an unsupported result if the optional SIC backend cannot initialize.
  service->initialize();
  ABinderProcess_joinThreadPool();
  return 0;
}
