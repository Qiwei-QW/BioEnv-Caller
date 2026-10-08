package bioenv.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls a version number out of a tool's {@code --version} output.
 *
 * <p>Tools are wildly inconsistent here: {@code mafft} prints
 * {@code v7.525 (2024/Mar/13)}, {@code seqkit} prints {@code seqkit v2.13.0},
 * {@code iqtree2} prints a banner. Rather than special-casing each, scan for
 * the first plausible dotted version token.
 */
public final class VersionParser {

    /** Version-like token: optional v prefix, digits, dots, optional suffix. */
    private static final Pattern VERSION = Pattern.compile(
            "\\bv?(\\d+\\.\\d+(?:\\.\\d+)?(?:[._-]?[A-Za-z0-9]+)?)\\b");

    private VersionParser() {
    }

    /**
     * Returns a cleaned version string such as {@code 7.525}, or an empty
     * string when nothing version-like is present.
     */
    public static String extract(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || WslBridge.isCondaNoise(line)) {
                continue;
            }
            Matcher m = VERSION.matcher(line);
            if (m.find()) {
                String v = m.group(1);
                // Reject things that are obviously not versions, e.g. dates
                // like 2024.3 or bare decimals such as 0.5 in prose.
                if (looksLikeVersion(v)) {
                    return v;
                }
            }
        }
        return "";
    }

    private static boolean looksLikeVersion(String v) {
        if (v == null || v.isEmpty()) {
            return false;
        }
        // Require at least one dot: "7.525" yes, "7" no.
        if (v.indexOf('.') < 0) {
            return false;
        }
        // Reject four-digit leading years, e.g. 2024.3
        if (v.length() >= 4 && Character.isDigit(v.charAt(0))
                && Character.isDigit(v.charAt(1)) && Character.isDigit(v.charAt(2))
                && Character.isDigit(v.charAt(3))) {
            return false;
        }
        return true;
    }
}
