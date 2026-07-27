package com.fadcam.ui.faditor.layers;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

/**
 * Harness stub of the real TimedItem, carrying ONLY the payload accessors that
 * {@link ObjectPalette#payloadKindOf} consults. The real class drags in the whole editor
 * model (keyframes, transforms, Android types); the payload discrimination under test is
 * exactly these six null-checks, so a stub reproduces it faithfully and lets the rule be
 * proven off-device.
 */
public class TimedItem {

    private Clip clip;
    private TextOverlayItem textOverlay;
    private AudioClip audioClip;
    private SpriteOverlayItem sprite;
    private WaveformOverlayInstance waveform;
    private CaptionSpanRef captionSpan;

    public static TimedItem ofText() {
        TimedItem i = new TimedItem();
        i.textOverlay = new TextOverlayItem(false);
        return i;
    }

    public static TimedItem ofImage() {
        TimedItem i = new TimedItem();
        i.textOverlay = new TextOverlayItem(true);
        return i;
    }

    public static TimedItem ofSprite() {
        TimedItem i = new TimedItem();
        i.sprite = new SpriteOverlayItem();
        return i;
    }

    public static TimedItem ofAudio() {
        TimedItem i = new TimedItem();
        i.audioClip = new AudioClip();
        return i;
    }

    public static TimedItem ofWaveform() {
        TimedItem i = new TimedItem();
        i.waveform = new WaveformOverlayInstance();
        return i;
    }

    public static TimedItem ofCaption() {
        TimedItem i = new TimedItem();
        i.captionSpan = new CaptionSpanRef();
        return i;
    }

    /** An overlay (PiP) clip. */
    public static TimedItem ofOverlayClip() {
        TimedItem i = new TimedItem();
        i.clip = new Clip(true);
        return i;
    }

    /** A master-spine clip — deliberately NOT an overlay, so it has no identity of its own. */
    public static TimedItem ofMasterClip() {
        TimedItem i = new TimedItem();
        i.clip = new Clip(false);
        return i;
    }

    /** No payload at all — must fall back to the row's kind. */
    public static TimedItem empty() {
        return new TimedItem();
    }

    public Clip getClip() { return clip; }
    public TextOverlayItem getTextOverlay() { return textOverlay; }
    public AudioClip getAudioClip() { return audioClip; }
    public SpriteOverlayItem getSprite() { return sprite; }
    public WaveformOverlayInstance getWaveform() { return waveform; }
    public CaptionSpanRef getCaptionSpan() { return captionSpan; }
}
