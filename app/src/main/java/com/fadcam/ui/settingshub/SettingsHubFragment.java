package com.fadcam.ui.settingshub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.BuildConfig;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.ui.BaseFragment;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.type.Type;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * SETTINGS, rebuilt for Joy Creator: every setting in one calm list grouped by what it is for,
 * with search and section chips at the bottom in the thumb's reach.
 *
 * <p>A new screen beside the classic Settings tab, which stays whole and one tap away under
 * Classic settings until this one is better in every way. Opened from the lobby's floor as an
 * overlay, so Back returns to the lobby exactly where it was. Each row opens the very screen the
 * classic tab opens ({@link SettingsDestinations}).</p>
 *
 * <p>This class only wires the parts together: {@link SettingsCatalog} holds, {@link SettingsSearch}
 * finds, {@link SettingsLines} lays out, {@link SettingsHubAdapter} draws, {@link SettingsStatus}
 * reads the live values, {@link SettingsSearchDock} takes the thumb's input.</p>
 */
public class SettingsHubFragment extends BaseFragment {

    public static final String TAG = "settings_hub";
    private static final String ARG_SEARCH = "focus_search";

    /** Show the hub over the lobby; with {@code focusSearch}, the keyboard opens on the search. */
    public static void open(@NonNull FragmentActivity activity, boolean focusSearch) {
        if (activity.getSupportFragmentManager().findFragmentByTag(TAG) != null) return;
        SettingsHubFragment f = new SettingsHubFragment();
        Bundle b = new Bundle();
        b.putBoolean(ARG_SEARCH, focusSearch);
        f.setArguments(b);
        OverlayNavUtil.show(activity, f, TAG);
    }

    // Kept on the fragment, not the view: the view is rebuilt each time a settings screen opened
    // from here closes again, and the words typed and values read should still be there.
    private String query = "";
    private Map<SettingsKey, SettingsStatus.Value> values = Collections.emptyMap();

    private final SettingsKeyboard keyboard = new SettingsKeyboard();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reloadStatus = this::reloadStatus;
    /** Any setting written, by a sheet open over the hub or by a screen it opened: re-read the values. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsChanged = (sp, key) -> {
        main.removeCallbacks(reloadStatus);
        main.postDelayed(reloadStatus, 150);
    };
    private SharedPreferences prefs;
    private SettingsStatus status;
    private SettingsSearch search;
    private List<List<SettingsKey>> shown;
    private SettingsHubAdapter adapter;
    private SettingsSearchDock dock;
    private LinearLayoutManager layout;
    private RecyclerView list;
    private LinearLayout empty;
    private TextView count;
    private int dockHeight, lift;
    /** A chip just tapped stays ON through its jump, until the list is moved by hand. */
    private int pinnedSection = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_hub, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        Context c = requireContext();
        status = new SettingsStatus(c);
        shown = SettingsCatalog.shown(c);
        search = new SettingsSearch(SettingsCatalog.entries(c, shown));

        TextView title = v.findViewById(R.id.settingshub_title);
        Type.display(title, Type.EXTRA);
        title.setAllCaps(true);
        count = v.findViewById(R.id.settingshub_count);
        Type.mono(count, Type.REGULAR);
        count.setSingleLine(true);
        count.setAllCaps(true);   // after setSingleLine: both are transformations, the last one wins
        TextView back = v.findViewById(R.id.settingshub_back);
        back.setText("arrow_back");
        SheetKit.label(back, getString(R.string.settingshub_back));
        back.setOnClickListener(b -> OverlayNavUtil.dismiss(requireActivity()));

