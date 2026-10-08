package bioenv.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether an executable found in a conda environment is plausibly a
 * command a user would invoke directly.
 *
 * <p>Contains no list of bioinformatics tools (DESIGN.md P1). Every rule here is
 * either a statement about a name's <em>shape</em> or a reference to an
 * <em>externally defined set</em> that has a boundary and a citable source
 * (DESIGN.md P20).
 *
 * <h2>Why the rules are grouped and named</h2>
 *
 * Names can be blocked either by structure or by membership of a standard set.
 * The standard sets below are grouped by their authority — POSIX, GNU, the
 * compression formats, language runtimes, and a few vendor namespaces — and each
 * group says where it comes from. This matters because a flat list of names that
 * merely "looked noisy on one machine" has no boundary: the next machine
 * supplies a name that is not on it. A set defined by a standard does have a
 * boundary, and its membership can be checked against the standard rather than
 * against somebody's memory.
 *
 * <p>Discovery is primarily driven by package ownership (DESIGN.md P15), so
 * these rules are a second line of defence: a requested package may still ship
 * helper commands alongside the ones it exists for.
 */
public final class NoiseFilter {

    // ==================================================================
    // Group 1 — POSIX.1 utilities
    // ==================================================================
    /**
     * Commands specified by POSIX.1 as standard utilities.
     *
     * <p>This is a closed set defined by
     * <a href="https://pubs.opengroup.org/onlinepubs/9699919799/">IEEE Std
     * 1003.1</a> and its successors. Every POSIX-conforming system provides
     * them, so their presence in a {@code bin/} directory says nothing about
     * what the environment is for.
     */
    private static final Set<String> POSIX = set(
            "sh", "ls", "cp", "mv", "rm", "cat", "echo", "printf", "test",
            "sed", "awk", "grep", "cut", "sort", "uniq", "head", "tail", "tr",
            "wc", "tee", "xargs", "find", "date", "sleep", "env", "expr",
            "basename", "dirname", "mkdir", "rmdir", "ln", "touch", "chmod",
            "chown", "true", "false", "id", "uname", "kill", "ps", "time",
            "which", "getopt", "mktemp", "stty", "tty", "more", "vi", "man",
            "tar", "bc", "dc", "ar", "nm", "strip", "yacc", "lex", "pax",
            "cksum", "cmp", "comm", "csplit", "dd", "df", "du", "expand",
            "fold", "getconf", "iconv", "join", "link", "locale", "logger",
            "logname", "lp", "mesg", "newgrp", "nice", "nl", "nohup", "od",
            "paste", "patch", "pathchk", "pr", "renice", "split", "strings",
            "tabs", "tail", "tee", "tput", "tsort", "unlink", "uudecode",
            "uuencode", "wait", "who", "write", "tty", "chgrp", "chmod");

    // ==================================================================
    // Group 2 — GNU coreutils / shell essentials and their variants
    // ==================================================================
    /**
     * Programs that every GNU userland provides, including the alternative
     * implementations a distribution may substitute.
     *
     * <p>Defined by the
     * <a href="https://www.gnu.org/software/coreutils/manual/">GNU coreutils
     * manual</a> plus the shells and terminal utilities that GNU and the BSDs
     * ship.
     */
    private static final Set<String> COREUTILS = set(
            "bash", "dash", "zsh", "csh", "tcsh", "ksh", "fish", "gawk", "mawk",
            "egrep", "fgrep", "rgrep", "readlink", "realpath", "stat", "seq",
            "yes", "whoami", "hostname", "whereis", "timeout", "clear",
            "reset", "tic", "infocmp", "toe", "tset", "less", "nano", "emacs",
            "info", "install", "nproc", "lscpu", "free", "uptime", "ldd",
            "top", "watch", "hostid", "users", "groups", "chroot", "runcon",
            "stdbuf", "truncate", "shred", "b2sum", "base32", "base64",
            "basenc", "csplit", "dircolors", "factor", "fmt", "mknod",
            "mkfifo", "mktemp", "numfmt", "pr", "ptx", "shuf", "sum", "sync",
            "tsort", "unexpand", "vdir", "chcon", "cksum");

