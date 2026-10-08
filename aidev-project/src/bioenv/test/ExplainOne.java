package bioenv.test;

import bioenv.core.CommandBuilder;
import bioenv.core.CorrectionStore;
import bioenv.core.DiscoveryCache;
import bioenv.core.EnvFingerprint;
import bioenv.core.EnvRunner;
import bioenv.core.EnvScanner;
import bioenv.core.HelpParser;
import bioenv.core.NoiseFilter;
import bioenv.core.ParamValue;
import bioenv.core.Runner;
import bioenv.core.Schema;
import bioenv.core.SchemaBuilder;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;
import bioenv.core.TypeInference;
import bioenv.core.VersionParser;
import bioenv.core.WslBridge;
import bioenv.ui.MainPanel;
import bioenv.ui.SchemaFormPanel;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * Shows how the parser classifies one real help document, parameter by
 * parameter, including the reason each type was chosen.
 *
 * <p>Usage: {@code java -cp <classes> bioenv.core.ExplainOne <helpFile> <command>}
 */
public final class ExplainOne {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : System.getenv("TEMP") + "/of_help.txt";
        String cmd = args.length > 1 ? args[1] : "orthofinder";

        String text = readAll(new File(path));
        line("=== Parsing help for '" + cmd + "' ===");
        line("source: " + path + "  (" + text.split("\n").length + " lines)");
        line("");

        Schema s = HelpParser.parse(text, cmd);
        line("options found : " + s.options.size());
        line("groups        : " + s.groups);
        line("subcommands   : " + s.subcommands.size());
        line("");
        line(String.format("%-18s %-14s %-14s %-6s %s",
                "FLAG", "PLACEHOLDER", "TYPE", "CONF", "WHY"));
        line(repeat('-', 110));
        for (Schema.Option o : s.options) {
            line(String.format("%-18s %-14s %-14s %-6s %s",
                    o.flag,
                    o.arg.isEmpty() ? "-" : o.arg,
                    o.kind,
                    shortConf(o.confidence),
                    o.typeReason));
        }
        line("");
        line("--- details for the ones with defaults or choices ---");
        for (Schema.Option o : s.options) {
            if (o.defaultValue.isEmpty() && o.choices.isEmpty()
                    && o.min == null && o.max == null) {
                continue;
            }
            StringBuilder sb = new StringBuilder("  " + o.flag + ":");
            if (!o.defaultValue.isEmpty()) {
                sb.append(" default=").append(o.defaultValue);
            }
            if (!o.choices.isEmpty()) {
                sb.append(" choices=").append(o.choices);
            }
            if (o.min != null || o.max != null) {
                sb.append(" range=").append(o.min).append("..").append(o.max);
            }
            sb.append("   isSwitch=").append(o.isSwitch);
            line(sb.toString());
        }
        line("");
        line("--- description as parsed (shows what the split produced) ---");
        for (Schema.Option o : s.options) {
            String d = o.description == null ? "" : o.description;
            if (d.length() > 78) {
                d = d.substring(0, 78) + "...";
            }
            line("  " + pad(o.flag, 8) + d);
        }
    }

    private static String shortConf(TypeInference.Confidence c) {
        switch (c) {
            case HIGH:
                return "HIGH";
            case MEDIUM:
                return "MED";
            default:
                return "LOW";
        }
    }

    private static String readAll(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), "UTF-8"));
        String l;
        while ((l = br.readLine()) != null) {
            sb.append(l).append('\n');
        }
        br.close();
        return sb.toString();
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < n) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static void line(String s) {
        System.out.println(s);
    }
}
