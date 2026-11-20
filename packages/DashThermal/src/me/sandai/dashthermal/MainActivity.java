// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toolbar;

import androidx.core.view.ViewCompat;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.sandai.dashpower.ThermalServiceContract;

/** Main page: searchable, filterable list of per-app thermal overrides. */
public class MainActivity extends CollapsingToolbarBaseActivity
        implements AppListAdapter.Listener {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_MODIFIED = 1;
    private static final int FILTER_GENERAL = 2;
    private static final int FILTER_GAMING = 3;
    private static final int FILTER_OTHERS = 4;
    private static final int REQUEST_DETAIL = 1;
    private static final String STATE_FILTER = "filter";
    private static final String STATE_QUERY = "query";
    private static final String STATE_SEARCH_OPEN = "searchOpen";
    private static final String PREF_SHOW_SYSTEM = "show_system";

    private final ThermalServiceClient mClient = new ThermalServiceClient();
    private final ExecutorService mLoadExecutor = Executors.newSingleThreadExecutor();

    private TextView mErrorHint;
    private TextView mUnavailableText;
    private RecyclerView mRecyclerView;
    private Toolbar mToolbar;
    private AppListAdapter mAdapter;

    private final Map<String, AppEntry> mEntries = new LinkedHashMap<>();
    private final Map<String, Integer> mOverrides = new LinkedHashMap<>();
    private final Set<AppEntry> mPendingRestorePolicies = new HashSet<>();

    private IBinder mServiceBinder;
    private boolean mAvailable;
    private boolean mPerformanceMode;
    private boolean mResumed;
    private boolean mPackagesDirty = true;
    private boolean mListLoaded;
    private boolean mAwaitingListRestore;
    private int mLoadRequest;
    private boolean mSearchOpen;

    private final ContentObserver mConfigObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            if (mResumed) {
                mClient.refresh();
            }
        }
    };

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getData() != null) {
                IconCache.invalidate(intent.getData().getSchemeSpecificPart());
            }
            onPackagesChanged();
        }
    };
    private final BroadcastReceiver mExternalAppsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            IconCache.clear();
            onPackagesChanged();
        }
    };
    private int mFilter = FILTER_ALL;
    private String mQuery = "";
    private boolean mShowSystem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mToolbar = findViewById(com.android.settingslib.collapsingtoolbar.R.id.action_bar);
        mToolbar.getContext().getTheme().applyStyle(R.style.ThemeOverlay_DashThermal_Toolbar, true);
        mToolbar.setPopupTheme(R.style.ThemeOverlay_DashThermal_Popup);
        setContentView(R.layout.activity_main);
        setTitle(R.string.app_name);

        if (savedInstanceState != null) {
            mAwaitingListRestore = true;
            mFilter = savedInstanceState.getInt(STATE_FILTER, FILTER_ALL);
            mQuery = savedInstanceState.getString(STATE_QUERY, "");
            mSearchOpen = savedInstanceState.getBoolean(STATE_SEARCH_OPEN);
        }
        mShowSystem = getPreferences(MODE_PRIVATE).getBoolean(PREF_SHOW_SYSTEM, false);

        mErrorHint = findViewById(R.id.error_hint);
        mUnavailableText = findViewById(R.id.unavailable_text);
        mRecyclerView = findViewById(R.id.app_list);

        mAdapter = new AppListAdapter(this, Arrays.<CharSequence>asList(
                getString(R.string.filter_all_apps),
                getString(R.string.filter_modified),
                getString(R.string.section_general),
                getString(R.string.section_gaming),
                getString(R.string.section_others)));
        mAdapter.setSelectedFilter(mFilter);
        if (mAwaitingListRestore) {
            mAdapter.setStateRestorationPolicy(
                    RecyclerView.Adapter.StateRestorationPolicy.PREVENT);
        }
        // Do not animate a removed row's pre-save content over the updated list.
        mRecyclerView.setItemAnimator(null);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.setAdapter(mAdapter);

        showUnavailable();
        final IntentFilter packages = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        registerReceiver(mPackageReceiver, packages, Context.RECEIVER_EXPORTED);
        final IntentFilter external = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        external.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        registerReceiver(mExternalAppsReceiver, external, Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_FILTER, mFilter);
        outState.putString(STATE_QUERY, mQuery);
        outState.putBoolean(STATE_SEARCH_OPEN, mSearchOpen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        getContentResolver().registerContentObserver(
                PerformanceModeClient.STATE_URI, false, mConfigObserver);
        mClient.start(this::onServiceState);
    }

    @Override
    protected void onPause() {
        mResumed = false;
        getContentResolver().unregisterContentObserver(mConfigObserver);
        mClient.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mPackageReceiver);
        unregisterReceiver(mExternalAppsReceiver);
        mClient.shutdown();
        mLoadExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        new MenuInflater(mToolbar.getContext()).inflate(R.menu.main_menu, menu);

        final MenuItem searchItem = menu.findItem(R.id.action_search);
        final SearchView searchView = (SearchView) searchItem.getActionView();
        searchView.setQueryHint(getString(R.string.search_apps_hint));
        searchView.setMaxWidth(Integer.MAX_VALUE);
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                mSearchOpen = true;
                getAppBarLayout().setExpanded(false, false);
                ViewCompat.setNestedScrollingEnabled(mRecyclerView, false);
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                mSearchOpen = false;
                searchView.setQuery("", false);
                mQuery = "";
                applyFilters();
                getAppBarLayout().setExpanded(false, false);
                ViewCompat.setNestedScrollingEnabled(mRecyclerView, true);
                return true;
            }
        });
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String query) {
                mQuery = query == null ? "" : query;
                applyFilters();
                return false;
            }
        });

        if (mSearchOpen) {
            final String query = mQuery;
            searchItem.expandActionView();
            searchView.setQuery(query, false);
            searchView.clearFocus();
        }

        final MenuItem showSystem = menu.findItem(R.id.action_show_system);
        showSystem.setTitle(mShowSystem ? R.string.action_hide_system : R.string.action_show_system);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_show_system) {
            mShowSystem = !mShowSystem;
            item.setTitle(mShowSystem ? R.string.action_hide_system : R.string.action_show_system);
            final SharedPreferences.Editor editor = getPreferences(MODE_PRIVATE).edit();
            editor.putBoolean(PREF_SHOW_SYSTEM, mShowSystem);
            editor.apply();
            if (mAvailable) {
                loadApps();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onPackagesChanged() {
        mPackagesDirty = true;
        mLoadRequest++;
        if (mAvailable && mResumed) {
            // Uninstall may also have removed a saved override.
            mClient.refresh();
        }
    }

    private void onServiceState(IBinder binder, Bundle state) {
        if (binder == null) {
            showUnavailable();
            return;
        }
        final boolean performanceMode = state.getBoolean(
                ThermalServiceContract.KEY_PERFORMANCE_MODE);
        final boolean serviceChanged = binder != mServiceBinder;
        final boolean modeChanged = performanceMode != mPerformanceMode;
        mAvailable = true;
        mServiceBinder = binder;
        mPerformanceMode = performanceMode;
        mUnavailableText.setVisibility(View.GONE);
        mAdapter.setFilterVisible(true);

        final String error = state.getString(ThermalServiceContract.KEY_ERROR, "");
        if (!error.isEmpty()) {
            mErrorHint.setText(getString(R.string.last_error_format, error));
            mErrorHint.setVisibility(View.VISIBLE);
        } else {
            mErrorHint.setVisibility(View.GONE);
        }

        mOverrides.clear();
        final Bundle overrides = state.getBundle(ThermalServiceContract.KEY_OVERRIDES);
        if (overrides != null) {
            for (String pkg : overrides.keySet()) {
                mOverrides.put(pkg, overrides.getInt(pkg, -1));
            }
        }
        if (mPackagesDirty) {
            loadApps();
            return;
        }
        for (AppEntry entry : mEntries.values()) {
            final int override = mOverrides.getOrDefault(entry.packageName, -1);
            if (serviceChanged || (modeChanged && override == -1)
                    || entry.overrideProfile != override || entry.overrideUnconfirmed) {
                reconcileRow(entry, override, -1, null, false, serviceChanged);
            }
        }
    }

    private void showUnavailable() {
        mAvailable = false;
        mListLoaded = false;
        mPendingRestorePolicies.clear();
        mServiceBinder = null;
        mPackagesDirty = true;
        mLoadRequest++;
        mUnavailableText.setVisibility(View.VISIBLE);
        mErrorHint.setVisibility(View.GONE);
        mAdapter.setFilterVisible(false);
        if (!mEntries.isEmpty()) {
            mEntries.clear();
            mAdapter.setItems(new ArrayList<>());
        }
    }

    private void loadApps() {
        final boolean showSystem = mShowSystem;
        final int request = ++mLoadRequest;
        mPackagesDirty = false;
        mLoadExecutor.execute(() -> {
            final List<AppEntry> entries = buildEntries(showSystem);
            final Collator collator = Collator.getInstance(Locale.getDefault());
            entries.sort((a, b) -> collator.compare(a.label, b.label));
            runOnUiThread(() -> {
                if (isDestroyed() || !mAvailable || request != mLoadRequest) {
                    return;
                }
                mEntries.clear();
                for (AppEntry entry : entries) {
                    entry.listPosition = mEntries.size();
                    entry.overrideProfile = mOverrides.getOrDefault(entry.packageName, -1);
                    mEntries.put(entry.packageName, entry);
                }
                mListLoaded = true;
                applyFilters();
            });
        });
    }

    private List<AppEntry> buildEntries(boolean showSystem) {
        final PackageManager pm = getPackageManager();
        final Map<String, AppEntry> result = new LinkedHashMap<>();
        if (!showSystem) {
            final LauncherApps launcherApps = getSystemService(LauncherApps.class);
            for (LauncherActivityInfo info
                    : launcherApps.getActivityList(null, Process.myUserHandle())) {
                final String pkg = info.getApplicationInfo().packageName;
                if (!result.containsKey(pkg)) {
                    result.put(pkg, new AppEntry(pkg, info.getLabel().toString()));
                }
            }
        } else {
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                final CharSequence label = info.loadLabel(pm);
                result.put(info.packageName, new AppEntry(info.packageName,
                        label == null ? info.packageName : label.toString()));
            }
        }
        return new ArrayList<>(result.values());
    }

    private void applyFilters() {
        final String query = mQuery.toLowerCase(Locale.ROOT);
        final List<AppEntry> filtered = new ArrayList<>();
        mPendingRestorePolicies.clear();
        for (AppEntry entry : mEntries.values()) {
            if (matchesFilter(entry, query)) {
                filtered.add(entry);
            }
        }
        mAdapter.setItems(filtered);
        maybeRestoreListState();
    }

    private void maybeRestoreListState() {
        // Category rows arrive asynchronously. Restoring against just the spinner
        // would clamp a saved scroll position before those rows can be inserted.
        if (mAwaitingListRestore && mListLoaded && mPendingRestorePolicies.isEmpty()) {
            mAwaitingListRestore = false;
            mAdapter.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.ALLOW);
        }
    }

    private boolean matchesFilter(AppEntry entry, String query) {
        if (!query.isEmpty()
                && !entry.label.toLowerCase(Locale.ROOT).contains(query)
                && !entry.packageName.toLowerCase(Locale.ROOT).contains(query)) {
            return false;
        }
        if (mFilter == FILTER_MODIFIED && entry.overrideProfile == -1) {
            return false;
        }
        if (mFilter >= FILTER_GENERAL) {
            if (entry.stockGroup == null) {
                if (mAwaitingListRestore && !entry.policySettled && !entry.overrideUnconfirmed) {
                    mPendingRestorePolicies.add(entry);
                }
                requestPolicy(entry);
                return false;
            }
            return sectionOf(entry.stockGroup) == mFilter;
        }
        return true;
    }

    private static int sectionOf(String stockGroup) {
        switch (stockGroup) {
            case "game":
            case "game2":
            case "yuanshen":
            case "xingtie":
                return FILTER_GAMING;
            case "navigation":
            case "camera":
            case "evaluation":
            case "huanji":
            case "arvr":
            case "demo":
                return FILTER_OTHERS;
            default:
                // class0, video, unclassified and anything new all land in General.
                return FILTER_GENERAL;
        }
    }

    @Override
    public void onFilterSelected(int filter) {
        if (mFilter == filter) {
            return;
        }
        mFilter = filter;
        applyFilters();
    }

    @Override
    public void onAppClick(AppEntry entry) {
        AppDetailActivity.start(this, entry.packageName, REQUEST_DETAIL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DETAIL || data == null
                || (resultCode != RESULT_OK && resultCode != AppDetailActivity.RESULT_UNCONFIRMED)) {
            return;
        }
        final String packageName = data.getStringExtra(AppDetailActivity.EXTRA_PACKAGE);
        final AppEntry entry = mEntries.get(packageName);
        final Bundle policy = data.getBundleExtra(AppDetailActivity.EXTRA_POLICY);
        if (policy == null) {
            if (entry != null) {
                // Do not display the pre-write policy when even readback failed.
                reconcileRow(entry, entry.overrideProfile, -1, null, true, false);
            }
            return;
        }
        final int override = policy.getInt(ThermalServiceContract.KEY_OVERRIDE_PROFILE, -1);
        if (override == -1) {
            mOverrides.remove(packageName);
        } else {
            mOverrides.put(packageName, override);
        }
        if (entry != null) {
            reconcileRow(entry, override,
                    policy.getInt(ThermalServiceContract.KEY_SELECTED_PROFILE, -1),
                    policy.getString(ThermalServiceContract.KEY_STOCK_GROUP), false, false);
        }
    }

    private void reconcileRow(AppEntry entry, int override, int selected, String stockGroup,
            boolean unconfirmed, boolean invalidateGroup) {
        final int previous = entry.displayProfile();
        entry.policyRequest++;
        entry.policyRequested = false;
        entry.policySettled = false;
        entry.overrideProfile = override;
        entry.overrideUnconfirmed = unconfirmed;
        entry.selectedProfile = selected;
        if (invalidateGroup || stockGroup != null) {
            entry.stockGroup = stockGroup;
        }
        mPendingRestorePolicies.remove(entry);
        updateRow(entry, previous);
        maybeRestoreListState();
    }

    private void updateRow(AppEntry entry, int previous) {
        final boolean visible = matchesFilter(entry, mQuery.toLowerCase(Locale.ROOT));
        mAdapter.updateRow(entry, visible, ThermalProfiles.canon(previous)
                != ThermalProfiles.canon(entry.displayProfile()));
        if (visible && entry.displayProfile() == -1 && !entry.overrideUnconfirmed
                && mAdapter.isBound(entry)) {
            requestPolicy(entry);
        }
    }

    @Override
    public void onIconNeeded(AppEntry entry) {
        if (entry.iconRequested) {
            return;
        }
        entry.iconRequested = true;
        final int request = mLoadRequest;
        mLoadExecutor.execute(() -> {
            try {
                final Drawable icon = getPackageManager().getApplicationIcon(entry.packageName);
                runOnUiThread(() -> {
                    if (!isDestroyed() && request == mLoadRequest
                            && mEntries.get(entry.packageName) == entry) {
                        IconCache.put(entry.packageName, icon);
                        mAdapter.iconLoaded(entry);
                    }
                    entry.iconRequested = false;
                });
            } catch (PackageManager.NameNotFoundException e) {
                // App vanished between listing and icon load; next reload drops it.
            }
        });
    }

    @Override
    public void onPolicyNeeded(AppEntry entry) {
        requestPolicy(entry);
    }

    private void requestPolicy(AppEntry entry) {
        if (entry.policyRequested || entry.overrideUnconfirmed) {
            return;
        }
        entry.policyRequested = true;
        final int request = ++entry.policyRequest;
        mClient.getAppPolicy(entry.packageName, new ThermalServiceClient.PolicyCallback() {
            @Override
            public void onPolicy(Bundle policy) {
                if (mEntries.get(entry.packageName) != entry || request != entry.policyRequest) {
                    return;
                }
                entry.policySettled = true;
                mPendingRestorePolicies.remove(entry);
                if (policy != null) {
                    final int previous = entry.displayProfile();
                    entry.stockGroup = policy.getString(ThermalServiceContract.KEY_STOCK_GROUP, "");
                    entry.selectedProfile = policy.getInt(
                            ThermalServiceContract.KEY_SELECTED_PROFILE, -1);
                    updateRow(entry, previous);
                }
                maybeRestoreListState();
            }

            @Override
            public void onError() {
                // Leave the subtitle empty; no retry to avoid a binder call loop.
                onPolicy(null);
            }
        });
    }
}
