package com.fadcam.joybrush;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import cc.joycreator.joybrush.android.JoyBrushActivity;

/**
 * DEBUG BUILDS ONLY. Opens Joy Brush's stroke replay benchmark over adb (JoyBrushActivity is not exported):
 *   adb shell am start -n com.fadcam.beta/com.fadcam.joybrush.JoyBrushBenchActivity --es jb_bench fastC,scribble
 *       --es jb_bench_brushes ink,media:dry:Proto --ei jb_bench_runs 3
 * Results: adb logcat -s JoyBrushPerf. The benchmark opens a fresh drawing and never saves (BenchRunner).
 */
public class JoyBrushBenchActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent next = new Intent(this, JoyBrushActivity.class);
        if (getIntent().getExtras() != null) next.putExtras(getIntent().getExtras());
        // No CLEAR_TASK: it would close the person's other Joy Creator screens. A second Joy Brush screen is fine.
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(next);
        finish();
    }
}
