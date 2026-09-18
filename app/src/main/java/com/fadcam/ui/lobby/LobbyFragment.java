package com.fadcam.ui.lobby;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.StatFs;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.FLog;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.ui.BaseFragment;
import com.fadcam.ui.faditor.FaditorEditorActivity;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.data.VideoIndexRepository;
import com.fadcam.ui.VideoItem;
import com.fadcam.ui.motion.Motion;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * THE LOBBY — Joy Creator's front door.
 *
 * <p>Design record 04 ("The Marquee"), built to JoyRaptor's own brainstorm of
 * 2026-09-17. The screen has two halves doing two different jobs:</p>
 *
 * <ul>
 *   <li><b>The top navigates by CATEGORY and is alive.</b> A wrap-around carousel of
 *       room titles — heavy white for the one you are on, thin grey for the rest — and
 *       a hero showing whatever that room last made.</li>
 *   <li><b>The bottom navigates by RECENCY, then never moves.</b> Recents, the New row,
 *       Joybot's one line, and a floor of quiet words for the rooms you visit rarely.</li>
 * </ul>
 *
 * <p><b>Why a carousel rather than tabs.</b> A new capability costs ONE WORD in
 * {@link #buildRooms()}. When the drawing engine lands it is a word on this row: no grid
 * to re-cut, no nav slot to argue about, no "where does it go". That is the cheapest way
 * to add a room to this app for the next five years, and it is the reason the marquee is
 * worth building instead of a bottom bar with six fixed icons.</p>
 *
 * <p><b>Colour discipline.</b> The hero's action button is the ONLY saturated control on
 * the screen. The New row is grey buttons with full-strength coloured glyphs — deliberately
 * NOT coloured buttons — so the accent stays an accent. Room gradients are JoyRaptor's final
 * mapping of 2026-09-17 and live in one table below, because he asked to be able to change
 * his mind later by feel rather than by argument.</p>
 *
 * <p><b>What this fragment does NOT do.</b> It does not replace any existing screen. Every
 * room it points at is an existing tab or activity, so the lobby is a router: reversible,
 * and it cannot lose a feature.</p>
 */
public class LobbyFragment extends BaseFragment {

    // ── The ramp, matching the studio tokens ────────────────────────────────
    private static final int INK      = 0xFFE4E4E7;
    private static final int DIM      = 0xFFA1A1AA;
    private static final int DIMMER   = 0xFF71717A;
    private static final int DIMMEST  = 0xFF4B4B55;
    private static final int PANEL    = 0xFF111114;
    private static final int CTL      = 0xFF1C1C22;
    private static final int ON_LIGHT = 0xFF050507;

    private static final int WARN     = 0xFFFBBF24;

    /** MainActivity tab positions, named so the routing reads as English. */
    private static final int TAB_CAPTURE  = 0;
    private static final int TAB_LIBRARY  = 1;
    private static final int TAB_REMOTE   = 2;
    private static final int TAB_STUDIO   = 3;
    private static final int TAB_SETUP    = 4;
    private static final int TAB_FINDER   = 5;

    /**
     * One room on the marquee.
     *
     * <p>A room is a CATEGORY OF THING YOU MAKE, not a feature. That is why Capture is on
     * here beside Studio: a recording is something you made, and its hero is the viewfinder
     * rather than a picture of a camera.</p>
     */
    private static final class Room {
        final String title;
        final int gradA, gradB;
        /** Ink that reads on this room's gradient — black for light pairs, white for dark. */
        final int onGrad;
        final String tag;
        final String action;
        final String emptyCopy;
        /** Tab to route to, or -1 when the room opens an activity / has nowhere to go yet. */
        final int tab;
        final String glyph;

        Room(String title, int gradA, int gradB, int onGrad, String tag,
             String action, String emptyCopy, int tab, String glyph) {
            this.title = title; this.gradA = gradA; this.gradB = gradB; this.onGrad = onGrad;
            this.tag = tag; this.action = action; this.emptyCopy = emptyCopy;
            this.tab = tab; this.glyph = glyph;
        }
    }

    private final List<Room> rooms = new ArrayList<>();
    private int active = 0;

    private ProjectStorage projectStorage;
    private List<ProjectStorage.ProjectSummary> projects = new ArrayList<>();

    private LinearLayout marquee, recentsRow, newRow, floorRow;
    private FrameLayout hero;
    private ImageView heroArt;
    private View heroWash, heroBar;
    private TextView heroTag, heroEmpty, heroName, heroSub, heroAction;
    private TextView statIcon, statText, wordmark, botOrb;
    private TextView libraryCount;
    private View hairline, hairlineFill;
    private View botLine;
    private TextView botLineText, botLineYes, botLineNo, botLineOrb;
    private Typeface iconFont;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int statIndex = 0;
    private Runnable statTick;

    // ── lifecycle ───────────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_lobby, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        iconFont = ResourcesCompat.getFont(requireContext(), R.font.materialicons);
        projectStorage = new ProjectStorage(requireContext());

        marquee    = v.findViewById(R.id.lobby_marquee);
        recentsRow = v.findViewById(R.id.lobby_recents);
        newRow     = v.findViewById(R.id.lobby_new_row);
        floorRow   = v.findViewById(R.id.lobby_floor);
        hero       = v.findViewById(R.id.lobby_hero);
        heroArt    = v.findViewById(R.id.lobby_hero_art);
        heroWash   = v.findViewById(R.id.lobby_hero_wash);
        heroBar    = v.findViewById(R.id.lobby_hero_bar);
        heroTag    = v.findViewById(R.id.lobby_hero_tag);
        heroEmpty  = v.findViewById(R.id.lobby_hero_empty);
        heroName   = v.findViewById(R.id.lobby_hero_name);
        heroSub    = v.findViewById(R.id.lobby_hero_sub);
        heroAction = v.findViewById(R.id.lobby_hero_action);
        statIcon   = v.findViewById(R.id.lobby_stat_icon);
        statText   = v.findViewById(R.id.lobby_stat_text);
        wordmark   = v.findViewById(R.id.lobby_wordmark);
        botOrb     = v.findViewById(R.id.lobby_bot);
        libraryCount = v.findViewById(R.id.lobby_library_count);
        hairline    = v.findViewById(R.id.lobby_hairline);
        botLine     = v.findViewById(R.id.lobby_bot_line);
        botLineText = v.findViewById(R.id.lobby_bot_line_text);
        botLineYes  = v.findViewById(R.id.lobby_bot_line_yes);
        botLineNo   = v.findViewById(R.id.lobby_bot_line_no);
        botLineOrb  = v.findViewById(R.id.lobby_bot_line_orb);
        botLineOrb.setBackground(circleGradient(0xFFCC27FF, 0xFF5C43FD));
        botLineYes.setBackground(pill(INK, dp(999)));
        botLineNo.setOnClickListener(b -> {
            botDismissed = true;
            botLine.setVisibility(View.GONE);
        });

        // Display face. Nothing heavier than Ubuntu Regular is bundled, so the marquee
        // borrows Android's own weight family — sans-serif-black / -light give REAL weights
        // rather than a synthesised bold, which at 29sp is the difference between a display
        // face and a smeared one. A bundled display font is on the asset list.
        wordmark.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        heroName.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));

        TextView libLabel = v.findViewById(R.id.lobby_library_label);
        libLabel.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));

        // Joybot's orb — the one place a gradient is allowed in the chrome, because it is
        // a character rather than a control.
        botOrb.setBackground(circleGradient(0xFFCC27FF, 0xFF5C43FD));
        v.findViewById(R.id.lobby_bot).setOnClickListener(b -> routeTab(TAB_STUDIO));

        View libraryDoor = v.findViewById(R.id.lobby_library_door);
        libraryDoor.setOnClickListener(b -> routeTab(TAB_LIBRARY));
        Motion.press(libraryDoor);
        Motion.press(botOrb);
        Motion.press(v.findViewById(R.id.lobby_search));
        v.findViewById(R.id.lobby_search).setOnClickListener(b -> routeTab(TAB_LIBRARY));

        buildRooms();
        buildNewRow();
        buildFloor();
        startStatCycle();
    }

    @Override
    public void onResume() {
        super.onResume();
        // The lobby's ground is true black. A maroon status bar above it is the single most
        // visible piece of the old identity left on the front door.
        try {
            android.view.Window w = requireActivity().getWindow();
            w.setStatusBarColor(0xFF000000);
            w.setNavigationBarColor(0xFF000000);
        } catch (Exception ignored) { }
        registerExportWatch();
        reloadProjects();
        paintMarquee();
        paintHero();
        loadRecentsAsync();
    }

    @Override
    public void onPause() {
        unregisterExportWatch();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (statTick != null) ui.removeCallbacks(statTick);
        unregisterExportWatch();
        hairlineFill = null;
        super.onDestroyView();
    }

    // ── the rooms ───────────────────────────────────────────────────────────

    /**
     * JoyRaptor's final room→colour mapping, 2026-09-17. ONE TABLE, deliberately: he asked
     * to be able to swap these by feel later, and a mapping spread across nine files is a
     * mapping nobody ever changes again.
     *
     * <p>Finder sits on golden→amber and Remote on indigo→blue rather than on their first
     * candidates, because aqua→lime, lime→yellow-green and cyan→aqua are neighbours on the
     * wheel and Studio, Finder and Remote would have read as one family side by side.</p>
     */
    private void buildRooms() {
        rooms.clear();
        rooms.add(new Room(getString(R.string.lobby_room_studio),
                0xFF35F6BF, 0xFF97FE8B, ON_LIGHT, "LAST PROJECT",
                getString(R.string.lobby_act_carry_on),
                getString(R.string.lobby_empty_studio), TAB_STUDIO, "movie_edit"));

        rooms.add(new Room(getString(R.string.lobby_room_capture),
                0xFFFA3D5D, 0xFFFF008C, Color.WHITE, "READY",
                getString(R.string.lobby_act_record), null, TAB_CAPTURE, "videocam"));

        rooms.add(new Room(getString(R.string.lobby_room_sprites),
                0xFFFF008C, 0xFFCC27FF, Color.WHITE, "LAST SHEET",
                getString(R.string.lobby_act_open),
                getString(R.string.lobby_empty_sprites), -1, "directions_run"));

        rooms.add(new Room(getString(R.string.lobby_room_avatar),
                0xFFCC27FF, 0xFF8C3DFA, Color.WHITE, "LAST CHARACTER",
                getString(R.string.lobby_act_open),
                getString(R.string.lobby_empty_avatar), -1, "accessibility_new"));

        rooms.add(new Room(getString(R.string.lobby_room_viz),
                0xFFFAA03D, 0xFFFC6818, ON_LIGHT, "LAST VISUALISER",
                getString(R.string.lobby_act_start),
                getString(R.string.lobby_empty_viz), -1, "graphic_eq"));
    }

    private Room room() { return rooms.get(active); }

    // ── the marquee ─────────────────────────────────────────────────────────

    /**
     * Rebuilds the title row with the active room first. Tapping the third word rotates the
     * list by three, so the carousel WRAPS rather than scrolling to an end and stopping —
     * which is what makes it feel like a dial rather than a list.
     */
    private void paintMarquee() {
        if (marquee == null) return;
        marquee.removeAllViews();
        for (int i = 0; i < rooms.size(); i++) {
            final int offset = i;
            Room r = rooms.get((active + i) % rooms.size());
            TextView t = new TextView(requireContext());
            t.setText(r.title);
            t.setIncludeFontPadding(false);
            t.setMaxLines(1);
            t.setSingleLine(true);
            if (i == 0) {
                t.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f);
                t.setTextColor(INK);
                t.setLetterSpacing(-0.045f);
            } else if (i == 1) {
                t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                t.setTextColor(DIMMER);
            } else {
                t.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                t.setTextColor(DIMMEST);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(15));
            lp.bottomMargin = dp(i == 0 ? 6 : 9);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> {
                if (offset == 0) { enterRoom(); return; }
                active = (active + offset) % rooms.size();
                paintMarquee();
                // The hero CROSS-FADES rather than cutting. Movement would imply the old
                // room's content went somewhere you could scroll back to; a fade says it
                // simply became something else, which is what actually happened.
                Motion.swap(hero, Motion.HERO, this::paintHero);
            });
            Motion.press(t);
            marquee.addView(t);
        }
    }

    // ── the hero ────────────────────────────────────────────────────────────

    private void paintHero() {
        if (hero == null) return;
        Room r = room();

        heroBar.setBackground(linearGradient(r.gradA, r.gradB));

        boolean hasContent;
        String name, sub;

        if (r.tab == TAB_CAPTURE) {
            // Capture's hero is the room's READINESS, not a picture of a camera. Until the
            // live viewfinder is wired through, it states what pressing Record will do.
            hasContent = true;
            name = "Back camera";
            sub  = capturedSummary();
        } else if (rooms.indexOf(r) == 0) {
            hasContent = !projects.isEmpty();
            name = hasContent ? projects.get(0).name : "";
            sub  = hasContent ? projectSub(projects.get(0)) : "";
        } else {
            // Sprite Lab, Avatar and Viz Lab all live INSIDE a project today (STATE §9
            // defect 13: "sheets live inside project.json, so a character perfected in one
            // project does not exist in the next"). So the honest hero is the most recent
            // project's, and the empty state is honest too when there is none.
            hasContent = !projects.isEmpty() && rooms.indexOf(r) != 4;
            name = hasContent ? projects.get(0).name : "";
            sub  = hasContent ? "IN " + projects.get(0).name.toUpperCase(Locale.US) : "";
        }

        heroTag.setText(r.tag);
        heroTag.setBackground(pill(0x8C050507, dp(999)));
        heroTag.setVisibility(hasContent ? View.VISIBLE : View.GONE);

        heroEmpty.setVisibility(hasContent ? View.GONE : View.VISIBLE);
        heroEmpty.setText(r.emptyCopy == null ? "" : r.emptyCopy);

        // THE WASH — and what it must NOT do.
        //
        // The first version laid the room's gradient over the whole hero at 30% and it
        // tinted the user's own footage green. That is the exact opposite of the rule this
        // studio is built on: "old black is great for letting your art be the centerpiece."
        // A front door that recolours your work is worse than a plain one.
        //
        // So the wash is now COLOURLESS and only at the edges: a short darkening at the top
        // so the tag reads, and a taller one at the bottom so the name and the room's 3dp
        // signature bar have something to sit against. The picture between them is untouched.
        GradientDrawable wash = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ 0xB3000000, 0x26000000, 0x00000000, 0x40000000, 0xD9000000 });
        wash.setGradientCenter(0.5f, 0.5f);
        heroWash.setBackground(wash);


        heroArt.setImageDrawable(null);
        heroArt.setTag(R.id.lobby_thumb_tag, null);
        if (hasContent) {
            heroArt.setBackgroundColor(0xFF08080B);
            if (!projects.isEmpty() && r.tab != TAB_CAPTURE) {
                loadThumbInto(heroArt, projects.get(0).videoUri);
            }
        } else {
            // A room with nothing in it has no artwork to protect, and a flat black
            // rectangle reads as a failure rather than as an invitation. This is the ONE
            // case where the room's own colour fills the hero: with no content, the colour
            // is the only thing on screen saying which room you are standing in.
            heroArt.setBackground(new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{ withAlpha(r.gradA, 0x47), withAlpha(r.gradB, 0x2B), 0xFF08080B }));
        }

        heroName.setText(name);
        heroName.setVisibility(hasContent ? View.VISIBLE : View.INVISIBLE);
        heroSub.setText(sub);
        heroSub.setVisibility(hasContent ? View.VISIBLE : View.INVISIBLE);

        heroAction.setText(hasContent ? r.action : getString(R.string.lobby_act_start));
        heroAction.setTextColor(r.onGrad);
        heroAction.setBackground(pillGradient(r.gradA, r.gradB, dp(999)));
        heroAction.setOnClickListener(v -> enterRoom());
        Motion.press(heroAction);
        hero.setOnClickListener(v -> enterRoom());
        Motion.press(hero);
    }

    /** Where a marquee word actually takes you. */
    private void enterRoom() {
        Room r = room();

        // "Carry on" names a PROJECT, so it must open that project. Routing to the project
        // LIST would be a button that lies: it says the name of the thing and then hands you
        // a list containing it. Caught by driving the screen rather than by reading it.
        if (rooms.indexOf(r) == 0 && !projects.isEmpty()) {
            openProject(projects.get(0).id);
            return;
        }

        if (r.tab >= 0) { routeTab(r.tab); return; }

        String projectId = projects.isEmpty() ? null : projects.get(0).id;
        int idx = rooms.indexOf(r);
        try {
            if (idx == 2 && projectId != null) {                    // Sprite Lab
                Intent i = new Intent(requireContext(),
                        com.fadcam.ui.faditor.sprite.SpriteSheetEditorActivity.class);
                i.putExtra(com.fadcam.ui.faditor.sprite.SpriteSheetEditorActivity.EXTRA_PROJECT_ID,
                        projectId);
                startActivity(i);
                return;
            }
            if (idx == 3 && projectId != null) {                    // Avatar Studio
                Intent i = new Intent(requireContext(),
                        com.fadcam.ui.faditor.avatar.AvatarStudioActivity.class);
                i.putExtra(com.fadcam.ui.faditor.avatar.AvatarStudioActivity.EXTRA_PROJECT_ID,
                        projectId);
                startActivity(i);
                return;
            }
        } catch (Exception e) {
            FLog.w("Lobby", "enterRoom failed for " + r.title, e);
        }
        // Viz Lab has no room yet, and neither bench has anywhere to live without a project.
        // Sending someone to the Studio is the honest fallback: it is where they will make one.
        routeTab(TAB_STUDIO);
    }

    private void routeTab(int position) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).switchFragment(position, true);
        }
    }

    // ── carry on ────────────────────────────────────────────────────────────

    /**
     * The recents row is DELIBERATELY MIXED — a project beside a sheet beside an export, in
     * the order you touched them. That is what makes it fast, and it is also what makes it
     * unreadable without a tell, which is what the corner cut is for.
     */
    private void paintRecents() {
        if (recentsRow == null) return;
        recentsRow.removeAllViews();

        if (recents.isEmpty()) {
            TextView none = new TextView(requireContext());
            none.setText("Nothing yet. Make something and it will land here.");
            none.setTextColor(DIMMER);
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            none.setPadding(0, dp(16), 0, dp(16));
            recentsRow.addView(none);
            libraryCount.setText("");
            return;
        }

        libraryCount.setText(String.format(Locale.US, "%d ITEM%s",
                recents.size(), recents.size() == 1 ? "" : "S"));

        int n = Math.min(recents.size(), 10);
        for (int i = 0; i < n; i++) {
            View card = recentCard(recents.get(i));
            recentsRow.addView(card);
            Motion.enter(card, i);
        }
    }

    private View recentCard(Recent item) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(pill(PANEL, dp(12)));
        card.setClipToOutline(true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(104),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMarginEnd(dp(9));
        card.setLayoutParams(clp);

        FrameLayout thumbWrap = new FrameLayout(requireContext());
        thumbWrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));

        ImageView thumb = new ImageView(requireContext());
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(0xFF17171D);
        loadThumbInto(thumb, item.thumbUri);
        thumbWrap.addView(thumb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // THE CORNER CUT. A solid diagonal in the room's gradient. No badge, no label, no
        // icon: the corner is already dead space, and colour is the fastest thing the eye
        // resolves. Aqua means project; pink-to-violet will mean sheet.
        View cut = new View(requireContext());
        cut.setBackground(new CornerCut(item.gradA, item.gradB));
        FrameLayout.LayoutParams cutLp = new FrameLayout.LayoutParams(dp(24), dp(24));
        cutLp.gravity = Gravity.BOTTOM | Gravity.END;
        thumbWrap.addView(cut, cutLp);
        card.addView(thumbWrap);

        TextView name = new TextView(requireContext());
        name.setText(item.name);
        name.setTextColor(INK);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(1);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(dp(8), dp(6), dp(8), 0);
        card.addView(name);

        TextView meta = new TextView(requireContext());
        meta.setText(item.kindLabel + " \u00b7 " + ago(item.when));
        meta.setTextColor(DIMMER);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        meta.setTypeface(Typeface.MONOSPACE);
        meta.setMaxLines(1);
        meta.setSingleLine(true);
        meta.setPadding(dp(8), dp(1), dp(8), dp(8));
        card.addView(meta);

        card.setOnClickListener(v -> {
            if (item.projectId != null) { openProject(item.projectId); return; }
            // A recording is not a project: opening it in the editor would silently CREATE
            // one. Sending the user to the Library is the honest action until "start a
            // project from this clip" exists as a deliberate choice.
            routeTab(TAB_LIBRARY);
        });
        Motion.press(card);
        return card;
    }


    /**
     * ONE ROW, MANY KINDS.
     *
     * <p>The recents row is deliberately mixed - a project beside a recording beside an
     * export, in the order you touched them. That is what makes it fast to use, and it is
     * also exactly what makes it unreadable without a tell, which is the whole reason the
     * corner cut exists. A row of nothing but projects would have made the cut decoration;
     * a mixed row makes it information.</p>
     */
    private static final class Recent {
        final String name, kindLabel, thumbUri;
        final long when;
        final int gradA, gradB;
        @Nullable final String projectId;

        Recent(String name, String kindLabel, String thumbUri, long when,
               int gradA, int gradB, @Nullable String projectId) {
            this.name = name; this.kindLabel = kindLabel; this.thumbUri = thumbUri;
            this.when = when; this.gradA = gradA; this.gradB = gradB;
            this.projectId = projectId;
        }
    }

    private List<Recent> recents = new ArrayList<>();

    /** Room gradients, reused so a card's corner matches the room that made it. */
    private static final int G_STUDIO_A  = 0xFF35F6BF, G_STUDIO_B  = 0xFF97FE8B;
    private static final int G_CAPTURE_A = 0xFFFA3D5D, G_CAPTURE_B = 0xFFFF008C;
    private static final int G_LIBRARY_A = 0xFF4397FD, G_LIBRARY_B = 0xFF55E0F9;
    private static final int G_SOUND_A   = 0xFFFAA03D, G_SOUND_B   = 0xFFFC6818;

    /**
     * Merge projects and recordings into one recency-ordered list, off the main thread.
     *
     * <p>{@code getVideos} walks the storage tree, so it can take a moment on a full phone.
     * The lobby paints the projects it already has IMMEDIATELY and folds the recordings in
     * when they arrive - a front door that waits on a file scan is a front door that feels
     * broken, however fast the scan usually is.</p>
     */
    private void loadRecentsAsync() {
        List<Recent> immediate = new ArrayList<>();
        for (ProjectStorage.ProjectSummary p : projects) {
            immediate.add(new Recent(p.name, "PROJECT", p.videoUri, p.lastModified,
                    G_STUDIO_A, G_STUDIO_B, p.id));
        }
        recents = immediate;
        paintRecents();
        paintBotLine();

        THUMB_IO.execute(() -> {
            final List<Recent> merged = new ArrayList<>(immediate);
            try {
                List<VideoItem> vids = VideoIndexRepository.getInstance(requireContext())
                        .getVideos(SharedPreferencesManager.getInstance(requireContext()));
                if (vids != null) {
                    for (VideoItem v : vids) {
                        if (v == null || v.uri == null) continue;
                        int a, b;
                        String label;
                        switch (v.category) {
                            case FADITOR:
                                a = G_LIBRARY_A; b = G_LIBRARY_B; label = "EXPORT"; break;
                            case SCREEN:
                                a = G_CAPTURE_A; b = G_CAPTURE_B; label = "SCREEN"; break;
                            case SHOT:
                                a = G_LIBRARY_A; b = G_LIBRARY_B; label = "PHOTO"; break;
                            case STREAM:
                                a = G_SOUND_A; b = G_SOUND_B; label = "STREAM"; break;
                            default:
                                a = G_CAPTURE_A; b = G_CAPTURE_B; label = "RECORDING"; break;
                        }
                        merged.add(new Recent(v.displayName, label, v.uri.toString(),
                                v.lastModified, a, b, null));
                    }
                }
            } catch (Exception e) {
                FLog.w("Lobby", "recordings scan failed; projects-only recents", e);
            }
            java.util.Collections.sort(merged, (x, y) -> Long.compare(y.when, x.when));
            ui.post(() -> {
                if (!isAdded()) return;
                recents = merged;
                paintRecents();
                paintBotLine();
            });
        });
    }

    // ── the New row ─────────────────────────────────────────────────────────

    /**
     * A lobby has two jobs and the first draft only built one. Carrying on and starting
     * fresh are different intents, and the old screen had no answer for the second except
     * entering a room first.
     *
     * <p>These are GREY BUTTONS WITH COLOURED GLYPHS, not coloured buttons. That keeps the
     * no-faded-colour rule and leaves the hero's action as the screen's only saturated
     * control, which is what makes the hierarchy unmistakable.</p>
     */
    private void buildNewRow() {
        if (newRow == null) return;
        newRow.removeAllViews();
        addNewButton("videocam",      0xFFFF008C, getString(R.string.lobby_new_recording),
                () -> routeTab(TAB_CAPTURE));
        addNewButton("movie_edit",    0xFF35F6BF, getString(R.string.lobby_new_project),
                () -> routeTab(TAB_STUDIO));
        addNewButton("directions_run",0xFFCC27FF, getString(R.string.lobby_new_character),
                () -> { active = 2; paintMarquee(); paintHero(); });
        addNewButton("folder",        0xFF55E0F9, getString(R.string.lobby_new_import),
                () -> routeTab(TAB_LIBRARY));
    }

    private void addNewButton(String glyph, int glyphColor, String label, Runnable onTap) {
        LinearLayout cell = new LinearLayout(requireContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setBackground(pill(CTL, dp(13)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(dp(8));
        cell.setLayoutParams(lp);
        cell.setPadding(0, dp(11), 0, dp(10));

        TextView ic = new TextView(requireContext());
        ic.setTypeface(iconFont);
        ic.setText(glyph);
        ic.setTextColor(glyphColor);
        ic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        ic.setGravity(Gravity.CENTER);
        cell.addView(ic);

        TextView tv = new TextView(requireContext());
        tv.setText(label);
        tv.setTextColor(DIM);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setMaxLines(1);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(2), dp(5), dp(2), 0);
        cell.addView(tv);

        cell.setOnClickListener(v -> onTap.run());
        newRow.addView(cell);
    }

    // ── the floor ───────────────────────────────────────────────────────────

    /**
     * Five words that never move. Archivo-weight 600 at 11.5sp in DIMMER, against the
     * marquee's black at 27sp in INK: the same instrument, played softly. A nav bar here
     * would compete with the marquee and the user would have to work out which navigation
     * system they were in.
     */
    private void buildFloor() {
        if (floorRow == null) return;
        floorRow.removeAllViews();
        addFloorWord("travel_explore", getString(R.string.lobby_floor_finder), () -> routeTab(TAB_FINDER));
        addFloorWord("cast",           getString(R.string.lobby_floor_remote), () -> routeTab(TAB_REMOTE));
        addFloorWord("build",          getString(R.string.lobby_floor_bits),   () -> routeTab(TAB_CAPTURE));
        addFloorWord("inventory_2",    getString(R.string.lobby_floor_packs),  () -> routeTab(TAB_SETUP));
        addFloorWord("tune",           getString(R.string.lobby_floor_setup),  () -> routeTab(TAB_SETUP));
    }

    private void addFloorWord(String glyph, String label, Runnable onTap) {
        LinearLayout cell = new LinearLayout(requireContext());
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cell.setLayoutParams(lp);
        cell.setPadding(dp(2), dp(9), dp(2), dp(9));

        TextView ic = new TextView(requireContext());
        ic.setTypeface(iconFont);
        ic.setText(glyph);
        ic.setTextColor(DIMMEST);
        ic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        ic.setPadding(0, 0, dp(5), 0);
        cell.addView(ic);

        TextView tv = new TextView(requireContext());
        tv.setText(label);
        tv.setTextColor(DIMMER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setMaxLines(1);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(tv);

        cell.setOnClickListener(v -> onTap.run());
        Motion.press(cell);
        floorRow.addView(cell);
    }


    /** Set once the user waves the line away, so it does not nag within a session. */
    private boolean botDismissed = false;

    /**
     * JOYBOT'S ONE LINE — the only thing on this screen that changes because of what you
     * DID rather than what you tapped.
     *
     * <p><b>The rule that governs it: it may only say things it has verified.</b> Every
     * observation below is computed from data already in hand — a count, or a comparison of
     * two timestamps. There is deliberately no branch that estimates, predicts or rounds a
     * number up to sound impressive. A front door that invents a statistic is a front door
     * you stop believing, and then the AI everywhere else in the app inherits that doubt.</p>
     *
     * <p>When there is nothing true and useful to say, it says nothing and takes up no
     * height. Silence is a valid state.</p>
     */
    private void paintBotLine() {
        if (botLine == null) return;
        if (botDismissed) { botLine.setVisibility(View.GONE); return; }

        String message = null;
        String yesLabel = null;
        Runnable onYes = null;

        int captures = 0;
        long newestCapture = 0L;
        for (Recent r : recents) {
            if (r.projectId == null) {
                captures++;
                if (r.when > newestCapture) newestCapture = r.when;
            }
        }
        long newestProject = projects.isEmpty() ? 0L : projects.get(0).lastModified;

        if (!projects.isEmpty() && newestCapture > newestProject && captures > 0) {
            // TRUE BY CONSTRUCTION: the newest thing you captured is more recent than the
            // newest thing you edited. It does not claim the clip is unused - only that it
            // arrived after your last edit, which is exactly what the sentence says.
            message = "Your newest recording landed after your last edit.";
            yesLabel = "Open Library";
            onYes = () -> routeTab(TAB_LIBRARY);
        } else if (projects.isEmpty() && captures > 0) {
            message = captures + (captures == 1 ? " recording" : " recordings")
                    + " and no projects yet.";
            yesLabel = "Start one";
            onYes = () -> routeTab(TAB_STUDIO);
        }

        if (message == null) { botLine.setVisibility(View.GONE); return; }

        botLineText.setText(message);
        botLineYes.setText(yesLabel);
        final Runnable action = onYes;
        botLineYes.setOnClickListener(v -> { if (action != null) action.run(); });
        Motion.press(botLineYes);
        if (botLine.getVisibility() != View.VISIBLE) {
            botLine.setVisibility(View.VISIBLE);
            Motion.enter(botLine, 0);
        }
    }

    // ── the cycling stat ────────────────────────────────────────────────────

    /**
     * Everything the old Home screen spent a third of the display on — storage, time left,
     * project count — in one line that changes every few seconds. When storage is genuinely
     * low it STOPS CYCLING and stays on the warning in amber, which is the only time that
     * number has ever deserved attention.
     */
    private void startStatCycle() {
        statTick = new Runnable() {
            @Override public void run() {
                if (!isAdded() || statText == null) return;
                long freeBytes = freeSpaceBytes();
                boolean low = freeBytes > 0 && freeBytes < 2L * 1024 * 1024 * 1024;
                if (low) {
                    statIcon.setText("warning");
                    statIcon.setTextColor(WARN);
                    statText.setTextColor(WARN);
                    statText.setText(humanBytes(freeBytes) + " left");
                } else {
                    statIcon.setTextColor(DIMMER);
                    statText.setTextColor(DIM);
                    switch (statIndex % 3) {
                        case 0:
                            statIcon.setText("storage");
                            statText.setText(humanBytes(freeBytes) + " free");
                            break;
                        case 1:
                            statIcon.setText("movie_edit");
                            statText.setText(projects.size() + " project"
                                    + (projects.size() == 1 ? "" : "s"));
                            break;
                        default:
                            statIcon.setText("timer");
                            statText.setText(approxRecordingTime(freeBytes));
                            break;
                    }
                    statIndex++;
                }
                ui.postDelayed(this, 3400L);
            }
        };
        ui.post(statTick);
    }


    // ── THE EXPORT HAIRLINE ─────────────────────────────────────────────────
    //
    // JoyRaptor, on the editor's version of this: "I like that it's out of the way and
    // it just adds a nice highlight color." It replaced a 58dp frosted strip in the
    // design, and the height that bought is what the New row and Joybot's line occupy.
    //
    // It is wired rather than merely drawn because a control that can never change is a
    // DEAD KNOB, and a dead knob is worse than a missing one. Export runs OUT OF PROCESS
    // so this listens to ExportService's broadcasts — LocalBroadcastManager is
    // in-process-only and would have silently received nothing.

    private final android.content.BroadcastReceiver exportWatch =
            new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            if (i == null || i.getAction() == null || hairline == null) return;
            switch (i.getAction()) {
                case com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_STARTED:
                    showHairline(0);
                    break;
                case com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_PROGRESS:
                    showHairline(i.getIntExtra(
                            com.fadcam.ui.faditor.export.ExportService.EXTRA_PROGRESS, 0));
                    break;
                default:
                    // completed, error or cancelled — all of them mean "stop showing a bar".
                    // The RESULT belongs in the recents row, which reloads on resume anyway.
                    hairline.setVisibility(View.GONE);
                    break;
            }
        }
    };

    private void showHairline(int percent) {
        if (hairline == null) return;
        hairline.setVisibility(View.VISIBLE);
        if (hairlineFill == null) {
            // One fill view, created once: a 2dp bar that grows. Animating a WIDTH would
            // relayout the whole screen every frame, so it scales on the X axis instead,
            // which is a compositor-only property.
            hairlineFill = new View(requireContext());
            hairlineFill.setBackground(linearGradient(0xFF35F6BF, 0xFF97FE8B));
            ((ViewGroup) hairline.getParent()).addView(hairlineFill,
                    ((ViewGroup) hairline.getParent()).indexOfChild(hairline) + 1,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));
            hairlineFill.setPivotX(0f);
        }
        hairlineFill.setVisibility(View.VISIBLE);
        float target = Math.max(0f, Math.min(1f, percent / 100f));
        hairlineFill.animate().cancel();
        hairlineFill.animate().scaleX(target)
                .setDuration(Motion.MENU).setInterpolator(Motion.LINEAR).start();
    }

    private void registerExportWatch() {
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_STARTED);
        f.addAction(com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_PROGRESS);
        f.addAction(com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_COMPLETED);
        f.addAction(com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_ERROR);
        f.addAction(com.fadcam.ui.faditor.export.ExportService.ACTION_EXPORT_CANCELLED);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                requireContext().registerReceiver(exportWatch, f,
                        android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                requireContext().registerReceiver(exportWatch, f);
            }
        } catch (Exception e) {
            FLog.w("Lobby", "export watch not registered", e);
        }
    }

    private void unregisterExportWatch() {
        try { requireContext().unregisterReceiver(exportWatch); } catch (Exception ignored) { }
    }

    // ── data ────────────────────────────────────────────────────────────────

    private void reloadProjects() {
        try {
            List<ProjectStorage.ProjectSummary> all = projectStorage.listProjects();
            projects = all == null ? new ArrayList<>() : all;
        } catch (Exception e) {
            FLog.w("Lobby", "listProjects failed", e);
            projects = new ArrayList<>();
        }
    }

    private void openProject(String id) {
        Intent i = new Intent(requireContext(), FaditorEditorActivity.class);
        i.putExtra(FaditorEditorActivity.EXTRA_PROJECT_ID, id);
        startActivity(i);
    }

    /**
     * THUMBNAILS. The hero is the first thing anyone sees, and a flat gradient reads as an
     * unfinished screen rather than as a design. A real frame from the user's own project is
     * what makes the lobby feel like it belongs to them.
     *
     * <p>Three rules, because MediaMetadataRetriever is slow and unforgiving:</p>
     * <ol>
     *   <li><b>Never on the main thread.</b> A 4K frame decode is tens of milliseconds and
     *       this runs while the lobby is being laid out.</li>
     *   <li><b>Cache by URI.</b> The same project appears as the hero AND as a recents card,
     *       and the user will scroll back and forth over both.</li>
     *   <li><b>Fail silently to the room's wash.</b> A missing file must never be an error
     *       state on the front door — that is what the corner cut and the name are for.</li>
     * </ol>
     */
    private static final LruCache<String, Bitmap> THUMBS = new LruCache<String, Bitmap>(24) {
        @Override protected int sizeOf(@NonNull String key, @NonNull Bitmap value) { return 1; }
    };
    private static final ExecutorService THUMB_IO = Executors.newFixedThreadPool(2);

    private void loadThumbInto(@NonNull ImageView target, @Nullable String uriString) {
        if (uriString == null || uriString.isEmpty()) return;
        target.setTag(R.id.lobby_thumb_tag, uriString);

        Bitmap cached = THUMBS.get(uriString);
        if (cached != null) { target.setImageBitmap(cached); return; }

        THUMB_IO.execute(() -> {
            Bitmap bmp = decodeFrame(uriString);
            if (bmp == null) return;
            THUMBS.put(uriString, bmp);
            ui.post(() -> {
                if (!isAdded()) return;
                // The row recycles nothing, but the hero DOES change while a decode is in
                // flight. Without this check, switching rooms mid-decode paints the old
                // project's frame onto the new room's hero.
                Object want = target.getTag(R.id.lobby_thumb_tag);
                if (want instanceof String && want.equals(uriString)) {
                    target.setImageBitmap(bmp);
                }
            });
        });
    }

    @Nullable
    private Bitmap decodeFrame(@NonNull String uriString) {
        // A still image project has no frame to seek — decode it directly.
        try {
            String lower = uriString.toLowerCase(Locale.US);
            if (lower.endsWith(".png") || lower.endsWith(".jpg")
                    || lower.endsWith(".jpeg") || lower.endsWith(".webp")) {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = 4;
                Uri u = Uri.parse(uriString);
                String path = u.getPath();
                if (u.getScheme() == null && new File(uriString).exists()) {
                    return android.graphics.BitmapFactory.decodeFile(uriString, o);
                }
                if (path != null && new File(path).exists()) {
                    return android.graphics.BitmapFactory.decodeFile(path, o);
                }
                try (java.io.InputStream in =
                             requireContext().getContentResolver().openInputStream(u)) {
                    return android.graphics.BitmapFactory.decodeStream(in, null, o);
                }
            }
        } catch (Exception | OutOfMemoryError ignored) { }

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            Uri u = Uri.parse(uriString);
            if (u.getScheme() == null) {
                mmr.setDataSource(uriString);
            } else {
                mmr.setDataSource(requireContext(), u);
            }
            // One second in, not zero: the first frame of a recording is very often black.
            Bitmap f = mmr.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (f == null) f = mmr.getFrameAtTime();
            return f;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
        }
    }

    private String projectSub(ProjectStorage.ProjectSummary p) {
        return ago(p.lastModified).toUpperCase(Locale.US);
    }

    private String capturedSummary() {
        return "READY · " + approxRecordingTime(freeSpaceBytes()).toUpperCase(Locale.US);
    }

    private long freeSpaceBytes() {
        try {
            File dir = requireContext().getFilesDir();
            StatFs st = new StatFs(dir.getAbsolutePath());
            return st.getAvailableBytes();
        } catch (Exception e) {
            return 0L;
        }
    }

    private String humanBytes(long b) {
        if (b <= 0) return "—";
        double gb = b / (1024d * 1024d * 1024d);
        if (gb >= 1) return String.format(Locale.US, "%.0f GB", gb);
        return String.format(Locale.US, "%.0f MB", b / (1024d * 1024d));
    }

    /** Rough, and labelled as rough. ~9 MB per second is a fair FHD30 working figure. */
    private String approxRecordingTime(long freeBytes) {
        if (freeBytes <= 0) return "—";
        long seconds = freeBytes / (9L * 1024 * 1024);
        long h = TimeUnit.SECONDS.toHours(seconds);
        if (h >= 24) return (h / 24) + "d " + (h % 24) + "h left";
        if (h >= 1) return h + "h " + (TimeUnit.SECONDS.toMinutes(seconds) % 60) + "m left";
        return TimeUnit.SECONDS.toMinutes(seconds) + "m left";
    }

    private String ago(long ts) {
        long d = System.currentTimeMillis() - ts;
        if (d < TimeUnit.MINUTES.toMillis(2)) return "just now";
        if (d < TimeUnit.HOURS.toMillis(1)) return TimeUnit.MILLISECONDS.toMinutes(d) + "m ago";
        if (d < TimeUnit.DAYS.toMillis(1)) return TimeUnit.MILLISECONDS.toHours(d) + "h ago";
        long days = TimeUnit.MILLISECONDS.toDays(d);
        if (days < 30) return days + "d ago";
        return (days / 30) + "mo ago";
    }

    // ── drawing helpers ─────────────────────────────────────────────────────

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private GradientDrawable linearGradient(int a, int b) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{a, b});
        g.setShape(GradientDrawable.RECTANGLE);
        return g;
    }

    private GradientDrawable pillGradient(int a, int b, int radius) {
        GradientDrawable g = linearGradient(a, b);
        g.setCornerRadius(radius);
        return g;
    }

    private GradientDrawable pill(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private GradientDrawable circleGradient(int a, int b) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{a, b});
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    /** The diagonal in a card's bottom-right corner, in the room's gradient. */
    private static final class CornerCut extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int a, b;

        CornerCut(int a, int b) { this.a = a; this.b = b; }

        @Override protected void onBoundsChange(@NonNull Rect bounds) {
            path.reset();
            path.moveTo(bounds.right, bounds.top);
            path.lineTo(bounds.right, bounds.bottom);
            path.lineTo(bounds.left, bounds.bottom);
            path.close();
            paint.setShader(new android.graphics.LinearGradient(
                    bounds.left, bounds.top, bounds.right, bounds.bottom,
                    a, b, android.graphics.Shader.TileMode.CLAMP));
        }

        @Override public void draw(@NonNull Canvas canvas) { canvas.drawPath(path, paint); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
