package com.fadcam.ui.trash;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fadcam.R;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.model.TrashItem;
import com.fadcam.ui.BaseFragment;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.type.Type;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * RECENTLY DELETED: the one Trash (the app's own and the Joy Creator folder's), in the Library's look.
 *
 * <p>A new screen beside the classic Trash, which stays whole and one tap away under "Classic trash"
 * until this one is better in every way. Every change it makes goes through the classic screen's own
 * helpers ({@link TrashActions}). Opened as an overlay from the Library or the Settings hub; its back
 * arrow and system Back return to whichever opened it.</p>
 *
 * <p>This class only wires the parts: {@link TrashSource} reads, {@link TrashFilter} decides,
 * {@link TrashAdapter} draws, {@link TrashDock} takes the thumb's input.</p>
 */
public class RecentlyDeletedFragment extends BaseFragment {

    public static final String TAG = "recently_deleted";
    private static final String ARG_OVER = "over_another_overlay";

    /** Show Recently deleted over the lobby, or over the overlay screen that is open now. */
    public static void open(@NonNull FragmentActivity activity) {
        if (activity.getSupportFragmentManager().findFragmentByTag(TAG) != null) return;
        View container = activity.findViewById(R.id.overlay_fragment_container);
        boolean over = container != null && container.getVisibility() == View.VISIBLE
                && activity.getSupportFragmentManager().findFragmentById(R.id.overlay_fragment_container) != null;
        RecentlyDeletedFragment f = new RecentlyDeletedFragment();
        Bundle b = new Bundle();
        b.putBoolean(ARG_OVER, over);
        f.setArguments(b);
        OverlayNavUtil.show(activity, f, TAG);
    }

    // Kept on the fragment, not the view: the view is rebuilt when the classic Trash closes over it.
    private final TrashSelection selection = new TrashSelection();
    private List<TrashEntry> entries = new ArrayList<>();
    private TrashSource.Loaded loaded;
    private TrashKind kind;
    private boolean sweptOnOpen;

    private TrashSource source;
    private TrashAdapter adapter;
    private TrashDock dock;
    private RecyclerView grid;
    private LinearLayout empty;
    private TextView count, notice;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_recently_deleted, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        source = new TrashSource(requireContext());
        TextView title = v.findViewById(R.id.trash2_title);
        Type.display(title, Type.EXTRA);
        title.setAllCaps(true);
        count = v.findViewById(R.id.trash2_count);
        Type.mono(count, Type.REGULAR);
        count.setSingleLine(true);
        count.setAllCaps(true);   // after setSingleLine: both are transformations, the last one wins
        notice = v.findViewById(R.id.trash2_notice);
        Type.body(notice, Type.REGULAR);
        TextView back = v.findViewById(R.id.trash2_back);
        back.setText("arrow_back");
        SheetKit.label(back, getString(R.string.trash_close));
        back.setOnClickListener(b -> close());

        grid = v.findViewById(R.id.trash2_grid);
        empty = v.findViewById(R.id.trash2_empty);
        adapter = new TrashAdapter(selection, new TrashAdapter.Listener() {
            @Override public void onTap(@NonNull TrashEntry e) { tap(e); }
            @Override public void onHold(@NonNull TrashEntry e) { toggle(e); }
        });
        grid.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        grid.setAdapter(adapter);