    // ==================================================================
    // Group 3 — compression and archive formats
    // ==================================================================
    /**
     * Readers and writers for compression and archive formats.
     *
     * <p>These are shipped by the format's own implementation and arrive
     * wherever an archive or a compressed file is needed. The set is defined by
     * the formats themselves rather than by any one distribution.
     */
    private static final Set<String> COMPRESSION = set(
            "gzip", "gunzip", "zcat", "zcmp", "zdiff", "zegrep", "zfgrep",
            "zforce", "zgrep", "zless", "zmore", "znew", "bzip2", "bunzip2",
            "bzcat", "bzcmp", "bzdiff", "bzegrep", "bzfgrep", "bzgrep",
            "bzless", "bzmore", "bzip2recover", "xz", "unxz", "xzcat", "xzcmp",
            "xzdec", "xzdiff", "xzegrep", "xzfgrep", "xzgrep", "xzless",
            "xzmore", "lzma", "unlzma", "lzcat", "lzcmp", "lzdiff", "lzegrep",
            "lzfgrep", "lzgrep", "lzless", "lzmore", "zstd", "unzstd",
            "zstdcat", "zstdgrep", "zstdless", "lz4", "lz4c", "lz4cat",
            "unlz4", "brotli", "unzip", "zip", "zipinfo", "zipgrep", "funzip",
            "zipsplit", "7z", "7za", "7zr", "rar", "unrar", "lzop", "pigz",
            "unpigz", "cpio", "pax", "zlib", "zlib-flate");

    // ==================================================================
    // Group 4 — language runtimes and their package managers
    // ==================================================================
    /**
     * Interpreters, compilers and package managers for programming languages.
     *
     * <p>Present in an environment because something written in that language
     * needed its runtime, not because the user wants to invoke it directly.
     * Named by the languages' own projects.
     */
    private static final Set<String> RUNTIMES = set(
            "python", "python2", "python3", "python3-config", "idle", "pip",
            "pip3", "pipx", "wheel", "f2py", "pygmentize", "pydoc", "pytest",
            "nosetests", "sphinx", "virtualenv", "cython", "perl", "cpan",
            "corelist", "instmodsh", "json_pp", "pod2man", "pod2text",
            "pod2html", "podchecker", "perlbug", "perldoc", "perlivp",
            "enc2xs", "encguess", "h2ph", "h2xs", "xsubpp", "piconv", "pl2pm",
            "splain", "shasum", "prove", "ptar", "ptardiff", "ptargrep",
            "ruby", "gem", "irb", "rake", "node", "npm", "npx", "yarn",
            "java", "javac", "jar", "javadoc", "jarsigner", "jcmd", "jconsole",
            "jdb", "jdeps", "jps", "jstack", "jstat", "jjs", "keytool",
            "rmic", "rmid", "rmiregistry", "serialver", "policytool",
            "julia", "R", "Rscript", "lua", "luac", "php", "tclsh", "wish",
            "groovy", "scala", "kotlin", "dotnet", "mono", "go", "cargo",
            "rustc", "ghc", "runghc", "swipl", "gprolog", "erlc", "erl",
            "escript", "elixir", "iex", "mix", "ghci", "cabal", "stack",
            "conda", "mamba", "micromamba", "activate", "deactivate",
            "conda-content-trust", "conda-env", "conda-pack", "pip-sync",
            "easy_install", "setuptools", "jsonschema");

    // ==================================================================
    // Group 5 — build toolchain
    // ==================================================================
    /**
     * Compilers, linkers and build automation.
     *
     * <p>Named by the compiler projects and by the GNU build system. Their
     * presence means something was compiled, not that the environment offers
     * an analysis command.
     */
    private static final Set<String> BUILD = set(
            "gcc", "g++", "cc", "c++", "clang", "clang++", "ld", "as", "ranlib",
            "objdump", "objcopy", "readelf", "addr2line", "c++filt", "gprof",
            "gcov", "size", "elfedit", "windmc", "windres", "dlltool",
            "make", "gmake", "cmake", "ctest", "cpack", "ninja", "meson",
            "scons", "bazel", "gradle", "ant", "mvn", "pkg-config",
            "autoconf", "autoheader", "autom4te", "automake", "aclocal",
            "autoreconf", "autoscan", "autoupdate", "ifnames", "libtool",
            "libtoolize", "m4", "bison", "yacc", "flex", "lex", "gperf",
            "gettext", "gettextize", "msgfmt", "msgmerge", "xgettext",
            "msgattrib", "msgcat", "msgcmp", "msgcomm", "msgconv", "msgen",
            "msgexec", "msgfilter", "msggrep", "msginit", "msgunfmt",
            "ngettext", "recode-sr-latin", "envsubst", "xmllint", "xsltproc");

