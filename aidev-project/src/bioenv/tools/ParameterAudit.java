package bioenv.tools;

import java.util.ArrayList;
import java.util.List;

import bioenv.core.EnvRunner;
import bioenv.core.EnvScanner;
import bioenv.core.HelpParser;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;
import bioenv.core.WslBridge;

/**
 * Surveys how parameters are being classified across every discovered tool, and
 * reports the cases most likely to be wrong.
 *
 * <p>The point is to replace guesswork about "some parameters are still wrong"
 * with counts: how often each type is inferred, how many options are switches,
 * how many have no type signal at all, and which specific options look
 * suspicious. A plan to improve inference should be based on what actually
 * fails.
 */
public final class ParameterAudit {

    public static void main(String[] args) {
        int sample = args.length > 0 ? Integer.parseInt(args[0]) : 40;

        WslBridge wsl = new WslBridge(WslBridge.listDistros().get(0));
        EnvScanner sc = new EnvScanner(wsl);
        sc.discover();
        EnvRunner er = new EnvRunner(wsl, sc.getCondaRoot());
        ToolRegistry reg = new ToolRegistry(sc, er);
        reg.scan();

        List<Tool> all = reg.sorted();
        line("=== Parameter inference audit ===");
        line("tools available: " + all.size() + ", sampling " + sample);
        line("");

        int tools = 0;
        int totalOptions = 0;
        int switches = 0;
        int unknown = 0;
        int lowConf = 0;
        int medConf = 0;
        int highConf = 0;

        // Cases worth looking at: options whose type came from a weak signal,
        // and options that look like a path but were not typed as one.
        List<String> suspicious = new ArrayList<String>();
        List<String> noSignal = new ArrayList<String>();

        for (Tool t : all) {
            if (tools >= sample) {
                break;
            }
            if (t.schema != null && !t.schema.options.isEmpty()) {
                continue; // already known from the cache
            }
            reg.introspect(t);
            tools++;
            if (t.schema == null) {
                continue;
            }
            for (Schema.Option o : t.schema.options) {
                totalOptions++;
                switch (o.confidence) {
                    case HIGH: highConf++; break;
                    case MEDIUM: medConf++; break;
                    default: lowConf++; break;
                }
                if (o.kind == Schema.Option.Kind.SWITCH) {
                    switches++;
                }
                if (o.kind == Schema.Option.Kind.TEXT
                        && (o.confidence == TypeInferenceConfidence.LOW)) {
                    unknown++;
                    if (noSignal.size() < 25) {
                        noSignal.add(t.command + " " + o.flag
                                + "  arg=[" + o.arg + "]  desc=" + shortText(o.description));
                    }
                }
                // An option whose text mentions a file or path but is not typed
                // as one is a likely miss.
                String blob = (o.arg + " " + o.description).toLowerCase();
                boolean pathish = blob.contains("file") || blob.contains("path")
                        || blob.contains("dir");
                if (pathish && o.kind != Schema.Option.Kind.INPUT_PATH
                        && o.kind != Schema.Option.Kind.OUTPUT_PATH) {
                    if (suspicious.size() < 30) {
                        suspicious.add(t.command + " " + o.flag
                                + "  kind=" + o.kind
                                + "  arg=[" + o.arg + "]"
                                + "  desc=" + shortText(o.description));
                    }
                }
            }
        }

        line("--- inference outcomes ---");
        line("  tools probed     : " + tools);
        line("  options total    : " + totalOptions);
        line("  switches         : " + switches);
        line("  confidence high  : " + highConf);
        line("  confidence medium: " + medConf);
        line("  confidence low   : " + lowConf);
        line("  untyped (TEXT+LOW): " + unknown);
        line("");

        line("--- options mentioning file/path but not typed as a path ---");
        for (String s : suspicious) {
            line("  " + s);
        }
        line("");

        line("--- options with no type signal ---");
        for (String s : noSignal) {
            line("  " + s);
        }
    }

    /** Local alias so the audit compiles without importing the enum directly. */
    private static final class TypeInferenceConfidence {
        static final bioenv.core.TypeInference.Confidence LOW =
                bioenv.core.TypeInference.Confidence.LOW;
    }

    private static String shortText(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ');
        return t.length() <= 60 ? t : t.substring(0, 60) + "...";
    }

    private static void line(String s) {
        System.out.println(s);
    }
}

