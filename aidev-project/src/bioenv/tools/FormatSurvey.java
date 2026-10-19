package bioenv.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import bioenv.core.HelpFormat;

/**
 * Runs the structure recogniser over the collected help samples and reports the
 * distribution, so the classifier is judged against real documents rather than
 * against samples written to make it pass.
 *
 * <p>Run: {@code java -cp <classes> bioenv.tools.FormatSurvey [sampleDir]}
 */
public final class FormatSurvey {

    public static void main(String[] args) {
        File dir = new File(args.length > 0 ? args[0]
                : "C:\\Users\\demo\\Desktop\\TBtools插件\\_helpsurvey");
        File[] files = dir.listFiles();
        if (files == null) {
            line("no sample directory: " + dir);
            return;
        }
        List<File> samples = new ArrayList<File>();
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".txt")) {
                samples.add(f);
            }
        }
        Collections.sort(samples);

        line("=== Structure recogniser against real samples ===");
        line("samples: " + samples.size());
        line("");

        Map<HelpFormat.Family, Integer> counts =
                new EnumMap<HelpFormat.Family, Integer>(HelpFormat.Family.class);
        Map<HelpFormat.Family, List<String>> examples =
                new EnumMap<HelpFormat.Family, List<String>>(HelpFormat.Family.class);
        List<String> unusable = new ArrayList<String>();
        List<String> badSamples = new ArrayList<String>();
        int withTypeColumn = 0;
        int withUsage = 0;
        int withAlias = 0;
        int withSections = 0;
        int counted = 0;

        for (File f : samples) {
            String text;
            try {
                text = new String(Files.readAllBytes(f.toPath()),
                        Charset.forName("UTF-8"));
            } catch (IOException e) {
                continue;
            }
            // A sample that is the output of a failed collection says nothing
            // about the tool's structure. Including such samples would inflate
            // the "unusable" count with documents that were never obtained.
            if (isCollectionFailure(text)) {
                badSamples.add(f.getName().replace(".txt", ""));
                continue;
            }
            counted++;
            HelpFormat.Report r = HelpFormat.analyse(text);
            Integer n = counts.get(r.dominant);
            counts.put(r.dominant, Integer.valueOf(n == null ? 1 : n + 1));
            List<String> ex = examples.get(r.dominant);
            if (ex == null) {
                ex = new ArrayList<String>();
                examples.put(r.dominant, ex);
            }
            if (ex.size() < 5) {
                ex.add(f.getName().replace(".txt", ""));
            }
            if (r.hasTypeColumn) {
                withTypeColumn++;
            }
            if (r.hasUsageLine) {
                withUsage++;
            }
            if (r.hasAliasGroups) {
                withAlias++;
            }
            if (r.hasNamedSections) {
                withSections++;
            }
            if (r.isUnusable() && unusable.size() < 8) {
                unusable.add(f.getName().replace(".txt", ""));
            }
        }

        line("[dominant description]");
        for (HelpFormat.Family fam : HelpFormat.Family.values()) {
            Integer n = counts.get(fam);
            if (n == null || n == 0) {
                continue;
            }
            line(String.format("  %-16s %4d (%4.1f%%)   e.g. %s", fam, n,
                    100.0 * n / Math.max(1, counted),
                    join(examples.get(fam))));
        }
        line("");

        line("[component presence, which is what parsing actually uses]");
        pct("type column", withTypeColumn, counted);
        pct("usage synopsis", withUsage, counted);
        pct("alias groups", withAlias, counted);
        pct("named sections", withSections, counted);
        line("");

        line("[documents with nothing usable]");
        line("  " + (unusable.isEmpty() ? "(none)" : join(unusable)));
        line("");
        line("[samples excluded: collection failed, so they prove nothing]");
        line("  count: " + badSamples.size());
        line("  " + join(badSamples));
    }

    /**
     * True when a sample is the output of a failed collection rather than a help
     * document.
     *
     * <p>Two situations must be told apart, because they mean opposite things:
     *
     * <ul>
     *   <li>The wrapper could not locate itself ({@code dirname: missing operand},
     *       {@code _helper: not found}) — the help was never obtained, so the
     *       sample must be discarded and re-collected.</li>
     *   <li>The tool ran and rejected the help request, because its arguments are
     *       files and it treats {@code --help} as one ({@code ACD file not
     *       opened}) — the sample is valid, and the tool genuinely documents
     *       nothing.</li>
     * </ul>
     *
     * <p>Counting the first kind as "no structure" would blame the tools for a
     * failure in the collection.
     */
    static boolean isCollectionFailure(String text) {
        if (text == null || text.trim().isEmpty()) {
            return true;
        }
        String low = text.toLowerCase();
        // The help was never produced: the command could not run.
        if (low.contains("missing operand")
                || low.contains("not found")
                || low.contains("modulenotfounderror")
                || low.contains("no module named")
                || low.contains("cannot open shared object")
                || low.contains("traceback")) {
            // Unless the document is substantial, in which case the phrase is
            // part of a description rather than the whole output.
            return text.length() < 400;
        }
        return false;
    }

    private static void pct(String label, int n, int total) {
        line(String.format("  %-16s %4d (%4.1f%%)", label, n,
                100.0 * n / Math.max(1, total)));
    }

    private static String join(List<String> items) {
        if (items == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static void line(String s) {
        System.out.println(s);
    }
}