        dock = new TrashDock(v.findViewById(R.id.trash2_dock), new TrashDock.Listener() {
            @Override public void onKind(@Nullable TrashKind k) { chooseKind(k); }
            @Override public void onOptions() { options(); }
            @Override public void onStopChoosing() { stopChoosing(); }
            @Override public void onRestore() { restore(); }
            @Override public void onDeleteForever() { deleteForever(); }
        });
        dock.chips.setSelected(kind);
        render();
        TrashLock.gate(requireActivity(), v, this::close);
    }

    @Override
    public void onResume() {
        super.onResume();
        showOverlayIfHidden();
        // The classic screen sweeps on every open; this one sweeps once per open, then just reads.
        reload(!sweptOnOpen);
        sweptOnOpen = true;
    }

    @Override
    public void onDestroyView() {
        if (source != null) source.cancel();
        super.onDestroyView();
    }

    /** Back while choosing stops choosing; otherwise it closes, back to whatever opened this. */
    @Override
    protected boolean onBackPressed() {
        if (!selection.isEmpty()) {
            stopChoosing();
            return true;
        }
        close();
        return true;
    }

    /**
     * Opened over the Library or the Settings hub: pop just this screen, so they show again.
     * ({@code OverlayNavUtil.dismiss} hides the whole overlay and pops one level, which left the
     * Library alive but invisible behind a bare lobby.) Opened on its own: dismiss the overlay.
     */
    private void close() {
        FragmentActivity a = getActivity();
        if (a == null || !isAdded()) return;
        boolean over = getArguments() != null && getArguments().getBoolean(ARG_OVER);
        if (over && a.getSupportFragmentManager().getBackStackEntryCount() > 1) {
            a.getSupportFragmentManager().popBackStack();
        } else {
            OverlayNavUtil.dismiss(a);
        }
    }

    /**
     * The classic Trash's back arrow hides the whole overlay and then pops one level, which lands
     * back here inside a hidden container. Show it again.
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

    // ── reading and drawing ─────────────────────────────────────────────────

    private void reload(boolean sweep) {
        source.load(sweep, l -> {
            if (!isAdded() || getView() == null) return;
            loaded = l;
            entries = l.entries;
            List<String> keys = new ArrayList<>(l.items.keySet());
            selection.retainOnly(keys);
            if (l.swept > 0) {
                Toast.makeText(requireContext(), getResources().getQuantityString(R.plurals.trash_done_swept,
                        l.swept, l.swept), Toast.LENGTH_LONG).show();
            }
            notice.setVisibility(l.folderUnreachable ? View.VISIBLE : View.GONE);
            render();
        });
    }

    private void render() {
        if (adapter == null) return;
        int minutes = TrashPolicy.minutes(requireContext());
        List<TrashEntry> shown = TrashFilter.apply(entries, kind);
        adapter.submit(shown, TrashPolicy.keepMillis(minutes));
        dock.chips.setCounts(TrashFilter.counts(entries));
        dock.setRule(TrashPolicy.rule(requireContext(), minutes));
        boolean reading = loaded == null;
        boolean nothing = shown.isEmpty() && !reading;
        empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
        if (nothing) TrashEmpty.paint(empty, kind, !entries.isEmpty(), () -> chooseKind(null));
        paintChoice();
    }

    private void paintChoice() {
        int n = selection.size();
        dock.setChoosing(n);
        int shown = adapter.getItemCount();
        String words = n > 0 ? getResources().getQuantityString(R.plurals.trash_count_selected, n, n)
                : getResources().getQuantityString(R.plurals.trash_count, shown, shown);
        count.setText(loaded == null ? getString(R.string.trash_count_reading, words) : words);
    }

    private void chooseKind(@Nullable TrashKind k) {
        kind = k;
        dock.chips.setSelected(k);
        render();
        grid.scrollToPosition(0);
    }

    // ── choosing ────────────────────────────────────────────────────────────

    private void tap(@NonNull TrashEntry e) {
        if (selection.isEmpty() && preview(e)) return;
        toggle(e);
    }

    private void toggle(@NonNull TrashEntry e) {
        selection.toggle(e.key);
        adapter.choiceChanged();
        paintChoice();
    }

    private void stopChoosing() {
        selection.clear();
        adapter.choiceChanged();
        paintChoice();
    }

    /** A recording or photo from the app's own Trash plays or shows, as the classic cards do. */
    private boolean preview(@NonNull TrashEntry e) {
        if (e.inFolder || e.look.uri == null) return false;
        boolean image = e.look.fileKind == FileKind.IMAGE;
        if (!image && e.look.fileKind != FileKind.VIDEO) return false;
        try {
            Intent i = new Intent(requireContext(), image ? com.fadcam.ui.ImageViewerActivity.class
                    : com.fadcam.ui.VideoPlayerActivity.class);
            i.setData(Uri.fromFile(new File(e.look.uri)));
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(requireContext(), R.string.trash_open_failed, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    @NonNull
    private List<TrashItem> chosenItems() {
        List<TrashItem> out = new ArrayList<>();
        if (loaded == null) return out;
        for (String key : selection.keys()) {
            TrashItem item = loaded.items.get(key);
            if (item != null) out.add(item);
        }
        return out;
    }

    // ── acting (every change goes through TrashFlows, then TrashActions: the classic helpers) ──

    private void restore() {
        TrashFlows.restore(requireContext(), chosenItems(), this::done);
    }

    private void deleteForever() {
        TrashFlows.deleteForever(requireContext(), chosenItems(), this::done);
    }

    private void options() {
        TrashOptionsSheet.show(requireContext(), !entries.isEmpty(), TrashPolicy.minutes(requireContext()),
                new TrashOptionsSheet.Listener() {
                    @Override public void onSelectAll() { selectAll(); }
                    @Override public void onRule() { TrashFlows.rule(requireContext(), entries, RecentlyDeletedFragment.this::done); }
                    @Override public void onEmpty() { TrashFlows.emptyTrash(requireContext(), entries.size(), RecentlyDeletedFragment.this::done); }
                    @Override public void onClassic() {
                        OverlayNavUtil.show(requireActivity(), new com.fadcam.ui.TrashFragment(), "trash");
                    }
                });
    }

    private void selectAll() {
        List<String> keys = new ArrayList<>();
        for (TrashEntry e : adapter.rows()) keys.add(e.key);
        selection.addAll(keys);
        adapter.choiceChanged();
        paintChoice();
    }

    /** After any action: say what happened, let go of the choice, read again. */
    private void done(@NonNull String message) {
        if (!isAdded() || getView() == null) return;
        if (!message.isEmpty()) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
        selection.clear();
        render();
        reload(false);
    }
}
