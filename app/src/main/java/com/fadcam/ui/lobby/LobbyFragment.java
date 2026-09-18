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
import com.fadcam.ui.faditor.Studio;

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

    // ── THE RAMP — ALIASES, NOT VALUES ───────────────────────
    //
    // This block used to hold eight numbers under the comment "matching the studio
    // tokens", and four of them did not match:
    //
    //     INK      #E4E4E7   vs  Studio.INK        #F2F2F5
    //     DIM      #A1A1AA   vs  Studio.INK_DIM    #C9C9D3
    //     DIMMER   #71717A   vs  Studio.INK_FAINT  #8A8A94
    //     DIMMEST  #4B4B55   vs  Studio.INK_OFF    #52525B
    //
    // Four near-misses of four tokens: a parallel ink ramp, on the app's front door, drifting
    // quietly away from the one the rest of the product uses. That is what "dozens of colours,
    // not hundreds" is actually aimed at — not a hundred different colours, but eight where
    // there should be four, none of them wrong enough to notice and all of them wrong.
    //
    // The short names stay because they read well at the call sites. They are aliases now, so
    // there is nothing left here to drift.
    private static final int INK      = Studio.INK;
    private static final int DIM      = Studio.INK_DIM;
    private static final int DIMMER   = Studio.INK_FAINT;
    private static final int DIMMEST  = Studio.INK_OFF;
    private static final int PANEL    = Studio.PANEL;
    private static final int CTL      = Studio.RAISED;
    private static final int ON_LIGHT = Studio.ON_GO;

    private static final int WARN     = Studio.CAREFUL;

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
    @Nullable private android.widget.HorizontalScrollView marqueeScroll;
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
        marqueeScroll = v.findViewById(R.id.lobby_marquee_scroll);
        recentsRow = v.findViewById(R.id.lobby_recents);
        newRow     = v.findViewById(R.id.lobby_new_row);
        floorRow   = v.findViewById(R.id.lobby_floor);
        hero       = v.findViewById(R.id.lobby_hero);
        // ── THE HERO'S SHARE ────────────────────────────────────────────────
        // The Marquee §01 draws it at 244 of 812 points. It was a LinearLayout weight, so
        // it absorbed every point the rest of the column did not use and landed at 39% on
        // this phone — nine points more than the drawing, taken out of the bottom third,
        // which is the part §02 exists to argue for. A weight cannot be capped, so the
        // proportion is set once the root knows its own height, which also holds it on a
        // screen of any shape.
        final View root = v;
        root.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        root.getViewTreeObserver().removeOnPreDrawListener(this);
                        int h = root.getHeight();
                        if (h > 0 && hero != null) {
                            int grown = hero.getHeight();      // what the weight gave it
                            int lo = Math.round(h * HERO_SHARE);
                            int hi = Math.round(h * HERO_SHARE_MAX);
                            int want = Math.max(lo, Math.min(hi, grown));
                            if (want != grown) {
                                ViewGroup.LayoutParams hp = hero.getLayoutParams();
                                if (hp instanceof LinearLayout.LayoutParams) {
                                    ((LinearLayout.LayoutParams) hp).weight = 0f;
                                }
                                hp.height = want;
                                hero.setLayoutParams(hp);
                            }
                        }
                        return true;
                    }
                });
        heroArt    = v.findViewById(R.id.lobby_hero_art);
        heroWash   = v.findViewById(R.id.lobby_hero_wash);
        heroBar    = v.findViewById(R.id.lobby_hero_bar);
        heroTag    = v.findViewById(R.id.lobby_hero_tag);
        heroEmpty  = v.findViewById(R.id.lobby_hero_empty);
        heroName   = v.findViewById(R.id.lobby_hero_name);
        heroSub    = v.findViewById(R.id.lobby_hero_sub);
        heroAction = v.findViewById(R.id.lobby_hero_action);

        // ── HOW TO START SOMETHING NEW ──────────────────────────────────────
        // JoyRaptor: "it is unclear to the user how to start a NEW project if theres already
        // a history project being shown."
        //
        // He is right, and it was a hierarchy problem rather than a missing feature. The
        // New row has always been down past the recents, but the HERO — the largest thing on
        // the screen — offered only "Carry on". So the loudest thing the lobby said was
        // always "continue this", and a first-time user with one stray project could
        // reasonably conclude that was the only thing on offer.
        //
        // The fix is a second control in the same row, deliberately quiet: filled means
        // "the thing you probably came for", a bare label means "also available". One
        // saturated control per screen still holds, and the question now answers itself
        // without anyone scrolling to find out.
        View heroNew = v.findViewById(R.id.lobby_hero_new);
        Type.display((TextView) heroNew, Type.SEMIBOLD);
        heroNew.setBackground(strokePill(Studio.alpha(Studio.INK, 0x33), dp(999)));
        Motion.press(heroNew);
        heroNew.setOnClickListener(b -> startNewInRoom());
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
        botLineOrb.setOrb(Studio.ROOM_AVATAR, Studio.ORB_DEEP);
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

        // ══ THE BANNER ══════════════════════════════════════════════════════════
        // JoyRaptor's neon wordmark, full-bleed across the chrome, with the controls
        // sitting on top of it.
        //
        // scaleType is MATRIX rather than one of the presets, and that is not fussiness:
        //   · FIT_CENTER would letterbox a 6.5:1 banner inside a 6:1 row and leave bars
        //   · CENTER_CROP fills, but crops from the CENTRE — on a narrow phone that eats
        //     the left edge, which is exactly where the wordmark is
        // So: scale to the row's HEIGHT, pin to x=0, and let the grid run off the right.
        // The art was drawn with its quiet end on the right for precisely this.
        ImageView banner = v.findViewById(R.id.lobby_banner);
        banner.setImageResource(R.drawable.lobby_banner);
        banner.addOnLayoutChangeListener((vw, l, t, r, b, ol, ot, orr, ob) -> fitBanner(banner));
        fitBanner(banner);

        // The scrim. Clear across the lettering, black by the time it reaches the stat,
        // the search and Joybot. Without it, grey 12sp type sits on a magenta grid and
        // becomes a texture rather than a readout.
        //
        // The stops are where they are because the bright ink measured out at 69% of the
        // art's width: the scrim stays out of the way until 55% and is fully down by 78%,
        // so it never dims the wordmark and is already solid under the controls.
        View bannerScrim = v.findViewById(R.id.lobby_banner_scrim);
        GradientDrawable wash = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ Studio.alpha(Studio.GROUND, 0x00), Studio.alpha(Studio.GROUND, 0x00),
                           Studio.alpha(Studio.GROUND, 0xCC), Studio.alpha(Studio.GROUND, 0xE6) });
        wash.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        bannerScrim.setBackground(wash);

        TextView libLabel = v.findViewById(R.id.lobby_library_label);
        // Section labels sit at 800 rather than 900. One notch down is enough to
        // rank them under the wordmark while still reading as the same voice — and
        // at 11sp the difference between 800 and 900 is legibility, not decoration.
        //
        // Caps to match the carousel: the two are the only headings on the screen, and a
        // capitalised dial above a mixed-case heading made them look like different systems.
        Type.display(libLabel, Type.EXTRA);
        libLabel.setAllCaps(true);
        libLabel.setLetterSpacing(0.01f);

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
        joybot.setOrb(Studio.ROOM_AVATAR, Studio.ORB_DEEP);
        // Joybot opens the ASSISTANT. He used to open the Studio's project list, which is
        // arbitrary: he is the AI everywhere else in the app — the button in the editor's
        // top bar and the face at the top of the chat are both him — and an avatar that
        // means "assistant" in two places and "project list" in a third teaches nobody
        // anything. Driving the lobby is what surfaced it.
        //
        // He carries the most recent project with him so the assistant opens knowing what
        // you were last working on, which is the whole premise of this screen. With no
        // projects he opens with no context, which the chat already handles — every use of
        // the project id in there is null-guarded.
        joybot.setOnClickListener(b -> { joybot.react(); openAssistant(); });

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
            w.setStatusBarColor(Studio.GROUND);
            w.setNavigationBarColor(Studio.GROUND);
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
                Studio.ROOM_STUDIO, Studio.GO_END, ON_LIGHT, "LAST PROJECT",
                getString(R.string.lobby_act_carry_on),
                getString(R.string.lobby_empty_studio), TAB_STUDIO, "movie_edit"));

        rooms.add(new Room(getString(R.string.lobby_room_capture),
                Studio.ROOM_CAPTURE, Studio.ROOM_SPRITE, Color.WHITE, "READY",
                getString(R.string.lobby_act_record), null, TAB_CAPTURE, "videocam"));

        rooms.add(new Room(getString(R.string.lobby_room_sprites),
                Studio.ROOM_SPRITE, Studio.ROOM_AVATAR, Color.WHITE, "LAST SHEET",
                getString(R.string.lobby_act_open),
                getString(R.string.lobby_empty_sprites), -1, "directions_run"));

        rooms.add(new Room(getString(R.string.lobby_room_avatar),
                Studio.ROOM_AVATAR, Studio.ROOM_AVATAR_DEEP, Color.WHITE, "LAST CHARACTER",
                getString(R.string.lobby_act_open),
                getString(R.string.lobby_empty_avatar), -1, "accessibility_new"));

        rooms.add(new Room(getString(R.string.lobby_room_viz),
                Studio.ROOM_VIZ, Studio.ROOM_VIZ_DEEP, ON_LIGHT, "LAST VISUALISER",
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
    /** How many copies of the room list the strip holds, so it can wrap without an end. */
    private static final int MARQUEE_COPIES = 3;
    /** Settle delay after the last scroll event before the carousel snaps. */
    private static final long MARQUEE_SETTLE_MS = 90L;
    /**
     * The hero's share of the window, from The Marquee §01: 244 of 812 points.
     *
     * <p>It was a LinearLayout weight, which meant the hero absorbed every point the rest
     * of the column did not use. On this phone that landed it at 39% — nine points more
     * than the drawing — and those nine points come out of the bottom third, which is the
     * part §02 exists to argue for. A weight cannot be capped, so the proportion is set
     * once after layout instead, which also holds it on a screen of any shape.
     */
    private static final float HERO_SHARE = 244f / 812f;

    /**
     * ...and its ceiling.
     *
     * <p>Pinning the hero to exactly {@link #HERO_SHARE} looked wrong on the phone, and
     * measuring said why: the bottom third of this build is 157dp SHORTER than the drawing's,
     * because the New row became one compact chip row on JoyRaptor's instruction instead of
     * the drawing's four stacked buttons. The drawing's proportion assumed the drawing's
     * content. Held at 30% exactly, that 157dp became a hole above the floor — and a hole is
     * the thing §02 was written to get rid of: <i>"it was empty and it read as unfinished".</i>
     *
     * <p>So the drawing's 30% is the FLOOR and this is the ceiling. The hero may take slack,
     * but it can no longer take all of it: unconstrained it reached 39%, which is nine points
     * of the bottom third. What the hero does not take goes to the recents, where it is spent
     * showing the user's own work.
     */
    private static final float HERO_SHARE_MAX = 0.35f;

    /** Recents card width. The Marquee §02: 100 points, with 9 between. */
    private static final int RECENT_CARD_DP = 100;

    /**
     * Thumbnail height. The Marquee §02 draws 60; this is 84, and the 24 is deliberate.
     *
     * <p>I nearly reverted this. Having measured a 157dp hole above the floor at the test
     * device's 549dp viewport, I re-measured at 411dp, found a 30dp gap, concluded the hole
     * was an artifact of a density override and put the thumbnail back to 60.
     *
     * <p>That was wrong, and the device itself says why:
     *
     * <pre>
     *   Physical size 1440x2960 @ 420dpi  =  548dp wide
     *   Override size 1080x2220 @ 315dpi  =  549dp wide
     * </pre>
     *
     * <p>Both configurations are 548dp. The override reproduces the Note 9's real width
     * faithfully; the 411dp I forced is a width this phone never has. So the hole is real on
     * his actual device, and the extra 24dp is spent the way this product is supposed to
     * spend height — <i>"old black is great for letting your art be the centerpiece"</i> —
     * rather than left as a gap.
     *
     * <p>The wider lesson, which is why this comment is long: the records are drawn for a
     * 390dp phone and his is 548dp, 40% wider. Anything specified in dp lands proportionally
     * smaller on his screen than in the drawing. That is why the hero is specified as a SHARE
     * and not a height — a proportion survives the difference and a dp does not.
     */
    private static final int RECENT_THUMB_DP = 84;

    /** How small a word gets when it is far from the gutter. 0.55 x 34sp reads as ~19sp. */
    private static final float MARQUEE_MIN_SCALE = 0.55f;
    /** Distance over which a word goes from full size to minimum, in dp. */
    private static final int MARQUEE_FALLOFF_DP = 190;
    /** Gap between words. */
    private static final int MARQUEE_GAP_DP = 10;
    /**
     * How much of a word's full-size width its slot reserves.
     *
     * <p>JoyRaptor: <i>"move the words closer so that they look more like a list where a lot
     * of the words are on the screen closer together."</i>
     *
     * <p>The slot is fixed — that is what makes each word an island that can thicken without
     * disturbing its neighbours — but it does not have to be the FULL width. Reserving 100%
     * of the heaviest rendering meant a shrunken word sat in a box far wider than itself and
     * the strip read as five things scattered across a line rather than as a list.
     *
     * <p>It is 1.0 — the full width — and the tightening is done differently, because
     * narrowing the slot did not work. At 0.78 the front word grew past its box and the next
     * word, sitting in its own box right behind it, was drawn straight over its tail: "STUDIO"
     * rendered as "STUDI" with CAPTURE on top of the O.
     *
     * <p>So the boxes stay full width and the PACKING is done with translationX instead, in
     * {@link #layoutMarquee}. That is not a reflow — nothing is re-measured and no text is
     * re-shaped — so the islands stay islands; they are simply slid together to take up the
     * slack that shrinking left behind.
     */
    private static final float MARQUEE_SLOT = 1f;

    @Nullable private Runnable marqueeSettle;
    private boolean marqueeSnapping = false;

    /**
     * THE CAROUSEL.
     *
     * <p>JoyRaptor, on the first version: <i>"the words should be at their [biggest] right as
     * they are under the left aligned edge and as they move away from being at the left
     * aligned edge, whether to the left of it or to the right of it ... they should be having
     * a smooth transition. Right now there's a complete timing lag ... so you have a thick
     * thing going away and then a popping at the end as the new word that came to rest bulks
     * up."</i>
     *
     * <p>He is describing the exact consequence of how it was built, not a tuning problem.
     * The first version set text SIZES only when the scroll settled, because changing a text
     * size forces a relayout and doing that every frame is unaffordable. That decision made
     * the pop inevitable: the outgoing word stayed at 34sp for the whole fling and the
     * incoming one jumped to 34sp after it stopped. Nothing about the timing could have
     * fixed it — size was a step function of a continuous input.
     *
     * <h3>Scale, not size</h3>
     * Every word is laid out ONCE at full size and then scaled. {@code scaleX/scaleY} is a
     * compositor property: no measure, no layout, no text re-shaping, so it can be recomputed
     * for every word on every scroll frame and stay smooth under a fling. Size is now a
     * continuous function of distance from the gutter, which is what was asked for.
     *
     * <h3>Weight cross-fades instead of switching</h3>
     * Weight cannot be interpolated by scaling — a light face scaled up is still light. So
     * each word is TWO stacked TextViews, one Archivo 200 and one Archivo 900, with their
     * alphas cross-faded by the same distance value. A word thickens as it approaches the
     * gutter and thins as it leaves, continuously, because alpha is also compositor-only.
     *
     * <h3>Packing</h3>
     * Scaling does not change a view's layout width, so scaled-down words would leave holes.
     * Each word is therefore positioned by {@code translationX}, walking outward from
     * whichever word is nearest the gutter and accumulating SCALED widths. The scroll
     * container still moves through the unscaled layout, which keeps the snap arithmetic
     * simple and — unlike the first version — means the layout never shifts underneath the
     * snap, so the whole class of ordering bugs that produced disappears.
     */
    private void paintMarquee() {
        if (marquee == null) return;
        marquee.removeAllViews();

        for (int copy = 0; copy < MARQUEE_COPIES; copy++) {
            for (int i = 0; i < rooms.size(); i++) {
                final int roomIndex = i;
                marquee.addView(marqueeWord(rooms.get(i).title, roomIndex));
            }
        }

        // Measured on the sandbox at 548dp: the unfocused room words were clickable nodes
        // 20.3-21.8dp tall, under Studio Final §04's 28dp floor, sitting in a 66dp band —
        // so most of the strip you can see was not a strip you could press. The words are
        // SCALED, which is why they measure small however large the type is set, and why
        // this works off layout slots rather than drawn bounds. Each word now owns its
        // frozen slot, the full height of the band, and half the gap to each neighbour.
        // Nothing is drawn any differently.
        com.fadcam.ui.TouchDelegates.growChildren(marquee);

        marquee.post(() -> {
            centreOnActive();
            layoutMarquee();
        });

        if (marqueeScroll != null && marqueeScroll.getTag(R.id.lobby_marquee) == null) {
            marqueeScroll.setTag(R.id.lobby_marquee, Boolean.TRUE);   // wire listeners once
            marqueeScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
                layoutMarquee();
                if (marqueeSnapping) return;
                if (marqueeSettle != null) ui.removeCallbacks(marqueeSettle);
                marqueeSettle = this::settleMarquee;
                ui.postDelayed(marqueeSettle, MARQUEE_SETTLE_MS);
            });
        }
    }

    /**
     * One word: ONE view, in a slot that never changes size.
     *
     * <p>JoyRaptor: <i>"if it's having to reformat each time, perhaps rethink your
     * architecture. Perhaps having them all on one line where they reformat affecting each
     * other is wrong. Maybe they should be isolated islands so that when they reformat, they
     * just thicken up in their place."</i>
     *
     * <p>That is the right architecture and this is it. The previous version packed the words
     * by walking along the row accumulating their scaled widths, so every word's position
     * depended on every word before it — and it stacked TWO faces per word, a thin one and a
     * black one, cross-fading their alphas to fake a weight change. Both were wrong:
     *
     * <ul>
     *   <li>the packing made the strip one coupled system, so an error anywhere moved
     *       everything;</li>
     *   <li>and Archivo 900 is WIDER than Archivo 200, so cross-fading them showed two
     *       different-width renderings of the same word at once. That is not a word
     *       thickening, it is a word ghosting — visible in a still and worse in motion.</li>
     * </ul>
     *
     * <p>Now each word owns a fixed slot, measured once at its heaviest, and never moves
     * relative to its neighbours. Only the scroll moves it.
     */
    private View marqueeWord(String title, int roomIndex) {
        TextView t = new TextView(requireContext());
        t.setText(title);
        t.setIncludeFontPadding(false);
        t.setMaxLines(1);
        t.setSingleLine(true);
        // AFTER setSingleLine, and that order is load-bearing. Both are implemented as
        // TransformationMethods and a TextView holds exactly one, so setting single-line
        // second silently threw the capitals away — the row had quietly gone back to mixed
        // case with nothing in the code looking wrong.
        t.setAllCaps(true);
        t.setTextColor(INK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f);
        t.setLetterSpacing(0f);
        Type.display(t, Type.BLACK);
        t.setTag(R.id.lobby_marquee, roomIndex);

        // Measured at BLACK, which is the widest this word will ever be, and then frozen.
        // A fixed slot is what makes the island idea work: the box cannot change, so the
        // weight inside it can change freely without disturbing anything outside it.
        t.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                  View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.round(t.getMeasuredWidth() * MARQUEE_SLOT),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(MARQUEE_GAP_DP));
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);

        // Scale about the BOTTOM LEFT corner.
        //
        // JoyRaptor: "you need to make sure that the words are lined up on the bottom. That
        // way it seems like it's growing instead of sinking into something."
        //
        // He is describing what a centre pivot does to a row of text. Scaled about its
        // centre, a word shrinks toward its own middle, so its baseline RISES as it leaves
        // and drops as it arrives — the word looks like it is sinking into the strip rather
        // than growing out of it, and the row has no common line to read along.
        //
        // Pinned at the bottom-left, every word sits on one baseline no matter its size, and
        // the only thing that changes is how far up it reaches. pivotY is set per frame in
        // layoutMarquee because the height is not known until the view has been laid out.
        t.setPivotX(0f);

        t.setOnClickListener(v -> {
            if (roomIndex == active) { enterRoom(); return; }
            scrollToWord(v, true);
        });
        return t;
    }

    /** 1 at the gutter, falling to 0 at the far end of the falloff, eased. */
    private float nearness(float distancePx) {
        float t = Math.min(1f, Math.abs(distancePx) / dp(MARQUEE_FALLOFF_DP));
        // Cosine rather than linear: a linear falloff has a corner exactly at the gutter,
        // and the corner is visible as a hitch precisely where the eye is looking.
        return (float) ((Math.cos(t * Math.PI) + 1.0) / 2.0);
    }

    /**
     * Size and weight for the current scroll position — every word, every frame.
     *
     * <p>SIZE is {@code scaleX/scaleY}: a compositor property, so it costs no measure and no
     * layout and stays smooth under a fling.
     *
     * <p>WEIGHT is the variable font's own {@code wght} axis, driven continuously from 200 to
     * 900. This is what Archivo being a VARIABLE font buys: a real weight at any value, not a
     * pick from a handful of cut faces. There is nothing to cross-fade, so there is nothing
     * to ghost — the letterforms genuinely thicken.
     *
     * <p>Setting the axis re-shapes the glyphs, which would normally force a relayout. It
     * does not here, because each word's slot was measured at its heaviest and fixed: the box
     * is already big enough for anything the axis can produce, so the text simply redraws
     * inside it. That is the whole reason the fixed slot is worth its extra whitespace.
     *
     * <p>Below API 26 the axis does not exist, and the words fall back to two cut weights.
     * Two steps instead of seven hundred, on a version of Android where nobody is judging
     * the animation.
     */
    private void layoutMarquee() {
        if (marquee == null || marqueeScroll == null) return;
        int n = marquee.getChildCount();
        if (n == 0) return;
        float p = marqueeScroll.getScrollX() + dp(18);

        float[] sc = new float[n];
        int front = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            TextView t = (TextView) marquee.getChildAt(i);
            // The baseline. Set every frame rather than once, because a view has no height
            // until it is laid out and the strip is built before that happens.
            t.setPivotY(t.getHeight());
            float d = t.getLeft() - p;
            if (Math.abs(d) < bestD) { bestD = Math.abs(d); front = i; }
            float near = nearness(d);
            sc[i] = MARQUEE_MIN_SCALE + (1f - MARQUEE_MIN_SCALE) * near;
            t.setScaleX(sc[i]);
            t.setScaleY(sc[i]);
            t.setTextColor(blend(DIMMEST, INK, near));
            setWeight(t, Math.round(200 + 700 * near));
        }

        // ── TAKE UP THE SLACK ───────────────────────────────────────────────
        // Every word's box is sized for its HEAVIEST, FULL-SIZE rendering, so a shrunken
        // word leaves (1 - scale) of its box empty. Left alone the strip reads as five things
        // scattered along a line; JoyRaptor asked for "more like a list ... closer together".
        //
        // Each word is slid toward the front word by the slack of everything between them.
        // Nothing is re-measured and no glyph is re-shaped — the boxes are untouched and only
        // translationX moves — so the islands stay islands.
        //
        // The FRONT word is the anchor and never moves. That matters for more than tidiness:
        // the snap targets are computed from getLeft(), so if the word in the gutter were
        // displaced from its own layout position, every settle would land beside itself.
        float slack = 0f;
        for (int i = front; i < n; i++) {
            View t = marquee.getChildAt(i);
            t.setTranslationX(-slack);
            slack += t.getWidth() * (1f - sc[i]);
        }
        slack = 0f;
        for (int i = front - 1; i >= 0; i--) {
            View t = marquee.getChildAt(i);
            slack += t.getWidth() * (1f - sc[i]);
            t.setTranslationX(slack);
        }
    }

    /** Last weight applied, so the axis is only rewritten when it actually changes. */
    private static final int WEIGHT_TAG = R.id.lobby_marquee_weight;

    private void setWeight(TextView t, int weight) {
        // Quantised to 20 units. The axis is imperceptible at finer steps and re-shaping the
        // glyph run is the one part of this loop that is not free.
        int q = Math.max(200, Math.min(900, (weight / 20) * 20));
        Object prev = t.getTag(WEIGHT_TAG);
        if (prev instanceof Integer && (Integer) prev == q) return;
        t.setTag(WEIGHT_TAG, q);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            t.getPaint().setFontVariationSettings("'wght' " + q);
            t.invalidate();
        } else {
            Type.display(t, q >= 550 ? Type.BLACK : Type.EXTRA_LIGHT);
        }
    }

    /** The x the strip must be scrolled to for {@code word} to sit in the gutter. */
    private int scrollTargetFor(View word) {
        return Math.max(0, word.getLeft() - dp(18));
    }

    private void scrollToWord(View word, boolean commit) {
        if (marqueeScroll == null) return;
        marqueeSnapping = true;
        marqueeScroll.smoothScrollTo(scrollTargetFor(word), 0);
        ui.postDelayed(() -> {
            marqueeSnapping = false;
            if (commit) commitActive(word);
            layoutMarquee();
        }, Motion.MENU);
    }

    /** Snap to whichever word is nearest the gutter. */
    private void settleMarquee() {
        if (marquee == null || marqueeScroll == null || marquee.getChildCount() == 0) return;
        int x = marqueeScroll.getScrollX();
        View best = null;
        int bestD = Integer.MAX_VALUE;
        for (int i = 0; i < marquee.getChildCount(); i++) {
            View c = marquee.getChildAt(i);
            int d = Math.abs(scrollTargetFor(c) - x);
            if (d < bestD) { bestD = d; best = c; }
        }
        if (best != null) scrollToWord(best, true);
    }

    /** Adopt the room a settled word belongs to, and swap the hero to match. */
    private void commitActive(View word) {
        Object tag = word.getTag(R.id.lobby_marquee);
        if (!(tag instanceof Integer)) return;
        int idx = (Integer) tag;
        recentreCopies(word);
        if (idx == active) return;
        active = idx;
        Motion.swapPicture(hero, Motion.HERO, this::paintHero);
    }

    /**
     * Jump a whole copy when a settle lands outside the middle one.
     *
     * <p>Invisible, because the copies are identical. It is what turns a finite strip into a
     * dial with no end to hit in either direction.
     */
    private void recentreCopies(View word) {
        if (marqueeScroll == null || marquee.getChildCount() == 0) return;
        int per = rooms.size();
        int index = marquee.indexOfChild(word);
        if (index / per == 1) return;
        View mid = marquee.getChildAt(index % per + per);
        if (mid == null) return;
        marqueeScroll.scrollTo(scrollTargetFor(mid), 0);
        layoutMarquee();
    }

    /** Put the active room in the gutter, in the middle copy. */
    private void centreOnActive() {
        if (marquee == null || marqueeScroll == null) return;
        View mid = marquee.getChildAt(rooms.size() + active);
        if (mid != null) marqueeScroll.scrollTo(scrollTargetFor(mid), 0);
    }

    private static int blend(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000
                | ((int) (ar + (br - ar) * f) << 16)
                | ((int) (ag + (bg - ag) * f) << 8)
                | (int) (ab + (bb - ab) * f);
    }

    /**
     * Horizontal drags on the hero scroll the carousel; vertical ones are left alone.
     *
     * <p>The axis test matters. Without it the hero would swallow every downward drag and
     * the page underneath could not be scrolled from the largest area on the screen — which
     * is the classic way a carousel makes a whole page feel stuck.
     */
    private View.OnTouchListener heroSwipe() {
        return new View.OnTouchListener() {
            float downX, downY, lastX;
            boolean dragging;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                if (marqueeScroll == null) return false;
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = lastX = e.getRawX();
                        downY = e.getRawY();
                        dragging = false;
                        // The press feedback Motion.press used to give, folded in here so
                        // there is only ever one listener on this view.
                        if (!Motion.reduced(v.getContext())) {
                            v.animate().scaleX(Motion.PRESS_SCALE).scaleY(Motion.PRESS_SCALE)
                                    .setDuration(Motion.PRESS).setInterpolator(Motion.EASE_OUT).start();
                        }
                        // CONSUME the down and perform the click ourselves on up.
                        //
                        // Letting it through was the bug: the View starts its own press-and-
                        // click sequence on ACTION_DOWN, and taking the gesture over halfway
                        // through a MOVE does not unwind that — so a horizontal drag scrolled
                        // the carousel AND opened the room when the finger lifted. Owning the
                        // whole gesture is the only version where the two cannot both fire.
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging) {
                            // Not yet a drag — but STILL return true. Having consumed the
                            // DOWN, returning false here hands this one event to the View's
                            // own onTouchEvent, which restarts its press-and-click tracking
                            // and fires the click on UP. That is why a drag on the hero kept
                            // opening the room: the gesture was being owned by two things at
                            // once. Once you take the DOWN you own every event to the UP.
                            if (Math.abs(dx) < dp(12) || Math.abs(dx) <= Math.abs(dy)) return true;
                            dragging = true;
                            // Let go of the press look the moment this becomes a drag: a
                            // picture that stays shrunk while it is being scrubbed reads as
                            // stuck rather than as held.
                            v.animate().scaleX(1f).scaleY(1f)
                                    .setDuration(Motion.PRESS).setInterpolator(Motion.EASE_OUT).start();
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        // 1:1 with the finger. The hero is wide and the strip is narrow, but
                        // scaling the movement would make the picture and the words disagree
                        // about how far the dial had turned.
                        marqueeScroll.scrollBy(Math.round(lastX - e.getRawX()), 0);
                        lastX = e.getRawX();
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                        v.animate().scaleX(1f).scaleY(1f)
                                .setDuration(Motion.PRESS).setInterpolator(Motion.EASE_OUT).start();
                        if (dragging) {
                            dragging = false;
                            // No fling: a drag on the hero is a deliberate nudge to the next
                            // room, not a spin. It settles to the nearest word immediately.
                            settleMarquee();
                        } else {
                            // Never moved far enough to be a drag, so it was a tap.
                            v.performClick();
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f)
                                .setDuration(Motion.PRESS).setInterpolator(Motion.EASE_OUT).start();
                        dragging = false;
                        return true;
                }
                return false;
            }
        };
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
        heroTag.setBackground(pill(Studio.alpha(Studio.ON_GO, 0x8C), dp(999)));
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
                new int[]{ Studio.alpha(Studio.GROUND, 0xB3), Studio.alpha(Studio.GROUND, 0x26),
                           Studio.alpha(Studio.GROUND, 0x00), Studio.alpha(Studio.GROUND, 0x40),
                           Studio.alpha(Studio.GROUND, 0xD9) });
        wash.setGradientCenter(0.5f, 0.5f);
        heroWash.setBackground(wash);


        heroArt.setImageDrawable(null);
        heroArt.setTag(R.id.lobby_thumb_tag, null);
        if (hasContent) {
            heroArt.setBackgroundColor(Studio.SURFACE);
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
                    new int[]{ withAlpha(r.gradA, 0x47), withAlpha(r.gradB, 0x2B), Studio.SURFACE }));
        }

        // The biggest control on the screen announced NOTHING: it is a FrameLayout whose
        // children are pictures and whose text lives in siblings. Everything the eye gets
        // from it — which room, which item, what pressing it does — now has words.
        hero.setContentDescription(hasContent
                ? getString(R.string.lobby_a11y_hero, r.tag, name, r.action)
                : getString(R.string.lobby_a11y_hero_empty, r.title));
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
        // ── THE HERO IS PART OF THE DIAL ────────────────────────────────────
        // JoyRaptor: "I was trying to drag on the actual hero, and that didn't work."
        //
        // Of course he was — the hero IS the room the carousel is pointing at, so it is the
        // biggest and most obvious thing to push. Only the word strip listened, which made
        // the picture look like a separate, inert thing that merely reported the dial's
        // state. Horizontal drags on it are handed to the strip, so the two behave as one
        // control; a tap still enters, because a tap was never ambiguous.
        hero.setOnTouchListener(heroSwipe());
        // Motion.press is NOT called on the hero, and must not be: it installs its own
        // OnTouchListener, and a View has room for exactly one. It was being set five lines
        // after heroSwipe() and silently replacing it, which is why dragging the hero kept
        // opening the room no matter what the drag logic did — the drag logic was never
        // attached. The press feedback lives inside heroSwipe instead.
    }

    /** Where a marquee word actually takes you. */
    /**
     * Start something NEW in the room the carousel is pointing at.
     *
     * <p>Room-aware rather than a single "new project": the hero is showing a room, and the
     * new thing a person wants in Sprite Lab is a sheet, not a timeline. Routing to the room
     * and letting it do its own creation is also the only version that stays correct as
     * rooms are added.
     */
    private void startNewInRoom() {
        Room r = room();
        if (r.tab == TAB_CAPTURE) { routeTab(TAB_CAPTURE); return; }
        routeTab(TAB_STUDIO);
    }

    /** A pill that is only an outline — available, and clearly not the primary. */
    private android.graphics.drawable.GradientDrawable strokePill(int stroke, int radiusPx) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        d.setColor(0x00000000);
        d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

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

    /** Joybot's destination: the chat, with whatever you last touched for context. */
    private void openAssistant() {
        Intent i = new Intent(requireContext(),
                com.fadcam.ui.faditor.ai.ChatAssistantActivity.class);
        if (!projects.isEmpty()) {
            i.putExtra(com.fadcam.ui.faditor.ai.ChatAssistantActivity.EXTRA_PROJECT_ID,
                    projects.get(0).id);
        }
        startActivity(i);
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
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(RECENT_CARD_DP),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMarginEnd(dp(9));
        card.setLayoutParams(clp);

        FrameLayout thumbWrap = new FrameLayout(requireContext());
        thumbWrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(RECENT_THUMB_DP)));

        ImageView thumb = new ImageView(requireContext());
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(Studio.LANE_B);
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

        // The card is one control and its children are its description, not four
        // separate announcements. Name, what kind of thing it is, and how long ago.
        card.setContentDescription(getString(R.string.lobby_a11y_recent,
                item.name, item.kindLabel, ago(item.when)));
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
    // ROOM_STUDIO, not GO. They hold the same value and always will — the Studio room's
    // identity IS the go colour, which is the joke — but they are different ROLES, and
    // naming the room by the action token is how a room quietly repaints itself the next
    // time the primary action changes.
    private static final int G_STUDIO_A  = Studio.ROOM_STUDIO,  G_STUDIO_B  = Studio.GO_END;
    private static final int G_CAPTURE_A = Studio.ROOM_CAPTURE, G_CAPTURE_B = Studio.ROOM_SPRITE;
    private static final int G_LIBRARY_A = Studio.VIDEO,        G_LIBRARY_B = Studio.ARMED_LIGHT;
    private static final int G_SOUND_A   = Studio.ROOM_VIZ,     G_SOUND_B   = Studio.ROOM_VIZ_DEEP;

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
        addNewChip("videocam",       G_CAPTURE_A, G_CAPTURE_B, getString(R.string.lobby_new_recording), R.string.lobby_a11y_new_recording,
                true,  () -> routeTab(TAB_CAPTURE));
        addNewChip("movie_edit",     G_STUDIO_A, G_STUDIO_B, getString(R.string.lobby_new_project), R.string.lobby_a11y_new_project,
                false, () -> routeTab(TAB_STUDIO));
        // Character SELECTS the Sprite Lab and then ENTERS it. It used to only select —
        // the dial turned, the hero changed, and nothing else happened. Three chips in this
        // row act and one pointed, which reads as the chip not working; and a chip labelled
        // "Character" is a promise to take you where characters are made.
        //
        // enterRoom() is what handles the awkward part: Sprite Lab lives INSIDE a project,
        // so with no project to open it falls back to the Studio, which is where you would
        // make one. Selecting first is what makes that legible — the dial has visibly moved
        // to Sprite Lab, so the Studio is obviously a step on the way rather than the wrong
        // door.
        addNewChip("directions_run", Studio.ROOM_AVATAR, Studio.ROOM_AVATAR_DEEP, getString(R.string.lobby_new_character), R.string.lobby_a11y_new_character,
                false, () -> { active = 2; paintMarquee(); paintHero(); enterRoom(); });
        addNewChip("folder",         Studio.ARMED_LIGHT, Studio.ARMED, getString(R.string.lobby_new_import), R.string.lobby_a11y_new_import,
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
    private void addNewChip(String glyph, int gradA, int gradB, String label, int spokenRes,
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
        // An icon-font glyph IS a piece of text, and its text is the glyph's NAME. Left
        // alone, a screen reader reads this chip as "videocam Recording" — the icon out
        // loud, then the label. The label beside it already carries the meaning, so the
        // glyph steps out of the way rather than being given a second name for the same
        // thing.
        ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ic.setTextColor(Studio.GROUND);
        ic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        ic.setIncludeFontPadding(false);
        ic.setGravity(Gravity.CENTER);
        cell.addView(ic);

        TextView tv = new TextView(requireContext());
        tv.setText(label);
        tv.setTextColor(Studio.GROUND);
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

        // The chip is ONE control, so it speaks once, as itself. Without this the two
        // children are announced separately and the row reads as eight items, not four.
        cell.setContentDescription(getString(spokenRes));
        cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
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


    /**
     * Scale the banner to the chrome's height and pin it to the left edge.
     *
     * <p>Recomputed on layout because the row's height is a dp value and the view's width
     * is not known until measure — and because a fold or a rotation changes both. Setting
     * the matrix once in onViewCreated leaves the art at whatever scale a zero-width view
     * implied, which is none.
     */
    private void fitBanner(ImageView banner) {
        android.graphics.drawable.Drawable d = banner.getDrawable();
        if (d == null || banner.getHeight() == 0) return;
        float iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) return;
        float s = banner.getHeight() / ih;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(s, s);
        banner.setImageMatrix(m);
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
        // Same reason as the New chips: an icon font's text is the glyph's NAME, so this
        // would be read aloud as "travel_explore Finder".
        ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
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

        cell.setContentDescription(label);
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
            hairlineFill.setBackground(linearGradient(Studio.GO, Studio.GO_END));
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
