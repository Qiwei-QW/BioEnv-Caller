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
 * Lists every candidate list the parser still accepts, so the surviving ones can
 * be judged individually.
 *
 * <p>Removing false lists is only an improvement if the true ones remain. This
 * exists to check the other half of that trade: not that the false ones are gone,
 * but that the real ones are still there.
 *
 * <p>Run: {@code java -cp <classes> bioenv.test.ShowChoices}
 */
public final class ShowChoices {

    public static void main(String[] args) {
        String dir = "C:\\Users\\14231\\Desktop\\TBtools插件\\_helpsurvey";
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
        for (File f : samples) {
            Schema s = HelpParser.parse(read(f), f.getName().replace(".txt", ""));
            for (Schema.Option o : s.options) {
                if (o.choices.isEmpty()) {
                    continue;
                }
                total++;
                if (total <= 60) {
                    line(String.format("  %-18s %-14s %s",
                            f.getName().replace(".txt", ""), o.flag, o.choices));
                }
            }
        }
        line("");
        line("candidate lists accepted: " + total + "  (shown: "
                + Math.min(60, total) + ")");
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
