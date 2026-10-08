package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.util.Map;

import javax.swing.JCheckBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import bioenv.core.CommandBuilder;
import bioenv.core.HelpParser;
import bioenv.core.ParamValue;
import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.ui.SchemaFormPanel;

/**
 * Verifies that what the user types into a parameter's field reaches the command.
 *
 * <p>Two separate faults produced the same symptom — a value typed in and then
 * missing from the command line — and both are covered here because fixing either
 * one alone would have left the symptom in place for some options.
 *
 * <p>First, {@link ParamValue#contributes()} required the literal text
 * {@code true} for a switch, a rule left over from when a switch was a tick box.
 * Whether a switch is a tick box or a text field, whatever the user puts in must
 * count as intent, so typing anything else into a switch-shaped option was
 * silently discarded.
 *
 * <p>Second, the parser read {@code -o, --out-file string} as taking no value at
 * all: the lower-case type name did not match the convention that a placeholder is
 * upper case or bracketed, so it was taken for the start of the description. An
 * option that appears to take no value then has its typed value dropped by the
 * first fault, so the two compounded.
 *
 * <p>The end-to-end block also pins the shape of a switch on the command line. A
 * tick box reports its tick as the text {@code "true"}, and that text must not be
 * written after the flag: an inferred switch stays a switch through
 * {@code collect()}, so {@code CommandBuilder} writes the bare flag.
 *
 * <p>Run: {@code java -Djava.awt.headless=true -cp &lt;classes&gt; bioenv.test.TypedValueReachesCommandTest}
 */
