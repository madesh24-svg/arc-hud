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
    /** Controls for whatever is playing on the TV. arg: seconds for FORWARD/BACKWARD, percent for the volume. */
    static final int PAUSE = 13;
    static final int RESUME = 14;
    static final int FORWARD = 15;
    static final int BACKWARD = 16;
    static final int VOL_UP = 17;
    static final int VOL_DOWN = 18;
    static final int VOL_SET = 19;
    static final int MUTE = 20;
    static final int UNMUTE = 21;

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
    private static final Pattern PREV_P = Pattern.compile("^(?:previous|previous song|previous track"
            + "|play previous|last song|the last one|play the previous one|previous one)$");
    private static final Pattern PAUSE_P = Pattern.compile("^(?:pause|pause it|pause that|pause this|pause the"
            + " (?:video|music|song|movie|show|tv|playback|player)|pause (?:video|music|playback)|hold on|hold it"
            + "|wait|freeze)$");
    private static final Pattern RESUME_P = Pattern.compile("^(?:resume|continue|unpause|un pause|play|play it"
            + "|play that|resume it|resume that|resume the (?:video|music|song|movie|show|playback)"
            + "|resume (?:video|music|playback)|continue playing|keep playing|carry on|play again"
            + "|continue the (?:video|music|song|movie|show))$");

    /** Forward and back: the strong words may stand alone, the weak ones need an amount of time. */
    private static final Pattern FWD_STRONG = Pattern.compile("^(?:fast forward|forward|forwards|skip forward"
            + "|skip ahead|jump forward|jump ahead|go forward|move forward|seek forward|forward the video"
            + "|forward it|fast forward it)(?: (.+))?$");
    private static final Pattern FWD_WEAK = Pattern.compile("^(?:skip|jump|go ahead|move ahead|seek ahead|go) (.+)$");
    private static final Pattern BACK_STRONG = Pattern.compile("^(?:rewind|rewind it|rewind the video|backward"
            + "|backwards|go back|go backward|go backwards|skip back|skip backward|skip backwards|jump back"
            + "|jump backward|move back|move backward|seek back|seek backward|back)(?: (.+))?$");
    private static final Pattern TIME = Pattern.compile("\\d|\\b(?:second|seconds|sec|secs|minute|minutes|min|mins"
            + "|half|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty|fifty|sixty"
            + "|ninety)\\b");
    private static final Pattern UNIT = Pattern.compile("\\b(?:second|seconds|sec|secs|minute|minutes|min|mins)\\b");
    private static final Pattern MINUTES = Pattern.compile("\\b(?:minute|minutes|min|mins)\\b");

    private static final Pattern VOL_WORD = Pattern.compile("\\b(?:volume|louder|quieter|softer|mute|unmute|muted"
            + "|sound|lower|raise|increase|decrease|reduce|turn it up|turn it down|crank|loud|quiet|silence)\\b");
    private static final Pattern UNMUTE_P = Pattern.compile("\\bun ?mute\\b");
    private static final Pattern MUTE_P = Pattern.compile("\\b(?:mute|muted|silence)\\b");
    private static final Pattern VOL_UP_P = Pattern.compile("\\b(?:up|louder|raise|increase|higher|more|boost"
            + "|crank|loud)\\b");
    private static final Pattern VOL_DOWN_P = Pattern.compile("\\b(?:down|quieter|softer|lower|decrease|reduce"
            + "|less|quiet|low)\\b");
    private static final Pattern SMALL_P = Pattern.compile("\\b(?:bit|little|slightly|touch|notch)\\b");
    private static final Pattern BIG_P = Pattern.compile("\\b(?:lot|much|way|loads)\\b");
    private static final Pattern MAX_P = Pattern.compile("\\b(?:max|maximum|full|highest)\\b");
    private static final Pattern MIN_P = Pattern.compile("\\b(?:minimum|lowest|zero)\\b");

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
    private static final Pattern REMOTE_WORD = Pattern.compile("\\b(?:brightness|power|shut down|restart|reboot"
            + "|home screen|go home|subtitles|captions|full screen|turn on|switch on|switch off)\\b");

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
        if (PAUSE_P.matcher(t).matches()) {
            return Cmd.of(PAUSE);
        }
        if (RESUME_P.matcher(t).matches()) {
            return Cmd.of(RESUME);
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
        if (VOL_WORD.matcher(t).find()) {
            Cmd v = volume(t);
            if (v != null) {
                return v;
            }
        }
        Cmd seek = seek(t);
        if (seek != null) {
            return seek;
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

    private static Cmd seek(String t) {
        Matcher m = FWD_STRONG.matcher(t);
        if (m.matches() && timeOk(m.group(1))) {
            return new Cmd(FORWARD, String.valueOf(seconds(m.group(1))), null, false, false);
        }
        m = BACK_STRONG.matcher(t);
        if (m.matches() && timeOk(m.group(1))) {
            return new Cmd(BACKWARD, String.valueOf(seconds(m.group(1))), null, false, false);
        }
        m = FWD_WEAK.matcher(t);
        if (m.matches() && UNIT.matcher(m.group(1)).find()) {
            return new Cmd(FORWARD, String.valueOf(seconds(m.group(1))), null, false, false);
        }
        return null;
    }

    private static boolean timeOk(String rest) {
        return rest == null || TIME.matcher(rest).find();
    }

    /** "30 seconds", "a minute", "half a minute", "two minutes"; 10 seconds when no amount is said. */
    static int seconds(String rest) {
        if (rest == null) {
            return 10;
        }
        boolean minutes = MINUTES.matcher(rest).find();
        int n;
        if (rest.contains("half")) {
            n = 30;
            minutes = false;
        } else {
            n = number(rest);
            if (n < 0) {
                n = minutes ? 1 : 10;
            }
        }
        int s = minutes ? n * 60 : n;
        return Math.max(5, Math.min(300, s));
    }

    private static final String[] WORDS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
            "nineteen", "twenty"};
    private static final String[] TENS = {"thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    /** First number in the text, digits or words up to ninety-nine; -1 when there is none. */
    static int number(String t) {
        int total = -1;
        for (String w : t.split(" ")) {
            int v = -1;
            if (w.matches("\\d{1,3}")) {
                v = Integer.parseInt(w);
            } else {
                for (int i = 0; i < WORDS.length && v < 0; i++) {
                    if (WORDS[i].equals(w)) {
                        v = i;
                    }
                }
                for (int i = 0; i < TENS.length && v < 0; i++) {
                    if (TENS[i].equals(w)) {
                        v = 30 + 10 * i;
                    }
                }
            }
            if (v < 0) {
                continue;
            }
            if (total < 0) {
                total = v;
            } else if (total >= 20 && total % 10 == 0 && v < 10) {
                total += v;
            } else {
                break;
            }
        }
        return total;
    }

    /** Volume commands; null when the sentence names no direction, level or mute. */
    private static Cmd volume(String t) {
        if (UNMUTE_P.matcher(t).find()) {
            return Cmd.of(UNMUTE);
        }
        if (MUTE_P.matcher(t).find()) {
            return Cmd.of(MUTE);
        }
        boolean up = VOL_UP_P.matcher(t).find();
        boolean down = VOL_DOWN_P.matcher(t).find() && !up;
        int n = number(t);
        if (MAX_P.matcher(t).find() && !down) {
            return new Cmd(VOL_SET, "100", null, false, false);
        }
        if (MIN_P.matcher(t).find()) {
            return new Cmd(VOL_SET, "0", null, false, false);
        }
        if (t.contains("half")) {
            return new Cmd(VOL_SET, "50", null, false, false);
        }
        if (n >= 0 && n <= 100) {
            boolean by = t.contains(" by ");
            boolean to = t.contains(" to ") || t.contains(" at ");
            if (!by && (to || (!up && !down))) {
                return new Cmd(VOL_SET, String.valueOf(n), null, false, false);
            }
            if (up || down) {
                return new Cmd(up ? VOL_UP : VOL_DOWN, String.valueOf(Math.max(1, n)), null, false, false);
            }
        }
        if (!up && !down) {
            return null;
        }
        int step = SMALL_P.matcher(t).find() ? 5 : (BIG_P.matcher(t).find() ? 25 : 10);
        return new Cmd(up ? VOL_UP : VOL_DOWN, String.valueOf(step), null, false, false);
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
