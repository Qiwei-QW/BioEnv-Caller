package bioenv.tools;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Measures what each part of a help document contributes to the parameter list.
 *
 * <p>A parameter can be declared in the option list, named in a usage synopsis, or
 * merely demonstrated in an example. The three need separating because only the
 * first two are declarations, yet the synopsis is the <em>only</em> place some
 * parameters appear — so it cannot simply be discarded along with the examples.
 *
 * <p>The classification here uses document structure only (section headings and
 * where a flag sits), never a tool's name or a list of tools, so the result
 * describes a property of help documents rather than of one machine's install.
 *
 * <p>Run: {@code java -cp <classes> bioenv.tools.SourceSurvey}
 */
public final class SourceSurvey {

    /** A heading introducing worked examples. */
    private static final Pattern EXAMPLE_HEADING = Pattern.compile(
            "(?i)^\\s*(some\\s+|usage\\s+)?examples?\\s*:?\\s*$");

    /** A heading introducing a usage synopsis. */
    private static final Pattern USAGE_HEADING = Pattern.compile(
            "(?i)^\\s*(basic\\s+|general\\s+)?(usage|synopsis)\\s*:?\\s*$");

    /** A flag anywhere in a line. */
    private static final Pattern FLAG = Pattern.compile(
            "(?:^|[\\s\\[({,|])(-{1,2}[A-Za-z][A-Za-z0-9_.-]*)");

    /** A numbered list item, which introduces a demonstration. */
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+[.)]\\s+\\S");

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0]
                : "C:\\Users\\demo\\Desktop\\TBtools插件\\_helpsurvey";
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

        int onlyOptions = 0;
        int onlyUsage = 0;
        int onlyExample = 0;
        int inBoth = 0;
        int total = 0;
        int docsWithUsageOnly = 0;
        int docsWithExampleOnly = 0;
        List<String> usageOnlyExamples = new ArrayList<String>();
        List<String> exampleOnlyExamples = new ArrayList<String>();

        for (File f : samples) {
            String text = read(f);
            String name = f.getName().replace(".txt", "");

            Map<String, Set<String>> flags = new LinkedHashMap<String, Set<String>>();
            classify(text, flags);

            boolean sawUsageOnly = false;
            boolean sawExampleOnly = false;
            for (Map.Entry<String, Set<String>> e : flags.entrySet()) {
                total++;
                Set<String> where = e.getValue();
                boolean inOpt = where.contains("options");
                boolean inUse = where.contains("usage");
                boolean inEx = where.contains("example");

                if (inOpt && !inUse && !inEx) {
                    onlyOptions++;
                } else if (!inOpt && inUse && !inEx) {
                    onlyUsage++;
                    sawUsageOnly = true;
                    if (usageOnlyExamples.size() < 25) {
                        usageOnlyExamples.add(name + "  " + e.getKey());
                    }
                } else if (!inOpt && !inUse && inEx) {
                    onlyExample++;
                    sawExampleOnly = true;
                    if (exampleOnlyExamples.size() < 25) {
                        exampleOnlyExamples.add(name + "  " + e.getKey());
                    }
                } else if (inOpt && (inUse || inEx)) {
                    inBoth++;
                }
            }
            if (sawUsageOnly) {
                docsWithUsageOnly++;
            }
            if (sawExampleOnly) {
                docsWithExampleOnly++;
            }
        }

        line("=== What each part of the help contributes ===");
        line("documents: " + samples.size() + "   flags: " + total);
        line("");
        line("[flags by the set of places they appear]");
        line(String.format("  only in the option list        : %5d (%s%%)",
                onlyOptions, pct(onlyOptions, total)));
        line(String.format("  only in a usage synopsis       : %5d (%s%%)",
                onlyUsage, pct(onlyUsage, total)));
        line(String.format("  only in an example             : %5d (%s%%)",
                onlyExample, pct(onlyExample, total)));
        line(String.format("  in the option list and elsewhere: %4d (%s%%)",
                inBoth, pct(inBoth, total)));
        line("");
        line("[documents affected]");
        line("  with a synopsis-only flag : " + docsWithUsageOnly
                + " (" + pct(docsWithUsageOnly, samples.size()) + ")");
        line("  with an example-only flag : " + docsWithExampleOnly
                + " (" + pct(docsWithExampleOnly, samples.size()) + ")");
        line("");
        line("[synopsis-only flags — these would be LOST if the synopsis were dropped]");
        for (String s : usageOnlyExamples) {
            line("    " + s);
        }
        line("");
        line("[example-only flags — these are the ones to exclude]");
        for (String s : exampleOnlyExamples) {
            line("    " + s);
        }
    }

    /**
     * Records, for every flag, which structural parts of the document named it.
     *
     * <p>Parts are decided by position: a line inside an examples block is an
     * example; a line carrying a usage label, or inside a usage block, is a
     * synopsis; a line that has a description column is an option entry.
     */
    private static void classify(String text, Map<String, Set<String>> flags) {
        boolean inExamples = false;
        boolean inUsage = false;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (EXAMPLE_HEADING.matcher(t).matches()) {
                inExamples = true;
                inUsage = false;
                continue;
            }
            if (USAGE_HEADING.matcher(t).matches()) {
                inUsage = true;
                inExamples = false;
                continue;
            }
            // Another heading ends the current block.
            if (t.endsWith(":") && !t.startsWith("-")) {
                inExamples = false;
                inUsage = false;
            }
            if (NUMBERED.matcher(t).find()) {
                inExamples = true;
            }

            Matcher m = FLAG.matcher(line);
            while (m.find()) {
                String flag = m.group(1);
                Set<String> where = flags.get(flag);
                if (where == null) {
                    where = new LinkedHashSet<String>();
                    flags.put(flag, where);
                }
                if (inExamples) {
                    where.add("example");
                } else if (inUsage || t.matches("(?i).*\\busage\\b.*")) {
                    where.add("usage");
                } else if (hasDescriptionColumn(line)) {
                    where.add("options");
                } else {
                    where.add("prose");
                }
            }
        }
    }

    /** True when a line has a run of two or more spaces separating fields. */
    private static boolean hasDescriptionColumn(String line) {
        return Pattern.compile("\\S\\s{2,}\\S").matcher(line).find();
    }

    private static String pct(int n, int total) {
        return String.format("%.1f", 100.0 * n / Math.max(1, total));
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