public final class TypedValueReachesCommandTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        line("=== A typed value reaches the command ===");
        line("");

        line("[a switch-shaped option keeps what was typed]");
        // The first fault on its own: whatever is typed must count as intent.
        Schema.Option sw = option("--reverse", "", "reverse sequence",
                Schema.Option.Kind.SWITCH);
        sw.isSwitch = true;
        for (String typed : new String[]{"x", "1", "yes", "true", "on"}) {
            ParamValue pv = new ParamValue(sw);
            pv.set(typed);
            check("typing '" + typed + "' counts as intent", pv.contributes());
        }
        ParamValue empty = new ParamValue(sw);
        empty.set("");
        check("an empty field still contributes nothing", !empty.contributes());
        ParamValue untouched = new ParamValue(sw);
        untouched.setUntouched("x");
        check("a programmatic fill is not intent", !untouched.contributes());
        line("");

        line("[a lower-case type name is a value position]");
        // The second fault: cobra writes "-o, --out-file string".
        Schema help = HelpParser.parse(
                "Flags:\n"
                + "  -k, --color                    colorize sequences\n"
                + "  -r, --reverse                  reverse sequence\n"
                + "  -o, --out-file string          out file (default \"-\")\n"
                + "  -w, --line-width int           line width (default 60)\n"
                + "  -G, --gap-letters string       gap letters\n"
                + "  -t, --seq-type string          sequence type\n"
                + "  -j, --threads int              number of CPUs\n",
                "seqkit");
        check("-o takes a value", takesValue(help, "--out-file"));
        check("-w takes a value", takesValue(help, "--line-width"));
        check("-G takes a value", takesValue(help, "--gap-letters"));
        check("-t takes a value", takesValue(help, "--seq-type"));
        check("-j takes a value", takesValue(help, "--threads"));
        check("the value word is recorded",
                "--out-file".equals("--out-file")
                        && "string".equals(help.option("--out-file").arg));
        line("");

        line("[real switches are still switches]");
        // The gap test must not turn genuine switches into value options.
        check("-k is still a switch", isSwitch(help, "--color"));
        check("-r is still a switch", isSwitch(help, "--reverse"));
        Schema more = HelpParser.parse(
                "Flags:\n"
                + "  -p, --complement               complement sequence\n"
                + "  -n, --name                     only print names\n"
                + "      --quiet                    be quiet\n"
                + "  -u, --upper-case               print in upper case\n",
                "seqkit");
        check("--complement is a switch", isSwitch(more, "--complement"));
        check("--name is a switch", isSwitch(more, "--name"));
        check("--quiet is a switch", isSwitch(more, "--quiet"));
        check("--upper-case is a switch", isSwitch(more, "--upper-case"));
        line("");

        line("[a description whose first word is plain stays a description]");
        // The gap test decides; it must not fire on ordinary prose.
        Schema prose = HelpParser.parse(
                "Flags:\n"
                + "  -o, --out-file string          out file\n"
                + "  -m, --mode                     sequence type (dna|rna)\n"
                + "  -x, --extra                    reverse sequence\n",
                "t");
        check("a two-word description keeps its first word",
                prose.option("--mode") != null
                        && prose.option("--mode").description.startsWith("sequence"));
        check("and does not invent a value position",
                prose.option("--mode") != null && prose.option("--mode").arg.isEmpty());
        line("");

        line("[end to end: fill the control, read the command]");
        // Each row is driven by the control its kind calls for: a path option is
        // a text field, so its value is typed; a switch is a tick box, so its
        // value is its ticked state. Ticking is what the user does, and it is
        // what puts the bare flag on the command line.
        //
        // Historical note: this block used to cast both rows to JTextField and
        // setText("x") on the switch, because every row was a text field when the
        // two underlying faults were fixed. The parser now infers SWITCH for a
        // flag that takes no value, and SchemaFormPanel.kindOf() honours it, so
        // the switch is rendered as a JCheckBox. The intent of the check is
        // unchanged - the user's input must reach the command - so it now
        // expresses that intent through the control the user actually gets.
        // (ParamValue.contributes() still accepts typed text for a switch; the
        // [a switch-shaped option keeps what was typed] block above covers that.)
        final SchemaFormPanel[] formRef = new SchemaFormPanel[1];
        final Schema[] schemaRef = new Schema[1];
        final String[] command = new String[1];
        final boolean[] controlsFound = new boolean[2];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                Schema s = HelpParser.parse(
                        "Usage:\n  seqkit seq [flags] \n\nFlags:\n"
                        + "  -r, --reverse                  reverse sequence\n"
                        + "  -o, --out-file string          out file (default \"-\")\n",
                        "seqkit");
                schemaRef[0] = s;
                Tool t = new Tool("seqkit", "seqkit_env", "/envs/seqkit_env");
                t.schema = s;
                SchemaFormPanel form = new SchemaFormPanel();
                form.showTool(t);
                form.setSize(900, 700);
                layout(form);
                formRef[0] = form;

                Component outC = findNamed(form, "--out-file");
                Component revC = findNamed(form, "--reverse");
                controlsFound[0] = outC instanceof JTextField;
                controlsFound[1] = revC instanceof JCheckBox;
                if (outC instanceof JTextField) {
                    ((JTextField) outC).setText("C:\\Users\\14231\\Desktop\\111\\222.fasta");
                }
                if (revC instanceof JCheckBox) {
                    ((JCheckBox) revC).setSelected(true);
                }
                layout(form);

                Map<String, ParamValue> vals = form.collect();
                command[0] = CommandBuilder.preview(t, s, "seq", vals, "");
            }
        });
        System.out.println("      command: " + command[0]);
        check("the path option is a field to type in", controlsFound[0]);
        check("the switch is a tick box to tick", controlsFound[1]);
        check("the output path is on the command line",
                command[0].contains("222.fasta"));
        check("the switch is on the command line", command[0].contains("--reverse"));
        // A tick box reports its tick as the text "true" (see readInto). That text
        // must not follow the flag onto the command line: seqkit seq would read
        // "true" as an input filename. The flag must stand alone.
        check("the ticked switch is written bare, with no value after it",
                !command[0].contains("--reverse true")
                        && !command[0].contains("--reverse  true"));
        check("the path is not lost", !command[0].endsWith("seq"));
        line("");

        line("=== results ===");
        line("  passed: " + pass);
        line("  failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
        line("");
        line("What the user types is what runs.");
    }

    private static Schema.Option option(String flag, String arg, String desc,
                                        Schema.Option.Kind kind) {
        Schema.Option o = new Schema.Option();
        o.flag = flag;
        o.arg = arg;
        o.description = desc;
        o.kind = kind;
        return o;
    }

    private static boolean takesValue(Schema s, String flag) {
        Schema.Option o = s.option(flag);
        return o != null && !o.isSwitch && !o.arg.isEmpty();
    }

    private static boolean isSwitch(Schema s, String flag) {
        Schema.Option o = s.option(flag);
        return o != null && o.isSwitch;
    }

    private static Component findNamed(Container c, String name) {
        for (Component ch : c.getComponents()) {
            if (name.equals(ch.getName())) {
                return ch;
            }
            if (ch instanceof Container) {
                Component r = findNamed((Container) ch, name);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static void layout(Container c) {
        c.doLayout();
        for (Component ch : c.getComponents()) {
            if (ch instanceof Container) {
                layout((Container) ch);
            }
        }
        c.doLayout();
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        line("  " + (ok ? "PASS" : "FAIL") + "  " + label);
    }

    private static void line(String s) {
        System.out.println(s);
    }
}
