// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main page: searchable, filterable list of per-app thermal overrides. */
public class MainActivity extends CollapsingToolbarBaseActivity
        implements AppListAdapter.Listener {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_MODIFIED = 1;
    private static final int FILTER_GENERAL = 2;
    private static final int FILTER_GAMING = 3;
    private static final int FILTER_OTHERS = 4;
    private static final String PREF_SHOW_SYSTEM = "show_system";

    private final ThermalServiceClient mClient = new ThermalServiceClient();
    private final ExecutorService mLoadExecutor = Executors.newSingleThreadExecutor();

    private TextView mErrorHint;
    private TextView mUnavailableText;
    private RecyclerView mRecyclerView;
    private Toolbar mToolbar;
    private AppListAdapter mAdapter;

    private final List<AppEntry> mAllEntries = new ArrayList<>();
    private final Map<String, Integer> mOverrides = new LinkedHashMap<>();

    private boolean mAvailable;
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
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.setAdapter(mAdapter);

        showUnavailable();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mClient.start(this::onServiceState);
    }

    @Override
    protected void onPause() {
        mClient.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
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
                getAppBarLayout().setExpanded(false, false);
                ViewCompat.setNestedScrollingEnabled(mRecyclerView, false);
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
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

    private void onServiceState(boolean available, Bundle state) {
        if (!available) {
            showUnavailable();
            return;
        }
        mAvailable = true;
        mUnavailableText.setVisibility(View.GONE);
        mAdapter.setFilterVisible(true);

        final String error = state.getString(ThermalServiceClient.KEY_ERROR, "");
        if (!error.isEmpty()) {
            mErrorHint.setText(getString(R.string.last_error_format, error));
            mErrorHint.setVisibility(View.VISIBLE);
        } else {
            mErrorHint.setVisibility(View.GONE);
        }

        mOverrides.clear();
        final Bundle overrides = state.getBundle(ThermalServiceClient.KEY_OVERRIDES);
        if (overrides != null) {
            for (String pkg : overrides.keySet()) {
                mOverrides.put(pkg, overrides.getInt(pkg, -1));
            }
        }
        loadApps();
    }

    private void showUnavailable() {
        mAvailable = false;
        mUnavailableText.setVisibility(View.VISIBLE);
        mAdapter.setFilterVisible(false);
        mAdapter.setItems(new ArrayList<>());
    }

    private void loadApps() {
        final boolean showSystem = mShowSystem;
        final Map<String, Integer> overrides = new LinkedHashMap<>(mOverrides);
        mLoadExecutor.execute(() -> {
            final List<AppEntry> entries = buildEntries(showSystem);
            for (AppEntry entry : entries) {
                final Integer override = overrides.get(entry.packageName);
                entry.overrideProfile = override == null ? -1 : override;
            }
            final Collator collator = Collator.getInstance(Locale.getDefault());
            entries.sort((a, b) -> collator.compare(a.label, b.label));
            runOnUiThread(() -> {
                if (!mAvailable) {
                    return;
                }
                mAllEntries.clear();
                mAllEntries.addAll(entries);
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
        final boolean categoryFilter = mFilter >= FILTER_GENERAL;
        final List<AppEntry> filtered = new ArrayList<>();
        for (AppEntry entry : mAllEntries) {
            if (mFilter == FILTER_MODIFIED && entry.overrideProfile == -1) {
                continue;
            }
            if (categoryFilter) {
                if (entry.stockGroup == null) {
                    requestPolicy(entry);
                    continue;
                }
                if (sectionOf(entry.stockGroup) != mFilter) {
                    continue;
                }
            }
            if (!query.isEmpty()
                    && !entry.label.toLowerCase(Locale.ROOT).contains(query)
                    && !entry.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            filtered.add(entry);
        }
        mAdapter.setItems(filtered);
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
        AppDetailActivity.start(this, entry.packageName);
    }

    @Override
    public void onIconNeeded(AppEntry entry) {
        mLoadExecutor.execute(() -> {
            try {
                final Drawable icon = getPackageManager()
                        .getApplicationIcon(entry.packageName);
                IconCache.put(this, entry.packageName, icon);
                runOnUiThread(() -> {
                    final int index = mAdapter.indexOf(entry.packageName);
                    if (index >= 0) {
                        mAdapter.notifyItemChanged(index);
                    }
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
        if (entry.policyRequested) {
            return;
        }
        entry.policyRequested = true;
        mClient.getAppPolicy(entry.packageName, new ThermalServiceClient.PolicyCallback() {
            @Override
            public void onPolicy(Bundle policy) {
                if (policy != null) {
                    entry.stockGroup = policy.getString(
                            ThermalServiceClient.KEY_STOCK_GROUP, "");
                    entry.overrideProfile = policy.getInt(
                            ThermalServiceClient.KEY_OVERRIDE_PROFILE, entry.overrideProfile);
                    entry.selectedProfile = policy.getInt(
                            ThermalServiceClient.KEY_SELECTED_PROFILE, entry.selectedProfile);
                }
                if (mFilter >= FILTER_GENERAL) {
                    applyFilters();
                    return;
                }
                final int index = mAdapter.indexOf(entry.packageName);
                if (index >= 0) {
                    mAdapter.notifyItemChanged(index);
                }
            }

            @Override
            public void onError() {
                // Leave the subtitle empty; no retry to avoid a binder call loop.
            }
        });
    }
}