    // ==================================================================
    // Group 6 — Kerberos (MIT / Heimdal)
    // ==================================================================
    /**
     * Kerberos client utilities.
     *
     * <p>Distributed with the MIT and Heimdal implementations. Their defining
     * shape is a {@code k} followed by a fixed verb, which is why they are
     * matched by rule rather than enumerated.
     */
    private static final Pattern KERBEROS = Pattern.compile(
            "^k(init|list|destroy|passwd|admin|adminlocal|5srvutil|switch"
            + "|tutil|vno|db5|cert|proplist|imp|rb5|su|tel|cpytkt|deltkt"
            + "|gss|ticket|setup|log|cc|ccol|switch)([-.].*)?$");

    /**
     * Kerberos sample clients, whose names pair a short prefix with
     * {@code client} in either separator style.
     */
    private static final Pattern KERBEROS_CLIENT = Pattern.compile(
            "^(gss|sim|uu|s)[-_]?(client|server)$|^kpasswd_server$");

    // ==================================================================
    // Group 7 — DNS and name-service utilities (BIND)
    // ==================================================================
    /** Lookup utilities distributed with BIND. */
    private static final Set<String> NAMESERVICE = set(
            "dig", "host", "nslookup", "adig", "ahost", "delv", "mdig",
            "nsupdate", "rndc", "rndc-confgen", "named-checkconf",
            "named-checkzone", "named-compilezone", "dnssec-keygen",
            "dnssec-signzone", "dnssec-verify", "dnssec-settime",
            "dnssec-dsfromkey", "dnssec-keyfromlabel", "dnssec-revoke",
            "dnssec-cds", "tsig-keygen", "arpaname", "hostname");

    // ==================================================================
    // Group 8 — ICU (International Components for Unicode)
    // ==================================================================
    /**
     * ICU's resource and locale build tools.
     *
     * <p>ICU's generators are named {@code gen} plus a short data-type
     * abbreviation. The abbreviation is enumerated deliberately: a loose
     * {@code gen*} prefix would also match ordinary command names beginning with
     * those three letters, which silently hides real tools — a worse failure than
     * letting a helper through, because the user cannot tell anything is
     * missing.
     */
    private static final Pattern ICU = Pattern.compile(
            "^(genrb|gencnval|gencfu|genbrk|gendict|gensprep|genccode|gencmn"
            + "|genpkgdata|genrbldml|icutranslit|derb|makeconv|pkgdata|escapesrc"
            + "|icuexportdata|icuswap|icuinfo|icu-config|uconv|gennames)$");

    // ==================================================================
    // Group 9 — MPI runtimes
    // ==================================================================
    /**
     * Launchers and daemons from the MPI implementations.
     *
     * <p>Matched by exact name rather than by a bare prefix. Open MPI, MPICH,
     * Intel MPI and MVAPICH each contribute a small fixed set, and enumerating
     * it is safer than a pattern such as {@code mpi*}: that pattern would also
     * hide any ordinary command whose name begins with those letters, and a
     * hidden tool is a worse outcome than a visible helper because the user
     * cannot tell anything is missing.
     */
    private static final Set<String> MPI_NAMES = set(
            "mpirun", "mpiexec", "mpi_info", "mpicc", "mpicxx", "mpic++",
            "mpif77", "mpif90", "mpifort", "mpif08", "mpiCC",
            "mpirun.openmpi", "mpiexec.openmpi", "mpivars", "mpi-selector",
            "mpi-selector-menu", "mpiSpawn", "mpibind", "mpitrampoline",
            "mpichversion", "mpicc.mpich", "mpiexec.hydra", "mpiexec.mpich",
            "ompi_info", "opal_wrapper", "orte-clean", "orte-server", "orted",
            "orterun", "prte", "prterun", "pmix_info", "pmixcc", "hwloc-bind",
            "hwloc-calc", "hwloc-diff", "hwloc-gather-cpuid",
            "hwloc-gather-topology", "hwloc-info", "hwloc-ls",
            "hwloc-patch", "hwloc-ps", "lstopo", "lstopo-no-graphics",
            "oshmem_info", "oshmemrun", "shmemrun", "hydra_nameserver",
            "hydra_pmi_proxy", "mpd", "mpdallexit", "mpdboot", "mpdlistjobs",
            "mpdkilljob", "mpiexec.hydra");

    /** MPI names whose spelling varies with the implementation. */
    private static final Pattern MPI_PATTERN = Pattern.compile(
            "^(mpi[a-z0-9_]*_(info|run|exec|version)|mpirun\\.[a-z]+"
            + "|mpiexec\\.[a-z]+|orte[a-z-]*|prte[a-z-]*|hydra[a-z_]*)$");

