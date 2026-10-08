package bioenv.test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import bioenv.core.HelpParser;
import bioenv.core.Schema;

/**
 * Counts parameters that were picked up from prose rather than declared anywhere.
 *
 * <p>A synopsis line is prose-free by nature; a paragraph that merely mentions an
 * option is not. When a prose line is treated as a synopsis, the options it
 * mentions become parameters with no description — but only if the option list
 * does not already declare them, since a declared one wins.
 *
 * <p>Counting those quantifies whether the continuation test needs tightening.
 *
 * <p>Run: {@code java -cp <classes> bioenv.test.ProseParamSurvey}
 */
public final class ProseParamSurvey {

    /** Punctuation that prose carries and a synopsis does not. */
    private static final java.util.regex.Pattern SENTENCE_PUNCT =
            java.util.regex.Pattern.compile("[.,;!?]\\s|\\s(and|or|the|which|that|with)\\s",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

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

        int total = 0;
        int synopsisOnly = 0;
        int fromProseLike = 0;
        List<String> examples = new ArrayList<String>();

        for (File f : samples) {
            String text = read(f);
            String name = f.getName().replace(".txt", "");
            Schema s = HelpParser.parse(text, name);

            for (Schema.Option o : s.options) {
                total++;
                if (!o.fromUsageLine) {
                    continue;
                }
                synopsisOnly++;
                // Was the flag mentioned on a line that reads like prose?
                if (namedInProse(text, o.flag)) {
                    fromProseLike++;
                    if (examples.size() < 30) {
                        examples.add(name + "  " + o.flag
                                + "   arg=[" + o.arg + "]");
                    }
                }
            }
        }

        line("=== Parameters taken from prose ===");
        line("documents        : " + samples.size());
        line("parameters total : " + total);
        line("synopsis-only    : " + synopsisOnly);
        line("from a prose-like line : " + fromProseLike);
        line("");
        line("[the ones that look like prose]");
        for (String e : examples) {
            line("    " + e);
        }
    }

    /** True when the flag is named on a line carrying sentence punctuation. */
    private static boolean namedInProse(String text, String flag) {
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.contains(flag)) {
                continue;
            }
            if (startsFlag(t)) {
                continue;
            }
            if (SENTENCE_PUNCT.matcher(t).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsFlag(String t) {
        return t.matches("^\\s{0,10}\\[?-{1,2}[A-Za-z].*")
                && t.matches(".*\\s{2,}.*");
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
