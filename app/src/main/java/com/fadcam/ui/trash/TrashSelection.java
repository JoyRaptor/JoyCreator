package com.fadcam.ui.trash;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which deleted things are chosen, by key. Pure: tested on a JVM.
 *
 * <p>Survives a reload: whatever is still in the Trash stays chosen, whatever went is let go, so a
 * delete or restore of part of a choice never leaves a ghost behind. A chip that hides a chosen
 * thing does not unchoose it; the bar counts what you chose, not what you can see.</p>
 */
public final class TrashSelection {

    private final Set<String> keys = new LinkedHashSet<>();

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public int size() {
        return keys.size();
    }

    public boolean contains(String key) {
        return keys.contains(key);
    }

    /** @return true when it is chosen now */
    public boolean toggle(String key) {
        if (keys.remove(key)) return false;
        keys.add(key);
        return true;
    }

    public void clear() {
        keys.clear();
    }

    public void addAll(Collection<String> more) {
        keys.addAll(more);
    }

    /** True when every one of {@code shown} is chosen (and there is at least one). */
    public boolean coversAll(Collection<String> shown) {
        return !shown.isEmpty() && keys.containsAll(shown);
    }

    /** Let go of anything no longer present. */
    public void retainOnly(Collection<String> present) {
        keys.retainAll(new LinkedHashSet<>(present));
    }

    public List<String> keys() {
        return new ArrayList<>(keys);
    }
}
