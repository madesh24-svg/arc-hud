package io.archud.link;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one spoken sentence into one command. No network and no model: a list of patterns that cover the many
 * ways people say the same thing ("open", "launch", "start"; "play", "put on", "I want to hear"), with filler
 * words ("please", "can you", "on the TV") stripped first. Anything it does not recognise comes back UNKNOWN
 * and Jarvis stays quiet about it unless it was spoken straight after the wake word.
 */
final class JarvisBrain {
    static final int UNKNOWN = 0;
    static final int SLEEP = 1;
    static final int HELP = 2;
    static final int STOP = 3;
    static final int NEXT = 4;
    static final int PREV = 5;
    static final int TOGGLE = 6;
    static final int PHOTOS = 7;
    static final int OPEN_AUDIO = 8;
    static final int OPEN_APP = 9;
    static final int PLAY = 10;
    static final int ASK_PLAY = 11;
    static final int NEEDS_REMOTE = 12;

    /** One command. arg = app name or YouTube search; local = words to look for in the phone's songs, or null. */
    static final class Cmd {
        final int type;
        final String arg;
        final String local;
        /** For "a song from X": any of the first few results will do. */
        final boolean random;
        /** A guess from a bare phrase: stay silent when it does not work out. */
        final boolean soft;

        Cmd(int type, String arg, String local, boolean random, boolean soft) {
            this.type = type;
            this.arg = arg;
            this.local = local;
            this.random = random;
            this.soft = soft;
        }

        static Cmd of(int type) {
            return new Cmd(type, "", null, false, false);
        }
    }

    private static final String ARC = "(?:arc|ark|arch|aarc|arck)";
    /** "Jarvis" (also what the recognizer tends to hear instead) or "arc arc". */
    private static final Pattern WAKE = Pattern.compile("\\b(?:jarvis|jarvas|jervis|javis|travis|jarvice)\\b"
            + "|\\b" + ARC + " " + ARC + "\\b|\\b(?:arcarc|arkark)\\b");
    private static final Pattern LEAD = Pattern.compile("^(?:(?:hey|hi|ok|okay|please|kindly|just|so|now|and|then|um|uh"
            + "|can you|could you|would you|will you|can u|i want you to|i want to|i wanna|i would like to"
            + "|i'd like to|i d like to|let's|lets|go ahead and|if you can|you can|maddy's|maddys|maddy) )+");
    private static final Pattern TRAIL = Pattern.compile("(?: (?:please|now|for me|on the tv|on tv|on my tv"
            + "|on the television|on television|thanks|thank you|jarvis|right now|again please))+$");

    private static final Pattern SLEEP_P = Pattern.compile("^(?:go to sleep|sleep|stop listening|that's all|thats all"
            + "|that will be all|that is all|goodbye|good bye|good night|bye|bye bye|dismissed|stand by|standby"
            + "|go back to sleep)$");
    private static final Pattern HELP_P = Pattern.compile("^(?:help|what can you do|what can i say|what do you do"
            + "|how do you work)$");
    private static final Pattern STOP_P = Pattern.compile("^(?:stop|stop it|stop all|stop everything|stop casting"
            + "|stop playing|cancel|cancel it|close|close it|exit|quit|turn off|shut up|be quiet|enough"
            + "|stop the music|stop the song|stop the photos)$");
    private static final Pattern NEXT_P = Pattern.compile("^(?:next|skip|next song|skip song|next track|skip this"
            + "|skip it|skip this song|play next|play the next one|next one|another|another one|another song"
            + "|another result|another video|next result|something else|different one|a different one"
            + "|try another|try another one|not this one|play another|play another one|play another song"
            + "|play something else|play a different one|play a different song|one more|next video)$");
    private static final Pattern PREV_P = Pattern.compile("^(?:previous|previous song|previous track|go back"
            + "|play previous|last song|the last one|play the previous one|previous one)$");
    private static final Pattern TOGGLE_P = Pattern.compile("^(?:pause|resume|continue|unpause|play|pause it"
            + "|pause the music|pause the song|pause music|resume it|resume the music|resume the song|play it"
            + "|continue playing|keep playing|hold on|wait)$");

