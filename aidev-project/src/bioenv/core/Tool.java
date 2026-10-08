package bioenv.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A tool discovered inside a conda environment.
 *
 * <p>This type carries only what discovery can observe: where the command
 * lives and which environment provides it. It deliberately holds no
 * hand-written knowledge about the tool — its parameters come from
 * {@link SchemaBuilder} parsing the tool's own help output.
 *
 * <p>See DESIGN.md P1 (no hard-coded tool names) and P4 (usable without prior
 * knowledge).
 */
public final class Tool {

    /** Command name as invoked, e.g. the executable's basename. */
    public String command;
    /** Absolute path of the conda environment prefix providing the command. */
    public String envPrefix;
    /** Environment name, for display. */
    public String envName;
    /**
     * The conda package this command came from.
     *
     * <p>Kept so the tree can group the commands one package provides. A package
     * may legitimately ship a great many — EMBOSS alone installs several hundred
     * programs — and listing them flat buries everything else.
     */
    public String packageName = "";
    /**
     * Version of {@link #packageName}, as conda records it.
     *
     * <p>Decides what a rescan does with this tool: a different version means the
     * tool changed, so its help is read again from scratch; the same version means
     * nothing about it changed, so its schema and every setting the user made in it
     * are kept as they are.
     *
     * <p>Deliberately the <em>package</em> version rather than {@link #version}:
     * the latter is only known after the tool has been probed, which is exactly the
     * work a rescan is trying to avoid. Empty when the package is unknown — in that
     * case nothing is assumed and the tool is kept.
     */
    public String packageVersion = "";
    /** Version string when it could be probed; empty otherwise. */
    public String version = "";

    /** Parameter schema discovered from help; never null, possibly empty. */
    public Schema schema;

    /** Paths of any deprecated/legacy helper, kept for diagnostics. */
    public final List<String> notes = new ArrayList<String>();

    public Tool() {
    }

    public Tool(String command, String envName, String envPrefix) {
        this.command = command;
        this.envName = envName;
        this.envPrefix = envPrefix;
    }

    /**
     * Label for the tool tree: the command, and nothing else.
     *
     * <p>The version used to be appended once it had been probed. Probing happens
     * when a tool is first opened, so the name grew a suffix only after the user had
     * visited it — and only became visible on the next rebuild of the list, which
     * made it appear to change by itself. A name that reads one way before you click
     * it and another way afterwards is worse than no version at all.
     *
     * <p>{@link #version} is still recorded: the cache keeps it, and the self-test
     * reports it. It is simply not part of the name.
     */
    public String label() {
        return command;
    }

    /**
     * True when the tool exposes subcommands rather than (or in addition to)
     * plain options — samtools, bcftools, seqkit, git and friends.
     */
    public boolean isMultiLevel() {
        return schema != null && !schema.subcommands.isEmpty();
    }

    /**
     * True when nothing could be learned from help, so the UI must fall back to
     * raw command-line entry (DESIGN.md P6).
     */
    public boolean isOpaque() {
        return schema == null || (!schema.hasOptions() && schema.subcommands.isEmpty());
    }

    @Override
    public String toString() {
        return command + "@" + envName;
    }
}