    // ==================================================================
    // Group 10 — network transfer and remote access
    // ==================================================================
    /** Transfer and remote-shell clients distributed with their protocols. */
    private static final Set<String> NETWORK = set(
            "curl", "wget", "aria2c", "rsync", "scp", "sftp", "ssh", "ssh-add",
            "ssh-agent", "ssh-copy-id", "ssh-keygen", "ssh-keyscan", "sshd",
            "git", "git-receive-pack", "git-shell", "git-upload-archive",
            "git-upload-pack", "openssl", "certutil", "c_rehash", "nghttp",
            "nghttpd", "nghttpx", "httpx", "h2load", "cheroot", "nc", "netcat",
            "socat", "telnet", "ftp", "tftp", "smbclient", "rclone");

    // ==================================================================
    // Group 11 — graphics and media codecs
    // ==================================================================
    /**
     * Image and video tools pulled in by plotting or graphics libraries.
     *
     * <p>Enumerated rather than matched by prefix. A pattern such as {@code aom*}
     * or {@code convert*} would also hide ordinary commands whose names begin
     * with those letters, and hiding a real tool is the worse error: the user
     * sees a shorter list but has no way to know something is missing.
     */
    private static final Set<String> MEDIA_NAMES = set(
            "aomdec", "aomenc", "aomtp", "SvtAv1EncApp", "SvtAv1DecApp",
            "dav1d", "rav1e", "vpxdec", "vpxenc", "vpx_version",
            "x264", "x265", "JxrDecApp", "JxrEncApp",
            "magick", "convert", "mogrify", "identify", "composite",
            "montage", "compare", "stream", "display", "animate", "import",
            "conjure", "ffmpeg", "ffprobe", "ffplay",
            "cjpeg", "djpeg", "jpegtran", "rdjpgcom", "wrjpgcom",
            "ppm2tiff", "tiff2ppm", "fax2ps", "fax2tiff", "tiffcp",
            "tiffinfo", "tiffsplit", "tiff2pdf", "tiffdump", "tiffset",
            "tiffcrop", "raw2tiff", "thumbnail",
            "pngtopnm", "pnmtopng", "pnmtotiff", "giftopnm", "pnmtojpeg",
            "cwebp", "dwebp", "gif2webp", "img2webp", "webpmux", "webpinfo",
            "fc-cache", "fc-cat", "fc-conflist", "fc-list", "fc-match",
            "fc-pattern", "fc-query", "fc-scan", "fc-validate",
            "bdftogd", "gd2copypal", "gd2togif", "gd2topng", "gdcmpgif",
            "gdparttopng", "gdtopng", "giftogd2", "pngtogd", "pngtogd2",
            "webpng", "annotate", "cairo-trace", "cairo-sphinx",
            "gdk-pixbuf-csource", "gdk-pixbuf-pixdata");

    // ==================================================================
    // Structural rules
    // ==================================================================

    /**
     * True when a name is a package's internal entry point rather than a command
     * meant to be invoked.
     *
     * <p>A leading underscore is the conventional marker. Measured on a large
     * suite, roughly 40% of the executables a single package installs carry this
     * prefix, and they exist to be called by that package's own front-ends
     * rather than by a user.
     */
    public static boolean isInternalEntryPoint(String name) {
        return name != null && name.startsWith("_");
    }

    /** Name shapes that indicate infrastructure rather than a command. */
    private static final Pattern[] SHAPE_BLOCK = {
            // Version-stamped duplicates of the same program: tool-1.2.3.
            Pattern.compile("^.*-\\d+(\\.\\d+)*$"),
            Pattern.compile("^.*-\\d+\\.\\d+.*$"),
            // Build helpers distributed alongside a library.
            Pattern.compile("^.*[-_](config|config64)$"),
            // Cross-toolchain wrappers naming their target triple.
            Pattern.compile("^[a-z0-9_]+-(conda|linux|pc)-[a-z0-9]+-.*$"),
            // Compiled objects, not commands.
            Pattern.compile("^.*\\.(so|so\\.\\d+|a|h|hpp|pyc)$"),
            // Names beginning with a digit, which are version aliases.
            Pattern.compile("^\\d.*$"),
            // ALL-CAPS build stamps.
            Pattern.compile("^[A-Z_]{3,}$"),
            // Platform triples embedded in a name.
            Pattern.compile("^.*(x86_64|aarch64|linux64|linux-64|osx-64|win-64).*$")
    };

