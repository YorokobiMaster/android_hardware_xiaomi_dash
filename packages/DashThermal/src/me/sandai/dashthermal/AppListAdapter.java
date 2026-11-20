// Copyright (C) 2026 YorokobiMaster
// SPDX-License-Identifier: Apache-2.0
package me.sandai.dashthermal;

import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.widget.SettingsSpinnerAdapter;

import java.util.ArrayList;
import java.util.List;

/** Main list: a filter spinner row that scrolls with the per-app rows below it. */
final class AppListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_FILTER = 0;
    private static final int VIEW_TYPE_APP = 1;
    private static final Object SHAPE = new Object();
    private static final Object SUBTITLE = new Object();
    private static final Object ICON = new Object();

    interface Listener {
        void onAppClick(AppEntry entry);

        /** Called from onBindViewHolder for icons that are not cached yet. */
        void onIconNeeded(AppEntry entry);

        /** Called from onBindViewHolder for rows whose stock group is unknown. */
        void onPolicyNeeded(AppEntry entry);

        /** Called when the filter spinner changes selection; the id is the label index. */
        void onFilterSelected(int filter);
    }

    private final Listener mListener;
    private final List<CharSequence> mFilterLabels;
    private final List<AppEntry> mItems = new ArrayList<>();

    private RecyclerView mRecyclerView;
    private boolean mFilterVisible;
    private int mSelectedFilter;
    private SettingsSpinnerAdapter<CharSequence> mSpinnerAdapter;

    AppListAdapter(Listener listener, List<CharSequence> filterLabels) {
        mListener = listener;
        mFilterLabels = filterLabels;
    }

    void setItems(List<AppEntry> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        mRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        mRecyclerView = null;
    }

    boolean isBound(AppEntry entry) {
        final int position = indexOf(entry.packageName);
        return position >= 0 && mRecyclerView != null
                && mRecyclerView.findViewHolderForAdapterPosition(position) != null;
    }

    void setSelectedFilter(int filter) {
        mSelectedFilter = filter;
    }

    void updateRow(AppEntry entry, boolean visible, boolean subtitleChanged) {
        final int oldPosition = indexOf(entry.packageName);
        if (oldPosition >= 0 && visible) {
            if (subtitleChanged) {
                notifyItemChanged(oldPosition, SUBTITLE);
            }
            return;
        }
        if (oldPosition < 0 && !visible) {
            return;
        }

        final AppEntry oldFirst = mItems.isEmpty() ? null : mItems.get(0);
        final AppEntry oldLast = mItems.isEmpty() ? null : mItems.get(mItems.size() - 1);
        final boolean wasSingle = mItems.size() == 1;
        final int offset = mFilterVisible ? 1 : 0;
        if (visible) {
            int position = 0;
            while (position < mItems.size()
                    && mItems.get(position).listPosition < entry.listPosition) {
                position++;
            }
            mItems.add(position, entry);
            notifyItemInserted(position + offset);
        } else {
            mItems.remove(oldPosition - offset);
            notifyItemRemoved(oldPosition);
        }
        if (mItems.isEmpty()) {
            return;
        }

        // An insert/remove moves holders without rebinding them. Affected
        // surviving end rows need new corners even though their data is unchanged.
        final AppEntry first = mItems.get(0);
        final AppEntry last = mItems.get(mItems.size() - 1);
        final boolean boundaryChanged = wasSingle != (mItems.size() == 1);
        if (oldFirst != null && oldFirst != entry && (oldFirst != first || boundaryChanged)) {
            notifyItemChanged(indexOf(oldFirst.packageName), SHAPE);
        }
        if (oldLast != null && oldLast != oldFirst && oldLast != entry
                && (oldLast != last || boundaryChanged)) {
            notifyItemChanged(indexOf(oldLast.packageName), SHAPE);
        }
        if (first != entry && first != oldFirst && first != oldLast) {
            notifyItemChanged(offset, SHAPE);
        }
        if (last != entry && last != first && last != oldFirst && last != oldLast) {
            notifyItemChanged(offset + mItems.size() - 1, SHAPE);
        }
    }

    void iconLoaded(AppEntry entry) {
        final int position = indexOf(entry.packageName);
        if (position >= 0) {
            notifyItemChanged(position, ICON);
        }
    }

    void setFilterVisible(boolean visible) {
        if (mFilterVisible == visible) {
            return;
        }
        mFilterVisible = visible;
        if (visible) {
            notifyItemInserted(0);
        } else {
            notifyItemRemoved(0);
        }
    }

    int indexOf(String packageName) {
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i).packageName.equals(packageName)) {
                return i + (mFilterVisible ? 1 : 0);
            }
        }
        return -1;
    }

    @Override
    public int getItemViewType(int position) {
        return mFilterVisible && position == 0 ? VIEW_TYPE_FILTER : VIEW_TYPE_APP;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_FILTER) {
            return new FilterViewHolder(
                    inflater.inflate(R.layout.item_filter_spinner, parent, false));
        }
        return new ViewHolder(inflater.inflate(R.layout.item_app, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder.getItemViewType() == VIEW_TYPE_FILTER) {
            bindFilterRow((FilterViewHolder) holder);
            return;
        }
        final ViewHolder appHolder = (ViewHolder) holder;
        final int appPosition = position - (mFilterVisible ? 1 : 0);
        final AppEntry entry = mItems.get(appPosition);
        bindShape(appHolder, appPosition);
        appHolder.label.setText(entry.label);
        bindSubtitle(appHolder, entry);
        bindIcon(appHolder, entry);

        if (entry.overrideProfile == -1 && entry.selectedProfile == -1
                && !entry.policyRequested) {
            mListener.onPolicyNeeded(entry);
        }

        appHolder.itemView.setOnClickListener(v -> mListener.onAppClick(entry));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
            @NonNull List<Object> payloads) {
        if (payloads.isEmpty() || !(holder instanceof ViewHolder)) {
            onBindViewHolder(holder, position);
            return;
        }
        final ViewHolder appHolder = (ViewHolder) holder;
        final int appPosition = position - (mFilterVisible ? 1 : 0);
        final AppEntry entry = mItems.get(appPosition);
        if (payloads.contains(SHAPE)) {
            bindShape(appHolder, appPosition);
        }
        if (payloads.contains(SUBTITLE)) {
            bindSubtitle(appHolder, entry);
        }
        if (payloads.contains(ICON)) {
            bindIcon(appHolder, entry);
        }
    }

    private void bindShape(ViewHolder holder, int position) {
        final int background = mItems.size() == 1 ? R.drawable.bg_card
                : position == 0 ? R.drawable.bg_profile_top
                : position == mItems.size() - 1 ? R.drawable.bg_profile_bottom
                : R.drawable.bg_profile_center;
        holder.itemView.setBackgroundResource(background);
        final int padding = holder.itemView.getResources().getDimensionPixelSize(R.dimen.card_padding);
        holder.itemView.setPaddingRelative(padding, padding, padding, padding);
    }

    private void bindSubtitle(ViewHolder holder, AppEntry entry) {
        final CharSequence subtitle = buildSubtitle(holder.itemView, entry);
        holder.subtitle.setText(subtitle);
        holder.subtitle.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
    }

    private void bindIcon(ViewHolder holder, AppEntry entry) {
        final Drawable icon = IconCache.get(holder.itemView.getContext(), entry.packageName);
        if (icon != null) {
            holder.icon.setImageDrawable(icon);
        } else {
            holder.icon.setImageDrawable(IconCache.getDefault(holder.itemView.getContext()));
            mListener.onIconNeeded(entry);
        }
    }

    @Override
    public int getItemCount() {
        return mItems.size() + (mFilterVisible ? 1 : 0);
    }

    private void bindFilterRow(FilterViewHolder holder) {
        if (mSpinnerAdapter == null) {
            mSpinnerAdapter = new SettingsSpinnerAdapter<>(holder.spinner.getContext());
            mSpinnerAdapter.addAll(mFilterLabels);
        }
        mSpinnerAdapter.setSelectedPosition(mSelectedFilter);
        holder.spinner.setAdapter(mSpinnerAdapter);
        holder.spinner.setSelection(mSelectedFilter);
        holder.spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mSelectedFilter == position) {
                    return;
                }
                mSelectedFilter = position;
                mSpinnerAdapter.setSelectedPosition(position);
                mListener.onFilterSelected(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private static CharSequence buildSubtitle(View view, AppEntry entry) {
        final int profile = entry.displayProfile();
        if (profile == -1) {
            return "";
        }
        final int nameRes = ThermalProfiles.nameRes(ThermalProfiles.canon(profile));
        return nameRes != 0 ? view.getContext().getString(nameRes)
                : String.valueOf(profile);
    }

    static final class FilterViewHolder extends RecyclerView.ViewHolder {
        final Spinner spinner;

        FilterViewHolder(View itemView) {
            super(itemView);
            spinner = itemView.findViewById(R.id.spinner);
        }
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView subtitle;

        ViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.app_icon);
            label = itemView.findViewById(R.id.app_label);
            subtitle = itemView.findViewById(R.id.app_subtitle);
        }
    }
}
