package bioenv.test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import bioenv.core.HelpParser;
import bioenv.core.Schema;

/**
 * Reports how the parser actually classifies parameters across the collected
 * samples, by evidence source.
 *
 * <p>The point is to see what the parser leans on in practice. If most types came
 * from {@code HEURISTIC}, the structured sources are not being found; if they came
 * from declared types and placeholders, the parser is reading what the documents
 * state rather than guessing at it.
 *
 * <p>Run: {@code java -cp <classes> bioenv.test.EvidenceSurvey [sampleDir]}
 */
public final class EvidenceSurvey {

    public static void main(String[] args) {
        File dir = new File(args.length > 0 ? args[0]
                : "C:\\Users\\14231\\Desktop\\TBtools插件\\_helpsurvey");
        File[] files = dir.listFiles();
        if (files == null) {
            line("no sample directory: " + dir);
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

        Map<Schema.Option.Evidence, Integer> byEvidence =
                new EnumMap<Schema.Option.Evidence, Integer>(
                        Schema.Option.Evidence.class);
        Map<Schema.Option.Kind, Integer> byKind =
                new EnumMap<Schema.Option.Kind, Integer>(Schema.Option.Kind.class);
        int total = 0;
        int withOptions = 0;
        int declaredOptions = 0;
        List<String> declaredExamples = new ArrayList<String>();

        for (File f : samples) {
            Schema s = HelpParser.parse(read(f), f.getName().replace(".txt", ""));
            if (s.options.isEmpty()) {
                continue;
            }
            withOptions++;
            for (Schema.Option o : s.options) {
                total++;
                bump(byEvidence, o.evidence);
                bump(byKind, o.kind);
                if (o.evidence == Schema.Option.Evidence.TYPE_COLUMN) {
                    declaredOptions++;
                    if (declaredExamples.size() < 12) {
                        declaredExamples.add(f.getName().replace(".txt", "")
                                + " " + o.flag + " -> " + o.kind
                                + " (" + o.declaredWord + ")");
                    }
                }
            }
        }

        line("=== Parameter classification across real samples ===");
        line("samples parsed with options: " + withOptions
                + " / " + samples.size());
        line("options classified: " + total);
        line("");

        line("[by evidence, strongest first]");
        for (Schema.Option.Evidence e : Schema.Option.Evidence.values()) {
            Integer n = byEvidence.get(e);
            if (n == null || n == 0) {
                continue;
            }
            line(String.format("  %-14s %5d (%5.1f%%)", e, n,
                    100.0 * n / Math.max(1, total)));
        }
        line("");

        line("[resulting control kinds]");
        for (Schema.Option.Kind k : Schema.Option.Kind.values()) {
            Integer n = byKind.get(k);
            if (n == null || n == 0) {
                continue;
            }
            line(String.format("  %-14s %5d (%5.1f%%)", k, n,
                    100.0 * n / Math.max(1, total)));
        }
        line("");

        line("[options whose type the author declared: "
                + declaredOptions + "]");
        for (String e : declaredExamples) {
            line("  " + e);
        }
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()),
                    Charset.forName("UTF-8"));
        } catch (IOException e) {
            return "";
        }
    }

    private static <K> void bump(Map<K, Integer> m, K key) {
        Integer n = m.get(key);
        m.put(key, Integer.valueOf(n == null ? 1 : n + 1));
    }

    private static void line(String s) {
        System.out.println(s);
    }
}