        list = v.findViewById(R.id.settingshub_list);
        empty = v.findViewById(R.id.settingshub_empty);
        adapter = new SettingsHubAdapter(this::openRow);
        adapter.setValues(values);
        layout = new LinearLayoutManager(c);
        list.setLayoutManager(layout);
        list.setItemAnimator(null);   // a value arriving repaints its row; it must not blink
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
                    dock.hideKeyboard();
                    pinnedSection = -1;
                }
            }
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) { spy(); }
        });

        LinearLayout dockView = v.findViewById(R.id.settingshub_dock);
        dock = new SettingsSearchDock(dockView, new SettingsSearchDock.Listener() {
            @Override public void onQuery(@NonNull String q) { query = q; render(); }
            @Override public void onSection(int section) { jump(section); }
        });
        dock.chips.setShown(shown);
        dockView.addOnLayoutChangeListener((x, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != dockHeight) {
                dockHeight = b - t;
                list.post(this::pad);
            }
        });
        SettingsKeyboard.liftOver(v, dockView, px -> {
            lift = px;
            list.post(this::pad);
        });
        dock.setQuery(query);
        render();
        if (getArguments() != null && getArguments().getBoolean(ARG_SEARCH)) {
            getArguments().putBoolean(ARG_SEARCH, false);   // once: not again when a screen closes over it
            dock.focusSearch();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        keyboard.takeOver(requireActivity());
        showOverlayIfHidden();
        prefs = SharedPreferencesManager.getInstance(requireContext()).sharedPreferences;
        prefs.registerOnSharedPreferenceChangeListener(prefsChanged);
        // Read again on every return: a folder chosen, a theme picked, Motion Lab switched on.
        reloadStatus();
    }

    @Override
    public void onPause() {
        keyboard.giveBack(getActivity());
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefsChanged);
        main.removeCallbacks(reloadStatus);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (status != null) status.cancel();
        if (dock != null) dock.hideKeyboard();
        super.onDestroyView();
    }

    /** Back with words in the search clears them first. */
    @Override
    protected boolean onBackPressed() {
        return dock != null && dock.clearSearch();
    }

    // ── drawing ─────────────────────────────────────────────────────────────

    private void render() {
        if (adapter == null) return;
        boolean searching = !query.trim().isEmpty();
        List<SettingsLines.Line> lines;
        if (searching) {
            pinnedSection = -1;
            List<SettingsKey> hits = search.find(query);
            lines = SettingsLines.results(hits, SettingsCatalog.sectionOf());
            count.setText(getResources().getQuantityString(R.plurals.settingshub_matches, hits.size(), hits.size()));
        } else {
            lines = SettingsLines.browse(shown);
            count.setText(getString(R.string.settingshub_version, BuildConfig.VERSION_NAME));
        }
        boolean wasSearching = layout.getReverseLayout();
        // Hits gather just above the search field, the best one nearest the thumb.
        layout.setReverseLayout(searching);
        adapter.submit(lines);
        boolean nothing = searching && lines.isEmpty();
        empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
        if (nothing) SettingsEmpty.paint(empty, query, dock::clearSearch);
        if (searching || wasSearching) list.scrollToPosition(0);
        list.post(this::spy);
    }

    /** Keep the last row and the empty note clear of the dock, wherever the keyboard has lifted it. */
    private void pad() {
        if (list == null) return;
        int bottom = dockHeight + lift;
        list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(),
                bottom + SettingsViews.dp(requireContext(), 8));
        empty.setPadding(empty.getPaddingLeft(), empty.getPaddingTop(), empty.getPaddingRight(), bottom);
    }

    /** Light the chip of the section in view: the one at the top, or the last one once at the end. */
    private void spy() {
        if (layout == null || dock == null || layout.getReverseLayout()) return;
        if (pinnedSection >= 0) {
            dock.chips.setCurrent(pinnedSection);
            return;
        }
        int pos = list.canScrollVertically(1) ? layout.findFirstVisibleItemPosition()
                : layout.findLastVisibleItemPosition();
        int section = SettingsLines.sectionAt(adapter.lines(), pos);
        if (section >= 0) dock.chips.setCurrent(section);
    }

    private void jump(int section) {
        dock.hideKeyboard();
        int pos = SettingsLines.headerOf(adapter.lines(), section);
        if (pos < 0) return;
        pinnedSection = section;
        dock.chips.setCurrent(section);
        LinearSmoothScroller to = new LinearSmoothScroller(requireContext()) {
            @Override protected int getVerticalSnapPreference() { return SNAP_TO_START; }
        };
        to.setTargetPosition(pos);
        layout.startSmoothScroll(to);
    }

    // ── acting ──────────────────────────────────────────────────────────────

    private void openRow(@NonNull SettingsKey key) {
        dock.hideKeyboard();
        SettingsDestinations.open(this, key);
    }

    private void reloadStatus() {
        if (status == null) return;
        status.load(read -> {
            if (!isAdded() || getView() == null) return;
            values = read;
            adapter.setValues(read);
        });
    }

    /**
     * A settings screen's own back arrow closes the whole overlay ({@code OverlayNavUtil.dismiss}
     * hides the container, then pops one level), which lands back on this hub inside a hidden
     * container: the lobby would show with the hub invisible over it. Show it again.
     */
    private void showOverlayIfHidden() {
        FragmentActivity a = requireActivity();
        View container = a.findViewById(R.id.overlay_fragment_container);
        if (container == null || container.getVisibility() == View.VISIBLE) return;
        if (a.getSupportFragmentManager().findFragmentById(R.id.overlay_fragment_container) != this) return;
        container.setAlpha(0f);
        container.setVisibility(View.VISIBLE);
        container.animate().alpha(1f).setDuration(120).start();
    }
}
