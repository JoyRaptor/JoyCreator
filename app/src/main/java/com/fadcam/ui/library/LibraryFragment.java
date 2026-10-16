package com.fadcam.ui.library;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.ui.BaseFragment;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.ui.TrashFragment;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.studio.NewProjectFlow;
import com.fadcam.ui.type.Type;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE LIBRARY: every kind of thing the user has made or brought in, in one calm grid.
 *
 * <p>A new screen beside the classic Records tab, which stays whole and one tap away under
 * "Classic view" (owner ruling f). Opened from the lobby's Library row and its search glass, as an
 * overlay, so Back returns to the lobby exactly where it was.</p>
 *
 * <p>This class only wires the parts together: {@link LibrarySource} reads, {@link LibraryFilter}
 * decides, {@link LibraryAdapter} draws, {@link LibraryDock} takes the thumb's input.</p>
 */
public class LibraryFragment extends BaseFragment {

    private static final String TAG = "library";
    private static final String ARG_SEARCH = "focus_search";
    private static final String ARG_KIND = "kind";
    private static final String ARG_START_NEW = "start_new";
    /** MainActivity's tabs for the two classic screens this one is replacing. */
    private static final int TAB_RECORDS = 1, TAB_STUDIO = 3;

    /** Show the Library over the lobby; with {@code focusSearch}, the keyboard opens on the search. */
    public static void open(@NonNull FragmentActivity activity, boolean focusSearch) {
        Bundle b = new Bundle();
        b.putBoolean(ARG_SEARCH, focusSearch);
        show(activity, b);
    }

    /**
     * Show the Library on one kind's chip. On Projects it is the Studio's project list, with New
     * project in reach; {@code startNew} (the lobby's New buttons) also opens the new-project sheet
     * over it at once, so closing that sheet leaves you on your projects.
     */
    public static void open(@NonNull FragmentActivity activity, @NonNull LibraryKind kind, boolean startNew) {
        Bundle b = new Bundle();
        b.putString(ARG_KIND, kind.name());
        b.putBoolean(ARG_START_NEW, startNew && kind == LibraryKind.PROJECTS);
        show(activity, b);
    }

    private static void show(@NonNull FragmentActivity activity, @NonNull Bundle args) {
        if (activity.getSupportFragmentManager().findFragmentByTag(TAG) != null) return;
        LibraryFragment f = new LibraryFragment();
        f.setArguments(args);
        OverlayNavUtil.show(activity, f, TAG);
    }

    private final LibraryFilter filter = new LibraryFilter();
    private NewProjectFlow newProject;
    private int gridFloor;
    private LibrarySource source;
    private LibraryAdapter adapter;
    private LibraryDock dock;
    private GridLayoutManager grid;
    private RecyclerView list;
    private LinearLayout empty;
    private TextView count, notice;
    private List<LibraryItem> items = new ArrayList<>();
    private boolean complete;
    private boolean dense;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Here, as the platform requires: the video picker answers this screen.
        newProject = new NewProjectFlow(this, null);
        String kind = getArguments() == null ? null : getArguments().getString(ARG_KIND);
        if (kind != null) filter.setKind(LibraryKind.valueOf(kind));
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_library, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        source = new LibrarySource(requireContext());
        dense = LibraryPrefs.list(requireContext());
        filter.setSort(LibraryPrefs.sort(requireContext()));

        TextView title = v.findViewById(R.id.library_title);
        Type.display(title, Type.EXTRA);
        title.setAllCaps(true);
        count = v.findViewById(R.id.library_count);
        Type.mono(count, Type.REGULAR);
        count.setSingleLine(true);
        count.setAllCaps(true);   // after setSingleLine: both are transformations, the last one wins
        notice = v.findViewById(R.id.library_notice);
        Type.body(notice, Type.REGULAR);
        TextView back = v.findViewById(R.id.library_back);
        back.setText("arrow_back");
        SheetKit.label(back, getString(R.string.library_back));
        back.setOnClickListener(b -> OverlayNavUtil.dismiss(requireActivity()));

        sortBtn = v.findViewById(R.id.library_sort);
        viewBtn = v.findViewById(R.id.library_view);
        sortBtn.setOnClickListener(b -> cycleSort());
        viewBtn.setOnClickListener(b -> cycleView());
        paintHeaderCycles();

