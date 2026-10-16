package bioenv.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds a shell command from a {@link Schema} and the values a user expressed.
 *
 * <p>Driven entirely by the schema the tool's own help produced, so it works for
 * any command, single-level or multi-level (DESIGN.md P1, P5).
 *
 * <h2>Only expressed intent reaches the command line</h2>
 *
 * An option appears only when the user filled it in or chose it
 * (DESIGN.md P12). Options left untouched are omitted entirely, so a tool's own
 * defaults apply. This replaces an earlier behaviour that wrote every
 * help-stated default into the command, which produced long commands nobody
 * wrote and passed values that were never meant to be arguments.
 *
 * <h2>Arguments look hand-written</h2>
 *
 * Values are quoted only when they need to be, and switches contribute their
 * flag alone (DESIGN.md P13).
 *
 * <h2>Argument order</h2>
 *
 * Subcommand, then named options, then positionals. Many tools are shell
 * wrappers that treat a leading path as an unknown option, so emitting options
 * before positionals avoids a whole class of confusing failures.
 */
public final class CommandBuilder {

    /**
     * Builds the full bash script that runs a tool.
     *
     * @param tool      the tool to run; its {@code envPrefix} must be resolved
     * @param schema    schema the values were collected against
     * @param subPath   subcommand path, e.g. {@code "stats"}; empty for none
     * @param values    user-expressed values keyed by option flag
     * @param rawArgs   free-form arguments appended verbatim
     * @param condaRoot conda prefix used to prime {@code PATH}
     */
    /**
     * Wraps a command line the user typed, so it runs inside the tool's conda
     * environment.
     *
     * <p>The environment still matters for a hand-written command — the tool is
     * only on PATH inside its own environment — so the typed line goes through the
     * same {@code conda run} wrapper as a generated one. What is <em>not</em> added
     * is the program name: the preview shows the command the user asked for, so
     * they will have written the program name themselves. If they left it out, it
     * is supplied, because a command line that names no program cannot run.
     *
     * <p>The working directory <em>is</em> honoured. It is a setting of the run, not
     * part of the command text, and a typed command containing relative paths needs
     * it exactly as a generated one does. Leaving it out meant a hand-written
     * command silently lost the directory the user had set, and every relative path
     * in it failed to resolve.
     *
     * @param commandLine the typed command, without any wrapper
     * @param condaRoot   conda prefix used to prime {@code PATH}
     * @param workdir     directory to run in, or null for the default
     */
    public static String wrapTyped(Tool tool, String commandLine, String condaRoot,
                                   String workdir) {
        String typed = commandLine == null ? "" : commandLine.trim();
        // Strip a trailing continuation or blank lines, which are layout rather
        // than part of the command.
        while (typed.endsWith("\\") || typed.endsWith("\n")) {
            typed = typed.substring(0, typed.length() - 1).trim();
        }
        if (typed.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        if (condaRoot != null && !condaRoot.isEmpty()) {
            sb.append("export PATH=").append(WslBridge.quote(condaRoot + "/bin"))
              .append(":$PATH\n");
        }

        // A directory the user chose, so a relative path in the typed command means
        // what they expect it to mean.
        if (workdir != null && !workdir.trim().isEmpty()) {
            sb.append("cd ").append(WslBridge.quote(WslBridge.toWslPath(workdir)))
              .append(" || exit 1\n");
        }

        // A command whose first word is already the program needs no name added;
        // anything else is taken to be arguments and gets the name prefixed.
        sb.append("conda run -p ").append(WslBridge.quote(tool.envPrefix))
          .append(" --no-capture-output ");
        if (startsWithProgram(typed, tool.command)) {
            sb.append(typed);
        } else {
            sb.append(WslBridge.quoteIfNeeded(tool.command)).append(' ').append(typed);
        }
        sb.append('\n');
        return sb.toString();
    }

    /** Wraps a typed command without a working directory. */
    public static String wrapTyped(Tool tool, String commandLine, String condaRoot) {
        return wrapTyped(tool, commandLine, condaRoot, null);
    }

    /** True when a command line already begins with the program's own name. */
    static boolean startsWithProgram(String typed, String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        // Compare only the first word, ignoring any leading path the user wrote.
        String first = typed.split("\\s+", 2)[0];
        String base = first;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        return base.equals(command);
    }

    public static String buildScript(Tool tool, Schema schema, String subPath,
                                     Map<String, ParamValue> values, String rawArgs,
                                     String condaRoot) {
        return buildScript(tool, schema, subPath, values, rawArgs, condaRoot, null);
    }

    /**
     * The script, with a marker naming this run.
     *
     * <h2>Why a marker</h2>
     *
     * Stopping a run means killing it inside WSL. Killing the {@code wsl.exe} process
     * on the Windows side does nothing: the tool is a Linux process in another
     * session, and the two are not one process tree, so terminating the client leaves
     * the work running. What works is a second {@code wsl} call that runs {@code pkill}
     * on the far side.
     *
     * <p>Something has to say <em>which</em> processes to kill. A marker in the run's
     * own environment is that answer, and it stops only this run: a
     * {@code pkill -f orthofinder} would also take down an orthofinder the user
     * started in their own terminal, which is not the plugin's to stop.
     *
     * @param marker the run's unique marker, or null for none
     */
    public static String buildScript(Tool tool, Schema schema, String subPath,
                                     Map<String, ParamValue> values, String rawArgs,
                                     String condaRoot, String marker) {
        StringBuilder sb = new StringBuilder();

        // Non-interactive shells do not source the profile, so conda must be put
        // on PATH explicitly or `conda run` will not be found.
        if (condaRoot != null && !condaRoot.isEmpty()) {
            sb.append("export PATH=").append(WslBridge.quote(condaRoot + "/bin"))
              .append(":$PATH\n");
        }

        // The marker is exported before the tool starts, so every process in the chain
        // inherits it — conda, the tool, and whatever the tool spawns.
        if (marker != null && !marker.isEmpty()) {
            sb.append("export ").append(MARKER_ENV).append('=')
              .append(WslBridge.quote(marker)).append('\n');
        }

        String workdir = workdir(values);
        if (workdir != null) {
            sb.append("cd ").append(WslBridge.quote(workdir)).append(" || exit 1\n");
        }

        // Create output directories up front: a missing parent produces a
        // tool-specific error that is harder to understand than a failed mkdir.
        for (String dir : outputDirectories(schema, values)) {
            sb.append("mkdir -p ").append(WslBridge.quote(dir)).append('\n');
        }

        sb.append(commandLine(tool, schema, subPath, values, rawArgs));
        sb.append('\n');
        return sb.toString();
    }

    /** The environment variable a run's marker is carried in. */
    public static final String MARKER_ENV = "BIOENV_JOB";

    /**
     * The command that stops a run, run inside WSL.
     *
     * <p>Reads the marker out of each process's own environment, so only this run's
     * processes are matched. A pattern search over command lines would match on text
     * the user could have typed anywhere; the environment is the run's own record of
     * itself.
     */
    public static String killScript(String marker) {
        if (marker == null || marker.isEmpty()) {
            return "";
        }
        String quoted = WslBridge.quote(marker);
        StringBuilder sb = new StringBuilder();
        // Terms first: a tool that is asked politely stops cleaning up after itself.
        // Then the rest of the chain, so nothing is left holding the terminal.
        sb.append("for p in $(pgrep -f . 2>/dev/null); do\n");
        sb.append("  if [ -r /proc/$p/environ ] && ")
          .append("tr '\\0' '\\n' < /proc/$p/environ 2>/dev/null | grep -qx ")
          .append(WslBridge.quote(MARKER_ENV + "=" + marker)).append("; then\n");
        sb.append("    kill -TERM $p 2>/dev/null\n");
        sb.append("  fi\n");
        sb.append("done\n");
        sb.append("sleep 1\n");
        sb.append("for p in $(pgrep -f . 2>/dev/null); do\n");
        sb.append("  if [ -r /proc/$p/environ ] && ")
          .append("tr '\\0' '\\n' < /proc/$p/environ 2>/dev/null | grep -qx ")
          .append(WslBridge.quote(MARKER_ENV + "=" + marker)).append("; then\n");
        sb.append("    kill -KILL $p 2>/dev/null\n");
        sb.append("  fi\n");
        sb.append("done\n");
        sb.append("exit 0\n");
        return sb.toString();
    }

    /** The command line itself, without the surrounding script scaffolding. */
    public static String commandLine(Tool tool, Schema schema, String subPath,
                                     Map<String, ParamValue> values, String rawArgs) {
        StringBuilder sb = new StringBuilder();
        sb.append("conda run -p ").append(WslBridge.quote(tool.envPrefix))
          .append(" --no-capture-output ").append(WslBridge.quoteIfNeeded(tool.command));

        if (subPath != null && !subPath.trim().isEmpty()) {
            for (String part : subPath.trim().split("\\s+")) {
                sb.append(' ').append(WslBridge.quoteIfNeeded(part));
            }
        }

        for (String a : buildArgs(schema, values)) {
            sb.append(' ').append(a);
        }

        if (rawArgs != null && !rawArgs.trim().isEmpty()) {
            // Appended unquoted on purpose: the user is expected to write
            // shell-ready text here, including quoting and redirection.
            sb.append(' ').append(flattenToLine(rawArgs));
        }
        return sb.toString();
    }

    /**
     * Turns free-form arguments into one line.
     *
     * <p>The box takes one argument per line, which is easier to read and to edit
     * than a single long line. A shell treats a newline as a separator, so the
     * command would run either way — but it would also be <em>shown</em> across
     * several lines, and the preview is meant to read as the one command it is.
     *
     * <p>Blank lines are dropped and each line is trimmed, so a stray empty line
     * does not become a run of spaces in the middle of the command.
     */
    public static String flattenToLine(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * Collects arguments for the options the user expressed.
     *
     * <p>Named options come before positionals; see the class comment for why.
     */
    public static List<String> buildArgs(Schema schema, Map<String, ParamValue> values) {
        List<String> args = new ArrayList<String>();
        for (ShownArg a : shownArgs(schema, values)) {
            args.add(a.text);
        }
        return args;
    }

    /**
     * One argument of the previewed command, with what is known about its origin.
     *
     * <p>A caller that presents the command in its own way — writing a long path
     * as its last name, say — needs to know which arguments are values and which
     * of those name a location. A flag's spelling is the tool's own and must be
     * shown exactly as it will be passed; a value that is not a path is not a
     * location either. Judging that here, where the schema is in hand, keeps a
     * display layer from guessing at a command's text.
     */
    public static final class ShownArg {

        /** The argument as it appears in the command, quoting included. */
        public final String text;

        /** True when this argument is an option's value rather than a flag. */
        public final boolean value;

        /**
         * True when the argument names something on disk: the option declares a
         * path kind, or the value was written as a path.
         */
        public final boolean path;

        ShownArg(String text, boolean value, boolean path) {
            this.text = text;
            this.value = value;
            this.path = path;
        }
    }

    /**
     * The arguments for the options the user expressed, each described.
     *
     * <p>{@link #buildArgs} is this list read as plain text. The two share one
     * traversal of the schema, so a rule about which arguments appear — emitted
     * once each, in this order — cannot drift between the command that runs and
     * the account a display gives of it.
     */
    public static List<ShownArg> shownArgs(Schema schema,
                                           Map<String, ParamValue> values) {
        List<ShownArg> args = new ArrayList<ShownArg>();
        if (schema == null || values == null) {
            return args;
        }

        // Named options first. Each flag is emitted once even when the help lists it
        // more than once — some documents describe the same flag twice, and both
        // entries share one key, so the value would otherwise be written twice and
        // the tool would see the option repeated.
        java.util.Set<String> emitted = new java.util.HashSet<String>();
        for (Schema.Option o : schema.options) {
            if (o.flag.isEmpty()) {
                continue;
            }
            if (!emitted.add(o.flag)) {
                continue;
            }
            ParamValue v = values.get(o.flag);
            if (v == null || !v.contributes()) {
                continue;
            }
            args.add(new ShownArg(WslBridge.quoteIfNeeded(o.flag), false, false));
            if (!o.isSwitch) {
                args.add(valueArg(o, v));
            }
        }

        // Positionals last, in schema order; also once each.
        java.util.Set<String> positionalDone = new java.util.HashSet<String>();
        for (Schema.Option o : schema.options) {
            if (!o.flag.isEmpty() || o.isSwitch) {
                continue;
            }
            String pkey = positionalKey(o);
            if (!positionalDone.add(pkey)) {
                continue;
            }
            ParamValue v = values.get(pkey);
            if (v == null || !v.contributes()) {
                continue;
            }
            args.add(valueArg(o, v));
        }
        return args;
    }

    /** The stored key for an option: its flag, or its label when positional. */
    public static String key(Schema.Option o) {
        return o == null ? "" : o.key();
    }

    private static String positionalKey(Schema.Option o) {
        return "@" + o.label();
    }

    /**
     * One option's value, with paths translated and quoting applied.
     *
     * <p>A path is translated when it looks like one, and when the option is known
     * to hold a path. Judging from the text matters because the form starts every
     * option as a plain text field, so most values carry no kind at all: a Windows
     * path typed into such a field used to be passed through unchanged, and the
     * tool inside WSL could not open it.
     *
     * <p>It must not fire on ordinary values, though. {@code --mode msa} is not a
     * path, and rewriting it would break the command outright — hence the shape
     * test rather than a blanket translation.
     *
     * <p>The returned argument also says whether it names a location, because a
     * caller that shows the command may want to write it shorter and must not do
     * so for anything else.
     */
    private static ShownArg valueArg(Schema.Option o, ParamValue v) {
        String raw = v.commandText();
        boolean declared = declaresPath(o.kind);
        boolean windows = looksLikeWindowsPath(raw);
        String text = raw;
        if (declared) {
            // Every path in the value, not just the first. A list kind carries several
            // separated by commas, and a Windows path is no more usable for being the
            // second one in a list.
            text = translatePaths(raw);
        } else if (windows) {
            text = WslBridge.toWslPath(raw);
        }
        // A path already written in POSIX form is a location too, and the file
        // chooser is not the only way one arrives: the field takes typing.
        boolean path = declared || windows || text.startsWith("/");
        return new ShownArg(quoteValue(text), true, path);
    }

    /**
     * Translates each path in a comma-separated value.
     *
     * <p>Only the pieces that are paths are touched: the separators are kept as they
     * are, and so is any piece that does not look like a Windows path.
     */
    public static String translatePaths(String text) {
        if (text == null || text.indexOf(',') < 0) {
            return WslBridge.toWslPath(text);
        }
        StringBuilder sb = new StringBuilder();
        for (String piece : text.split(",", -1)) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(WslBridge.toWslPath(piece));
        }
        return sb.toString();
    }

    /**
     * True when a kind holds one or more paths.
     *
     * <p>All of them are translated the same way, and for the same reason: a path
     * chosen on Windows is not one the tool can open, however many are in the list.
     * The list kinds are included because a comma-separated list of Windows paths is
     * exactly as unusable as a single one.
     */
    public static boolean declaresPath(Schema.Option.Kind kind) {
        if (kind == null) {
            return false;
        }
        switch (kind) {
            case INPUT_PATH:
            case OUTPUT_PATH:
            case PATH_LIST:
            case DIRECTORY_PATH:
                return true;
            default:
                return false;
        }
    }

    /**
     * Quotes a value for the shell.
     *
     * <p>A value that begins with a dash is a value, not an option — the user typed
     * it after a flag that takes one. Left bare it would be read as the next option,
     * so it is quoted to keep the tool from seeing it that way. Everything else
     * follows the ordinary rule of quoting only when the text needs it.
     */
    public static String quoteValue(String text) {
        if (text != null && text.startsWith("-") && !text.startsWith("'")) {
            return "'" + text.replace("'", "'\\''") + "'";
        }
        return WslBridge.quoteIfNeeded(text);
    }

    /**
     * True when a value is written as a Windows path.
     *
     * <p>Recognised by its shape alone: a drive letter followed by a separator
     * ({@code C:\...} or {@code C:/...}), or a UNC share ({@code \\wsl$\...}). A
     * value with no such prefix is left exactly as typed, because most values are
     * not paths and translating them would corrupt the command.
     */
    public static boolean looksLikeWindowsPath(String text) {
        if (text == null || text.length() < 3) {
            return false;
        }
        String t = text.trim();
        // A drive letter, a colon, then a separator.
        if (t.length() >= 3 && Character.isLetter(t.charAt(0)) && t.charAt(1) == ':'
                && (t.charAt(2) == '\\' || t.charAt(2) == '/')) {
            return true;
        }
        // A UNC path to a WSL share.
        return t.startsWith("\\\\wsl$\\") || t.startsWith("\\\\wsl.localhost\\");
    }

    /**
     * Output directories that must exist before the tool runs.
     *
     * <p>A missing parent produces a tool-specific error that is harder to
     * understand than a failed {@code mkdir}, so the directory is created first.
     *
     * <p>An option marked as an output file is taken at its word. Otherwise a value
     * is judged by its shape: a Windows path ending in a file name names a
     * destination, and its folder has to exist. Without this the automatic
     * {@code mkdir} only ever fired for options the user had explicitly retyped as
     * output files, which, since every option starts as plain text, was almost
     * none of them.
     */
    private static List<String> outputDirectories(Schema schema,
                                                  Map<String, ParamValue> values) {
        List<String> out = new ArrayList<String>();
        if (schema == null || values == null) {
            return out;
        }
        for (Schema.Option o : schema.options) {
            ParamValue v = values.get(key(o));
            if (v == null || !v.contributes()) {
                continue;
            }
            // Every path kind that names a file to be written, plus any value that
            // simply looks like a Windows path — the user may type one into a field
            // whose kind says nothing about paths.
            boolean declaredOutput = declaresOutput(o.kind);
            String raw = v.commandText();
            if (!declaredOutput && !looksLikeWindowsPath(raw)) {
                continue;
            }
            String wsl = WslBridge.toWslPath(raw);
            // A directory the user named has nothing to create; only a path with a
            // file name after it implies a folder that may be missing.
            int slash = wsl.lastIndexOf('/');
            if (slash <= 0) {
                continue;
            }
            String dir = wsl.substring(0, slash);
            // Create it only when it is absent, so the command stays quiet about
            // directories that already exist.
            if (!out.contains(dir)) {
                out.add(dir);
            }
        }
        return out;
    }

    /**
     * True when a kind names something to be written, so its folder may need making.
     *
     * <p>A list of output files is included, and every path in it is looked at: a
     * tool asked to write several files needs each folder to exist, not just the
     * first.
     */
    public static boolean declaresOutput(Schema.Option.Kind kind) {
        return kind == Schema.Option.Kind.OUTPUT_PATH
                || kind == Schema.Option.Kind.PATH_LIST;
    }

    /**
     * The directory the command should run in, or null to leave the default.
     *
     * <p>Previously this defaulted to the input file's folder or the home
     * directory. Both add noise to the command and, worse, an implicit
     * {@code cd} changes the meaning of relative paths the user typed. Only an
     * explicit choice produces a {@code cd}.
     */
    private static String workdir(Map<String, ParamValue> values) {
        if (values == null) {
            return null;
        }
        ParamValue wd = values.get("__workdir__");
        if (wd == null || !wd.contributes()) {
            return null;
        }
        return WslBridge.toWslPath(wd.commandText());
    }

    /** Human-readable preview shown before running. */
    public static String preview(Tool tool, Schema schema, String subPath,
                                 Map<String, ParamValue> values, String rawArgs) {
        return commandLine(tool, schema, subPath, values, rawArgs);
    }

    /**
     * The part of the command the user actually expressed: the tool, its
     * subcommand, their options, and any raw arguments.
     *
     * <p>This is what the preview pane shows. The conda wrapper
     * ({@code conda run -p <env> --no-capture-output}) is an implementation
     * detail of how the plugin reaches the tool, not something the user asked
     * for, so presenting it would bury their own arguments in noise
     * (DESIGN.md P13). The full command is still echoed into the log when a run
     * starts, because reproducing a failure requires it.
     *
     * <p>Example of what this returns:
     * <pre>
     *   orthofinder -f pep -M msa -T fasttree -t 12
     * </pre>
     */
    public static String userFacingPreview(Tool tool, Schema schema, String subPath,
                                           Map<String, ParamValue> values,
                                           String rawArgs) {
        return assemble(tool.command, subPath, buildArgs(schema, values), rawArgs);
    }

    /**
     * 只要"调用"那一段：程序名加子命令，不含任何参数。
     *
     * <p>预览里这一段是<b>锁住的</b>：它跟着表单走，用户改不动。于是"预览上写的是哪个
     * 工具"永远等于左边选中的那个 —— 不会出现"看着是 A、实际跑 B"。
     * 用户能在它后面随便写参数、管道、别的什么。
     *
     * <p>复用 {@link #assemble}：拼接顺序（程序名、子命令、参数、自由文本）只写一份，
     * 这里传空参数就是"只有调用"。
     */
    public static String invocation(Tool tool, String subPath) {
        if (tool == null) {
            return "";
        }
        return assemble(tool.command, subPath, java.util.Collections.<String>emptyList(), "");
    }

    /**
     * Joins a program, an optional subcommand, arguments and raw text into the
     * one line the preview shows.
     *
     * <p>Shared by the command as it will run and any other account of it, so the
     * ordering rules — subcommand first, then arguments, then whatever was typed
     * free-form — have a single statement.
     */
    static String assemble(String program, String subPath, List<String> args,
                           String rawArgs) {
        StringBuilder sb = new StringBuilder();
        sb.append(WslBridge.quoteIfNeeded(program));

        if (subPath != null && !subPath.trim().isEmpty()) {
            for (String part : subPath.trim().split("\\s+")) {
                sb.append(' ').append(WslBridge.quoteIfNeeded(part));
            }
        }
        for (String a : args) {
            sb.append(' ').append(a);
        }
        if (rawArgs != null && !rawArgs.trim().isEmpty()) {
            sb.append(' ').append(flattenToLine(rawArgs));
        }
        return sb.toString();
    }
}
