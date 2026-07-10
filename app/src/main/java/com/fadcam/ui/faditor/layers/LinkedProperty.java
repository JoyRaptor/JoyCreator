package com.fadcam.ui.faditor.layers;

/**
 * G9 object linking (gesture contract §5.6, PLAN_G9_LINK_ENGINE.md §2): the property axes a
 * {@link LinkGroup} can bind between its members. v1 ships TIME (move-together); the transform
 * axes are declared so storage/UI enums are stable, but their propagation lands with the G2
 * Prop-adapter work (they serialize by NAME — never reorder, only append).
 */
public enum LinkedProperty {
    /** Timeline position — members move together in time (v1: MOVE only, trim does not propagate). */
    TIME,
    /** Canvas position (normalized center). Declared for storage stability; propagation later. */
    POSITION,
    /** Opacity. Piggyback attachment (contract §4 Axis-2) binds this: host fades take the rider. */
    OPACITY,
    /** Uniform scale. Declared for storage stability; propagation later. */
    SCALE,
    /** Rotation degrees. Declared for storage stability; propagation later. */
    ROTATION
}
