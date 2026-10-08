package bioenv.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Decides how to run a command, and learns the answer per tool.
 *
 * <p>Two routes exist and they differ by two orders of magnitude in cost:
 *
 * <ul>
 *   <li><b>direct</b> — execute {@code <env>/bin/<command>} by absolute path,
 *       measured at about 4 ms</li>
 *   <li><b>through conda</b> — {@code conda run -p <env> <command>}, measured at
 *       about 2100 ms, almost all of it conda starting up</li>
 * </ul>
 *
 * <p>Probing only needs a program to reach its argument parser, so the direct
 * route is usually enough. It is not always enough: a command that is itself a
 * script may need the environment active in order to import its own libraries,
 * and then the direct call exits with a missing-module error instead of printing
 * help. That is detected rather than assumed (DESIGN.md P22).
 *
 * <h2>Why the answer is measured, not inferred</h2>
 *
 * Deciding by file type does not work. Two commands that are both Python scripts
 * were observed to disagree: one ran fine by absolute path and the other failed.
 * The only reliable test is to try it and look at what comes back.
 *
 * <h2>Why the answer is remembered</h2>
 *
 * The test costs one probe, and a tool is probed repeatedly across sessions
 * (help, subcommands, each option group). Remembering the verdict means the cost
 * is paid once per tool rather than once per probe.
 */
public final class RouteResolver {

    /** Which way a command must be run. */
    public enum Route {
        /** Absolute path: fast, and adequate for most commands. */
        DIRECT,
        /** Through conda: slower, but sets the environment up. */
        CONDA
    }

    private final EnvRunner runner;
    private final Map<String, Route> learned = new HashMap<String, Route>();
    private final Set<String> undecided = new HashSet<String>();

    public RouteResolver(EnvRunner runner) {
        this.runner = runner;
    }

    /**
     * Returns the route for a command, testing it the first time.
     *
     * <p>The test is one probe on the direct route. If that probe's output shows
     * the environment was missing, the command is marked for the conda route; if
     * the environment does not even expose the command as a file, conda is used
     * as well, since there is no direct path to take.
     */
    public Route routeFor(String envPrefix, String command) {
        if (envPrefix == null || command == null) {
            return Route.CONDA;
        }
        String key = envPrefix + "\u0000" + command;
        Route known = learned.get(key);
        if (known != null) {
            return known;
        }
        if (!undecided.add(key)) {
            // Already being decided on another thread; assume the safe route
            // rather than probing twice.
            return Route.CONDA;
        }
        Route decided = decide(envPrefix, command);
        learned.put(key, decided);
        return decided;
    }

    /**
     * Runs the command's help through the chosen route.
     *
     * <p>The point of the class: this is what the rest of the code calls, so the
     * route decision is made once and reused.
     */
    public String run(String envPrefix, String invocation) {
        String head = invocation.trim();
        int sp = head.indexOf(' ');
        if (sp > 0) {
            head = head.substring(0, sp);
        }
        Route r = routeFor(envPrefix, head);
        if (r == Route.DIRECT) {
            String out = runner.runDirect(envPrefix, invocation);
            // The verdict was reached earlier, but the environment may have
            // changed since; if the direct call now fails, fall back and relearn.
            if (!EnvRunner.looksLikeEnvironmentFailure(out)) {
                return out;
            }
            learned.put(envPrefix + "\u0000" + head, Route.CONDA);
        }
        return runner.runCapture(envPrefix, invocation);
    }

    /** Performs the one-time test for a command. */
    private Route decide(String envPrefix, String command) {
        if (!runner.hasBinary(envPrefix, command)) {
            // Not a file in the environment's bin/, so there is no direct path.
            return Route.CONDA;
        }
        String probe = runner.runDirect(envPrefix, command + " --help");
        if (probe == null || probe.trim().isEmpty()) {
            // No output at all from the direct route is not enough to judge;
            // use the safe route.
            return Route.CONDA;
        }
        return EnvRunner.looksLikeEnvironmentFailure(probe)
                ? Route.CONDA : Route.DIRECT;
    }

    /** The verdict recorded for a command, or null when not yet decided. */
    public Route knownRoute(String envPrefix, String command) {
        return learned.get(envPrefix + "\u0000" + command);
    }

    /** Number of commands whose route has been decided. */
    public int decidedCount() {
        return learned.size();
    }

    /** How many commands use each route, for reporting. */
    public Map<Route, Integer> summary() {
        Map<Route, Integer> m = new HashMap<Route, Integer>();
        for (Route r : learned.values()) {
            Integer n = m.get(r);
            m.put(r, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
        }
        return m;
    }

    /**
     * Restores previously learned verdicts.
     *
     * <p>Saved with the discovery cache, so a later session does not re-test every
     * command. The verdicts are invalidated with the rest of the cache when the
     * environments change.
     */
    public void loadVerdicts(Map<String, Route> saved) {
        if (saved != null) {
            learned.putAll(saved);
        }
    }

    /** The verdicts learned so far, for saving. */
    public Map<String, Route> verdicts() {
        return new HashMap<String, Route>(learned);
    }

    /** Forgets a verdict, so the next probe re-tests. */
    public void forget(String envPrefix, String command) {
        learned.remove(envPrefix + "\u0000" + command);
    }
}