        list = v.findViewById(R.id.library_grid);
        gridFloor = list.getPaddingBottom();
        empty = v.findViewById(R.id.library_empty);
        adapter = new LibraryAdapter(new LibraryAdapter.Listener() {
            @Override public void onOpen(@NonNull LibraryItem it) { open(it); }
            @Override public void onMenu(@NonNull LibraryItem it) { menu(it); }
        });
        grid = new GridLayoutManager(requireContext(), dense ? 1 : 2);
        grid.setSpanSizeLookup(adapter.spans(grid));
        list.setLayoutManager(grid);
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) dock.hideKeyboard();
            }
        });

        dock = new LibraryDock(v.findViewById(R.id.library_dock), new LibraryDock.Listener() {
            @Override public void onKind(@Nullable LibraryKind kind) { chooseKind(kind); }
            @Override public void onQuery(@NonNull String q) { filter.setQuery(q); render(); }
            @Override public void onSharedCycle() { filter.setShared(filter.shared().next()); render(); }
            @Override public void onOptions() { options(); }
            @Override public void onNewProject() { startNewProject(); }
        });
        // The view is rebuilt when Trash is opened over it and closed again; the filter is not.
        dock.setQuery(filter.query());
        dock.paintShared(filter.shared());
        dock.chips.setSelected(filter.kind());
        liftDockOverKeyboard(v, v.findViewById(R.id.library_dock));
        render();
        if (getArguments() != null && getArguments().getBoolean(ARG_SEARCH)) {
            getArguments().putBoolean(ARG_SEARCH, false);   // once: not again when Trash closes over it
            dock.focusSearch();
        }
        if (getArguments() != null && getArguments().getBoolean(ARG_START_NEW)) {
            getArguments().putBoolean(ARG_START_NEW, false);   // once, as with search
            v.post(this::startNewProject);                      // once the screen is attached
        }
    }

    private void startNewProject() {
        if (!isAdded() || dock == null) return;
        dock.hideKeyboard();
        newProject.start();
    }

    /** The window's keyboard mode before this screen took over, restored on pause. */
    private int previousSoftInputMode = -1;

    @Override
    public void onResume() {
        super.onResume();
        // The search field sits at the bottom; with the host window's default mode (pan or
        // nothing) the keyboard covered it, so you typed blind. Resize the window while this
        // screen is up and put the host's own mode back when it leaves.
        android.view.Window w = requireActivity().getWindow();
        previousSoftInputMode = w.getAttributes().softInputMode;
        w.setSoftInputMode((previousSoftInputMode & ~android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // Read again on every return: a project edited, a drawing saved, a file shared in.
        reload();
    }

    @Override
    public void onPause() {
        if (previousSoftInputMode != -1 && getActivity() != null) {
            getActivity().getWindow().setSoftInputMode(previousSoftInputMode);
        }
        previousSoftInputMode = -1;
        super.onPause();
    }

    private void reload() {
        source.load((all, done, folder) -> {
            if (!isAdded() || getView() == null) return;
            items = all;
            complete = done;
            showNotice(folder);
            render();
        });
    }

    @Override
    public void onDestroyView() {
        if (source != null) source.cancel();
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
        List<LibraryItem> shown = filter.apply(items);
        adapter.submit(LibrarySections.rows(shown, filter.sort() == LibraryFilter.Sort.NEWEST,
                LibraryFormat.startOfToday()), dense);
        Map<LibraryKind, Integer> counts = filter.counts(items);
        Set<LibraryKind> present = EnumSet.noneOf(LibraryKind.class);
        for (LibraryItem it : items) present.add(it.kind);
        dock.chips.setCounts(counts, present);
        dock.paintShared(filter.shared());
        // On the Projects chip, however it was chosen, New project shows and the grid's floor
        // rises by it, so the last cards still scroll clear.
        int lift = dock.showNewProject(filter.kind() == LibraryKind.PROJECTS);
        list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), gridFloor + lift);

        String n = getResources().getQuantityString(R.plurals.library_count, shown.size(), shown.size());
        count.setText(complete ? n : getString(R.string.library_count_reading, n));
        boolean nothing = shown.isEmpty() && complete;
        empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
        if (nothing) LibraryEmpty.paint(empty, filter, this::showEverything);
    }

    private void showNotice(@NonNull LibrarySource.FolderState folder) {
        int words = folder == LibrarySource.FolderState.NOT_CHOSEN ? R.string.library_notice_no_folder
                : folder == LibrarySource.FolderState.UNREACHABLE ? R.string.library_notice_unreachable : 0;
        notice.setVisibility(words == 0 ? View.GONE : View.VISIBLE);
        if (words != 0) notice.setText(words);
    }

    private void chooseKind(@Nullable LibraryKind kind) {
        filter.setKind(kind);
        dock.chips.setSelected(kind);
        render();
        list.scrollToPosition(0);
    }

    private void showEverything() {
        dock.clearSearch();
        filter.setShared(LibraryFilter.Shared.ANY);
        chooseKind(null);
    }

    // ── acting ──────────────────────────────────────────────────────────────

    private void open(@NonNull LibraryItem it) {
        dock.hideKeyboard();
        if (!LibraryOpener.open(requireActivity(), it)) menu(it);
    }

    private void menu(@NonNull LibraryItem it) {
        dock.hideKeyboard();
        LibraryItemSheet.show(requireContext(), it, new LibraryItemSheet.Listener() {
            @Override public void onOpen(@NonNull LibraryItem item) {
                if (isAdded() && !LibraryOpener.open(requireActivity(), item)) {
                    Toast.makeText(requireContext(), R.string.library_open_failed, Toast.LENGTH_SHORT).show();
                }
            }
            @Override public void onDone(@NonNull String message, boolean changed) {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                if (changed) reload();
            }
        });
    }

    private TextView sortBtn, viewBtn;

    /** Sort and view are always one tap away in the header, each a cycle that shows what it is now. */
    private void paintHeaderCycles() {
        boolean newest = filter.sort() == LibraryFilter.Sort.NEWEST;
        sortBtn.setText(newest ? "schedule" : "sort_by_alpha");
        SheetKit.label(sortBtn, getString(newest ? R.string.library_sort_cycle_newest : R.string.library_sort_cycle_name));
        viewBtn.setText(dense ? "view_list" : "grid_view");
        SheetKit.label(viewBtn, getString(dense ? R.string.library_view_cycle_list : R.string.library_view_cycle_grid));
    }

    private void cycleSort() {
        LibraryFilter.Sort next = filter.sort() == LibraryFilter.Sort.NEWEST
                ? LibraryFilter.Sort.NAME : LibraryFilter.Sort.NEWEST;
        filter.setSort(next);
        LibraryPrefs.setSort(requireContext(), next);
        paintHeaderCycles();
        render();
        list.scrollToPosition(0);
    }

    private void cycleView() {
        dense = !dense;
        LibraryPrefs.setList(requireContext(), dense);
        grid.setSpanCount(dense ? 1 : 2);
        paintHeaderCycles();
        render();
    }

    private void options() {
        dock.hideKeyboard();
        LibraryOptionsSheet.show(requireContext(), new LibraryOptionsSheet.Listener() {
            @Override public void onTrash() {
                com.fadcam.ui.trash.RecentlyDeletedFragment.open(requireActivity());
            }
            @Override public void onClassic() { leaveFor(TAB_RECORDS); }
            @Override public void onClassicStudio() { leaveFor(TAB_STUDIO); }
        });
    }

    /** Out to a classic screen: the overlay closes and its tab shows. */
    private void leaveFor(int tab) {
        FragmentActivity a = requireActivity();
        OverlayNavUtil.dismiss(a);
        if (a instanceof MainActivity) ((MainActivity) a).switchFragment(tab, true);
    }

    /**
     * Keep the dock above the keyboard. Up to Android 10 the window's own bottom inset already
     * includes the keyboard (MainActivity pads the overlay by it); from 11 the keyboard is its own
     * inset, so the dock is lifted by the part of it the system bars do not already cover.
     */
    private static void liftDockOverKeyboard(@NonNull View root, @NonNull View dock) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int lift;
            if (Build.VERSION.SDK_INT >= 30) {
                int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
                int bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
                lift = ime - bars;
            } else {
                // Android 10 and older: the keyboard arrives inside the system window inset, and the
                // stable inset is what the bars alone take. The difference is the keyboard.
                lift = insets.getSystemWindowInsetBottom() - insets.getStableInsetBottom();
            }
            dock.setTranslationY(-Math.max(0, lift));
            return insets;
        });
    }
}
