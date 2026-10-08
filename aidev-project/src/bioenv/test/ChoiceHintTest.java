package bioenv.test;

import bioenv.core.HelpParser;
import bioenv.core.Schema;

/**
 * Verifies the spelling that turns a parameter into a dropdown when the tool's help
 * did not list the candidates.
 *
 * <p>The panel lets a description be rewritten, and the description is read again,
 * so a value list typed there is what makes the parameter a {@code Choice}. The
 * recognition is structural — a run of short identifiers separated by commas — so
 * the separator matters, and a Chinese enumeration comma is not one.
 *
 * <p>Run: {@code java -cp &lt;classes&gt; bioenv.test.ChoiceHintTest}
 */
public final class ChoiceHintTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        line("=== 冒号 + 英文逗号：认出来了 ===");
        Schema.Option ok = first("OPTIONS:\n"
                + "  -f <x>    output format Options: FASTA,EMBL,GenBank,UniProt\n");
        check("it became a Choice",
                ok != null && ok.kind == Schema.Option.Kind.CHOICE);
        check("with four candidates",
                ok != null && ok.choices != null && ok.choices.size() == 4);
        if (ok != null && ok.choices != null) {
            line("    candidates: " + ok.choices);
        }
        line("");

        line("[冒号后不留空格也认]");
        Schema.Option tight = first("OPTIONS:\n"
                + "  -f <x>    output format Options:FASTA,EMBL,GenBank,UniProt\n");
        check("it became a Choice",
                tight != null && tight.kind == Schema.Option.Kind.CHOICE);
        check("with four candidates",
                tight != null && tight.choices != null
                        && tight.choices.size() == 4);
        line("");

        line("[值里有数字或横线的，不用冒号也认]");
        // looksLikeValueSet accepts a run whose tokens mostly carry a digit,
        // underscore, dot or hyphen, or are spelled in capitals.
        Schema.Option shaped = first("OPTIONS:\n"
                + "  -m <x>    model BLOSUM62,BLOSUM80,PAM250\n");
        check("it became a Choice",
                shaped != null && shaped.kind == Schema.Option.Kind.CHOICE);
        line("");

        line("=== 中文顿号：切不开，认不出来 ===");
        // splitTokens splits on a comma only, so the whole run arrives as one
        // token, fails the shape test, and no list is found.
        Schema.Option cjk = first("OPTIONS:\n"
                + "  -f <x>    output format Options: FASTA\u3001EMBL\u3001GenBank\u3001UniProt\n");
        check("not a Choice",
                cjk == null || cjk.kind != Schema.Option.Kind.CHOICE);
        if (cjk != null) {
            line("    (its kind came out as " + cjk.kind + ")");
        }
        line("");

        line("=== 没有冒号、值又不带特征：不认 ===");
        Schema.Option prose = first("OPTIONS:\n"
                + "  -f <x>    output format FASTA,EMBL,GenBank,UniProt\n");
        check("mixed-case words after a plain word are not a list",
                prose != null && prose.kind != Schema.Option.Kind.CHOICE);
        line("");

        line("=== 值里带空格也不行 ===");
        Schema.Option spaced = first("OPTIONS:\n"
                + "  -f <x>    format Options: FASTA files,EMBL entries\n");
        check("not a Choice",
                spaced == null || spaced.kind != Schema.Option.Kind.CHOICE);
        line("");

        line("  passed: " + pass + "   failed: " + fail);
        if (fail > 0) {
            line("");
            line("FAILURES PRESENT");
            System.exit(1);
        }
    }

    private static Schema.Option first(String help) {
        Schema s = HelpParser.parse(help, "probe");
        return s.options.isEmpty() ? null : s.options.get(0);
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