    private static final Pattern PLAY_P = Pattern.compile("^(?:play|put on|listen to|hear|stream|sing|watch"
            + "|search for|search|find|look up|look for|queue up|queue|start playing|begin playing)(?: for)? ?(.*)$");
    private static final Pattern AUDIO_VERB = Pattern.compile("^(?:play|put on|listen to|hear|sing|stream|queue up"
            + "|queue|start playing|begin playing)\\b.*");
    private static final Pattern ON_YOUTUBE = Pattern.compile("\\bon (?:you ?tube|smart ?tube)\\b");
    private static final Pattern ARTICLES = Pattern.compile("^(?:(?:me|us|a|an|the|some|any|my) )+");
    private static final Pattern SOMETHING_FROM = Pattern.compile("^(?:something|anything|some thing|a thing)"
            + "(?: nice| good| nice and| new| old)? (?:from|by|of) (?:the )?(?:(?:movie|film|album) )?(.+)$");
    private static final Pattern KIND_OF = Pattern.compile("^(?:[a-z']+ ){0,2}?(song|songs|track|tracks|music|video"
            + "|videos|number|numbers|melody|melodies) (from|of|in|by|out of) (?:the )?"
            + "(?:(?:movie|film|album|series|show) )?(.+)$");
    private static final Pattern KIND_TITLE = Pattern.compile("^(?:song|track|music|video|number) (.+)$");
    private static final Pattern TITLE_KIND = Pattern.compile("^(.+?) (?:song|songs|track|music video|video song"
            + "|full song|video|lyrical)$");
    private static final Pattern NOT_A_SONG = Pattern.compile(".*\\b(?:video|trailer|movie|film|episode|full|live"
            + "|interview|news|match|cricket|podcast|documentary|series|show|teaser|scene|scenes|review)\\b.*");

    private static final Pattern PHOTO_WORD = Pattern.compile("\\b(?:photo|photos|picture|pictures|pics|pic|gallery"
            + "|images|image)\\b");
    private static final Pattern SHOW_VERB = Pattern.compile("^(?:open|show|see|view|display|go to|launch|take me to"
            + "|bring up|let me see|start)\\b.*");
    private static final Pattern OPEN_AUDIO_P = Pattern.compile("^(?:(?:open|show|go to|launch|start) (?:my |the )?"
            + "(?:music|songs|audio|music player|song list|audio player|music library|song library)"
            + "|play (?:some |my )?(?:music|songs))$");
    private static final Pattern OPEN_P = Pattern.compile("^(?:open up|open|launch|start up|start|run|go to"
            + "|switch to|take me to|bring up|fire up|load|show me|show|get me) (.+)$");
    private static final Pattern APP_FILLER = Pattern.compile("^(?:(?:the|my|app|up|application) )+");
    private static final Pattern APP_TAIL = Pattern.compile(" (?:app|application|please)$");
    private static final Pattern REMOTE_WORD = Pattern.compile("\\b(?:volume|louder|quieter|softer|mute|unmute"
            + "|brightness|rewind|fast forward|forward|power|shut down|restart|reboot|home screen|go home"
            + "|subtitles|captions|full screen|turn up|turn down|turn on|switch on|switch off)\\b");

    private JarvisBrain() {
    }

    /** Lower case, letters, digits and apostrophes only, single spaces. */
    static String normalize(String raw) {
        String t = raw.toLowerCase(Locale.ROOT).replace('’', '\'').replace('‘', '\'');
        t = t.replaceAll("[^a-z0-9' ]", " ").replaceAll("\\s+", " ").trim();
        return t;
    }

    /** What follows the wake word in this sentence (possibly ""), or null when it holds no wake word. */
    static String afterWake(String raw) {
        String t = normalize(raw);
        Matcher m = WAKE.matcher(t);
        if (!m.find()) {
            return null;
        }
        return t.substring(m.end()).trim();
    }

