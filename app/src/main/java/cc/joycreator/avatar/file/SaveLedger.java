package cc.joycreator.avatar.file;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Where each character on the screen was last saved, kept by the character itself. Saves run one after another on the folder's one
 * thread; each reads its path at the moment its turn comes, so two saves of a never-saved character make ONE file, and a save that
 * finishes late can only ever name the character it was for.
 */
public final class SaveLedger<D> {
    /** Writes one character, returns its path (or null when the folder could not be reached). */
    public interface Saver<D> {
        String save(D doc, String path);
    }

    private final Map<D, String> savedAs = Collections.synchronizedMap(new IdentityHashMap<>());

    /** Call on the folder thread: saves the character where it was saved before (else at {@code startPath}, else as a new file). */
    public String run(D doc, String startPath, Saver<D> saver) {
        String known = savedAs.get(doc);
        String rel = saver.save(doc, known != null ? known : startPath);
        if (rel != null) savedAs.put(doc, rel);
        return rel;
    }

    /** A character that arrived with a place (opened from the folder), or one that has none yet (null). */
    public void adopt(D doc, String path) {
        if (path != null) savedAs.put(doc, path);
        else savedAs.remove(doc);
    }

    public String pathOf(D doc) {
        return savedAs.get(doc);
    }
}
