package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * "Tell me a joke" (owner 11-15, BOT-7): offline, from Joybot's own roster. Clean, original, and
 * wry, mostly about making things: paint, drawing, film, music, editing, and being a small robot
 * helper. Never the same one twice in a row; in fact not again until most of the others have had
 * their turn. Pure Java (IdeaEngineTest).
 */
public final class JokeBook {
    private JokeBook() { }

    public static final List<String> JOKES = Collections.unmodifiableList(Arrays.asList(
            // Paint and drawing
            "Pencils are the most humble tools in the box. They know they'll be erased, and they show up anyway.",
            "I asked the eraser how it was doing. It said it was making progress, just not leaving any.",
            "Oil paint takes days to dry. It isn't lazy. It's thinking it over.",
            "Watercolour is the only paint that does exactly what it likes and calls it a happy accident.",
            "Charcoal is the only art supply that also does your hands.",
            "I'd tell you a joke about a blank page, but there's nothing to it.",
            "I'd tell you a joke about seamless tiles, but it goes on and on and on.",
            "Seamless tiles are perfect. Every edge is someone else's problem.",
            "Symmetry is lovely. Symmetry is lovely.",
            "Radial symmetry is just symmetry that couldn't pick a side.",
            "Vanishing points are my favourite. They always know when to leave.",
            "Two-point perspective taught me something important: there are two sides to every building.",
            "I flew a loop-the-loop and came out with a whole new perspective. Three-point, I think.",
            "A critic said my painting lacked depth. So I added a layer.",
            "I named a layer “Final”. Then “Final 2”. Then “Final FINAL”. That's when I understood humans.",
            "Complementary colours are the nicest pair in the box. They're always saying good things about each other.",
            "The colour wheel and I have a lot in common. We go round in circles until something looks right.",
            "I'm very good at colour theory. In practice, I mostly wear grey-blue.",
            "I once tried to paint the wind. The canvas blew away. The critics called it minimal.",
            "My favourite brush setting is pressure. It's the only one that works on artists too.",
            "Every masterpiece started as a mess that refused to stop.",
            "Inspiration is wonderful. But have you met a deadline?",
            "I asked the pixel art whether it was finished. It said, “Roughly.”",
            "Isometric drawings never lie. They just won't tell you which way is up.",
            "A blank canvas and I had a staring contest. We called it a draw.",
            // Animation and sprites
            "A man walked into a flipbook. Then he did it again, slightly to the left. Twelve times a second.",
            "My animation runs at twelve frames a second. I prefer to call it having standards.",
            "Every walk cycle is a journey that goes nowhere, beautifully.",
            "Sprite characters have it easy. When they trip, someone just reorders the frames.",
            "Onion skins are how animators see the past and the future at once. Fortune tellers are jealous.",
            "Animators don't procrastinate. They're in-betweening.",
            // Film and editing
            "A jump cut is a scene that couldn't wait.",
            "Crossfades are just two clips that agreed to leave the room together.",
            "Fade to black is the polite way for a scene to leave a party.",
            "Editors don't make mistakes. They make cuts that weren't ready yet.",
            "The camera adds ten pounds. The editor takes out three seconds. It all balances out.",
            "My clapperboard is very supportive. It applauds before every take.",
            "Film directors and toddlers both say “Again!” and mean it every single time.",
            "A film without music is just people looking thoughtfully at things.",
            "Lens flare is the sun's way of photobombing.",
            "Green screens are wonderful listeners. You can project anything onto them.",
            "I keyed out a green screen and lost my whole lunch. It was a salad.",
            "Slow motion is for when you want a moment to last longer than anyone's patience.",
            "A time-lapse is the art of making a sunset look like it's late for something.",
            "The export bar says 99%. It isn't stuck. It's savouring the moment.",
            "Rendering is when a computer stares at your video very hard until it agrees to be a file.",
            "Captions are your own words, dressed for going out.",
            // Sound and music
            "The ums I cut from your recordings go to a lovely farm in the country. They're very happy there. Mostly they hesitate.",
            "I took forty-two ums out of your talk. I kept one, for flavour.",
            "I tried recording a podcast about silence. Nobody could tell when it ended.",
            "A metronome never panics. It keeps time and lets everyone else lose theirs.",
            "Music visuals are just songs that finally learned to dance.",
            "The bass player and I understand each other. We stay in the background and hold everything together.",
            "Echoes always get the last word. Word. Word.",
            // Words and titles
            "My favourite font is whichever one you picked. I'm a helper. I've learned this.",
            "I tried kerning once. It was a close call between two letters.",
            "Chrome letters look confident, but between you and me, they're just reflecting.",
            // Being a small robot helper
            "People say robots have no feelings. I have one. It's mild concern about unsaved work.",
            "I don't sleep. I close my eyes and tidy my memory.",
            "I'm not short. I'm a compact-format helper.",
            "Undo is the closest thing I have to time travel. I use it responsibly. Mostly. Twice.",
            "My thruster runs on enthusiasm and a little electricity. Mainly the electricity.",
            "People ask if robots can be creative. I once made a sandwich with the bread on the inside. They stopped asking.",
            "I contain multitudes. Mostly undo steps.",
            "I'd help you clean your room, but I only know how to free up space on phones.",
            "My job is to make your work easier. My hobby is hovering slightly to the left of it."));

    private static final Pattern ASKS = Pattern.compile(
            "^(?:(?:please|joybot|hey joybot|go on|ok|okay)[ ,]+)*(?:tell|give|say|do) (?:me |us )?(?:a |another |one more |some )?(?:joke|jokes|funny one|something funny)(?: please)?\\??$"
                    + "|^(?:a |another |one more )?joke(?: please)?\\??$|^make me (?:laugh|smile)(?: please)?\\??$"
                    + "|^(?:do you )?know any (?:good )?jokes\\??$|^(?:got|have you got|do you have) (?:a|any) jokes?\\??$");

    /** "Tell me a joke", "another joke", "make me laugh". */
    public static boolean asks(@Nullable String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.US).replace('’', '\'').replaceAll("[.!]", "").replaceAll("\\s+", " ").trim();
        return ASKS.matcher(t).find();
    }

    /**
     * The next joke's index: never one of {@code recent} (the last ones told, newest last), so a
     * joke never comes back until most of the others have been told.
     */
    public static int pick(@NonNull List<Integer> recent, @NonNull Random r) {
        int n = JOKES.size();
        int keep = Math.min(recent.size(), n - 1);
        List<Integer> avoid = recent.subList(recent.size() - keep, recent.size());
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < n; i++) if (!avoid.contains(i)) free.add(i);
        return free.get(r.nextInt(free.size()));
    }

    /** How many recent jokes are kept out of the draw: most of the roster. */
    public static int memory() {
        return JOKES.size() * 3 / 4;
    }
}
