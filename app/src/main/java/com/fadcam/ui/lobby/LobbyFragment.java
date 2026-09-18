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

import com.fadcam.ui.type.Type;
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
    private JoybotView joybot;
    private FrameLayout hero;
    private ImageView heroArt;
    private View heroWash, heroBar;
    private TextView heroTag, heroEmpty, heroName, heroSub, heroAction;
    private TextView statIcon, statText, wordmark;
    private TextView libraryCount;
    private View hairline, hairlineFill;
    private View botLine;
    private TextView botLineText, botLineYes, botLineNo;
    private JoybotView botLineOrb;
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
        libraryCount = v.findViewById(R.id.lobby_library_count);
        hairline    = v.findViewById(R.id.lobby_hairline);
        botLine     = v.findViewById(R.id.lobby_bot_line);
        botLineText = v.findViewById(R.id.lobby_bot_line_text);
        botLineYes  = v.findViewById(R.id.lobby_bot_line_yes);
        botLineNo   = v.findViewById(R.id.lobby_bot_line_no);
        botLineOrb  = v.findViewById(R.id.lobby_bot_line_orb);
        // Same character, same disc, smaller. He is the one speaking this line, so it
        // gets his face rather than a coloured dot standing in for one.
        botLineOrb.setOrb(0xFFCC27FF, 0xFF5C43FD);
        botLineYes.setBackground(pill(INK, dp(999)));
        botLineNo.setOnClickListener(b -> {
            botDismissed = true;
            botLine.setVisibility(View.GONE);
        });

        // ── THE VOICE ───────────────────────────────────────────────────────────
        // Archivo, the face this screen was actually designed in, is now bundled.
        // What stood here before was Typeface.create("sans-serif-black"), which is
        // not a font request — it asks the OEM for whatever IT thinks black is, so
        // the lobby wore Samsung One on this phone and Roboto on a Pixel.
        //
        // The wordmark is the heaviest thing on the screen at 900 because it is the
        // one word that is never a control: you cannot press it, so it has to earn
        // its space by being the anchor everything else is measured against.
        Type.display(wordmark, Type.BLACK);
        Type.display(heroName, Type.BLACK);

        TextView libLabel = v.findViewById(R.id.lobby_library_label);
        // Section labels sit at 800 rather than 900. One notch down is enough to
        // rank them under the wordmark while still reading as the same voice — and
        // at 11sp the difference between 800 and 900 is legibility, not decoration.
        Type.display(libLabel, Type.EXTRA);

        // ── JOYBOT ─────────────────────────────────────────────────────────
        // The orb behind him is gone. It existed to give a flat glyph something to sit on;
        // the real art has its own silhouette, and a gradient disc behind it just clipped
        // his ears and his base off. He is drawn in the Joybot violet instead, which keeps
        // the identity the orb was carrying without boxing him in.
        //
        // He smiles when pushed, and he smiles while the recents strip is being scrolled,
        // which is JoyRaptor's note that on a scroll "it looks like he's watching and
        // reacting". The push is not a navigation: tapping him opens the Studio, and the
        // smile is the acknowledgement that the tap landed.
        joybot = v.findViewById(R.id.lobby_bot);
        joybot.setOrb(0xFFCC27FF, 0xFF5C43FD);
        joybot.setOnClickListener(b -> { joybot.react(); routeTab(TAB_STUDIO); });

        View libraryDoor = v.findViewById(R.id.lobby_library_door);
        libraryDoor.setOnClickListener(b -> routeTab(TAB_LIBRARY));
        Motion.press(libraryDoor);
        Motion.press(v.findViewById(R.id.lobby_search));
        v.findViewById(R.id.lobby_search).setOnClickListener(b -> routeTab(TAB_LIBRARY));

        // "On scroll gesture in screens it looks like he's watching and reacting."
        // The recents strip is the only thing on this screen the user scrolls, so it is
        // the thing he watches.
        joybot.watch(v.findViewById(R.id.lobby_recents_scroll));

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
                // The live word. JoyRaptor asked for "studio" larger; 34sp is where
                // Archivo 900 stops looking like big text and starts looking like a
                // sign. The negative tracking is not a flourish — Archivo's sidebearings
                // are cut for text sizes, and left alone at display size the letters
                // drift apart and the word loses its shape as a single object.
                Type.display(t, Type.BLACK);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f);
                t.setTextColor(INK);
                t.setLetterSpacing(-0.05f);
            } else if (i == 1) {
                // On deck. Medium rather than regular: at 15sp beside a 34sp black,
                // a 400 weight reads as disabled rather than as next.
                Type.display(t, Type.MEDIUM);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                t.setTextColor(DIMMER);
            } else {
                Type.display(t, Type.REGULAR);
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
                // swapPicture, not swap: the hero is a PHOTOGRAPH, and a straight
                // dissolve leaves both frames legible at the midpoint. See the mockup —
                // "so two states never read as two states".
                Motion.swapPicture(hero, Motion.HERO, this::paintHero);
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
        // A project's own name is something you READ, so it is the body face, not the
        // display one. Archivo down here would make every card shout.
        Type.body(name, Type.SEMIBOLD);
        name.setMaxLines(1);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(dp(8), dp(6), dp(8), 0);
        card.addView(name);

        TextView meta = new TextView(requireContext());
        meta.setText(item.kindLabel + " \u00b7 " + ago(item.when));
        meta.setTextColor(DIMMER);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f);
        // Duration and date are machine facts, so they get the mono — and because it is
        // mono, 2:05 and 12:05 occupy the same column and the cards stop looking ragged.
        Type.mono(meta, Type.MEDIUM);
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

        // Each chip wears the gradient of the room it opens, so the row is a colour key for
        // the marquee above it as well as a set of actions. Import has no room of its own --
        // it is a Library action -- so it takes the Library's cyan rather than inventing a
        // fifth identity the rest of the app would never repeat.
        addNewChip("videocam",       0xFFFA3D5D, 0xFFFF008C, getString(R.string.lobby_new_recording),
                true,  () -> routeTab(TAB_CAPTURE));
        addNewChip("movie_edit",     0xFF35F6BF, 0xFF97FE8B, getString(R.string.lobby_new_project),
                false, () -> routeTab(TAB_STUDIO));
        addNewChip("directions_run", 0xFFCC27FF, 0xFF8C3DFA, getString(R.string.lobby_new_character),
                false, () -> { active = 2; paintMarquee(); paintHero(); });
        addNewChip("folder",         0xFF55E0F9, 0xFF22D3EE, getString(R.string.lobby_new_import),
                false, () -> routeTab(TAB_LIBRARY));
    }

    /** Visual height of a New chip. Half the old stacked cell, as asked. */
    private static final int CHIP_H = 34;
    /** Horizontal run of the shear. At 34dp tall this is roughly a 16 degree lean. */
    private static final int CHIP_SLANT = 10;

    /**
     * ONE NEW CHIP.
     *
     * <p>Built to JoyRaptor's five instructions: half the old height, filled with the room
     * gradient, icon punched out in pure black, label to the right of the icon rather than
     * under it, and much less corner rounding.
     *
     * <h3>Pure black, not dark grey</h3>
     * The punch-out is {@code 0xFF000000} exactly. Against these gradients that is between
     * 9:1 and 14:1 -- the highest contrast available -- and it is also the only place on the
     * screen where black sits on TOP of colour rather than underneath it, which is what makes
     * the glyph read as a hole cut in the chip instead of an icon drawn on it.
     *
     * <h3>The touch target is bigger than the chip</h3>
     * 34dp is below the 48dp minimum a finger needs, and JoyRaptor uses this app one-handed
     * while holding a baby, so a chip that is merely pretty to hit is a defect. The VIEW stays
     * 34dp so the layout is what was designed; a {@link android.view.TouchDelegate} extends
     * each chip's hit rectangle into the padding above and below. That padding was already
     * there as breathing room, so the target costs no height.
     */
    private void addNewChip(String glyph, int gradA, int gradB, String label,
                            boolean first, Runnable onTap) {
        LinearLayout cell = new LinearLayout(requireContext());
        cell.setOrientation(LinearLayout.HORIZONTAL);
        // CENTRED, both axes. These were left-aligned with a nudge for the shear, which
        // JoyRaptor called correctly: "too left". A parallelogram's centroid is the same
        // point as its bounding box's, so content centred in the box IS centred in the
        // shape -- the nudge was solving a problem that does not exist and created a real
        // one, because the four chips are different widths and a fixed left inset made the
        // gap between glyph and left edge look different in every one.
        cell.setGravity(Gravity.CENTER);

        // Only the first chip is squared off on the left, so the row starts flush with the
        // 18dp gutter every other element on this screen starts at. Every edge after it leans.
        cell.setBackground(new SlantDrawable(gradA, gradB,
                dp(CHIP_SLANT), dp(3), first, false));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(CHIP_H), 1f);
        // Gap closed by ~30% (5dp -> 3.5dp) so the four read as one banded object with
        // divisions rather than as four separate buttons that happen to lean the same way.
        lp.setMarginEnd(Math.round(3.5f * getResources().getDisplayMetrics().density));
        cell.setLayoutParams(lp);
        // Symmetric padding, just enough to keep the content off the slanted ends.
        cell.setPadding(dp(6), 0, dp(6), 0);

        TextView ic = new TextView(requireContext());
        ic.setTypeface(iconFont);
        ic.setText(glyph);
        ic.setTextColor(0xFF000000);
        ic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        ic.setIncludeFontPadding(false);
        ic.setGravity(Gravity.CENTER);
        cell.addView(ic);

        TextView tv = new TextView(requireContext());
        tv.setText(label);
        tv.setTextColor(0xFF000000);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        // Display face at 700: these are labels on a coloured field, not prose, and Archivo's
        // tight apertures hold their shape at this size where the body face starts to close in.
        Type.display(tv, Type.BOLD);
        tv.setIncludeFontPadding(false);
        tv.setMaxLines(1);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(dp(5));
        tv.setLayoutParams(tlp);
        cell.addView(tv);

        cell.setOnClickListener(v -> { Motion.press(v); onTap.run(); });
        newRow.addView(cell);
        growTouchTarget(cell, dp(9));
    }

    /**
     * Extends a child's hit rectangle vertically without changing its size.
     *
     * <p>Posted rather than run inline because a view has no bounds until it has been laid out,
     * and a TouchDelegate built from a zero-sized rectangle silently swallows every touch it
     * is given.
     */
    private void growTouchTarget(final View child, final int extraPx) {
        final ViewGroup parent = (ViewGroup) child.getParent();
        if (parent == null) return;
        parent.post(() -> {
            android.graphics.Rect r = new android.graphics.Rect();
            child.getHitRect(r);
            r.top -= extraPx;
            r.bottom += extraPx;
            android.view.TouchDelegate existing = parent.getTouchDelegate();
            MultiTouchDelegate d;
            if (existing instanceof MultiTouchDelegate) {
                d = (MultiTouchDelegate) existing;
            } else {
                d = new MultiTouchDelegate(parent);
                parent.setTouchDelegate(d);
            }
            d.add(r, child);
        });
    }

    /**
     * A ViewGroup holds exactly ONE TouchDelegate. That is the trap in this pattern: four chips
     * each calling setTouchDelegate in turn leaves only the last one with an enlarged target,
     * and the bug looks like "the last button is easier to press", which nobody reports.
     *
     * <p>This holds several and dispatches to whichever child's expanded rectangle was hit.
     */
    private static final class MultiTouchDelegate extends android.view.TouchDelegate {
        private final java.util.List<android.view.TouchDelegate> parts = new java.util.ArrayList<>();
        private final java.util.List<android.graphics.Rect> rects = new java.util.ArrayList<>();
        private android.view.TouchDelegate active;

        MultiTouchDelegate(View host) {
            super(new android.graphics.Rect(), host);
        }

        void add(android.graphics.Rect r, View child) {
            rects.add(new android.graphics.Rect(r));
            parts.add(new android.view.TouchDelegate(new android.graphics.Rect(r), child));
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            // Whichever delegate took the DOWN keeps the whole gesture. Re-testing on every
            // MOVE would hand a drag across the row to a second chip and fire a stray tap.
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                active = null;
                int x = (int) e.getX(), y = (int) e.getY();
                for (int i = 0; i < rects.size(); i++) {
                    if (rects.get(i).contains(x, y)) { active = parts.get(i); break; }
                }
            }
            return active != null && active.onTouchEvent(e);
        }
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
        // The javadoc above has always said "Archivo-weight 600"; until the face was
        // bundled that was an aspiration and the code quietly used DEFAULT_BOLD. Now it
        // is simply true -- the floor really is the marquee's instrument played softly.
        Type.display(tv, Type.SEMIBOLD);
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

    /**
     * The hero's meta line: {@code 6 LAYERS · 0:47 · 2 HOURS AGO}.
     *
     * <p>The mockup specifies all three parts and the screen was shipping only the last
     * one, which made the hero a picture with a timestamp rather than a picture with a
     * project under it. Layers and duration are the two facts that tell you whether the
     * thing you are about to reopen is the sketch or the real one.
     *
     * <h3>It reads a cache, never the disk</h3>
     * Getting the first two means parsing the project's JSON, and this is called from
     * {@code paintHero} — which runs on every marquee tick, so on the main thread several
     * times a second. So the line renders immediately from whatever is already known and
     * {@link #warmHeroMeta} fills the rest in from a background thread, repainting once
     * when it lands. Until then the line is the age alone: thinner, but true.
     *
     * <p>That ordering is the point. JoyRaptor's rule for Joybot — "it never invents a
     * number" — applies just as much here: a placeholder layer count that turns out to be
     * wrong is worse than no layer count, because the wrong one gets believed.
     */
    private String projectSub(ProjectStorage.ProjectSummary p) {
        String cached = heroMeta.get(p.id);
        String age = ago(p.lastModified).toUpperCase(Locale.US);
        warmHeroMeta(p);
        return cached == null ? age : cached + " \u00b7 " + age;
    }

    /** project id -> "6 LAYERS \u00b7 0:47", once it has been read. */
    private final java.util.Map<String, String> heroMeta = new java.util.HashMap<>();
    private final java.util.Set<String> heroMetaInFlight = new java.util.HashSet<>();

    /**
     * Read one project's layer count and duration off the main thread.
     *
     * <p>Guarded by an in-flight set as well as by the cache, because the marquee ticks
     * faster than a JSON parse completes — without it, four seconds of looking at the
     * lobby would queue a dozen reads of the same file.
     */
    private void warmHeroMeta(ProjectStorage.ProjectSummary p) {
        if (heroMeta.containsKey(p.id) || !heroMetaInFlight.add(p.id)) return;
        final String id = p.id;
        THUMB_IO.execute(() -> {
            String line = null;
            try {
                com.fadcam.ui.faditor.model.FaditorProject proj =
                        projectStorage.load(id);
                if (proj != null && proj.getTimeline() != null) {
                    com.fadcam.ui.faditor.model.Timeline t = proj.getTimeline();
                    // "Layers" as the user counts them: everything stacked ON the spine.
                    // The spine's own clips are the movie, not a layer of it.
                    int layers = t.getOverlayClips().size()
                            + t.getTextOverlays().size()
                            + t.getSpriteOverlays().size()
                            + t.getAudioClips().size()
                            + t.getAdjustmentLayers().size()
                            + t.getWaveformOverlays().size();
                    long ms = t.getTotalDurationMs();
                    StringBuilder sb = new StringBuilder();
                    if (layers > 0) {
                        sb.append(layers).append(layers == 1 ? " LAYER" : " LAYERS");
                    }
                    if (ms > 0) {
                        if (sb.length() > 0) sb.append(" \u00b7 ");
                        sb.append(mmss(ms));
                    }
                    if (sb.length() > 0) line = sb.toString();
                }
            } catch (Throwable ignored) {
                // A project that will not parse still has a name, a picture and an age.
                // The hero is not the place to report that; the editor is.
            }
            final String result = line;
            ui.post(() -> {
                heroMetaInFlight.remove(id);
                if (result == null || !isAdded()) return;
                heroMeta.put(id, result);
                paintHero();
            });
        });
    }

    /** m:ss, or h:mm:ss past the hour. */
    private static String mmss(long ms) {
        long total = ms / 1000L;
        long h = total / 3600L, m = (total / 60L) % 60L, sec = total % 60L;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                     : String.format(Locale.US, "%d:%02d", m, sec);
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
