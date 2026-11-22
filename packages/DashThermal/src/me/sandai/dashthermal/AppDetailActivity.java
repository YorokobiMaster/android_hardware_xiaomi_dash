// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import me.sandai.dashpower.ThermalServiceContract;

/** Detail page: fixed scheduling policies in sections; the radio rests on the current one. */
public class AppDetailActivity extends CollapsingToolbarBaseActivity {

    static final int RESULT_UNCONFIRMED = RESULT_FIRST_USER;
    static final String EXTRA_PACKAGE = "packageName";
    static final String EXTRA_POLICY = "policy";
    private static final String STATE_RESULT_CODE = "resultCode";
    private static final String STATE_RESULT = "result";

    private static final String STATE_FINISH_AFTER_SAVE = "finishAfterSave";

    private static final class SaveResult {
        final int code;
        final Bundle policy;

        SaveResult(int code, Bundle policy) {
            this.code = code;
            this.policy = policy;
        }
    }

    /** A configuration change must not split a save from its acknowledgement. */
    private static final class Session {
        final ThermalServiceClient client = new ThermalServiceClient();
        boolean saving;
        boolean finishAfterSave;
        SaveResult result;
        Consumer<SaveResult> listener;

        void save(String packageName, int profileId) {
            saving = true;
            client.setAppProfile(packageName, profileId, success -> {
                if (success) {
                    // The acknowledged manual choice is itself the effective profile, even 0.
                    final Bundle policy = new Bundle();
                    policy.putInt(ThermalServiceContract.KEY_OVERRIDE_PROFILE, profileId);
                    policy.putInt(ThermalServiceContract.KEY_SELECTED_PROFILE, profileId);
                    complete(RESULT_OK, policy);
                    return;
                }

                // The file may have been replaced before a persistence error was reported.
                client.getAppPolicy(packageName, new ThermalServiceClient.PolicyCallback() {
                    @Override
                    public void onPolicy(Bundle policy) {
                        complete(RESULT_UNCONFIRMED, policy);
                    }

                    @Override
                    public void onError() {
                        onPolicy(null);
                    }
                });
            });
        }

        private void complete(int code, Bundle policy) {
            saving = false;
            result = new SaveResult(code, policy);
            if (listener != null) {
                listener.accept(result);
            }
        }
    }

    private Session mSession;
    private final ExecutorService mLoadExecutor = Executors.newSingleThreadExecutor();

    private final Map<Integer, ImageView> mRadios = new HashMap<>();

    private String mPackageName;
    private int mSelectedProfile = -1;
    private int mPolicyRequest;
    private View mUnknownOption;
    private int mResultCode = RESULT_CANCELED;
    private Intent mResult;

    private LinearLayout mOptionsContainer;
    private LinearLayout mOtherOptions;
    private View mHeader;
    private View mFooter;
    private TextView mUnavailableText;

    static void start(Activity activity, String packageName, int requestCode) {
        final Intent intent = new Intent(activity, AppDetailActivity.class);
        intent.putExtra(EXTRA_PACKAGE, packageName);
        activity.startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Session retained = (Session) getLastCustomNonConfigurationInstance();
        mSession = retained == null ? new Session() : retained;
        mPackageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        if (mPackageName == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_app_detail);
        setTitle(R.string.app_name);

        mHeader = findViewById(R.id.app_header);
        mOptionsContainer = findViewById(R.id.options_container);
        mFooter = findViewById(R.id.detail_footer);
        mUnavailableText = findViewById(R.id.unavailable_text);

        if (savedInstanceState != null) {
            mResultCode = savedInstanceState.getInt(STATE_RESULT_CODE, RESULT_CANCELED);
            mResult = savedInstanceState.getParcelable(STATE_RESULT, Intent.class);
            setResult(mResultCode, mResult);
            if (retained == null) {
                mSession.finishAfterSave = savedInstanceState.getBoolean(STATE_FINISH_AFTER_SAVE);
            }
        }
        buildOptions();
        setOptionsEnabled(false);
        mSession.listener = this::onSaveComplete;
        if (mSession.result != null) {
            recordResult(mSession.result);
        }
        if (mSession.finishAfterSave && !mSession.saving) {
            finishAfterTransition();
        } else {
            loadHeader();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPackageName == null) {
            return;
        }
        mSession.client.start((binder, state) -> {
            if (mSession.saving) {
                return;
            }
            if (binder != null) {
                loadPolicy();
            } else {
                showUnavailable();
            }
        });
    }

