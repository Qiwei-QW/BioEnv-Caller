package bioenv.core;

import java.util.Map;

/**
 * Executes a resolved tool inside its conda environment.
 *
 * <p>Foreground execution with live output streaming and cooperative
 * cancellation: the user watches progress and can abort. Long jobs therefore
 * require TBtools to stay open.
 */
public final class Runner {

    private final Transport wsl;

    /** Handle for a job in flight, allowing the UI to request cancellation. */
    public static final class Job {
        private volatile Process process;
        private volatile boolean cancelled;
        private final long startedAt = System.currentTimeMillis();
        /** Names this run, so it can be stopped inside WSL. */
        private final String marker = "bioenv-"
                + Long.toHexString(System.nanoTime()) + "-"
                + Integer.toHexString((int) (Math.random() * 0xFFFF));

        /**
         * The transport used to stop the run on the far side, set once it is known.
         *
         * <p>Typed as {@link Transport}: cancelling is "issue a kill over a fresh
         * channel", which is a transport-level operation. Deliberately not
         * {@code WslBridge} — an SSH run must be able to kill its remote process
         * too, and there is no SSH special case to write here because each
         * implementation of {@link Transport#runDetached} opens its own channel.
         */
        private volatile Transport bridge;
        private volatile String distro;

        void attach(Process p) {
            this.process = p;
            if (cancelled && p != null) {
                p.destroyForcibly();
            }
        }

        /** Tells the job how to reach the far side, so cancelling can be done there. */
        void useBridge(Transport b, String distroName) {
            this.bridge = b;
            this.distro = distroName;
        }

        /** This run's marker, carried in the environment of everything it starts. */
        public String marker() {
            return marker;
        }

        /**
         * Stops the run.
         *
         * <p>Done on the far side, because the tool is a Linux process in another
         * session and terminating the local client does not touch it — the client
         * and the tool are not in one process tree. The marker says which processes
         * over there are this run's.
         *
         * <p>The kill goes out over {@link Transport#runDetached}, which each
         * transport implements on a channel of its own: stopping an SSH run must
         * not reuse the connection the run itself occupies, and neither must the
         * WSL case. Keeping that inside the transport is what leaves this method
         * free of per-channel branching.
         *
         * <p>The client is terminated as well, so the wait for output returns instead
         * of blocking until the far side notices.
         */
        public void cancel() {
            cancelled = true;
            Transport b = bridge;
            if (b != null) {
                try {
                    b.runDetached(CommandBuilder.killScript(marker), distro);
                } catch (Exception ignored) {
                    // Falling back to killing the client is better than nothing: the
                    // wait returns, and the far side is left to finish on its own.
                    // This also absorbs the IOException runDetached declares.
                }
            }
            Process p = process;
            if (p != null) {
                p.destroyForcibly();
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public long elapsedMillis() {
            return System.currentTimeMillis() - startedAt;
        }
    }

    /** Outcome of a run. */
    public static final class RunResult {
        public int exitCode = -1;
        public boolean cancelled;
        public long elapsedMillis;
        public String commandPreview = "";

        public boolean ok() {
            return exitCode == 0 && !cancelled;
        }
    }

    /**
     * @param wsl how to reach the Linux side; typed as {@link Transport} so a run
     *            behaves identically over WSL and SSH
     */
    public Runner(Transport wsl) {
        this.wsl = wsl;
    }

    /**
     * Runs a tool, streaming output to {@code sink}.
     *
     * <p>The command is assembled by {@link CommandBuilder} from the tool's
     * discovered schema, so this method knows nothing about any particular tool.
     */
    public RunResult run(Tool tool, Schema schema, String subPath,
                         Map<String, ParamValue> values, String rawArgs,
                         String condaRoot, final Job job,
                         final Transport.LineSink sink) {
        RunResult res = new RunResult();
        long t0 = System.currentTimeMillis();

        // The run names itself, so it can be stopped inside WSL. Killing the wsl.exe
        // client leaves the tool running — a Linux process is not in that process
        // tree — so stopping has to be done on the far side, and this is what says
        // which processes over there belong to this run.
        String marker = job == null ? null : job.marker();
        String script = CommandBuilder.buildScript(tool, schema, subPath, values,
                rawArgs, condaRoot, marker);
        res.commandPreview = CommandBuilder.preview(tool, schema, subPath, values,
                rawArgs);

        return execute(tool, script, res, t0, job, sink);
    }

    /**
     * Runs a command line the user typed themselves.
     *
     * <p>Someone who knows the tool should not have to express their intent through
     * the form. The typed line is what runs, reached through the same conda wrapper
     * as a generated one, so routing and environment selection behave identically.
     *
     * <p>The working directory is honoured here too. It is a setting of the run
     * rather than part of the command text, and a typed command full of relative
     * paths needs it just as much as a generated one — leaving it out meant a
     * hand-written command silently ran somewhere else, and every relative path in
     * it failed.
     *
     * @param commandLine the command without the wrapper, exactly as typed
     * @param workdir     directory to run in, or null for the default
     */
    public RunResult runTyped(Tool tool, String commandLine, String condaRoot,
                              String workdir, final Job job,
                              final Transport.LineSink sink) {
        RunResult res = new RunResult();
        long t0 = System.currentTimeMillis();
        res.commandPreview = commandLine;
        String script = CommandBuilder.wrapTyped(tool, commandLine, condaRoot, workdir);
        return execute(tool, script, res, t0, job, sink);
    }

    /** Runs a typed command in the default directory. */
    public RunResult runTyped(Tool tool, String commandLine, String condaRoot,
                              final Job job, final Transport.LineSink sink) {
        return runTyped(tool, commandLine, condaRoot, null, job, sink);
    }

    /** Runs an assembled script and collects its output. */
    private RunResult execute(Tool tool, String script, RunResult res, long t0,
                              final Job job, final Transport.LineSink sink) {
        // Echo the exact command so the log is self-documenting and a failure
        // can be reproduced verbatim outside TBtools.
        if (sink != null) {
            sink.line("$ " + res.commandPreview, false);
            sink.line("", false);
        }

        Transport.Result r;
        // Told before the work starts, so a cancellation arriving during the run can
        // reach the far side. Handed the bridge and its distribution rather than
        // looking them up later, because by then the run may already be over.
        if (job != null) {
            job.useBridge(wsl, wsl.getDistro());
        }
        try {
            r = wsl.runBashStreaming(script, new Transport.LineSink() {
                @Override
                public void line(String text, boolean isError) {
                    // Hide conda's own wrapper diagnostics so the user sees the
                    // tool's real error rather than conda noise.
                    if (isError && WslBridge.isCondaNoise(text)) {
                        return;
                    }
                    if (sink != null) {
                        sink.line(text, isError);
                    }
                }
            });
        } catch (Exception e) {
            if (sink != null) {
                sink.line("Failed to start WSL process: " + e.getMessage(), true);
            }
            res.exitCode = -1;
            res.elapsedMillis = System.currentTimeMillis() - t0;
            return res;
        }

        res.exitCode = r.exitCode();
        res.cancelled = job != null && job.isCancelled();
        res.elapsedMillis = System.currentTimeMillis() - t0;
        return res;
    }
}
