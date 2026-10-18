package com.fadcam.ui.faditor.ai.actions;

import android.net.Uri;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.ai.actions.ActionSpec.Type;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.model.WaveformStyle;
import com.fadcam.ui.faditor.waveform.WaveformStyleIO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The visualizer look family (gap F2): the on-canvas audio visualizer's object drawer. One action,
 * {@code set_visualizer_look}, carries everything the drawer's Style tab (the look carousel, the
 * gradient/solid swatches, the sensitivity, frequency, bar count, bar width/gap and the
 * up/down, data, flip, centre and shape buttons) and its position/size menu (X, Y, width,
 * height, rotation) can set.
 *
 * <p>Not here: the WaveformVisualizerSettingsSheet. That sheet edits the timeline's tape
 * waveform look ({@code TapeWaveformStyle}, app preferences), not a visualizer on the canvas.
 * Also not here: the layer stack (Layers section), the radial ring size, attach/detach, hide/lock
 * and fades, since the drawer has no plain value to set for them.</p>
 *
 * <p>Ranges match the drawer's own sliders: sensitivity 0.5–3, bars 0 (auto) or 12–128, low
 * frequency 1–200 Hz, high up to 22000 Hz, bar width 0–48 dp, gap 0–24 dp, width 0.1–1, height
 * 0.05–1, X/Y 0–1, rotation ±180.</p>
 */
final class VisualizerActions {
    private VisualizerActions() { }

    private static final String FAMILY = "visualizer";

