package com.fadcam.joyfolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which works use which files. Drives "used in Lecture 3, Dino walk" on every file, the gallery's
 * "not used in any project" and "used by 2+" filters, and Consolidate's question about shared files.
 */
public final class UsageMap {

    private final Map<String, Set<String>> worksByFile = new HashMap<>();

    /** Record that the work at {@code workRel} uses the file at {@code fileRel}. */
    public void add(String workRel, String fileRel) {
        Set<String> works = worksByFile.get(fileRel);
        if (works == null) worksByFile.put(fileRel, works = new LinkedHashSet<>());
        works.add(workRel);
    }

    public Set<String> worksUsing(String fileRel) {
        Set<String> s = worksByFile.get(fileRel);
        return s == null ? Collections.<String>emptySet() : Collections.unmodifiableSet(s);
    }

    /** True when some work other than {@code workRel} also uses the file. */
    public boolean usedByOthers(String fileRel, String workRel) {
        for (String w : worksUsing(fileRel)) if (!w.equals(workRel)) return true;
        return false;
    }

    /** Material and supplies no work uses. Works themselves are never "unused". */
    public List<String> unused(Collection<String> allFiles) {
        List<String> out = new ArrayList<>();
        for (String f : allFiles) {
            if (FileKind.of(Placement.nameOf(f)).isWork()) continue;
            if (worksUsing(f).isEmpty()) out.add(f);
        }
        return out;
    }

    public List<String> usedByAtLeast(Collection<String> allFiles, int works) {
        List<String> out = new ArrayList<>();
        for (String f : allFiles) if (worksUsing(f).size() >= works) out.add(f);
        return out;
    }
}
