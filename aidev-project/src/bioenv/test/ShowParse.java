package bioenv.test;

import java.util.List;

import bioenv.core.HelpParser;
import bioenv.core.Schema;

/**
 * Shows the full parse of real help text, so the result of the rework can be
 * inspected tool by tool rather than only in aggregate.
 *
 * <p>Run: {@code java -cp <classes> bioenv.test.ShowParse <sampleFile>}
 */
public final class ShowParse {

    public static void main(String[] args) throws Exception {
        String dir = "C:\\Users\\14231\\Desktop\\TBtools插件\\_helpsurvey\\";
        String[] names = args.length > 0 ? args
                : new String[]{"clustalw", "abiview", "2to3-3.10", "FastTree"};
        for (String name : names) {
            java.io.File f = new java.io.File(dir + name + ".txt");
            if (!f.isFile()) {
                line("(no sample: " + name + ")");
                continue;
            }
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.Charset.forName("UTF-8"));
            if (FormatSurvey.isCollectionFailure(text)) {
                line("(collection failed: " + name + ")");
                continue;
            }
            Schema s = HelpParser.parse(text, name);
            line("=" .repeat(72));
            line(name + "   [" + (s.format == null ? "?" : s.format.describe()) + "]");
            line("=".repeat(72));
            int shown = 0;
            for (Schema.Option o : s.options) {
                if (shown++ >= 8) {
                    line("  ... " + (s.options.size() - 8) + " more");
                    break;
                }
                line(String.format("  %-18s %-12s %-16s %s",
                        o.flag,
                        o.kind,
                        o.evidence,
                        o.typeReason));
            }
            line("");
        }
    }

    private static void line(String s) {
        System.out.println(s);
    }
}
