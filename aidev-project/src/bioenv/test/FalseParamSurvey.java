package bioenv.test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import bioenv.core.HelpParser;
import bioenv.core.Schema;

/**
 * Measures how widely prose is being read as parameters.
 *
 * <p>A parameter that came from prose has a signature that can be checked
 * mechanically. It is extracted from a synopsis rather than from an option list,
 * and the text never presents it as an option anywhere else — it exists only
 * because a hyphen inside a word was mistaken for a flag. A genuine synopsis
 * parameter, by contrast, is either also documented in the option list or appears
 * in a synopsis made of flags and placeholders.
 *
 * <p>Counting these quantifies the problem instead of judging it from one example.
 *
 * <p>Run: {@code java -cp <classes> bioenv.test.FalseParamSurvey}
 */
public final class FalseParamSurvey {

    /** A name that reads as an ordinary lower-case English word. */
    private static final Pattern WORDY = Pattern.compile(
            "^--?[a-z]{5,}$");

    /** Hyphenated compounds that occur in ordinary technical prose. */
    private static final Pattern COMPOUND = Pattern.compile(
            "\\b[a-z]+-[a-z]+\\b");

    /** Sentence punctuation, which a synopsis does not contain. */
    private static final Pattern SENTENCE = Pattern.compile(
            "[a-z]{2,}\\s+[a-z]{2,}\\s+(the|and|for|with|that|from|are|is|of|to)\\b");

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0]
                : "C:\\Users\\14231\\Desktop\\TBtools插件\\_helpsurvey";
        File[] files = new File(dir).listFiles();
        if (files == null) {
            return;
        }
        List<File> samples = new ArrayList<File>();
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".txt")
                    && !FormatSurvey.isCollectionFailure(read(f))) {
                samples.add(f);
            }
        }
        Collections.sort(samples);

        int samplesWithCompounds = 0;
        int totalCompounds = 0;
        int totalOptions = 0;
        int fromSynopsis = 0;
        int suspicious = 0;
        int proseSynopsisLines = 0;
        List<String> examples = new ArrayList<String>();

        for (File f : samples) {
            String text = read(f);
            String name = f.getName().replace(".txt", "");

            Matcher c = COMPOUND.matcher(text);
            int compounds = 0;
            while (c.find()) {
                compounds++;
            }
            if (compounds > 0) {
                samplesWithCompounds++;
                totalCompounds += compounds;
            }

            // Lines that carry sentence structure cannot be synopses.
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (t.contains("-") && SENTENCE.matcher(t).find()) {
                    proseSynopsisLines++;
                }
            }

            Schema s = HelpParser.parse(text, name);
            for (Schema.Option o : s.options) {
                totalOptions++;
                if (!o.fromUsageLine) {
                    continue;
                }
                fromSynopsis++;
                // A synopsis parameter should normally also be documented, or at
                // least look like a flag rather than a word.
                boolean documented = appearsAsOptionLine(text, o.flag);
                if (!documented && WORDY.matcher(o.flag).matches()) {
                    suspicious++;
                    if (examples.size() < 25) {
                        examples.add(name + "  " + o.flag
                                + "   arg=[" + o.arg + "]");
                    }
                }
            }
        }

        line("=== Prose read as parameters ===");
        line("samples: " + samples.size());
        line("");
        line("[hyphenated compounds in the help text]");
        line("  documents containing any : " + samplesWithCompounds
                + " (" + pct(samplesWithCompounds, samples.size()) + ")");
        line("  total occurrences       : " + totalCompounds);
        line("");
        line("[lines that are prose but contain a hyphen]");
        line("  count: " + proseSynopsisLines);
        line("  (each is a line that could be misread as a synopsis)");
        line("");
        line("[parameters extracted from a synopsis]");
        line("  options parsed      : " + totalOptions);
        line("  from a synopsis     : " + fromSynopsis);
        line("  suspicious (word-shaped, not documented elsewhere) : "
                + suspicious);
        for (String e : examples) {
            line("    " + e);
        }
    }

    /** True when the text presents this flag at the start of a line. */
    private static boolean appearsAsOptionLine(String text, String flag) {
        if (flag.isEmpty()) {
            return false;
        }
        for (String line : text.split("\n")) {
            String t = line.trim();
            // Leading brackets are stripped in some documents.
            if (t.startsWith("[" + flag + "]") || t.startsWith(flag)) {
                int at = flag.length();
                if (t.length() == at || !Character.isLetterOrDigit(t.charAt(at))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String pct(int n, int total) {
        return String.format("%.2f%%", 100.0 * n / Math.max(1, total));
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 52 ? s : s.substring(0, 52) + "...";
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()),
                    Charset.forName("UTF-8"));
        } catch (Exception e) {
            return "";
        }
    }

    private static void line(String s) {
        System.out.println(s);
    }
}