    @Override
    protected void onPause() {
        mPolicyRequest++;
        mSession.client.stop();
        super.onPause();
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        return mSession;
    }

    @Override
    protected void onDestroy() {
        mSession.listener = null;
        if (!isChangingConfigurations()) {
            mSession.client.shutdown();
        }
        mLoadExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_RESULT_CODE, mSession.saving ? RESULT_UNCONFIRMED : mResultCode);
        outState.putParcelable(STATE_RESULT, mSession.saving ? resultFor(null) : mResult);
        outState.putBoolean(STATE_FINISH_AFTER_SAVE, mSession.finishAfterSave);
    }

    @Override
    public void onBackPressed() {
        if (mSession.saving) {
            mSession.finishAfterSave = true;
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void finishAfterTransition() {
        if (mSession.saving) {
            mSession.finishAfterSave = true;
            return;
        }
        super.finishAfterTransition();
    }

    @Override
    public void finish() {
        if (mSession.saving) {
            mSession.finishAfterSave = true;
            return;
        }
        super.finish();
    }

    private void loadHeader() {
        mLoadExecutor.execute(() -> {
            final PackageManager pm = getPackageManager();
            try {
                final ApplicationInfo info = pm.getApplicationInfo(mPackageName, 0);
                final CharSequence label = info.loadLabel(pm);
                final Drawable icon = pm.getApplicationIcon(info);
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    final CharSequence title = label == null ? mPackageName : label;
                    ((TextView) findViewById(R.id.app_label)).setText(title);
                    ((ImageView) findViewById(R.id.app_icon)).setImageDrawable(icon);
                });
            } catch (PackageManager.NameNotFoundException e) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        finish();
                    }
                });
            }
        });
    }

    private void buildOptions() {
        addSection(R.string.section_general, ThermalProfiles.GENERAL);
        addSection(R.string.section_gaming, ThermalProfiles.GAMING);
        mOtherOptions = addSection(R.string.section_others, ThermalProfiles.OTHERS);
    }

    private LinearLayout addSection(int headerRes, int[] profileIds) {
        final LayoutInflater inflater = LayoutInflater.from(this);
        final TextView header = (TextView) inflater.inflate(
                R.layout.item_section_header, mOptionsContainer, false);
        header.setText(headerRes);
        mOptionsContainer.addView(header);
        final LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        mOptionsContainer.addView(group, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        for (int profileId : profileIds) {
            addOption(group, profileId);
        }
        return group;
    }

    private View addOption(LinearLayout group, int profileId) {
        final View card = LayoutInflater.from(this).inflate(
                R.layout.item_profile_option, group, false);
        bindOption(card, profileId);
        mRadios.put(profileId, card.findViewById(R.id.option_radio));
        if (ThermalProfiles.known(profileId)) {
            card.setOnClickListener(v -> onProfileSelected(profileId));
        }
        if (group.getChildCount() > 0) {
            ((LinearLayout.LayoutParams) card.getLayoutParams()).topMargin =
                    getResources().getDimensionPixelSize(R.dimen.profile_row_gap);
        }
        group.addView(card);
        updateOptionBackgrounds(group);
        return card;
    }

    private void updateOptionBackgrounds(LinearLayout group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            final int background = group.getChildCount() == 1 ? R.drawable.bg_card
                    : i == 0 ? R.drawable.bg_profile_top
                    : i == group.getChildCount() - 1 ? R.drawable.bg_profile_bottom
                    : R.drawable.bg_profile_center;
            final View row = group.getChildAt(i);
            final int padding = getResources().getDimensionPixelSize(R.dimen.card_padding);
            row.setBackgroundResource(background);
            row.setPaddingRelative(padding, padding, padding, padding);
        }
    }

    private void bindOption(View card, int profileId) {
        final TextView title = card.findViewById(R.id.option_title);
        final TextView summary = card.findViewById(R.id.option_summary);
        final int nameRes = ThermalProfiles.nameRes(profileId);
        title.setText(nameRes != 0 ? getString(nameRes)
                : getString(R.string.profile_unknown, profileId));
        final int summaryRes = ThermalProfiles.summaryRes(profileId);
        if (summaryRes != 0) {
            summary.setText(summaryRes);
        } else {
            summary.setVisibility(View.GONE);
        }
    }

    private void loadPolicy() {
        final int request = ++mPolicyRequest;
        setOptionsEnabled(false);
        mSession.client.getAppPolicy(mPackageName, new ThermalServiceClient.PolicyCallback() {
            @Override
            public void onPolicy(Bundle policy) {
                if (mPolicyRequest == request && !mSession.saving) {
                    displayPolicy(policy);
                }
            }

            @Override
            public void onError() {
                onPolicy(null);
            }
        });
    }

    private void displayPolicy(Bundle policy) {
        if (policy == null) {
            showUnavailable();
            return;
        }
        showAvailable();
        final int previous = mSelectedProfile;
        mSelectedProfile = ThermalProfiles.canon(policy.getInt(
                ThermalServiceContract.KEY_SELECTED_PROFILE, 0));
        if (mUnknownOption != null && previous != mSelectedProfile) {
            mOtherOptions.removeView(mUnknownOption);
            mRadios.remove(previous);
            mUnknownOption = null;
            updateOptionBackgrounds(mOtherOptions);
        }
        if (mSelectedProfile != -1 && !mRadios.containsKey(mSelectedProfile)) {
            mUnknownOption = addOption(mOtherOptions, mSelectedProfile);
        }
        updateSelection();
        setOptionsEnabled(true);
    }

    private void onProfileSelected(int profileId) {
        if (mSession.saving || profileId == mSelectedProfile) {
            return;
        }
        mPolicyRequest++;
        setOptionsEnabled(false);
        mSession.save(mPackageName, profileId);
    }

    private Intent resultFor(Bundle policy) {
        return new Intent().putExtra(EXTRA_PACKAGE, mPackageName).putExtra(EXTRA_POLICY, policy);
    }

    private void recordResult(SaveResult result) {
        mResultCode = result.code;
        mResult = resultFor(result.policy);
        setResult(mResultCode, mResult);
    }

    private void onSaveComplete(SaveResult result) {
        recordResult(result);
        if (result.code == RESULT_UNCONFIRMED) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
        }
        if (mSession.finishAfterSave) {
            finishAfterTransition();
        } else {
            displayPolicy(result.policy);
            if (result.policy == null) {
                mSession.client.refresh();
            }
        }
    }

    private void updateSelection() {
        for (Map.Entry<Integer, ImageView> entry : mRadios.entrySet()) {
            entry.getValue().setImageResource(entry.getKey() == mSelectedProfile
                    ? R.drawable.ic_radio_checked : R.drawable.ic_radio_unchecked);
        }
    }

    private void setOptionsEnabled(boolean enabled) {
        mOptionsContainer.setEnabled(enabled);
        for (int i = 0; i < mOptionsContainer.getChildCount(); i++) {
            final View child = mOptionsContainer.getChildAt(i);
            child.setEnabled(enabled);
            if (child instanceof LinearLayout) {
                final LinearLayout group = (LinearLayout) child;
                for (int j = 0; j < group.getChildCount(); j++) {
                    final View option = group.getChildAt(j);
                    option.setEnabled(enabled && option != mUnknownOption);
                }
            }
        }
    }

    private void showAvailable() {
        mHeader.setVisibility(View.VISIBLE);
        mOptionsContainer.setVisibility(View.VISIBLE);
        mFooter.setVisibility(View.VISIBLE);
        mUnavailableText.setVisibility(View.GONE);
    }

    private void showUnavailable() {
        mPolicyRequest++;
        setOptionsEnabled(false);
        mHeader.setVisibility(View.GONE);
        mOptionsContainer.setVisibility(View.GONE);
        mFooter.setVisibility(View.GONE);
        mUnavailableText.setVisibility(View.VISIBLE);
    }
}
