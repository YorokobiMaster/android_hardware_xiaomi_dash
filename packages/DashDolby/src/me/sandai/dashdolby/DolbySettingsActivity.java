/*
 * Copyright (C) 2026 YorokobiMaster
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package me.sandai.dashdolby;

import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.List;

public class DolbySettingsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new DolbySettingsFragment())
                    .commit();
        }
    }

    public static class DolbySettingsFragment extends SettingsBasePreferenceFragment
            implements Preference.OnPreferenceChangeListener {

        private static final String TAG = "DashDolby";
        private static final AudioAttributes MEDIA_ATTRIBUTES = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).build();

        private DolbyController mController;
        private AudioManager mAudioManager;
        private final AudioManager.OnDevicesForAttributesChangedListener mMediaRouteListener =
                (attributes, devices) -> {
                    if (mAudioManager != null) refreshIeqPreset(mController.getActiveProfile());
                };

        private MainSwitchPreference mDsOn;
        private ListPreference mProfile;
        private ListPreference mIeqPreset;
        private Preference mEqualizer;
        private SwitchPreferenceCompat mDialogEnhancer;
        private SwitchPreferenceCompat mVolumeLeveler;
        private SwitchPreferenceCompat mHeadphoneVirtualizer;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.dolby_prefs, rootKey);
            mController = DolbyController.getInstance(requireContext());

            mDsOn = findPreference("ds_on");
            mProfile = findPreference("profile");
            mIeqPreset = findPreference("ieq_preset");
            mEqualizer = findPreference("equalizer");
            mDialogEnhancer = findPreference("dialog_enhancer");
            mVolumeLeveler = findPreference("volume_leveler");
            mHeadphoneVirtualizer = findPreference("headphone_virtualizer");

            if (!mController.isAvailable()) {
                Toast.makeText(requireContext(), R.string.dolby_unavailable, Toast.LENGTH_LONG)
                        .show();
            }

            mDsOn.setOnPreferenceChangeListener(this);
            mProfile.setOnPreferenceChangeListener(this);
            mIeqPreset.setOnPreferenceChangeListener(this);
            mEqualizer.setOnPreferenceClickListener(preference -> {
                startActivity(new Intent(requireContext(), EqualizerActivity.class));
                return true;
            });
            mDialogEnhancer.setOnPreferenceChangeListener(this);
            mVolumeLeveler.setOnPreferenceChangeListener(this);
            mHeadphoneVirtualizer.setOnPreferenceChangeListener(this);

            refresh();
        }

        @Override
        public void onResume() {
            super.onResume();
            mAudioManager = requireContext().getSystemService(AudioManager.class);
            try {
                mAudioManager.addOnDevicesForAttributesChangedListener(MEDIA_ATTRIBUTES,
                        requireContext().getMainExecutor(), mMediaRouteListener);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not listen for media route changes", e);
            }
            refreshIeqPreset(mController.getActiveProfile());
        }

        @Override
        public void onPause() {
            AudioManager audioManager = mAudioManager;
            mAudioManager = null;
            try {
                audioManager.removeOnDevicesForAttributesChangedListener(mMediaRouteListener);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not remove media route listener", e);
            }
            super.onPause();
        }

        private void refresh() {
            mDsOn.setChecked(mController.isDsOn());

            int profile = mController.getActiveProfile();
            String[] names = getResources().getStringArray(R.array.profile_names);
            if (profile < names.length) {
                mProfile.setSummary(names[profile]);
            }
            mProfile.setValue(String.valueOf(profile));

            refreshProfilePrefs(profile);
        }

        private void refreshProfilePrefs(int profile) {
            refreshIeqPreset(profile);

            mDialogEnhancer.setChecked(mController.getProfileBool(profile,
                    DolbyAudioEffect.DAP_PARAM_DIALOG_ENHANCER, true));
            mVolumeLeveler.setChecked(mController.getProfileBool(profile,
                    DolbyAudioEffect.DAP_PARAM_VOLUME_LEVELER, true));
            mHeadphoneVirtualizer.setChecked(mController.getProfileBool(profile,
                    DolbyAudioEffect.DAP_PARAM_HEADPHONE_VIRTUALIZER, true));

            boolean on = mController.isDsOn();
            mProfile.setEnabled(on);
            mIeqPreset.setEnabled(on);
            mEqualizer.setEnabled(on);
            mDialogEnhancer.setEnabled(on);
            mVolumeLeveler.setEnabled(on);
            mHeadphoneVirtualizer.setEnabled(on);
        }

        private void refreshIeqPreset(int profile) {
            Integer current = mController.readCurrentIeqPreset(profile, getMediaPort());
            Integer saved = mController.getSavedProfileInt(profile,
                    DolbyAudioEffect.DAP_PARAM_IEQ_PRESET);
            Integer shown = current != null ? current : saved;
            mIeqPreset.setValue(shown == null ? null : shown.toString());
            CharSequence entry = mIeqPreset.getEntry();
            mIeqPreset.setSummary(entry == null ? getString(R.string.ieq_preset_default)
                    : current != null ? entry : getString(R.string.dolby_saved_choice, entry));
            mEqualizer.setVisible("0".equals(mIeqPreset.getValue()));
        }

        private int getMediaPort() {
            if (mAudioManager == null || !mController.isDsOn()) return -1;
            try {
                return getMediaPort(mAudioManager.getDevicesForAttributes(MEDIA_ATTRIBUTES));
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not query the media route", e);
                return -1;
            }
        }

        static int getMediaPort(List<AudioDeviceAttributes> devices) {
            if (devices.size() != 1) return -1;
            // Match the stock Dolby service device groups for recognized media routes.
            return switch (devices.get(0).getType()) {
                case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE ->
                    DolbyAudioEffect.PORT_SPEAKER;
                case AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC,
                        AudioDeviceInfo.TYPE_HDMI_EARC -> DolbyAudioEffect.PORT_HDMI;
                case AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> DolbyAudioEffect.PORT_MIRACAST;
                case AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                    DolbyAudioEffect.PORT_HEADPHONE;
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
                        AudioDeviceInfo.TYPE_BLE_HEARING_AID, AudioDeviceInfo.TYPE_BLE_CENTRAL,
                        AudioDeviceInfo.TYPE_BLE_CENTRAL_BROADCAST -> DolbyAudioEffect.PORT_BLUETOOTH;
                case AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
                        AudioDeviceInfo.TYPE_USB_HEADSET -> DolbyAudioEffect.PORT_USB;
                default -> -1;
            };
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            String key = preference.getKey();
            int profile = mController.getActiveProfile();
            switch (key) {
                case "ds_on":
                    mController.setDsOn((Boolean) newValue);
                    break;
                case "profile":
                    mController.setActiveProfile(Integer.parseInt((String) newValue));
                    break;
                case "ieq_preset":
                    int preset = Integer.parseInt((String) newValue);
                    mController.setIeqPreset(profile, preset);
                    if (preset == 0) {
                        requireView().post(() -> startActivity(
                                new Intent(requireContext(), EqualizerActivity.class)));
                    }
                    // The choice was handled above; keep the refreshed DSP value in the list.
                    refreshIeqPreset(profile);
                    return false;
                case "dialog_enhancer":
                    mController.setProfileBool(profile,
                            DolbyAudioEffect.DAP_PARAM_DIALOG_ENHANCER, (Boolean) newValue);
                    break;
                case "volume_leveler":
                    mController.setProfileBool(profile,
                            DolbyAudioEffect.DAP_PARAM_VOLUME_LEVELER, (Boolean) newValue);
                    break;
                case "headphone_virtualizer":
                    mController.setProfileBool(profile,
                            DolbyAudioEffect.DAP_PARAM_HEADPHONE_VIRTUALIZER,
                            (Boolean) newValue);
                    break;
                default:
                    return false;
            }
            refresh();
            return true;
        }
    }
}