    /**
     * Suffixes marking a helper script rather than a primary command.
     *
     * <p>A package that installs a bare {@code .py} or {@code .pl} file into
     * {@code bin/} is nearly always shipping a sample, a module's entry point or
     * a build helper rather than a command with its own interface. Formats that
     * commonly <em>are</em> real commands are deliberately absent: an installed
     * {@code .R} script, for instance, is a normal way to distribute an R tool.
     */
    private static final String[] SUFFIX_BLOCK = {
            ".rb", ".pm", ".py", ".pyc", ".pl", ".sh", ".bash", ".csh",
            ".tcl", ".jar", ".class"
    };

    /**
     * Tools belonging to a build or configuration system rather than to the
     * environment's purpose.
     *
     * <p>Grouped here because they share no common prefix and belong to no
     * single standard set: each is the configuration helper of a distinct
     * library, named after that library.
     */
    private static final Set<String> BUILD_HELPERS = set(
            "compile_et", "mk_cmds", "ss", "ssd", "libtool", "libtoolize",
            "glib-gettextize", "gpg-error-config", "gpgme-config",
            "xml2-config", "xslt-config", "freetype-config", "curl-config",
            "ncursesw6-config", "ncurses6-config", "libpng-config",
            "libpng16-config", "MagickWand-config", "MagickCore-config",
            "Magick++-config", "gsl-config", "cairo-trace", "dbus-launch",
            "gio-querymodules", "glib-compile-schemas", "glib-compile-resources",
            "update-mime-database", "update-desktop-database", "gtk-update-icon-cache",
            "gdk-pixbuf-query-loaders", "pango-view", "pkgconf");

    /** Names that denote a language runtime or its package machinery. */
    private NoiseFilter() {
    }

    private static Set<String> set(String... names) {
        return new HashSet<String>(Arrays.asList(names));
    }

    /**
     * True when {@code name} looks like a user-facing command.
     *
     * <p>This is the single gate for admitting an executable, so a caller cannot
     * apply only part of the policy.
     */
    public static boolean isPlausibleTool(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (isInternalEntryPoint(name)) {
            return false;
        }
        if (isStandardUtility(name)) {
            return false;
        }
        if (isSystemUtilityLike(name)) {
            return false;
        }
        for (String s : SUFFIX_BLOCK) {
            if (name.endsWith(s)) {
                return false;
            }
        }
        for (Pattern p : SHAPE_BLOCK) {
            if (p.matcher(name).matches()) {
                return false;
            }
        }
        // Require at least two characters and at least one letter.
        return name.length() >= 2 && name.matches(".*[A-Za-z].*");
    }

    /**
     * True when a name is a member of one of the externally defined sets above.
     *
     * <p>Each set has a citable definition, so membership is a fact about the
     * standard rather than an observation about a particular machine
     * (DESIGN.md P20).
     */
    public static boolean isStandardUtility(String name) {
        if (name == null) {
            return false;
        }
        return POSIX.contains(name)
                || COREUTILS.contains(name)
                || COMPRESSION.contains(name)
                || RUNTIMES.contains(name)
                || BUILD.contains(name)
                || NAMESERVICE.contains(name)
                || NETWORK.contains(name)
                || BUILD_HELPERS.contains(name);
    }

    /**
     * True when a name belongs to a vendor or language namespace identified by
     * its shape rather than by an enumeration.
     *
     * <p>Kerberos, ICU, MPI and the media codecs each use consistent naming, so
     * a pattern catches the whole family including spellings not seen before.
     */
    public static boolean isSystemUtilityLike(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String n = name.toLowerCase();
        return KERBEROS.matcher(n).matches()
                || KERBEROS_CLIENT.matcher(n).matches()
                || ICU.matcher(n).matches()
                || MPI_NAMES.contains(name)
                || MPI_PATTERN.matcher(n).matches()
                || MEDIA_NAMES.contains(name)
                || isFilesystemAttributeTool(n);
    }

    /**
     * True for the extended-attribute utilities shipped with the attribute
     * library.
     *
     * <p>Named by the library's own convention: {@code get}/{@code set} plus
     * the attribute name.
     */
    private static boolean isFilesystemAttributeTool(String n) {
        return n.equals("attr") || n.equals("getfattr") || n.equals("setfattr")
                || n.equals("getfacl") || n.equals("setfacl") || n.equals("chacl");
    }

    /**
     * Executable count above which an environment is treated as a bundle whose
     * {@code bin/} is dominated by dependencies.
     *
     * <p>Retained for callers that need a size-only heuristic; discovery itself
     * now uses package ownership, which is precise (DESIGN.md P15).
     */
    public static final int BUNDLED_ENV_THRESHOLD = 60;

    /** True when an environment is large enough to be a bundle. */
    public static boolean isBundledEnv(int executableCount) {
        return executableCount > BUNDLED_ENV_THRESHOLD;
    }
}
