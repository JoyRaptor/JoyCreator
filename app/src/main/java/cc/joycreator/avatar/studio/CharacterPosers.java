package cc.joycreator.avatar.studio;

/**
 * Which {@link CharacterPoser} Studio draws characters with: {@link RestPoser} until the solver port installs the real
 * one ({@code CharacterPosers.install(...)}, once, at start-up).
 */
public final class CharacterPosers {
    private static volatile CharacterPoser.Factory factory = RestPoser::new;
    private static volatile boolean real;

    private CharacterPosers() {}

    public static CharacterPoser.Factory get() {
        return factory;
    }

    /** The solver's poser (X2 / X3). After this, characters move in Studio's preview and export. */
    public static void install(CharacterPoser.Factory f) {
        if (f == null) return;
        factory = f;
        real = true;
    }

    /** False while only the standing stand-in is installed: Studio says nothing to the person, the notes say so. */
    public static boolean moves() {
        return real;
    }
}
