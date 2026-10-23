package com.fadcam.ui.about;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.fadcam.R;
import com.fadcam.ui.BaseFragment;
import com.fadcam.ui.OverlayNavUtil;
import com.fadcam.ui.faditor.SheetKit;
import com.fadcam.ui.motion.Motion;
import com.fadcam.ui.type.Type;

/**
 * ABOUT, rebuilt for Joy Creator: a calm product page over old black. Top to bottom: the neon
 * wordmark with a breathing glow, what Joy Creator is, the version big in mono with Check for
 * updates, the wings in their colours, privacy, the open-source credit, and the sign-off.
 *
 * <p>A new screen beside the classic About screen ({@code com.fadcam.ui.AboutFragment}), which stays
 * whole and is reached from the small Classic About link at the bottom of this page. Opened as an
 * overlay from the Settings hub and the classic Settings tab; its back arrow and system Back return
 * to whichever opened it.</p>
 *
 * <p>This class only wires the parts: {@link AboutHero}, {@link AboutVersion},
 * {@link AboutWings}, {@link AboutPrivacy}, {@link AboutCredits} and {@link AboutSignOff} each
 * build one section; {@link AboutKit} holds what they share.</p>
 */
public class AboutJoyFragment extends BaseFragment {

    public static final String TAG = "about_joy";

    /** Show the About page over the lobby, or over the overlay screen open now. */
    public static void open(@NonNull FragmentActivity activity) {
        if (activity.getSupportFragmentManager().findFragmentByTag(TAG) != null) return;
        OverlayNavUtil.show(activity, new AboutJoyFragment(), TAG);
    }

    /** The sections rise in once per visit, not again when the classic screen closes over this one. */
    private boolean entered;
    @Nullable private AboutHero hero;
    @Nullable private AboutVersion version;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about_joy, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        Context c = requireContext();
        AboutVersion.Info info = AboutVersion.Info.read(c);

        TextView title = v.findViewById(R.id.aboutjoy_title);
        Type.display(title, Type.EXTRA);
        title.setAllCaps(true);
        TextView count = v.findViewById(R.id.aboutjoy_count);
        Type.mono(count, Type.REGULAR);
        count.setSingleLine(true);
        count.setAllCaps(true);   // after setSingleLine: both are transformations, the last one wins
        count.setText(info.name.isEmpty() ? "" : getString(R.string.aboutjoy_count, info.name));
        TextView back = v.findViewById(R.id.aboutjoy_back);
        back.setText("arrow_back");
        SheetKit.label(back, getString(R.string.aboutjoy_back));
        back.setOnClickListener(b -> OverlayNavUtil.dismiss(requireActivity()));

        LinearLayout body = v.findViewById(R.id.aboutjoy_body);
        hero = new AboutHero(c);
        version = new AboutVersion(this, info);
        // Each part and the space above it. The hero is full-bleed; the rest sit in the gutter.
        add(body, hero.view, fullBleed(), 0);
        add(body, version.view, AboutKit.block(c, 34), 1);
        add(body, AboutWings.build(c), AboutKit.block(c, 38), 2);
        add(body, AboutPrivacy.build(c, body), AboutKit.block(c, 38), 3);
        add(body, AboutCredits.build(c), AboutKit.block(c, 26), 4);
        add(body, AboutSignOff.build(this), AboutKit.block(c, 44), 5);
        entered = true;
    }

    private void add(@NonNull LinearLayout body, @NonNull View part,
                     @NonNull LinearLayout.LayoutParams lp, int order) {
        body.addView(part, lp);
        // A slow, staggered rise: each part 80ms after the one above. Reduced motion keeps the
        // fade and drops the rise (Motion.enter decides).
        if (!entered) Motion.enter(part, order * 2);
    }

    @NonNull
    private static LinearLayout.LayoutParams fullBleed() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (hero != null) hero.breathe(true);
    }

    @Override
    public void onPause() {
        if (hero != null) hero.breathe(false);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (hero != null) hero.breathe(false);
        if (version != null) version.release();
        hero = null;
        version = null;
        super.onDestroyView();
    }
}