    static Cmd parse(String raw) {
        String t = normalize(raw);
        for (int i = 0; i < 3; i++) {
            t = LEAD.matcher(t).replaceFirst("").trim();
            t = TRAIL.matcher(t).replaceFirst("").trim();
        }
        if (t.isEmpty()) {
            return Cmd.of(UNKNOWN);
        }
        if (SLEEP_P.matcher(t).matches() || (t.contains("sleep") && t.split(" ").length <= 4)) {
            return Cmd.of(SLEEP);
        }
        if (HELP_P.matcher(t).matches()) {
            return Cmd.of(HELP);
        }
        if (STOP_P.matcher(t).matches()) {
            return Cmd.of(STOP);
        }
        if (NEXT_P.matcher(t).matches()) {
            return Cmd.of(NEXT);
        }
        if (PREV_P.matcher(t).matches()) {
            return Cmd.of(PREV);
        }
        if (TOGGLE_P.matcher(t).matches()) {
            return Cmd.of(TOGGLE);
        }
        if (OPEN_AUDIO_P.matcher(t).matches()) {
            return Cmd.of(OPEN_AUDIO);
        }
        if (PHOTO_WORD.matcher(t).find() && (SHOW_VERB.matcher(t).matches() || t.split(" ").length <= 3)
                && !AUDIO_VERB.matcher(t).matches() && !t.startsWith("next ") && !t.startsWith("previous ")) {
            return Cmd.of(PHOTOS);
        }
        Matcher play = PLAY_P.matcher(t);
        if (play.matches()) {
            return play(t, play.group(1).trim());
        }
        Matcher open = OPEN_P.matcher(t);
        if (open.matches()) {
            String name = cleanApp(open.group(1));
            if (!name.isEmpty()) {
                return new Cmd(OPEN_APP, name, null, false, false);
            }
        }
        if (REMOTE_WORD.matcher(t).find()) {
            return Cmd.of(NEEDS_REMOTE);
        }
        if (t.split(" ").length <= 3) {
            // a bare app name such as "smart tube" or "youtube"
            return new Cmd(OPEN_APP, cleanApp(t), null, false, true);
        }
        return Cmd.of(UNKNOWN);
    }

    private static String cleanApp(String name) {
        String n = APP_FILLER.matcher(name.trim()).replaceFirst("").trim();
        n = APP_TAIL.matcher(n).replaceFirst("").trim();
        return n;
    }

    private static Cmd play(String whole, String rest) {
        boolean audioVerb = AUDIO_VERB.matcher(whole).matches();
        boolean forceYoutube = !audioVerb;
        Matcher yt = ON_YOUTUBE.matcher(rest);
        if (yt.find()) {
            rest = (rest.substring(0, yt.start()) + rest.substring(yt.end())).replaceAll("\\s+", " ").trim();
            forceYoutube = true;
        }
        rest = ARTICLES.matcher(rest).replaceFirst("").trim();
        if (rest.isEmpty()) {
            return Cmd.of(ASK_PLAY);
        }
        Matcher some = SOMETHING_FROM.matcher(rest);
        if (some.matches()) {
            return new Cmd(PLAY, some.group(1).trim() + " songs", null, true, false);
        }
        Matcher kind = KIND_OF.matcher(rest);
        if (kind.matches()) {
            String x = kind.group(3).trim();
            boolean video = kind.group(1).startsWith("video");
            boolean by = "by".equals(kind.group(2));
            String query = video ? x : (by ? "songs by " + x : x + " songs");
            return new Cmd(PLAY, query, null, true, false);
        }
        String title = rest;
        Matcher kt = KIND_TITLE.matcher(rest);
        if (kt.matches()) {
            title = kt.group(1).trim();
        } else {
            Matcher tk = TITLE_KIND.matcher(rest);
            if (tk.matches()) {
                title = tk.group(1).trim();
            }
        }
        boolean looksLikeSong = audioVerb && !NOT_A_SONG.matcher(rest).matches();
        String query = looksLikeSong ? title + " song" : title;
        String local = forceYoutube || !looksLikeSong ? null : title;
        return new Cmd(PLAY, query, local, false, false);
    }
}