    static final List<String> JUSTIFY = Arrays.asList("BOTTOM", "CENTER", "TOP");
    static final List<String> DATA = Arrays.asList("WAVE", "SPECTRUM");
    static final List<String> CENTRE = Arrays.asList("OFF", "CENTER_LOW", "CENTER_HIGH");
    static final List<String> SHAPE = Arrays.asList("LINEAR", "RADIAL");

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("set_visualizer_look", "Change how a visualizer looks")
                .help("Change a visualizer's look: its style, colours, where it sits and how big, and how it"
                        + " reacts to the sound. Send only what should change. Styles (or a saved look's id):"
                        + " filled_wave, fire_mirror, ice_line, joy_aurora_veil, joy_ember_rise, joy_galaxy_ring,"
                        + " joy_laser_grid, joy_neon_stack, joy_pulse_ring, joy_retro_grid, joy_soft_glow,"
                        + " joy_velvet_pulse, line_glow, mirror_pulse, neon_bars, spectrum_mirror, spectrum_neon,"
                        + " sunset_spectrum. Mirrored bars: mirror_pulse or fire_mirror. Sizes are fractions of the"
                        + " frame. Give at (a time) when the visualizer has keyframes.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › double-tap a visualizer › Style")
                .id("id", "visualizer", true, "Which visualizer: its id from the project")
                .text("style", false, "The look, by id from the list (or its name)")
                .param("colors", Type.STRING_LIST, false,
                        "One colour (#RRGGBB) for solid, two (top, bottom) for a gradient, an empty list for"
                                + " the look's own colours")
                .number("x", false, "Centre across the frame: 0 left … 1 right", 0.0, 1.0)
                .number("y", false, "Centre down the frame: 0 top … 1 bottom", 0.0, 1.0)
                .number("width", false, "Width as a fraction of the frame, 0.1 to 1", 0.1, 1.0)
                .number("height", false, "Height as a fraction of the frame, 0.05 to 1", 0.05, 1.0)
                .number("rotation", false, "Turn in degrees, -180 to 180", -180.0, 180.0)
                .choice("justify", false, "Where the bars sit in the box", JUSTIFY)
                .choice("data", false, "WAVE follows loudness over time; SPECTRUM shows pitches, bass to treble", DATA)
                .flag("flip", false, "Swap left and right (bass and treble change places on a spectrum)")
                .choice("frequencyCentre", false,
                        "OFF spreads pitches evenly; CENTER_LOW puts the bass in the middle; CENTER_HIGH puts"
                                + " the treble in the middle", CENTRE)
                .choice("shape", false, "LINEAR is straight bars or a wave; RADIAL shoots bars out from a ring", SHAPE)
                .number("sensitivity", false, "How much it reacts: 0.5 calm … 3 very reactive", 0.5, 3.0)
                .number("bandCount", false, "How many bars: 0 lets the look decide, otherwise 12 to 128", 0.0, 128.0)
                .number("lowHz", false, "Lowest pitch it listens to, in Hz (1 to 200)", 1.0, 200.0)
                .number("highHz", false, "Highest pitch it listens to, in Hz (above lowHz, up to 22000)", 0.0, 22000.0)
                .number("barWidth", false, "Bar width in dp: 0 keeps the look's own, otherwise 1 to 48", 0.0, 48.0)
                .number("barGap", false, "Space between bars in dp: 0 keeps the look's own, otherwise 1 to 24", 0.0, 24.0)
                .time("at", false, "The moment on the timeline for a position, size or rotation change when the"
                        + " visualizer has keyframes", 0.0, null)
                .build(), VisualizerActions::setLook);
    }

    // ── handler ──────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult setLook(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        WaveformOverlayInstance wf = find(ctx.project, a.str("id"));

        // Check everything first, so a bad value changes nothing.
        WaveformStyle look = a.has("style") ? look(ctx, a.str("style")) : null;
        List<String> colours = null;
        if (a.has("colors")) {
            colours = new ArrayList<>();
            for (String c : a.list("colors")) colours.add(colour(c));
            if (colours.size() > 2) {
                throw new ActionError("Give one colour for a solid look, or two for a gradient, not "
                        + colours.size() + ".");
            }
        }
        Long bands = a.has("bandCount") ? a.whole("bandCount") : null;
        if (bands != null && bands > 0 && bands < 12) {
            throw new ActionError("Bars: use 0 to let the look decide, or a number from 12 to 128.");
        }
        boolean moves = a.has("x") || a.has("y") || a.has("width") || a.has("height") || a.has("rotation");
        boolean keyed = wf.isArmed();
        if (moves && keyed && !a.has("at")) {
            throw new ActionError("This visualizer's position or size changes over time. Say the moment (at, in"
                    + " milliseconds on the timeline) for the change.");
        }
        // The UI writes a moving visualizer at the playhead and reads a linked one at that moment;
        // this action has no playhead, so the visualizer's own start is the moment when none is given.
        long t = a.has("at") ? a.whole("at") : wf.getStartMs();
        boolean linked = wf.isLinked();

        // Apply, in the order the drawer's controls apply them.
        List<String> said = new ArrayList<>();
        if (look != null) {
            // Editor: picking a look in the Style carousel sets the look id and drops any custom layers.
            wf.setStyleId(look.id);
            wf.setCustomStyleJson(null);
            said.add("uses the look \"" + look.displayName + "\"");
        }
        if (colours != null) {
            // Editor: swatch click. A solid colour clears the gradient, a gradient clears the solid.
            if (colours.isEmpty()) {
                wf.setColorOverride(null);
                wf.setGradientOverride(null, null);
                said.add("has the look's own colours");
            } else if (colours.size() == 1) {
                wf.setColorOverride(colours.get(0));
                said.add("has a solid colour");
            } else {
                wf.setGradientOverride(colours.get(0), colours.get(1));
                said.add("has a two-colour gradient");
            }
        }
        if (a.has("sensitivity")) {
            // Editor: top-row sensitivity slider (0.5 + p/100 * 2.5).
            float s = a.num("sensitivity").floatValue();
            wf.setSensitivityOverride(s);
            said.add("reacts at " + String.format(Locale.US, "%.1f", s) + "x strength");
        }
        if (a.has("lowHz")) wf.setFrequencyRangeLowHz(a.whole("lowHz").intValue());   // editor: low slider
        if (a.has("highHz")) wf.setFrequencyRangeHighHz(a.whole("highHz").intValue()); // editor: high slider (keeps above low)
        if (a.has("lowHz") || a.has("highHz")) {
            said.add("listens to " + wf.getFrequencyRangeLowHz() + "–" + wf.getFrequencyRangeHighHz() + " Hz");
        }
        if (bands != null) {
            // Editor: bar count slider. 0 = auto, otherwise 8 + slider value (12..128).
            wf.setBandCountOverride(bands.intValue());
            said.add(bands == 0 ? "sets its bars automatically" : "has " + bands + " bars");
        }
        if (a.has("barWidth")) {
            wf.setBarWidthOverrideDp(a.num("barWidth").floatValue()); // editor: bar width slider, 0 = auto
            said.add(wf.getBarWidthOverrideDp() > 0f
                    ? "bars " + Math.round(wf.getBarWidthOverrideDp()) + " dp wide" : "automatic bar width");
        }
        if (a.has("barGap")) {
            wf.setBarGapOverrideDp(a.num("barGap").floatValue()); // editor: gap slider, 0 = auto
            said.add(wf.getBarGapOverrideDp() > 0f
                    ? "gaps " + Math.round(wf.getBarGapOverrideDp()) + " dp" : "automatic gaps");
        }
        if (a.has("justify")) {
            int j = JUSTIFY.indexOf(a.choice("justify", JUSTIFY)); // editor: vertical-align button, 0 bottom, 1 centre, 2 top
            wf.setJustify(j);
            said.add(new String[]{"sits at the bottom", "sits in the middle", "sits at the top"}[j]);
        }
        if (a.has("data")) {
            int d = DATA.indexOf(a.choice("data", DATA)); // editor: data-mode button, 0 wave, 1 spectrum
            wf.setDataMode(d);
            said.add(d == 1 ? "shows the pitches" : "shows the wave");
        }
        if (a.has("flip")) {
            boolean f = Boolean.TRUE.equals(a.bool("flip")); // editor: flip button (horizontal mirror)
            wf.setHorizontalMirror(f);
            said.add(f ? "is flipped left to right" : "is not flipped");
        }
        if (a.has("frequencyCentre")) {
            int c = CENTRE.indexOf(a.choice("frequencyCentre", CENTRE)) - 1; // editor: centre button, -1 off, 0 low, 1 high
            wf.setCenterMode(c);
            said.add(c == 0 ? "keeps the bass in the middle"
                    : (c == 1 ? "keeps the treble in the middle" : "spreads the pitches evenly"));
        }
        if (a.has("shape")) {
            int s = SHAPE.indexOf(a.choice("shape", SHAPE)); // editor: render-mode button, 0 linear, 1 radial
            wf.setRenderMode(s);
            said.add(s == 1 ? "is a ring" : "is straight bars");
        }
        if (moves) applyMotion(wf, a, t, linked, said, keyed);

        if (said.isEmpty()) return ActionResult.unchanged("Nothing to change on that visualizer.");
        ActionResult res = ActionResult.changed("The visualizer " + join(said) + ".",
                Arrays.asList(wf.getId()));
        if (look != null) res = res.withDetail("Any custom layers it had were dropped for the new look.");
        return res;
    }

    /**
     * Position, size and rotation, the way the drawer's position/size menu writes them. A moving
     * visualizer gets a key at {@code t}; a linked one is converted from the parent's world space.
     */
    private static void applyMotion(@NonNull WaveformOverlayInstance wf, @NonNull Args a, long t,
                                    boolean linked, @NonNull List<String> said, boolean keyed) {
        final String W = WaveformOverlayInstance.VIZ_WIDTH;
        final String H = WaveformOverlayInstance.VIZ_HEIGHT;
        final String X = KeyframeSet.X, Y = KeyframeSet.Y, ROT = KeyframeSet.ROTATION;
        if (a.has("x") || a.has("y")) {
            float nx = a.has("x") ? a.num("x").floatValue() : (linked ? wf.animatedCenterX(t) : wf.getCenterX());
            float ny = a.has("y") ? a.num("y").floatValue() : (linked ? wf.animatedCenterY(t) : wf.getCenterY());
            if (linked) {
                float[] own = new float[2];
                wf.worldToOwn(nx, ny, t, own);
                nx = own[0];
                ny = own[1];
            }
            if (keyed) {
                if (a.has("x") || linked) wf.addPropertyKeyframeAt(X, t, nx);
                if (a.has("y") || linked) wf.addPropertyKeyframeAt(Y, t, ny);
            } else {
                wf.setCenter(nx, ny);
            }
            said.add("moved");
        }
        if (a.has("width") || a.has("height")) {
            float nw = wf.getWidthFraction(), nh = wf.getHeightFraction();
            if (a.has("width")) {
                float v = a.num("width").floatValue();
                if (linked) v = wf.worldSizeToOwn(v, t);
                if (keyed) wf.addPropertyKeyframeAt(W, t, v); else nw = v;
            }
            if (a.has("height")) {
                float v = a.num("height").floatValue();
                if (linked) v = wf.worldSizeToOwn(v, t);
                if (keyed) wf.addPropertyKeyframeAt(H, t, v); else nh = v;
            }
            if (!keyed) wf.setSize(nw, nh);
            said.add("resized");
        }
        if (a.has("rotation")) {
            float v = a.num("rotation").floatValue();
            if (linked) v = wf.worldRotationToOwn(v, t);
            if (keyed) wf.addPropertyKeyframeAt(ROT, t, v); else wf.setRotationDeg(v);
            said.add("turned");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A visualizer by id; an unknown id lists the ones there are, so the model can ask. */
    @NonNull
    private static WaveformOverlayInstance find(@NonNull FaditorProject p, @NonNull String ref) throws ActionError {
        List<WaveformOverlayInstance> all = p.getTimeline().getWaveformOverlays();
        if (all.isEmpty()) throw new ActionError("This project has no visualizers.");
        String r = ref.trim();
        for (WaveformOverlayInstance w : all) if (r.equals(w.getId())) return w;
        StringBuilder sb = new StringBuilder();
        for (WaveformOverlayInstance w : all) {
            sb.append(sb.length() > 0 ? ", " : "").append(w.getId()).append(" (look ").append(w.getStyleId())
                    .append(", ").append(StudioCtx.clock(w.getStartMs())).append('–')
                    .append(w.getEndMs() == Long.MAX_VALUE ? "end" : StudioCtx.clock(w.getEndMs())).append(')');
        }
        throw new ActionError("No visualizer has the id \"" + r + "\". The visualizers are: " + sb + ".");
    }

    /**
     * A look by id or name: the built-in presets plus any saved in the project's pinned folder, the
     * same list the drawer's Style carousel shows.
     */
    @NonNull
    private static WaveformStyle look(@NonNull StudioCtx ctx, @NonNull String name) throws ActionError {
        List<WaveformStyle> all = new ArrayList<>(WaveformStyleIO.loadBuiltins(ctx.app));
        String pinned = ctx.project.getPinnedAssetDir();
        if (pinned != null) all.addAll(WaveformStyleIO.loadUserStyles(ctx.app, Uri.parse(pinned)));
        String n = name.trim();
        for (WaveformStyle s : all) if (n.equals(s.id)) return s;
        String want = n.toLowerCase(Locale.ROOT).replace('_', ' ');
        for (WaveformStyle s : all) {
            if (s.displayName != null && s.displayName.toLowerCase(Locale.ROOT).equals(want)) return s;
        }
        for (WaveformStyle s : all) {
            if (s.id != null && s.id.replace('_', ' ').equalsIgnoreCase(want)) return s;
        }
        StringBuilder sb = new StringBuilder();
        for (WaveformStyle s : all) {
            sb.append(sb.length() > 0 ? ", " : "").append(s.displayName).append(" (").append(s.id).append(')');
        }
        throw new ActionError("There's no look called \"" + n + "\". Looks: " + sb + ".");
    }

    /** #RRGGBB or #AARRGGBB, checked the way the drawer's colours are stored. */
    @NonNull
    private static String colour(@NonNull String s) throws ActionError {
        String h = s.trim();
        if (Args.parseColor(h) == null) throw new ActionError("A colour looks like #FF5EAE, not \"" + s + "\".");
        return h;
    }

    @NonNull
    private static String join(@NonNull List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() > 0 ? ", " : "").append(s);
        return sb.toString();
    }
}
