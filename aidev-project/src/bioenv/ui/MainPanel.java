package bioenv.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import bioenv.core.ConnectionModeStore;
import bioenv.core.CorrectionStore;
import bioenv.core.DiscoveryCache;
import bioenv.core.TargetMemory;
import javax.swing.ListSelectionModel;
import javax.swing.JList;
import javax.swing.DefaultListModel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import bioenv.core.EnvRunner;
import bioenv.core.EnvScanner;
import bioenv.core.Runner;
import bioenv.core.Schema;
import bioenv.core.SshTransportAdapter;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;
import bioenv.core.Transport;
import bioenv.core.WslBridge;
import bioenv.i18n.I18n;
import bioenv.i18n.Strings;

/**
 * Top-level panel registered with TBtools.
 *
 * <p>Flow: discover conda environments, list every command they provide,
 * introspect a command's own help into a {@link Schema}, render a form from
 * that schema, and run it.
 *
 * <p>No tool is named anywhere in this class (DESIGN.md P1): the list is
 * whatever discovery returns and the form is whatever the tool's help yields.
 * All blocking work happens on background threads.
 */
public final class MainPanel extends JPanel {

    // --- discovery bar ---
    /**
     * Connection mode: WSL or a Linux VM reached over SSH.
     *
     * <p>Sits first in the discovery bar because it decides what the combo box
     * beside it contains - WSL fills it with distribution names, Linux VM with
     * saved profile names. Both are the same question asked of two different
     * sources, so they share one box rather than sitting side by side, which
     * would leave the user guessing which one to use.
     */
    private final JComboBox<String> modeBox = new JComboBox<String>(
            new String[]{I18n.t(Strings.MODE_WSL), I18n.t(Strings.MODE_VM)});
    private final JComboBox<String> distroBox = new JComboBox<String>();
    private final JButton detectButton = new JButton(I18n.t(Strings.MENU_DETECT));

    /**
     * 已保存配置的右键菜单：重命名 / 删除。
     *
     * <h2>为什么是右键菜单而不是一个按钮</h2>
     *
     * <p>这两件事都是"对这一条配置"的操作，而"这一条"是由下拉框的当前选择决定的。
     * 做成常驻按钮就得占工具条一格，而且只有一个配置时下拉框是隐藏的，按钮旁边空无一物，
     * 用户看不出它会作用于谁。右键菜单挂在选择本身所在的控件上，作用于什么不言自明。
     *
     * <p>菜单里的动作只有在这个下拉框真的装着配置（也就是「虚拟机」方式）时才可用，
     * 所以菜单本身也只在该方式下弹出，见 {@code wireDistroBoxPopup()}。
     */
    private final javax.swing.JPopupMenu profileMenu = new javax.swing.JPopupMenu();
    private final JTextField filterField = new JTextField();
    /**
     * Applies initial divider positions once the panel has been laid out.' The label before the source box; its text follows the connection mode. */
    private JLabel distroLabel;
    /**
     * Applies initial divider positions once the panel has been laid out.' The "Connection" label; kept so its text can be refreshed on a language switch. */
    private JLabel modeLabel;
    /**
     * Applies initial divider positions once the panel has been laid out.' The "Filter" label; kept so its text can be refreshed on a language switch. */
    private JLabel filterLabel;
    /**
     * Applies initial divider positions once the panel has been laid out.' Language switch button. Refreshes itself; see {@link bioenv.i18n.LangToggle}. */
    private final bioenv.i18n.LangToggle langToggle = new bioenv.i18n.LangToggle();

    /**
     * True while {@link #applyConnectionMode()} is arranging the bar itself.
     *
     * <p>Switching to Linux VM fills the source box, and filling a combo box
     * fires its action listener. Without this guard the selection "change" would
     * be mistaken for the user picking a profile and would open the connection
     * dialog again, on top of the one already open.
     */
    private boolean adjustingMode;
    /**
     * 记住上次用的是哪个连接方式。
     *
     * <p>没有它的话，每次打开插件都会回到 WSL。一个只在 Linux VM 上干活的人
     * 每次都要手动切一次，而切模式会丢掉刚恢复出来的列表，于是又得重扫。
     */
    private final ConnectionModeStore modeStore = new ConnectionModeStore();
    /**
     * 记住每个通道上次用的是哪个具体目标。
     *
     * <p>{@link ConnectionModeStore} 只记「WSL 还是 Linux VM」；一个通道下面可能
     * 有两个 WSL 发行版或两台虚拟机配置，打开面板时还得再挑一次具体是哪一个。
     * 而绝大多数人每次用的都是同一台。
     */
    private final TargetMemory targetMemory = new TargetMemory();

    // --- tool tree, grouped by environment ---
    private final DefaultMutableTreeNode treeRoot =
            new DefaultMutableTreeNode(I18n.t(Strings.LABEL_TREE_ROOT));
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree toolTree = new JTree(treeModel);
    /**
     * Applies initial divider positions once the panel has been laid out.' Maps a tree node's label back to the tool it represents. */
    private final Map<String, Tool> nodeToTool = new HashMap<String, Tool>();
    /**
     * Applies initial divider positions once the panel has been laid out.' Sub-groups that must stay collapsed after a reload. */
    private final List<TreePath> collapsedGroups = new ArrayList<TreePath>();
    // --- form and output ---
    private final SchemaFormPanel formPanel = new SchemaFormPanel();

    /**
     * True once the user has typed into the command preview.
     *
     * <p>The preview follows the form until that happens, and stops following it
     * afterwards. Overwriting a command someone wrote by hand would be worse than
     * showing a stale one: they would lose work without being told.
     */
    private boolean previewEdited;

    /**
     * Applies initial divider positions once the panel has been laid out.' Raised while the plugin itself writes the preview, so it is not an edit. */
    private boolean previewWriting;

    /**
     * The last preview the form produced, kept so the paths it shows by name
     * alone can be written out again if the text is run by hand.
     *
     * <p>Null when nothing has been built, in which case there is nothing to
     * expand.
     */
    private bioenv.core.ConcisePreview shownPreview;

    /**
     * Applies initial divider positions once the panel has been laid out.' The preview's titled panel, so its title can report which state it is in. */
    private JPanel commandPanel;
    private final JTextArea commandArea = new JTextArea(3, 40);
    private final JTextArea logArea = new JTextArea(12, 40);
    private final JButton runButton = new JButton(I18n.t(Strings.MENU_RUN));
    private final JButton stopButton = new JButton(I18n.t(Strings.MENU_STOP));
    private final JButton clearLogButton = new JButton(I18n.t(Strings.MENU_CLEAR_LOG));
    /** "输出"标题：和清空按钮同处一行。 */
    private JLabel logTitle;
    /**
     * Opens the tool assistant: the tool's help on one tab, the user's own notes
     * on another. Named for what it opens rather than for the first tab, since
     * the window is no longer only a viewer.
     */
    private final JButton toolAssistantButton = new JButton(I18n.t(Strings.ASSISTANT_BUTTON));
    /**
     * Where the user's notes about a command are kept.
     *
     * <p>Held here, and deliberately not on the registry: the registry is what
     * builds schemas, and the notes must never reach it. Keeping the two apart
     * makes "notes cannot change the form" a fact about the wiring rather than a
     * rule someone has to remember not to break.
     */
    private final bioenv.core.ToolNotesStore toolNotes = new bioenv.core.ToolNotesStore();
    private final JButton learnedRulesButton = new JButton(I18n.t(Strings.MENU_EDIT_PARAMS));
    /**
     * Applies initial divider positions once the panel has been laid out.' Puts the command preview back under the form's control. */
    private final JButton rebuildPreviewButton = new JButton(I18n.t(Strings.MENU_REBUILD_PREVIEW));
    private final JLabel statusLabel = new JLabel(I18n.t(Strings.STATUS_READY));

    /**
     * Titled borders that carry display text, kept so a language switch can retitle
     * them.
     *
     * <p>A {@link javax.swing.border.TitledBorder} is not a component: nothing walks
     * the container tree and finds it. The only way to reach one again is to hold a
     * reference — hence these fields.
     */
    private javax.swing.border.TitledBorder treeBorder;
    private javax.swing.border.TitledBorder logBorder;
    private javax.swing.border.TitledBorder commandBorder;

    // --- state ---
    /**
     * 当前执行通道。
     *
     * <p>类型从 {@code WslBridge} 放宽为 {@link Transport}：面板现在既能走
     * WSL 也能走 SSH，而这两种通道在「跑一段 bash」这件事上完全同构。
     * 保持具体类型会让模式选择无法实现（除非到处写 instanceof 分叉）。
     */
    private Transport wsl;
    private EnvScanner scanner;
    private EnvRunner envRunner;
    private ToolRegistry registry;
    private Runner runner;
    private Tool selected;
    private Runner.Job currentJob;
    private boolean introspecting;

    /**
     * SSH 方式下用户选中的连接；WSL 方式下为 null。
     *
     * <p>只存选中的那一个而不是整个列表：列表每次要用时现读（读盘很便宜），
     * 存下来反而会在用户在对话框里改了配置之后变成过期快照。
     */
    private vmadapt.core.SshTarget sshTarget;

    /**
     * 与 {@link #sshTarget} 配套的远端文件工具，用于 SSH 方式下的工作目录与输入上传。
     *
     * <p>为什么必须另存这两个对象，而不能靠 {@link Transport}：远端建目录
     * （{@code mkdir -p}）和上传文件（scp）不是「跑一段 bash」的语义，
     * 所以不在 Transport 接缝上。WSL 侧这两件事是 {@link WslBridge} 的方法，
     * SSH 侧则由 vmadapt 的 RemoteFiles / ScpTransfer 提供。两条通道各有实现，
     * 面板按通道选一个即可，功能上不降级。
     *
     * <p>两者都用非空校验过的 target 构造；未选中配置时为 null。
     */
    private vmadapt.core.RemoteFiles sshFiles;
    private vmadapt.core.ScpTransfer sshScp;

    // --- values produced by the restore worker, applied on the event thread ---
    private ToolRegistry pendingRegistry;
    private EnvScanner pendingScanner;
    private Transport pendingBridge;
    private EnvRunner pendingEnvRunner;
    private ToolRegistry.Report pendingScanReport;
    private EnvScanner.Report lastReport;
    /**
     * Applies initial divider positions once the panel has been laid out.' Distributions found by the worker, for the box to show when it finishes. */
    private java.util.List<String> pendingDistros = new ArrayList<String>();
    /**
     * Applies initial divider positions once the panel has been laid out.' The distribution the worker loaded. */
    private String pendingDistro;
    /**
     * Applies initial divider positions once the panel has been laid out.' 自动加载时发现没有可恢复的缓存，需要用户自己点一次扫描。 */
    private boolean pendingNeedsManual;
    /**
     * True while the box is being filled in code.
     *
     * <p>Setting the items fires the selection listener, and acting on that would
     * start a second load every time the first one finished.
     */
    private boolean fillingDistros;
    /**
     * Which load is allowed to report.
     *
     * <p>Loading takes seconds, and a second one can start before the first has
     * finished — opening the panel starts one, and the user may click Detect, or
     * pick another distribution, while it is still running. Both would then write
     * to the log and the tool list, and the result read as one report printed
     * twice. Each load takes a number and only the newest one is heard.
     */
    private int loadGeneration;

    // --- split panes, whose initial proportions are applied after layout ---
    private JSplitPane outerSplit;

    /** 中间列／输出列那条分栏，换风格后要重新摆位，所以留个引用。 */
    private JSplitPane logSplitRef;
    private JPanel formAndPreview;
    private boolean outerSplitPending;

    /**
     * Applies initial divider positions once the panel has been laid out.' Starting width of the tool tree, in pixels. */
    private static final int TREE_WIDTH_PX = 260;

    /**
     * Starting width of the output column, in pixels.
     *
     * <p>Fixed rather than a fraction, for the same reason as the tree: output is
     * lines of text, so its useful width does not grow with the window, and the
     * spare room is better spent on the form. A fraction would also compute a
     * position past what a narrow window allows and be clamped.
     */
    private static final int LOG_WIDTH_PX = 340;

    /**
     * Applies initial divider positions once the panel has been laid out.' A component's minimum extent along one axis, or 0 when unset. */
    private static int minimumOf(java.awt.Component c, boolean horizontal) {
        if (c == null) {
            return 0;
        }
        java.awt.Dimension d = c.getMinimumSize();
        return horizontal ? d.width : d.height;
    }

    /**
     * Applies initial divider positions once the panel has been laid out.' Adopts a registry and its collaborators as the panel's live state. */
    private void adoptRegistry(ToolRegistry reg, EnvScanner sc,
                               Transport bridge, EnvRunner er) {
        this.registry = reg;
        this.scanner = sc;
        this.wsl = bridge;
        this.envRunner = er;
        this.runner = new Runner(bridge);
        // The form needs the store of parameters added by hand, so a parameter the
        // user added can be listed and removed.
        formPanel.setCustomOptions(reg.getCustomOptions());
        formPanel.setCorrections(reg.getCorrections());

        // A working directory on the Linux side, made on demand. Writing there is far
        // faster than writing through the Windows share, which matters most for the
        // tools that create many small files.
        //
        // These two capabilities are deliberately NOT on the Transport seam: making a
        // directory and pushing files over are not "run a command" semantics. Each
        // channel therefore supplies its own implementation — WslBridge for WSL,
        // RemoteFiles/ScpTransfer for SSH — and the panel picks by channel. Neither
        // mode is a degraded version of the other.
        final WslBridge makerBridge = bridge instanceof WslBridge
                ? (WslBridge) bridge : null;
        formPanel.setWorkdirMaker(new SchemaFormPanel.WorkdirMaker() {
            @Override
            public String make(String label) {
                if (makerBridge != null) {
                    return makerBridge.createWorkDir(null, label);
                }
                // 现取，不要在这里捕获成 final 局部变量。
                //
                // sshFiles 是在 adoptSshTarget 里才建出来的，而这个闭包装得更早；
                // 捕获的话，安装那一刻若是 null，之后就永远是 null。表现就是
                // 「无法复制」「无法在…创建工作目录」两条提示，而真正的原因
                // （连接目标还没建立）一个字都没出现在提示里，只能靠猜。
                vmadapt.core.RemoteFiles files = sshFiles;
                return files == null ? null : files.createWorkDir(label);
            }

            @Override
            public String lastFailure() {
                if (makerBridge != null) {
                    return "";
                }
                vmadapt.core.RemoteFiles files = sshFiles;
                if (files == null) {
                    return "还没有建立到 Linux 侧的连接（连接目标未就绪）。"
                            + "请先点「扫描 conda 环境」连上，再回来建目录。";
                }
                return files.lastFailure();
            }
        });
        formPanel.setInputCopier(new SchemaFormPanel.InputCopier() {
            @Override
            public java.util.List<String> copyInto(java.util.List<String> sources,
                                                   String targetDir) {
                if (makerBridge != null) {
                    return makerBridge.copyInto(sources, targetDir);
                }
                // 同上：这两个都在 adoptSshTarget 里赋值，必须现取。
                vmadapt.core.RemoteFiles files = sshFiles;
                vmadapt.core.ScpTransfer scp = sshScp;
                if (scp == null || files == null || sources == null
                        || sources.isEmpty() || targetDir == null) {
                    return null;
                }
                if (!files.ensureDir(targetDir)) {
                    return null;
                }
                if (!scp.upload(sources, targetDir, vmadapt.core.ScpTransfer.SILENT)) {
                    return null;
                }
                // The caller expects one result path per source, and those paths are
                // later written into the command line. Returning the Windows source
                // paths here would hand the remote tool paths that do not exist on
                // that machine, so translate each one to its remote location. Names
                // come from the local file's basename, which is what scp creates.
                java.util.List<String> remote =
                        new java.util.ArrayList<String>(sources.size());
                for (String s : sources) {
                    String base = new java.io.File(s).getName();
                    remote.add(files.remoteChild(targetDir, base));
                }
                // 但上面那几行只是把字符串拼出来，**不是事实**：上传返回 true 只说明
                // scp 没报错，东西落在哪里由远端决定。两者一旦分歧，用户会同时看到
                // 「已复制成功」和工具报「目录不存在」，且没有任何线索指向真正的
                // 分歧点。所以问一次远端实际有什么，对不上的位置返回 null，
                // 让调用方保留用户的原始值并如实提示「有几个没能复制」。
                return files.verifyPresent(remote);
            }
        });
    }

    /**
     * Applies initial divider positions once the panel has been laid out.' Logs a scan report. */
    private void reportScan(ToolRegistry.Report rep) {
        appendLog("");
        appendLog(I18n.t(Strings.LOG_COMMAND_DISCOVERY));
        for (String n : rep.notes) {
            appendLog("  " + n);
        }
        // 增量单独一行说清楚。用户最担心「点一次扫描会不会把设置冲掉」，
        // 而保留下来的那些确实原样留着——这件事必须让人看见，而不是靠猜。
        if (rep.toolsAdded > 0 || rep.toolsRemoved > 0
                || rep.toolsPreserved > 0 || rep.toolsRelearned > 0) {
            appendLog("  " + I18n.t(Strings.LOG_SCAN_DELTA,
                    Integer.valueOf(rep.toolsAdded),
                    Integer.valueOf(rep.toolsRemoved),
                    Integer.valueOf(rep.toolsPreserved),
                    Integer.valueOf(rep.toolsRelearned)));
        }
    }

    /**
     * 「上次扫描：…」那一行；没有记录时返回空串。
     *
     * <p>自动恢复出来的列表可能是几小时前扫的，而它和刚扫出来的长得一模一样。
     * 不把日期摆出来，用户就没有依据判断该不该点一次扫描。
     */
    private static String scannedAtLine(ToolRegistry reg) {
        if (reg == null || reg.getScannedAt() <= 0) {
            return "";
        }
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        return I18n.t(Strings.LOG_SCANNED_AT,
                f.format(new java.util.Date(reg.getScannedAt())));
    }

    /**
     * 可达性结论对应的一行日志。
     *
     * <p>三种失败分开说：端口被拒绝是「主机活着但 sshd 没起来」，超时是「没应答」，
     * 解析不了是「地址写错了」。混成一句「连不上」，用户只能瞎猜。
     */
    private static String reachMessage(vmadapt.core.Reachability.State st,
                                       vmadapt.core.SshTarget t) {
        String where = t == null ? "" : (t.host + ":" + (t.port > 0
                ? t.port : vmadapt.core.SshTarget.DEFAULT_PORT));
        if (st == vmadapt.core.Reachability.State.REFUSED) {
            return I18n.t(Strings.LOG_REACH_REFUSED, where);
        }
        if (st == vmadapt.core.Reachability.State.UNRESOLVED) {
            return I18n.t(Strings.LOG_REACH_UNRESOLVED, where);
        }
        return I18n.t(Strings.LOG_REACH_TIMEOUT, where);
    }

    /**
     * Logs why no tools are available, without leaving the panel blank.
     *
     * <p>The two channel flags are parameters rather than live {@code isSshMode()}
     * calls: this runs from a worker's {@code done()}, and the user may have
     * switched the connection mode while that worker was in flight. Asking the
     * combo box then would describe the run that just finished using the mode that
     * is selected *now* - so a failed SSH attempt could report WSL wording, or the
     * reverse. The caller passes the channel the finished run actually used.
     *
     * @param sshMode         this run used the Linux VM channel
     * @param sshTargetKnown  a profile was actually resolved (so we did connect)
     */
    private void applyDiscoveryReport(EnvScanner.Report rep,
                                      ToolRegistry reg, Runner r,
                                      boolean sshMode, boolean sshTargetKnown) {
        appendLog(I18n.t(Strings.LOG_ENV_CHECK));
        for (String n : rep.notes) {
            appendLog("  " + n);
        }
        for (String h : rep.hints) {
            appendLog(I18n.t(Strings.LOG_HINT_PREFIX) + h);
        }
        // The status line is the one thing a user sees without opening the log, so
        // it names the channel: "no conda" and "could not connect at all" have
        // completely different next steps, and only rep.wslOk tells them apart.
        if (rep.wslOk) {
            setStatus(I18n.t(Strings.STATUS_NO_CONDA));
        } else if (sshTargetKnown) {
            setStatus(I18n.t(Strings.STATUS_NO_CONNECTION));
        } else if (sshMode) {
            // Linux VM mode with nothing selected. "Could not connect" would be a
            // lie - we never tried - and the next step is different too.
            setStatus(I18n.t(Strings.STATUS_NO_PROFILE_TO_SCAN));
        } else {
            setStatus(I18n.t(Strings.STATUS_NO_CONDA));
        }
    }

    public MainPanel() {
        setLayout(new BorderLayout(6, 6));
        UiTheme.loadOnce();
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        add(buildDiscoveryBar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        wireEvents();
        // Refresh the panel's own display text when the language changes. Registered
        // last, so every component it touches already exists; the callback runs on
        // the EDT (I18n dispatches there), so plain setText is correct.
        I18n.addListener(languageRefresh);
        // 界面风格：先把上次的选择读进来（默认经典），再按它刷一遍；之后每次切换都走
        // 同一个 applyTheme()，所以两条路径不会长歪。
        UiTheme.addListener(themeRefresh);
        applyTheme();
        updateRunEnabled();
        // Put the panel back into the mode the user last used, BEFORE restoring
        // anything. Someone who works only against a Linux VM would otherwise be
        // dropped into WSL on every open and have to switch - and switching throws
        // away the list that was just restored.
        restoreConnectionMode();
        // Restore the previous session's tools so the list is not empty on
        // opening. Done on a worker because it touches WSL, and re-scans only
        // when the environments have changed (DESIGN.md P23).
        restoreFromCacheAsync();
    }

    /**
     * 把「连接方式」恢复成上次用的那个。
     *
     * <p>{@code adjustingMode} 会压住下拉框自己的监听：这里只是摆正初始状态，
     * 真正的加载交给构造器末尾的 {@link #restoreFromCacheAsync()}。否则切模式
     * 会顺带清空列表，等于白恢复一次。
     */
    private void restoreConnectionMode() {
        boolean wantVm = ConnectionModeStore.MODE_VM.equals(modeStore.load());
        int want = wantVm ? 1 : 0;
        if (modeBox.getSelectedIndex() == want) {
            return;
        }
        adjustingMode = true;
        try {
            modeBox.setSelectedIndex(want);
            // 监听被压住了，所以源标签要自己跟着换。
            applySourceLabels(wantVm);
        } finally {
            adjustingMode = false;
        }
    }

    /**
     * Re-labels every component this panel created, in whichever language is now
     * current.
     *
     * <p>Held as a field rather than an anonymous class so it can be removed again
     * with {@link #dispose()}: an anonymous listener cannot be referenced, so it
     * would stay registered forever and every language switch would run a refresh
     * against a panel that may already be gone.
     *
     * <p>Deliberately does <b>not</b> retranslate the status bar or the log. Those
     * hold what has already happened ("Scanning environments..."), and rewriting
     * history into the other language would misreport an event that has finished —
     * worse than leaving one old-language line on screen. The next status or log
     * line appears in the new language regardless.
     */
    /**
     * Applies initial divider positions once the panel has been laid out.' 风格切换器：顶栏右侧，挨着语言按钮。 */
    private final ThemeSwitcher themeSwitcher = new ThemeSwitcher();

    /**
     * Applies initial divider positions once the panel has been laid out.' 换风格时把这一层重刷一遍；由构造器注册到 {@link UiTheme}。 */
    private final Runnable themeRefresh = new Runnable() {
        @Override
        public void run() {
            applyTheme();
            revalidate();
            repaint();
            // 换风格会改按钮/输入框的内边距，控件尺寸跟着变。只重绘面板本身的话，
            // 控件让出来的那块旧像素没人擦 —— 屏幕上就留着半截旧按钮，看着像"重影"。
            // 把整个窗口重绘一遍最省事也最彻底。
            java.awt.Window w = javax.swing.SwingUtilities
                    .getWindowAncestor(MainPanel.this);
            if (w != null) {
                w.repaint();
            }
        }
    };

    private JScrollPane treeScrollRef;
    private JPanel commandPanelRef;
    private JPanel logPanelRef;

    /**
     * 按当前风格刷新颜色、字体与边框。
     *
     * <p>刻意不重建控件：换风格只该改"怎么画"。控件本身、位置、事件、以及用户已经填进去
     * 的值都必须原封不动 —— 重建会把它们一起丢掉。
     */
    private void applyTheme() {
        setBackground(UiTheme.appBg());
        UiTheme.applyRoles(this);
        UiTheme.buttons(this);
        // 深度装饰：底色往下继承 + 接管输入框/下拉框/滚动条。
        UiTheme.decorate(this);
        // 连接方式下拉框的字加粗。
        //
        // 它是顶栏唯一一个"当前选择"，加粗之后一眼能看出当前用的是什么，
        // 不用去读一行小字。放在 decorate 之后：decorate 会按主题设字号，
        // 这里在其基础上加粗，切主题时也会重新执行。
        if (modeBox != null) {
            Font mf = modeBox.getFont();
            modeBox.setFont(mf == null ? null
                    : mf.deriveFont(java.awt.Font.BOLD));
        }
        if (commandArea != null) {
            UiTheme.font(commandArea, UiTheme.mono(13, false));
            // 让文字域自己画底色。
            //
            // 之前设成非透明，指望外层滚动区那条圆角边框画深底；可那条底并不总是画出来，
            // 结果就是"浅色文字 + 白色底" —— 命令预览根本看不清。自己画最稳。
            commandArea.setOpaque(true);
        }
        // 三个分区标题：新风格不加框线，只留一行小字。
        treeBorder = themedTitledBorder(I18n.t(Strings.TITLE_TREE));
        if (treeScrollRef != null) {
            treeScrollRef.setBorder(treeBorder);
        }
        commandBorder = themedTitledBorder(I18n.t(previewTitleKey()));
        if (commandPanelRef != null) {
            commandPanelRef.setBorder(commandBorder);
        }
        // 标题已移到标题栏里，这里只更新文字 + 留内边距。
        //
        // 不能再设 TitledBorder：那会把"输出/清空"那一行覆盖回"边框上写标题"的
        // 老样子，切一次主题就退回去。
        if (logTitle != null) {
            logTitle.setText(I18n.t(Strings.TITLE_OUTPUT));
        }
        if (logPanelRef != null) {
            logPanelRef.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        }
        // 工具树是自绘的，圆点、引导线、字重都要跟着换一套取值。
        ToolTreeStyle.apply(toolTree, treeScrollRef);
        // 换风格会重建分隔条，位置得重新摆一遍 —— 否则切回来时各列会挪位。
        replaceDividers();
    }

    /**
     * 把各条分隔条摆回它们该在的位置。
     *
     * <p>原来的摆位都是"一次性"的：输出列靠组件首次拿到尺寸时算一次，工具树宽度靠
     * {@code outerSplitPending} 标志在第一次布局时算一次，参数区靠
     * {@code setDividerLocation(0.62)}。换风格会重建分隔条（setUI／updateUI），位置被
     * 重算成默认值，而这几个一次性入口都已经用过了 —— 于是切回来时各列会各自挪一两个
     * 像素：颜色其实是对的，只是内容整体位移，看上去像"没切干净"。
     *
     * <p>这里给一个可以反复调用的入口，换风格后重摆一遍。
     */
    private void replaceDividers() {
        if (logSplitRef != null && logSplitRef.getWidth() > 0) {
            int available = logSplitRef.getWidth();
            int max = available - logSplitRef.getDividerSize()
                    - minimumOf(logSplitRef.getLeftComponent(), true);
            int min = minimumOf(logSplitRef.getRightComponent(), true);
            if (max < min) {
                max = min;
            }
            int width = Math.max(min, Math.min(LOG_WIDTH_PX, max));
            logSplitRef.setDividerLocation(Math.max(0,
                    available - logSplitRef.getDividerSize() - width));
        }
        if (outerSplit != null) {
            outerSplitPending = true;      // 交给 doLayout() 重摆
        }
    }

    /** 分区标题：外框不加线，经典走原生。 */
    private javax.swing.border.TitledBorder themedTitledBorder(String title) {        if (UiTheme.flat()) {
            // 外框不加线：三列外边各画一圈，里面还有选项/附加参数/命令预览三层小框，
            // 叠起来就是"框套框"。外层的卡片感交给底色差，框线只留给内部的分区。
            return BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(), title,
                    javax.swing.border.TitledBorder.LEADING,
                    javax.swing.border.TitledBorder.TOP,
                    UiTheme.ui(12, true), UiTheme.text3());
        }
        return BorderFactory.createTitledBorder(title);
    }

    private final Runnable languageRefresh = new Runnable() {
        @Override
        public void run() {
            detectButton.setText(I18n.t(Strings.MENU_DETECT));
            rebuildProfileMenu();
            runButton.setText(I18n.t(Strings.MENU_RUN));
            stopButton.setText(I18n.t(Strings.MENU_STOP));
            clearLogButton.setText(I18n.t(Strings.MENU_CLEAR_LOG));
            toolAssistantButton.setText(I18n.t(Strings.ASSISTANT_BUTTON));
            learnedRulesButton.setText(I18n.t(Strings.MENU_EDIT_PARAMS));
            rebuildPreviewButton.setText(I18n.t(Strings.MENU_REBUILD_PREVIEW));
            rebuildPreviewButton.setToolTipText(I18n.t(Strings.HINT_REBUILD_PREVIEW));
            filterField.setToolTipText(I18n.t(Strings.HINT_FILTER_FIELD));
            modeBox.setToolTipText(I18n.t(Strings.HINT_MODE_BOX));
            commandArea.setToolTipText(I18n.t(Strings.HINT_COMMAND_AREA));
            // Rebuild the two mode items in the new language. The mode is identified
            // by index (isSshMode() reads it), not by text, so restoring the index
            // keeps the user in the mode they were in despite the new labels.
            int keepMode = modeBox.getSelectedIndex();
            adjustingMode = true;
            try {
                modeBox.removeAllItems();
                modeBox.addItem(I18n.t(Strings.MODE_WSL));
                modeBox.addItem(I18n.t(Strings.MODE_VM));
                modeBox.setSelectedIndex(keepMode < 0 ? 0 : keepMode);
            } finally {
                adjustingMode = false;
            }
            if (modeLabel != null) {
                modeLabel.setText(I18n.t(Strings.CONN_MODE_LABEL));
            }
            if (filterLabel != null) {
                filterLabel.setText(I18n.t(Strings.LABEL_FILTER));
            }
            // The source label and box tooltip depend on the mode, so re-derive
            // them through the same code path that set them initially.
            applySourceLabels(isSshMode());
            if (treeBorder != null) {
                treeBorder.setTitle(I18n.t(Strings.TITLE_TREE));
            }
            if (logBorder != null) {
                logBorder.setTitle(I18n.t(Strings.TITLE_OUTPUT));
            }
            treeRoot.setUserObject(I18n.t(Strings.LABEL_TREE_ROOT));
            treeModel.nodeChanged(treeRoot);
            refreshCommandPanelTitle();
        }
    };

    /**
     * Unregisters the language listener.
     *
     * <p>Call when the panel is discarded. Not calling it is not an immediate error,
     * but the listener table would grow by one on every panel creation and each
     * stale entry would still be invoked on every language switch.
     */
    public void dispose() {
        I18n.removeListener(languageRefresh);
        langToggle.dispose();
    }

    /**
     * Restores cached discovery results, rescanning only if they are stale.
     *
     * <p>The point is that a user who already waited for a scan should not wait
     * again on every start. The cache is checked against the current environment
     * state, so an installed or removed tool is noticed rather than served from a
     * stale list.
     */
    private void restoreFromCacheAsync() {
        loadDistroAsync(null);
    }

    /**
     * Fills the distribution box, then loads one distribution's tools.
     *
     * <p>Runs when the panel opens and again whenever the user picks a different
     * distribution, so the choice takes effect without a second click and the list
     * on screen always belongs to the distribution named in the box. Before this,
     * the box was filled only by a scan and stayed empty until one was run, and
     * only ever one distribution's results were kept.
     *
     * <p>In SSH mode the box holds saved connection names instead, and the loading
     * is skipped entirely: connecting to a machine the user has not picked yet would
     * put a blocking ssh.exe call behind an ordinary panel open, and opening the
     * panel must not wait on a network the user has not asked for. The user picks a
     * saved connection, or opens the connection dialog, and Detect does the work.
     *
     * @param wanted the distribution to load, or null for the one the previous
     *               session used, falling back to the machine's own default
     */
    private void loadDistroAsync(final String wanted) {
        loadDistroAsync(wanted, true);
    }

    /**
     * @param auto true 表示这是面板打开或用户切换目标时自动发起的。自动路径
     *             只恢复缓存、绝不重扫（见 {@link #startDiscovery(boolean)} 的说明）。
     */
    private void loadDistroAsync(final String wanted, final boolean auto) {
        if (isSshMode()) {
            // No SSH equivalent of "the machine's own default": every target needs
            // an address the user supplied. Only offer what is already saved.
            //
            // 这里刻意不给"可以弹引导窗"的许可：打开面板时回到上次用的方式只是便利，
            // 不是用户当下要连虚拟机。见 refreshSshList(boolean, boolean)。
            refreshSshList(true, false);
            return;
        }
        setStatus(I18n.t(Strings.STATUS_LOOKING_WSL));
        pendingNeedsManual = false;
        final int mine = ++loadGeneration;
        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                pendingDistros = WslBridge.listDistros();
                if (pendingDistros.isEmpty()) {
                    return null;
                }
                pendingDistro = chooseDistro(wanted, pendingDistros);
                publish(I18n.t(Strings.STATUS_CHECKING_ENVS, pendingDistro));
                Transport bridge = new WslBridge(pendingDistro);
                EnvScanner sc = new EnvScanner(bridge);
                EnvScanner.Report rep = sc.discover();
                if (!rep.condaOk) {
                    lastReport = rep;
                    return null;
                }
                EnvRunner er = new EnvRunner(bridge, sc.getCondaRoot());
                ToolRegistry reg = new ToolRegistry(sc, er);
                int restored = reg.loadCache();
                pendingRegistry = reg;
                pendingScanner = sc;
                pendingBridge = bridge;
                pendingEnvRunner = er;
                if (restored > 0 && (auto || reg.cacheIsFresh())) {
                    publish(I18n.t(Strings.STATUS_RESTORED, Integer.valueOf(restored)));
                    return null;
                }
                if (auto) {
                    // 自动路径不做长任务：没有可恢复的缓存时退回让用户点一次，
                    // 否则每次打开插件都可能无缘无故等上一次全量扫描。
                    pendingNeedsManual = true;
                    return null;
                }
                // Nothing cached, or it belongs to another distribution, or the
                // environments changed: scan.
                publish(I18n.t(restored > 0
                        ? Strings.STATUS_RESCANNING
                        : Strings.STATUS_NO_CACHE_SCAN));
                pendingScanReport = reg.scan();
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) {
                    setStatus(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                if (mine != loadGeneration) {
                    return; // a newer load has taken over
                }
                try {
                    get();
                } catch (Exception ex) {
                    setStatus(I18n.t(Strings.STATUS_RESTORE_FAILED));
                    return;
                }
                showDistros(pendingDistros, pendingDistro);
                // 记住这个发行版：下次打开面板直接回到它。
                if (pendingDistro != null && !pendingDistro.isEmpty()) {
                    targetMemory.rememberWslDistro(pendingDistro);
                }
                if (pendingNeedsManual) {
                    // 自动路径发现这个发行版还没扫过：不做长任务，退回让用户点一次。
                    appendLog(I18n.t(Strings.LOG_NO_CACHE_MANUAL));
                    // 状态栏必须说"要你点一下"，不能说"正在扫描"：这里已经决定不扫了。
                    //
                    // 这里<b>不</b>打开连接配置窗。那个窗是「Linux 虚拟机连接配置」，
                    // 而这条路径是 WSL 方式——用户当下要做的只是点一下扫描，弹一个
                    // 跟虚拟机有关的窗只会打断他，还会让人以为插件选错了方式。它只
                    // 在用户从下拉框里主动选了虚拟机方式时才该出现，那由 SSH 那条
                    // 路径（refreshSshList → shouldAutoOpenDialog）负责。
                    if (!LinkState.hasEverConnected()) {
                        setStatus(I18n.t(Strings.STATUS_FIRST_RUN_GUIDE));
                    } else {
                        setStatus(I18n.t(Strings.STATUS_NEEDS_FIRST_SCAN,
                                pendingDistro == null
                                        ? I18n.t(Strings.FORM_THIS_COMMAND)
                                        : pendingDistro));
                    }
                    return;
                }
                if (pendingRegistry == null) {
                    // No usable conda; explain rather than leaving a blank panel.
                    // This restore path never runs in SSH mode (loadDistroAsync
                    // returns early there), so the channel is WSL by construction.
                    if (lastReport != null) {
                        applyDiscoveryReport(lastReport, null, null, false, false);
                    } else {
                        setStatus(I18n.t(Strings.STATUS_READY_NO_WSL));
                    }
                    return;
                }
                adoptRegistry(pendingRegistry, pendingScanner, pendingBridge,
                        pendingEnvRunner);
                if (pendingScanReport != null) {
                    reportScan(pendingScanReport);
                } else {
                    appendLog(I18n.t(Strings.LOG_RESTORED_FOR, pendingDistro));
                    appendLog(I18n.t(Strings.LOG_DETECT_FOR_NEW));
                }
                refreshList();
                setStatus(I18n.t(Strings.STATUS_READY_COUNT,
                        Integer.valueOf(registry == null ? 0 : registry.getTools().size()),
                        pendingDistro));
            }
        }.execute();
    }

    /**
     * Applies initial divider positions once the panel has been laid out.' Puts the distributions in the box, without the listener reacting to it. */
    private void showDistros(java.util.List<String> distros, String chosen) {
        if (distros == null || distros.isEmpty()) {
            return;
        }
        fillingDistros = true;
        try {
            distroBox.removeAllItems();
            for (String d : distros) {
                distroBox.addItem(d);
            }
            if (chosen != null && distros.contains(chosen)) {
                distroBox.setSelectedItem(chosen);
            }
        } finally {
            fillingDistros = false;
        }
        // 只有一个发行版时下拉框纯属碍眼：没有可选的余地，还白占一行。
        boolean many = distros.size() > 1;
        distroBox.setVisible(many);
        if (distroLabel != null) {
            distroLabel.setVisible(many);
        }
    }

    /**
     * Which distribution to load when the user has not just chosen one.
     *
     * <p>The one the previous session used, so a restart returns to the same
     * place. Otherwise the machine's own default — which is what makes a fresh
     * install work with no configuration, and what the cache's record of the last
     * distribution keeps stable once the user has chosen.
     */
    private String chooseDistro(String wanted, java.util.List<String> distros) {
        if (wanted != null && distros.contains(wanted)) {
            return wanted;
        }
        // 上次用的那个发行版。它比缓存里的记录更准：缓存存的是最近写过的通道，
        // 而那可能是虚拟机，跟这里要选的 WSL 发行版根本不是一回事。
        String remembered = targetMemory.wslDistro();
        if (!remembered.isEmpty() && distros.contains(remembered)) {
            return remembered;
        }
        bioenv.core.DiscoveryCache.Data d = new bioenv.core.DiscoveryCache().load();
        if (d != null && d.distro != null && !d.distro.isEmpty()
                && distros.contains(d.distro)) {
            return d.distro;
        }
        return WslBridge.preferredDistro(distros);
    }

    // ------------------------------------------------------------------
    // Connection mode
    // ------------------------------------------------------------------

    /**
     * Applies initial divider positions once the panel has been laid out.' True in Linux VM mode. WSL is the default, and the pre-existing behaviour. */
    private boolean isSshMode() {
        return modeBox.getSelectedIndex() == 1;
    }

    /**
     * Switches connection mode: relabels the source box, refills it, and clears
     * the command list.
     *
     * <p>Clearing matters. A WSL distribution and a Linux VM have entirely
     * different software installed; keeping the previous channel's command list
     * would let the user press Run on a program that does not exist on the target
     * machine, and the error would point somewhere else entirely.
     */
    private void applyConnectionMode() {
        boolean ssh = isSshMode();
        // 记住这次的选择：下次打开插件直接回到同一个模式，不用用户再切一次。
        modeStore.save(ssh ? ConnectionModeStore.MODE_VM : ConnectionModeStore.MODE_WSL);
        adjustingMode = true;
        try {
            applySourceLabels(ssh);
            distroBox.removeAllItems();
        } finally {
            adjustingMode = false;
        }

        // The list belongs to the channel that produced it; see the method comment.
        registry = null;
        runner = null;
        scanner = null;
        envRunner = null;
        wsl = null;
        selected = null;
        lastReport = null;
        pendingScanReport = null;
        pendingRegistry = null;
        pendingBridge = null;
        nodeToTool.clear();
        treeRoot.removeAllChildren();
        treeModel.reload();
        updateRunEnabled();

        // A load that is already running belongs to the other mode. Bumping the
        // generation makes its done() drop the result rather than adopt a WSL
        // distro's registry while the user is looking at the Linux VM box.
        loadGeneration++;

        logArea.setText("");
        if (ssh) {
            // Entering the mode afresh clears the "just closed it" guard. That guard
            // exists only to stop the dialog reopening itself in the moment after it
            // is closed; it must not survive as a permanent "never ask again". Without
            // this reset, a user who closed the dialog once would never be guided
            // again on this machine, which is precisely the behaviour being fixed.
            dialogDismissedByUser = false;
            setStatus(I18n.t(Strings.STATUS_READING_PROFILES));
            // 用户把下拉框改成了「虚拟机」——他明确要连虚拟机，这时该引导他填配置。
            // 这是 applyConnectionMode 才有的许可：打开面板恢复上次方式时走的是
            // loadDistroAsync，那里传的是 false。
            refreshSshList(true, true);
        } else {
            setStatus(I18n.t(Strings.STATUS_LOOKING_WSL));
            loadDistroAsync(null);
        }
    }

    /**
     * Sets the source label and its tooltip for the given mode.
     *
     * <p>Extracted because two callers need exactly this pair of strings: opening
     * the panel in a mode, and re-labelling after a language switch. Duplicating it
     * is how the two drift apart and the label ends up describing the other mode.
     */
    private void applySourceLabels(boolean ssh) {
        if (distroLabel != null) {
            distroLabel.setText(I18n.t(ssh ? Strings.CONN_LABEL_SAVED : Strings.CONN_LABEL_WSL));
        }
        distroBox.setToolTipText(I18n.t(ssh ? Strings.HINT_PROFILE_BOX : Strings.HINT_DISTRO_BOX));
    }

    /**
     * 建好右键菜单的两项。语言切换时会重建，所以文案取当前语言。
     */
    private void rebuildProfileMenu() {
        profileMenu.removeAll();
        javax.swing.JMenuItem rename = new javax.swing.JMenuItem(
                I18n.t(Strings.MENU_PROFILE_RENAME));
        rename.setName("bioenv.profileRename");
        rename.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                renameSelectedProfile();
            }
        });
        profileMenu.add(rename);

        javax.swing.JMenuItem delete = new javax.swing.JMenuItem(
                I18n.t(Strings.BTN_PROFILE_DELETE));
        delete.setName("bioenv.profileDelete");
        delete.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                deleteSelectedProfile();
            }
        });
        profileMenu.add(delete);
        profileMenu.setName("bioenv.profileMenu");
    }

    /**
     * 给配置下拉框装上右键菜单：重命名 / 删除。
     *
     * <p>只在「虚拟机」方式、且框里真有配置时弹菜单。WSL 方式下同一个框里装的是发行版，
     * 对它谈"重命名配置"没有意义——弹一个点不动的菜单比不弹更让人困惑。
     *
     * <p>作用对象始终是<b>当前选中的那一条</b>，而不是右键点到的位置：下拉框一次只显示
     * 一条，所见即所选，这样不会出现"我点在这里，改的却是那里"。
     */
    private void wireDistroBoxPopup() {
        java.awt.event.MouseListener popup = new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                maybeShowProfileMenu(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                maybeShowProfileMenu(e);
            }
        };
        // pressed 与 released 都挂：哪个触发取决于平台，只挂一个会在某些系统上失灵。
        distroBox.addMouseListener(popup);
        if (distroLabel != null) {
            distroLabel.addMouseListener(popup);
        }
    }

    private void maybeShowProfileMenu(java.awt.event.MouseEvent e) {
        if (!e.isPopupTrigger() || !isSshMode() || distroBox.getItemCount() == 0) {
            return;
        }
        if (distroBox.getSelectedItem() == null) {
            return;
        }
        profileMenu.show(e.getComponent(), e.getX(), e.getY());
    }

    /**
     * 改掉当前选中配置的别名。
     *
     * <p>名字留空是允许的：那表示不要别名，显示名退回 {@code user@host:port}——
     * 相当于"把别名清掉"，这比强迫用户想个新名字合理。
     */
    private void renameSelectedProfile() {
        if (!isSshMode()) {
            return;
        }
        Object sel = distroBox.getSelectedItem();
        String name = sel == null ? null : sel.toString().trim();
        if (name == null || name.isEmpty()) {
            return;
        }

        String typed = (String) javax.swing.JOptionPane.showInputDialog(this,
                I18n.t(Strings.DLG_PROFILE_RENAME, name),
                I18n.t(Strings.DLG_PROFILE_RENAME_TITLE),
                javax.swing.JOptionPane.QUESTION_MESSAGE,
                null, null, name);
        if (typed == null) {
            return; // 取消
        }
        String newName = typed.trim();

        try {
            vmadapt.core.ConnectionStore.RenameResult r =
                    new vmadapt.core.ConnectionStore().rename(name, newName);
            switch (r) {
                case OK:
                    // 显示名变了，重新读一遍列表，让框里显示新名字。
                    setStatus(I18n.t(Strings.STATUS_PROFILE_RENAMED, name,
                            newName.isEmpty() ? name : newName));
                    // 记住的那台也要跟着改名，否则"上次用的机器"会指向一个不存在的名字。
                    if (name.equals(targetMemory.vmProfile())) {
                        targetMemory.rememberVmProfile(newName);
                    }
                    break;
                case DUPLICATE:
                    setStatus(I18n.t(Strings.ERR_PROFILE_RENAME_DUPLICATE, newName));
                    break;
                case NOT_FOUND:
                    setStatus(I18n.t(Strings.ERR_PROFILE_RENAME_MISSING, name));
                    break;
                default:
                    setStatus(I18n.t(Strings.ERR_PROFILE_RENAME_FAILED, name));
                    break;
            }
        } catch (Exception ex) {
            setStatus(I18n.t(Strings.ERR_PROFILE_RENAME_FAILED, name) + " " + ex.getMessage());
        }
        refreshSshList(false, false);
    }

    /**
     * 删掉下拉框里选中的那条配置。
     *
     * <p>按<b>下拉框里显示的那个名字</b>精确定位，找到哪条删哪条，而不是把名字交给
     * {@code ConnectionStore.remove} 让它在别名、地址、主机名之间挨个试。那个"最后比
     * 主机名"的兜底在这里是危险的：同一个主机上的两条配置（不同用户或不同端口）会被
     * 认成同一条，删掉的可能不是用户选的那条。界面上既然已经选中了一条，就按选中的删。
     */
    private void deleteSelectedProfile() {
        if (!isSshMode()) {
            return;
        }
        Object sel = distroBox.getSelectedItem();
        String name = sel == null ? null : sel.toString().trim();
        if (name == null || name.isEmpty()) {
            return;
        }

        int answer = javax.swing.JOptionPane.showConfirmDialog(this,
                I18n.t(Strings.CONFIRM_PROFILE_DELETE, name),
                I18n.t(Strings.CONFIRM_PROFILE_DELETE_TITLE),
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE);
        if (answer != javax.swing.JOptionPane.OK_OPTION) {
            return;
        }

        try {
            vmadapt.core.ConnectionStore store = new vmadapt.core.ConnectionStore();
            if (store.removeDisplayed(name)) {
                setStatus(I18n.t(Strings.STATUS_PROFILE_DELETED, name));
            } else {
                // 没删成有两种原因：文件写不进去，或那条已经不在了（另开一个窗口删过）。
                // 两者都如实说"删除失败"，不假装成功。
                setStatus(I18n.t(Strings.ERR_PROFILE_DELETE_FAILED, name));
            }
        } catch (Exception ex) {
            setStatus(I18n.t(Strings.ERR_PROFILE_DELETE_FAILED, name) + " " + ex.getMessage());
        }

        // 删掉的可能正是当前选中的那台，所以要重新读一遍列表，让它按真实内容重填。
        // mayGuide=false：删到一条不剩时不该顺势弹出连接向导——用户刚说的是"删掉"，
        // 不是"给我新建一个"。
        refreshSshList(false, false);
    }

    /**
     * Reads the saved profiles into the source box; opens the connection dialog
     * when there is nothing to choose from.
     *
     * <p>Reading is quick (one small JSON), but {@code ConnectionStore.list()}
     * copies the file aside when it is corrupt and always touches the user's home
     * directory. Doing it on a background thread keeps the event thread free at
     * the moment the panel opens or the mode changes.
     */
    private void refreshSshList() {
        refreshSshList(false);
    }

    /**
     * 读回已保存的连接配置并填进下拉框。
     *
     * @param auto 填好之后是否直接自动加载。面板打开、切换模式、切换目标都要自动；
     *             「刚关掉连接窗口」那一次不自动——用户可能只是去看了一眼，
     *             紧接着就去连一台机器会很突兀。
     */
    private void refreshSshList(final boolean auto) {
        refreshSshList(auto, true);
    }

    /**
     * @param auto   同 {@link #refreshSshList(boolean)}
     * @param mayGuide 是否允许在"从没连过"时自动弹出连接配置窗。
     *
     * <p>为什么要有这个参数：读配置列表这件事有两条来源，而它们对弹窗的期望相反。
     *
     * <ul>
     *   <li><b>用户把下拉框改成「虚拟机」</b>——他明确要连虚拟机了，这时该引导，
     *       传 {@code true}；</li>
     *   <li><b>打开面板、回到上次用的方式</b>——用户只是想看看命令列表，恢复上次的
     *       选择是便利，不是他要连虚拟机。传 {@code false}，不弹。</li>
     * </ul>
     *
     * <p>这两条以前共用同一个入口，于是"上次用过虚拟机方式"就等价于"每次打开都弹窗"，
     * 而弹窗本该由用户主动选虚拟机方式触发。
     */
    private void refreshSshList(final boolean auto, final boolean mayGuide) {
        final int mine = ++loadGeneration;
        new SwingWorker<java.util.List<vmadapt.core.SshTarget>, Void>() {
            @Override
            protected java.util.List<vmadapt.core.SshTarget> doInBackground() {
                return new vmadapt.core.ConnectionStore().list();
            }

            @Override
            protected void done() {
                if (mine != loadGeneration || !isSshMode()) {
                    return;
                }
                java.util.List<vmadapt.core.SshTarget> all;
                try {
                    all = get();
                } catch (Exception ex) {
                    setStatus(I18n.t(Strings.STATUS_PROFILE_READ_FAILED, ex.getMessage()));
                    return;
                }
                adjustingMode = true;
                try {
                    distroBox.removeAllItems();
                    for (vmadapt.core.SshTarget t : all) {
                        distroBox.addItem(displayNameOf(t));
                    }
                } finally {
                    adjustingMode = false;
                }
                // 只有一个配置时也<b>要显示</b>这个下拉框。
                //
                // 原来一条时把它藏起来（"没有可选的余地，还白占一行"）。但重命名和删除
                // 现在挂在它的右键上，藏起来就等于：只有一台机器的用户，那唯一一条配置
                // 既改不了名字也删不掉。它的角色从"选择器"变成了"这条配置的把手"，
                // 一条时正是最需要这个把手的时候。
                boolean show = !all.isEmpty();
                distroBox.setVisible(show);
                if (distroLabel != null) {
                    distroLabel.setVisible(show);
                }
                if (all.isEmpty()) {
                    setStatus(I18n.t(Strings.STATUS_NO_PROFILE));
                } else {
                    // Prefer whatever was selected before, so a refresh does not
                    // silently move the user to a different machine.
                    String keep = sshTarget == null ? null : displayNameOf(sshTarget);
                    if (keep != null && all.contains(keep)) {
                        distroBox.setSelectedItem(keep);
                    } else {
                        // 还没有选择时，回到上次用的那一台。
                        String remembered = targetMemory.vmProfile();
                        if (!remembered.isEmpty() && all.contains(remembered)) {
                            distroBox.setSelectedItem(remembered);
                        }
                    }
                    // 列表填好后 JComboBox 本来就会停在第一项上，也就是说配置已经
                    // 选好了。以前这里一律说「选一个再点扫描」，于是用户每次切到
                    // Linux VM 都要被催着去选一个本来就摆在那里、而且只有一条的
                    // 配置。有选中项就直接说清楚它能用了。
                    Object sel = distroBox.getSelectedItem();
                    if (sel != null) {
                        setStatus(I18n.t(Strings.STATUS_PROFILE_SELECTED, sel.toString()));
                    } else {
                        setStatus(I18n.t(Strings.STATUS_PROFILES_LOADED,
                                Integer.valueOf(all.size())));
                    }
                }
                // Opening the dialog is decided by "has this machine ever connected
                // successfully", NOT by whether a profile happens to exist:
                //   * never connected  -> guide the user in, whatever the list holds
                //     (an unusable profile is as much of a dead end as none at all);
                //   * connected before -> stay out of the way; the user knows the way
                //     and can open it deliberately.
                // dismissGuard handles the separate, session-scoped concern of not
                // reopening the thing the user just closed.
                //
                // mayGuide 另外把关：只有"用户主动选了虚拟机方式"这一条来源才允许弹。
                // 打开面板时恢复上次的选择不算，否则上次用过虚拟机就会每次开插件都弹。
                if (mayGuide && shouldAutoOpenDialog()) {
                    openConnectionDialog();
                } else if (auto && !all.isEmpty()) {
                    // 自动连接：先做可达性检查，能连上就恢复缓存。
                    // 这条路径失败只写日志和状态栏，不弹任何窗口。
                    startDiscovery(true);
                }
            }
        }.execute();
    }

    /**
     * Whether the connection dialog should open on its own right now.
     *
     * <p>Two independent conditions, and keeping them apart is the whole point:
     *
     * <ul>
     *   <li>{@link LinkState#hasEverConnected()} is a <b>durable fact</b> about the
     *       machine, so it survives a restart and decides whether guidance is still
     *       needed at all.</li>
     *   <li>{@link #dialogDismissedByUser} is a <b>session-scoped reaction</b> to
     *       the user closing the window a moment ago. It exists to stop the dialog
     *       immediately reopening itself, which is a loop the user cannot escape
     *       from — they close it, a refresh runs, and it comes straight back.</li>
     * </ul>
     *
     * <p>The previous implementation used one flag for both jobs, which made "the
     * user closed it once" and "this machine is already set up" indistinguishable:
     * dismissing the dialog permanently suppressed the guidance, and the dialog
     * never came back even on a machine that had never connected.
     */
    private boolean shouldAutoOpenDialog() {
        if (LinkState.hasEverConnected()) {
            return false;
        }
        return !dialogDismissedByUser;
    }

    /**
     * 下拉框里显示的名字：有别名用别名，否则用 {@code user@host:port}。
     *
     * <p>别名可能重复（存储层允许），所以选中后一律再回查一遍完整列表按名字
     * 取值，而不是把下拉框里的字符串直接当主机用。
     */
    private static String displayNameOf(vmadapt.core.SshTarget t) {
        // 规则由存储层提供，界面和"按显示名删除"用的是同一条，不会两边分岔。
        return vmadapt.core.ConnectionStore.displayName(t);
    }

    /**
     * Opens the connection-profile dialog, and refreshes the profile list once
     * it closes.
     *
     * <p>{@link vmadapt.ui.ConnectionPanel} is hosted in a JDialog rather than
     * reimplemented: it already carries test/save/generate-key/copy-key, and a
     * second copy would only drift from it. The dialog is non-modal (see the
     * comment below), so the user can keep working in the main panel while it is
     * open.
     *
     * <p>Returns silently when there is no display. A window cannot exist in a
     * headless JVM, and constructing a JDialog there throws HeadlessException -
     * which would propagate out of an ActionListener or a worker's done() and
     * take down the event thread. Nothing in this method is worth crashing the
     * panel for.
     */
    private void openConnectionDialog() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            // The profile path is a technical identifier, so it is passed as an
            // argument rather than baked into the translated sentence.
            setStatus(I18n.t(Strings.STATUS_NO_DISPLAY,
                    "%USERPROFILE%\\.vmadapt\\connections.json"));
            return;
        }
        if (connectionDialog == null) {
            java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
            connectionDialog = new javax.swing.JDialog(owner,
                    I18n.t(Strings.DIALOG_CONNECTION_TITLE));
            connectionDialog.setName("bioenv.connectionDialog");
            // Hand the connection panel the plugin's current language. The adapter
            // deliberately leaves t() unimplemented (returns null) so vmadapt falls
            // back to its own bilingual catalogue: the two modules use separate key
            // namespaces, and forwarding keys blindly would print raw keys like
            // "btn.test" on screen. Language switching still reaches the panel,
            // because the provider supplies langCode() and the change listener.
            vmadapt.ui.ConnectionPanel connectionPanel = new vmadapt.ui.ConnectionPanel();
            connectionPanel.setLangProvider(new PluginLangProvider());
            // A successful "Test connection" is one of the two events that count as
            // "this machine is set up" (the other is a successful Detect scan).
            //
            // This used to be inferred by watching the panel's status-label
            // background colour, because the panel published no success signal. That
            // coupled us to a private colour constant: changing the palette would have
            // silently stopped the suppression. ConnectionPanel now fires an explicit
            // callback, so the fact is reported rather than guessed.
            connectionPanel.setConnectedListener(
                    new vmadapt.ui.ConnectionPanel.ConnectedListener() {
                        @Override
                        public void connected(vmadapt.core.SshTarget target) {
                            LinkState.markConnected();
                        }
                    });
            connectionDialog.setContentPane(connectionPanel);
            connectionDialog.setSize(760, 560);
            connectionDialog.setMinimumSize(new Dimension(520, 380));
            connectionDialog.setLocationRelativeTo(this);
            connectionDialog.setResizable(true);
            // Dispose rather than exit, and rebuild the panel next time so the
            // dialog always opens with a freshly read config list.
            connectionDialog.setDefaultCloseOperation(
                    javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
            connectionDialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    connectionDialog = null;
                    // Set BEFORE refreshing, and deliberately not cleared afterwards:
                    // refreshSshList() decides in a SwingWorker's done(), i.e.
                    // asynchronously on this same thread, so a scoped flag would
                    // already be false again by the time it asks and the dialog would
                    // reopen itself immediately. That immediate reopen is the loop the
                    // user reported, and it must not come back.
                    //
                    // This is now the ONLY effect of closing the dialog. It no longer
                    // doubles as "this machine is set up": whether guidance is still
                    // needed is a durable fact kept by LinkState, so dismissing on a
                    // machine that has never connected only postpones the guidance to
                    // the next time the user switches into Linux VM mode.
                    dialogDismissedByUser = true;
                    if (isSshMode()) {
                        // The user may have added or deleted configs in there.
                        // mayGuide=false：这是他刚关掉那个窗之后的刷新，再弹一次
                        // 就是"关了又开"的死循环。
                        refreshSshList(false, false);
                    }
                }
            });
        }
        connectionDialog.setVisible(true);
        connectionDialog.toFront();
    }

    /**
     * Applies initial divider positions once the panel has been laid out.' The connection-profile dialog, created on demand; set back to null when closed. */
    private javax.swing.JDialog connectionDialog;

    /**
     * Exposes this plugin's language choice to the {@code vmadapt} module.
     *
     * <p>{@code vmadapt} must not depend on {@code bioenv.i18n} - it is a
     * standalone module that also builds on its own - so the dependency is
     * inverted: the plugin injects a provider instead.
     *
     * <p>{@link #t(String)} intentionally returns {@code null}. The two modules
     * keep separate key namespaces ({@code status.*} here, {@code btn.*} there),
     * so forwarding a key would miss and the panel would render raw key names
     * such as {@code btn.test}. Returning null tells {@code vmadapt} to use its
     * own catalogue, which is already bilingual; {@link #langCode()} and the
     * listener are what make switching work across the boundary.
     */
    private static final class PluginLangProvider implements vmadapt.ui.LangProvider {

        @Override
        public String langCode() {
            return I18n.get().code();
        }

        /**
     * Applies initial divider positions once the panel has been laid out.' Null on purpose: let the panel use its own catalogue. See the class note. */
        @Override
        public String t(String key) {
            return null;
        }

        @Override
        public void addListener(Runnable r) {
            I18n.addListener(r);
        }

        /**
     * Applies initial divider positions once the panel has been laid out.' Lets the panel detach when it is replaced, if it asks. */
        public void removeListener(Runnable r) {
            I18n.removeListener(r);
        }
    }

    /**
     * True while the user's own close of the dialog is being processed.
     *
     * <p>The automatic "no profiles yet, so open the dialog" behaviour must not
     * fire in response to the user dismissing that very dialog, or the window is
     * impossible to close: X disposes it, the refresh finds no profiles, and the
     * dialog springs straight back. The flag suppresses exactly that one reopen.
     */
    private boolean dialogDismissedByUser;

    /**
     * Switches the SSH channel to the selected profile: builds the adapter,
     * Scanner, Runner and remote file helpers.
     *
     * <p>Returns null when it is fine to continue (either WSL mode, or nothing
     * selected yet), otherwise a human-readable reason. Keeping every check here
     * saves both call sites - Detect and the restore path - from repeating the
     * failure branches.
     *
     * <p><b>Background threads only.</b> It reads the profile file and constructs
     * {@code SshTransport} and friends. The one event-thread caller (the combo
     * box listener) goes through {@link #refreshSelectionStatusAsync()} instead,
     * which hands the work off to a worker.
     */
    private String adoptSshTarget() {
        Object sel = distroBox.getSelectedItem();
        final String name = sel == null ? null : sel.toString().trim();
        if (name == null || name.isEmpty()) {
            return I18n.t(Strings.ERR_CHOOSE_PROFILE);
        }
        // Look the target up by name rather than trusting the displayed string: the
        // box may hold an alias, and the real address lives in the store.
        vmadapt.core.SshTarget t = null;
        for (vmadapt.core.SshTarget c : new vmadapt.core.ConnectionStore().list()) {
            if (displayNameOf(c).equals(name)) {
                t = c;
                break;
            }
        }
        if (t == null) {
            return I18n.t(Strings.ERR_PROFILE_MISSING, name);
        }
        String problem = t.validate();
        if (problem != null) {
            return problem;
        }
        this.sshTarget = t;
        // One SshTransport shared by the adapter and RemoteFiles keeps a single
        // connection policy (timeout, options) across scanning and file pushes.
        vmadapt.core.SshTransport raw = new vmadapt.core.SshTransport(t);
        this.sshFiles = new vmadapt.core.RemoteFiles(t, raw);
        this.sshScp = new vmadapt.core.ScpTransfer(t);
        return null;
    }

    /**
     * After the source box changes, resolves the selected profile on a worker and
     * updates the status line.
     *
     * <p>It exists for one reason: resolving reads the profile file, and the caller
     * is an {@code ActionListener} - the event thread. Nothing is connected here;
     * whether the host is reachable is Detect's business. This only checks that the
     * profile is locally usable.
     */
    private void refreshSelectionStatusAsync() {
        final int mine = ++loadGeneration;
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                String problem = adoptSshTarget();
                if (problem != null) {
                    return problem;
                }
                // Read back on the worker so the message is built from the same
                // snapshot the lookup used, not from a field the EDT might read
                // while this task is still running.
                return I18n.t(Strings.STATUS_PROFILE_SELECTED, sshTarget.toDisplay());
            }

            @Override
            protected void done() {
                if (mine != loadGeneration || !isSshMode()) {
                    return;
                }
                try {
                    setStatus(get());
                } catch (Exception ex) {
                    setStatus(I18n.t(Strings.STATUS_PROFILE_READ_FAILED, ex.getMessage()));
                    return;
                }
                // 切换目标之后自动加载。以前这里只写一行状态就让用户自己去点扫描，
                // 于是每次换一台机器都要手动等一次。自动路径只恢复缓存、绝不重扫；
                // 没扫过的目标会得到「点扫描」的提示。
                startDiscovery(true);
            }
        }.execute();
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private JPanel buildDiscoveryBar() {
        JPanel bar = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(2, 4, 2, 4);
        g.anchor = GridBagConstraints.WEST;

        // Connection mode leads the bar because it decides what the box next to it
        // contains: WSL distributions, or saved Linux VM profiles.
        g.gridx = 0; g.gridy = 0;
        modeLabel = new JLabel(I18n.t(Strings.CONN_MODE_LABEL));
        bar.add(modeLabel, g);
        g.gridx = 1;
        modeBox.setToolTipText(I18n.t(Strings.HINT_MODE_BOX));
        bar.add(modeBox, g);

        g.gridx = 2;
        // Kept in a field because its text switches between "WSL distro" and
        // "Saved profile" as the mode changes.
        distroLabel = new JLabel(I18n.t(Strings.CONN_LABEL_WSL));
        bar.add(distroLabel, g);
        g.gridx = 3;
        distroBox.setPreferredSize(new Dimension(150, distroBox.getPreferredSize().height));
        applySourceLabels(false);
        bar.add(distroBox, g);
        // No separate "configure" button: the connection dialog opens by itself the
        // moment there is a Linux VM profile to create or edit. A button that mostly
        // duplicated that moment was one more thing to explain, and it was dead in
        // WSL mode anyway.
        g.gridx = 4;
        bar.add(detectButton, g);
        g.gridx = 5;
        // 工具检索搬去了左侧工具树（见 buildCenter）。这里保留一个透明的弹性空列，
        // 语言按钮仍然被顶在右边缘 —— 撑开的宽度全在这一列左边。
        JPanel stretch = new JPanel();
        stretch.setOpaque(false);
        g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
        bar.add(stretch, g);

        // Language switch, last column, no weight: it takes its natural width and the
        // unused space stays with the filler above. anchor=EAST pins it to the right
        // even if the column is ever given more room than the button needs.
        g.gridx = 6;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        g.anchor = GridBagConstraints.EAST;
        // 风格切换器挨着语言按钮：两者都是"这个面板怎么显示"，放一起顺理成章。
        bar.add(themeSwitcher, g);
        g.gridx = 7;
        bar.add(langToggle, g);

        // 工具条整体跟着主题走；标签用次级灰，别和内容抢。
        UiTheme.role(bar, "surface");
        UiTheme.role(modeLabel, "muted");
        UiTheme.role(distroLabel, "muted");
        return bar;
    }

    /**
     * Builds the main area.
     *
     * <p>Every boundary the user might want to move is a {@link JSplitPane}, so
     * all three can be dragged:
     *
     * <pre>
     *   | tree |  +-------------------------------+
     *   |      |  | parameter form                |
     *   |      |  +-------------------------------+
     *   |      |  | command preview               |
     *   |      |  +-------------------------------+
     *   |      |  | output                        |
     *   |      |  +-------------------------------+
     *   |      |  | buttons (fixed)               |
     * </pre>
     *
     * <p>The earlier layout fixed the vertical arrangement with
     * {@code BorderLayout.NORTH}/{@code SOUTH} regions, which meant the form
     * absorbed all spare height and the other panes could not be resized at all.
     * Panes are given minimum sizes so that dragging cannot collapse one out of
     * existence.
     */
    private JSplitPane buildCenter() {
        // Tools are grouped by the environment that provides them: that is the
        // structure discovery produces, and it is how a user thinks about
        // their installations (DESIGN.md P9).
        toolTree.setRootVisible(true);
        toolTree.setShowsRootHandles(true);
        toolTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        JScrollPane treeScroll = new JScrollPane(toolTree);
        treeScrollRef = treeScroll;
        UiTheme.role(treeScroll, "surface");
        // 左侧工具树的外观：彩色圆点、引导线、名称与计数的字重分层、行高与底色。
        // 只有这一棵树走这套画法，面板其余部分（工具条/表单/预览/输出/按钮/状态栏）不变。
        ToolTreeStyle.apply(toolTree, treeScroll);
        treeBorder = BorderFactory.createTitledBorder(I18n.t(Strings.TITLE_TREE));
        treeScroll.setBorder(treeBorder);
        // 工具检索放在树标题下面、树上面：它筛的就是这棵树，贴着树一眼就明白作用范围。
        // 做成滚动面板的 column header，所以检索框不会跟着树一起滚走。
        filterLabel = new JLabel(I18n.t(Strings.LABEL_FILTER));
        UiTheme.role(filterLabel, "muted");
        filterLabel.setToolTipText(I18n.t(Strings.HINT_FILTER_FIELD));
        filterField.setToolTipText(I18n.t(Strings.HINT_FILTER_FIELD));
        JPanel filterRow = new JPanel(new BorderLayout(4, 0));
        filterRow.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
        filterRow.add(filterLabel, BorderLayout.WEST);
        filterRow.add(filterField, BorderLayout.CENTER);
        treeScroll.setColumnHeaderView(filterRow);
        // A modest minimum keeps the tree usable without preventing the divider
        // from moving. A large minimum here is what made the divider appear stuck
        // at the right: the split pane clamps a divider to the bounds implied by
        // both panes' minimum widths, so either side being too demanding leaves no
        // room to drag.
        treeScroll.setMinimumSize(new Dimension(120, 120));
        // Let long command names wrap rather than force a horizontal scrollbar,
        // which would otherwise push the tree's minimum width up.
        toolTree.setScrollsOnExpand(false);

        // The preview is editable: someone who knows the tool should be able to
        // type the command directly rather than work around the form. What the
        // plugin will not do is overwrite that: as soon as the text is edited by
        // hand, automatic updates stop and the text is left alone until the user
        // asks for it back.
        commandArea.setEditable(true);
        commandArea.setLineWrap(true);
        commandArea.setWrapStyleWord(true);
        commandArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        commandArea.setBackground(Color.WHITE);
        // Saying what the short names mean matters, because the pane is editable:
        // someone who reads "pep" as a file to be created in the working directory
        // would be surprised by where the argument actually points.
        commandArea.setToolTipText(I18n.t(Strings.HINT_COMMAND_AREA));
        // Distinguish the plugin's own writes from the user's typing: the former
        // must not count as an edit, or the preview would stop updating the moment
        // it was first filled in.
        commandArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                noteEdited();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                noteEdited();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                noteEdited();
            }
        });

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        commandPanel = new JPanel(new BorderLayout());
        commandPanelRef = commandPanel;
        UiTheme.role(commandPanel, "surface");
        UiTheme.role(commandArea, "term");
        commandBorder = BorderFactory.createTitledBorder(I18n.t(previewTitleKey()));
        commandPanel.setBorder(commandBorder);
        JScrollPane commandScroll = new JScrollPane(commandArea);
        UiTheme.role(commandScroll, "terminal");
        commandPanel.add(commandScroll, BorderLayout.CENTER);

        // The button sits inside the preview's own panel so the action is next to
        // the thing it acts on.
        rebuildPreviewButton.setEnabled(false);
        rebuildPreviewButton.setToolTipText(I18n.t(Strings.HINT_REBUILD_PREVIEW));
        rebuildPreviewButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                rebuildPreviewFromForm();
            }
        });
        JPanel cmdSouth = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 6, 2));
        JLabel hint = new JLabel(I18n.t(Strings.HINT_COMMAND_AREA_LONG));
        UiTheme.role(hint, "muted");
        hint.setForeground(new Color(0x66, 0x66, 0x66));
        cmdSouth.add(hint);
        cmdSouth.add(rebuildPreviewButton);
        commandPanel.add(cmdSouth, BorderLayout.SOUTH);
        commandPanel.setMinimumSize(new Dimension(200, 90));

        JPanel logPanel = new JPanel(new BorderLayout());
        logPanelRef = logPanel;
        UiTheme.role(logPanel, "surface");
        UiTheme.role(logArea, "surface");
        // 标题和"清空"放在同一行：左边"输出"，右边"清空"。
        //
        // 以前标题是 TitledBorder（画在边框上），按钮只能落在它下面一行 ——
        // 看起来像"输出"独占一行、按钮浮在右上角。改成一行两端的标题栏就对齐了。
        JPanel logHead = new JPanel(new BorderLayout(6, 0));
        logHead.setOpaque(false);
        logTitle = new JLabel(I18n.t(Strings.TITLE_OUTPUT));
        logTitle.setFont(logTitle.getFont().deriveFont(Font.BOLD));
        UiTheme.role(logTitle, "accent");
        logHead.add(logTitle, BorderLayout.WEST);
        logHead.add(clearLogButton, BorderLayout.EAST);
        logPanel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        logPanel.add(logHead, BorderLayout.NORTH);
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        logPanel.setMinimumSize(new Dimension(160, 80));
        // A preferred width makes the split pane give the column this much on its
        // own, which is what survives later layout passes: a divider location set
        // once is recalculated from the components' sizes, whereas the sizes
        // themselves are respected every time.
        logPanel.setPreferredSize(new Dimension(LOG_WIDTH_PX, 200));

        // The form and the preview stack in the middle; the output sits beside them
        // rather than below. Output is long and narrow by nature — a tool's messages
        // are lines of text — so a column gives it room to be read without taking
        // height from the form, and the form is what the user is working in.
        //
        // The preview is always shown, not folded. It is a few lines and it is what
        // the user checks before pressing Run; hiding it saved height but cost a
        // press every time it was wanted back.
        formAndPreview = new JPanel(new BorderLayout(0, 4));
        formAndPreview.add(formPanel, BorderLayout.CENTER);
        formAndPreview.add(commandPanel, BorderLayout.SOUTH);

        JPanel mainArea = new JPanel(new BorderLayout());
        // 运行/停止要和中间那张卡片连成一片：给 mainArea 卡片色，
        // 它下面的表单和底部按钮就同属一张卡，而不是按钮孤零零落在灰底上。
        UiTheme.role(mainArea, "surface");
        mainArea.add(formAndPreview, BorderLayout.CENTER);
        // The preferred width matters, not just the minimum. A split pane lays its
        // children out at their preferred sizes first, so a form whose preferred
        // width is small would leave the column narrow however the divider was set —
        // the divider position is recalculated from the child sizes on every layout
        // pass. Asking for a wide form is what makes the output column stay narrow.
        mainArea.setPreferredSize(new Dimension(900, 600));
        mainArea.setMinimumSize(new Dimension(320, 200));

        // The output column is given a fixed width when the pane first has a real
        // one. Setting the divider during construction or in doLayout does not work:
        // at both points the pane has no width, so its divider bounds are degenerate
        // and the value is thrown away as soon as the pane is laid out for real. A
        // resize event is the first moment the width is known.
        JSplitPane logSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                mainArea, logPanel);
        logSplit.setResizeWeight(1.0);   // the form absorbs the extra width
        logSplit.setDividerSize(6);
        logSplit.setBorder(null);
        logSplit.setMinimumSize(new Dimension(200, 200));
        logSplitRef = logSplit;
        logSplit.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean placed;

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (placed || logSplit.getWidth() <= 0) {
                    return;
                }
                int available = logSplit.getWidth();
                int max = available - logSplit.getDividerSize()
                        - minimumOf(logSplit.getLeftComponent(), true);
                int min = minimumOf(logSplit.getRightComponent(), true);
                if (max < min) {
                    max = min;
                }
                int width = Math.max(min, Math.min(LOG_WIDTH_PX, max));
                logSplit.setDividerLocation(Math.max(0,
                        available - logSplit.getDividerSize() - width));
                placed = true;
            }
        });

        // 底部只留 Run / Stop，而且只落在中间列下面 —— 输出列整列贯通到底。
        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4));
        stopButton.setEnabled(false);
        UiTheme.primary(runButton);
        actions.add(runButton);
        actions.add(stopButton);
        mainArea.add(actions, BorderLayout.SOUTH);

        // 另外三个按钮各有去处：帮助原文 / 编辑参数 跟着工具名走到中间面板右上角，
        // Clear log 进了输出框（见上面的 logPanel）。
        UiTheme.ghost(toolAssistantButton);
        UiTheme.ghost(learnedRulesButton);
        formPanel.addHeaderAction(toolAssistantButton);
        formPanel.addHeaderAction(learnedRulesButton);

        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.add(logSplit, BorderLayout.CENTER);
        // The middle column holds a three-column form whose own minimum can be wide.
        // Capping it here keeps room for the dividers to move.
        top.setMinimumSize(new Dimension(320, 200));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, top);
        split.setResizeWeight(0.24);
        split.setDividerSize(8);
        split.setBorder(null);
        // One-touch arrows are deliberately off: they draw a pair of collapse
        // buttons on the divider that duplicate what dragging already does, and
        // they sit on top of the tree's first rows.
        // Remembered so the initial proportions are applied once the window has
        // a real size; setting them now would be overwritten by layout.
        outerSplit = split;
        outerSplitPending = true;
        return split;
    }

    /**
     * Applies initial divider positions once the panel has been laid out.
     *
     * <p>Divider locations are meaningless before the component has a size: the
     * layout pass replaces them. Doing this after the first layout is what makes
     * the starting positions stick rather than being silently reset.
     *
     * <p>The tree width is set in pixels rather than as a fraction. A fraction
     * scales with the window, so in a narrow window it computes a position beyond
     * what the split pane allows and gets clamped — which is what made the tree
     * divider appear jammed against one edge and immovable.
     */
    @Override
    public void doLayout() {
        super.doLayout();
        if (outerSplitPending && outerSplit != null && outerSplit.getWidth() > 0) {
            int want = TREE_WIDTH_PX;
            int max = outerSplit.getWidth() - outerSplit.getDividerSize()
                    - minimumOf(outerSplit.getRightComponent(), true);
            int min = minimumOf(outerSplit.getLeftComponent(), true);
            if (max < min) {
                max = min; // the window is too narrow for both minimums
            }
            outerSplit.setDividerLocation(Math.max(min, Math.min(want, max)));
            outerSplit.setResizeWeight(0.0); // keep the tree width as set when resizing
            outerSplitPending = false;
        }

    }

    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout());
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        p.add(statusLabel, BorderLayout.CENTER);
        return p;
    }

    private void wireEvents() {
        detectButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startDiscovery();
            }
        });

        rebuildProfileMenu();
        wireDistroBoxPopup();

        filterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                refreshList();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                refreshList();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                refreshList();
            }
        });

        toolTree.addTreeSelectionListener(new TreeSelectionListener() {
            @Override
            public void valueChanged(TreeSelectionEvent e) {
                TreePath path = toolTree.getSelectionPath();
                if (path == null) {
                    return;
                }
                Object label = ((DefaultMutableTreeNode) path.getLastPathComponent())
                        .getUserObject();
                Tool t = nodeToTool.get(String.valueOf(label));
                if (t != null) {
                    onToolSelected(t);
                }
            }
        });

        formPanel.setChangeListener(new Runnable() {
            @Override
            public void run() {
                updatePreview();
            }
        });

        // Persist type corrections so the user's decision survives a restart.
        formPanel.setCorrectionSink(new SchemaFormPanel.CorrectionSink() {
            @Override
            public void correct(String command, String option,
                                bioenv.core.Schema.Option.Kind kind) {
                if (registry != null) {
                    registry.rememberCorrection(command, option, kind);
                    setStatus(I18n.t(Strings.STATUS_REMEMBERED_TYPE,
                            command, option, kind));
                }
            }
        });

        // Read those decisions back, so stating a kind once is enough. Without
        // this the chooser would forget on every restart, which is exactly the
        // repetition the memory exists to avoid.
        formPanel.setKindMemory(new SchemaFormPanel.KindMemory() {
            @Override
            public bioenv.core.Schema.Option.Kind recall(String command, String option) {
                if (registry == null) {
                    return null;
                }
                return registry.getCorrections().lookup(command, option);
            }
        });

        // The options window shows the command as well, so it needs the same text
        // the preview has. Supplied as a callback rather than reached for directly:
        // whoever builds the command is the one that knows how.
        formPanel.setCommandPreviewer(new SchemaFormPanel.CommandPreviewer() {
            @Override
            public String get() {
                return commandArea.getText();
            }
        });
        // Running from the window goes through the same path as the Run button, so
        // there is one definition of what running means.
        formPanel.setRunner(new SchemaFormPanel.Runner() {
            @Override
            public void run() {
                startRun();
            }
        });
        // Adding or removing a parameter by hand is reported in the status bar.
        formPanel.setStatusSink(new SchemaFormPanel.StatusSink() {
            @Override
            public void accept(String message) {
                setStatus(message);
                appendLog(message);
            }
        });
        // A command edited in the window is handed to the panel's preview, which is
        // then what runs.
        formPanel.setPreviewSink(new SchemaFormPanel.PreviewSink() {
            @Override
            public void setText(String text) {
                previewEdited = true;
                setPreviewText(text);
                updatePreviewHint();
            }
        });

        // Learn a subcommand's options only when the user opens it, rather than
        // probing every subcommand when the tool is selected (DESIGN.md P21).
        formPanel.setSubcommandLoader(new SchemaFormPanel.SubcommandLoader() {
            @Override
            public bioenv.core.Schema load(bioenv.core.Schema.Subcommand sub) {
                if (registry == null || selected == null) {
                    return null;
                }
                setStatus(I18n.t(Strings.STATUS_READING_HELP,
                        selected.command + " " + sub.name));
                bioenv.core.Schema child =
                        registry.introspectSubcommand(selected, sub);
                if (child != null) {
                    setStatus(I18n.t(Strings.STATUS_SUB_OPTIONS, selected.command,
                            sub.name, Integer.valueOf(child.options.size())));
                } else {
                    setStatus(I18n.t(Strings.STATUS_SUB_NO_OPTIONS, selected.command,
                            sub.name));
                }
                return child;
            }
        });

        runButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startRun();
            }
        });

        stopButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Runner.Job job = currentJob;
                if (job != null) {
                    job.cancel();
                    setStatus(I18n.t(Strings.STATUS_CANCELLING));
                }
            }
        });

        clearLogButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                logArea.setText("");
            }
        });

        // Choosing another distribution loads its tools straight away. Different
        // Linux systems hold different tools, so the list on screen has to follow
        // the box rather than wait for a second click.
        distroBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Two sources of programmatic changes: filling the box with WSL
                // distributions, and refilling it with profiles when the mode
                // changes. Neither is the user picking something.
                if (fillingDistros || adjustingMode) {
                    return;
                }
                Object sel = distroBox.getSelectedItem();
                if (sel == null) {
                    return;
                }
                logArea.setText("");
                lastReport = null;
                pendingScanReport = null;
                if (isSshMode()) {
                    // Picking a saved connection must not connect on its own: an
                    // unreachable machine would stall the event thread behind a
                    // timeout the user never asked for. Only record the choice and
                    // let Detect (a background worker) do the work.
                    //
                    // The lookup itself also stays off this thread: it reads the
                    // config file from disk, and this is an ActionListener, i.e.
                    // the event thread. So the work is handed to the same refresh
                    // path used elsewhere and the status line is written when it
                    // comes back.
                    refreshSelectionStatusAsync();
                    return;
                }
                loadDistroAsync(sel.toString(), true);
            }
        });

        modeBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // applyConnectionMode refills the source box, which fires the box's
                // own listener; that is not the user choosing anything.
                if (adjustingMode) {
                    return;
                }
                applyConnectionMode();
            }
        });

        toolAssistantButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openToolAssistant();
            }
        });

        learnedRulesButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showLearnedRules();
            }
        });
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /**
     * 一行进度文字的去处。
     *
     * <p>为什么要有这个接口：发现流程被拆成了 {@code discoverOverWsl} /
     * {@code discoverOverSsh} 两个普通方法，而 {@code SwingWorker.publish()}
     * 是 worker 的实例方法，普通方法里调不到。把 publish 作为一个回调传进去，
     * 两个通道的实现就能共用同一套「后台发、事件线程收」的机制，而不必各自
     * 拿 {@code SwingUtilities.invokeLater} 重造一遍。
     */
    private interface Progress {
        void say(String text);
    }

    private static final class DiscoveryResult {
        List<String> distros = new ArrayList<String>();
        String chosen;
        EnvScanner.Report report;
        EnvScanner scanner;
        Transport bridge;
        EnvRunner envRunner;
        ToolRegistry registry;
        ToolRegistry.Report scan;
        /**
         * SSH 方式下选中的配置，供 done() 里写状态文案用。
         *
         * <p>在后台线程里就把目标定下来并带回来，而不是在 done() 里重读下拉框：
         * 用户完全可能在一次漫长的 SSH 扫描期间又改了选择，那样状态栏会写成
         * 另一台机器的名字。
         */
        vmadapt.core.SshTarget sshTarget;
        /**
         * 本次运行是否走的是 Linux VM 通道。
         *
         * <p>必须和 {@link #sshTarget} 分开：{@code sshTarget} 为 null 有两种可能——
         * 没走 SSH，或者走了 SSH 但没选中任何配置。只靠它判断会把后者误当成 WSL，
         * 于是报出「WSL 不可达」这种和用户在做的事毫不相干的结论。
         */
        boolean sshMode;
        /**
         * 自动路径的探测结论；null 表示没做过可达性检查（手动路径不需要）。
         *
         * <p>非 REACHABLE 时界面要说清是哪一种「连不上」：端口被拒绝说明主机活着
         * 只是 sshd 没起来，超时多半是关机或地址变了，两者下一步动作不同。
         */
        vmadapt.core.Reachability.State reachState;
        /**
         * 自动路径发现「这个目标还没扫过」。
         *
         * <p>自动路径刻意不做长任务：没有缓存可恢复时，扫一遍要十几秒，而用户
         * 这次打开插件可能根本没打算用它。所以退回让用户自己点一次。
         */
        boolean needsManualScan;
    }

    /**
     * WSL 方式的发现流程。除通道构造外与改动前逐行一致。
     *
     * <p>在后台线程调用。{@code publish} 从 worker 里发出，由 {@code process}
     * 在事件线程上应用。
     */
    private DiscoveryResult discoverOverWsl(DiscoveryResult dr, Progress say, boolean auto) {
        say.say(I18n.t(Strings.STATUS_LOOKING_WSL));
        dr.distros = WslBridge.listDistros();
        if (dr.distros.isEmpty()) {
            dr.report = new EnvScanner.Report();
            dr.report.wslOk = false;
            // Two different situations end up here, and they need different
            // advice: nothing installed at all, or nothing but a container
            // engine's own systems, which have no bash and cannot host tools.
            java.util.List<String> all = WslBridge.listDistrosUnfiltered();
            if (all.isEmpty()) {
                dr.report.hints.add(I18n.t(Strings.HINT_NO_WSL_DISTRO));
            } else {
                dr.report.hints.add(I18n.t(Strings.HINT_ONLY_CONTAINER_DISTRO,
                        String.join(", ", all)));
            }
            return dr;
        }
        Object sel = distroBox.getSelectedItem();
        String distro = sel != null && dr.distros.contains(sel.toString())
                ? sel.toString() : WslBridge.preferredDistro(dr.distros);
        dr.chosen = distro;
        say.say(I18n.t(Strings.STATUS_USING_DISTRO, distro));

        Transport bridge = new WslBridge(distro);
        EnvScanner sc = new EnvScanner(bridge);
        say.say(I18n.t(Strings.STATUS_LOCATING_CONDA));
        dr.report = sc.discover();
        dr.scanner = sc;
        dr.bridge = bridge;
        if (!dr.report.condaOk) {
            return dr;
        }

        EnvRunner er = new EnvRunner(bridge, sc.getCondaRoot());
        dr.envRunner = er;
        dr.registry = new ToolRegistry(sc, er);

        // 先看缓存。这条路径同样曾经是无条件 scan()：点一次「扫描」就全量重扫，
        // 哪怕这个发行版几秒前刚扫过、环境一点没变。
        //
        // 注意它和 loadDistroAsync（面板打开时自动恢复）是两条独立的入口，
        // 只修其中一条会留下「自动恢复很快、手点扫描很慢」这种说不通的表现。
        int restored = dr.registry.loadCache();
        if (restored > 0 && (auto || dr.registry.cacheIsFresh())) {
            say.say(I18n.t(Strings.STATUS_RESTORED, Integer.valueOf(restored)));
            return dr;
        }
        if (auto) {
            // 自动路径只做「连上 + 读缓存」这类一秒内完成的事。
            //
            // 缓存过时或不存在时要扫十几秒，而用户这次打开插件可能根本没打算用它。
            // 把重扫留给用户主动点，自动路径的耗时才有上限。
            dr.needsManualScan = true;
            return dr;
        }
        say.say(I18n.t(restored > 0
                ? Strings.STATUS_RESCANNING
                : Strings.STATUS_NO_CACHE_SCAN));
        dr.scan = dr.registry.scan();
        return dr;
    }

    /**
     * SSH 方式的发现流程，与 {@link #discoverOverWsl} 同构。
     *
     * <p>两处刻意的差异，都是 SSH 特有的：
     *
     * <ul>
     *   <li>不调用 {@code WslBridge.listDistros()}。在 SSH 方式下列出 WSL 发行版
     *       既无意义，又会在没有装 WSL 的机器上白等一次子进程超时。</li>
     *   <li>失败时给出的排查建议是 SSH 的，而不是「装个 WSL2」。原提示里的
     *       {@code wsl --install} 对着一台连不上的虚拟机是误导。</li>
     * </ul>
     *
     * <p>在后台线程调用。SSH 连不通时要等到超时（默认 15 秒），这正是它不能在
     * 事件线程上跑的原因。
     */
    private DiscoveryResult discoverOverSsh(DiscoveryResult dr, Progress say, boolean auto) {
        // 先记下通道，再谈能不能连：后面任何一次提前返回都必须仍然知道
        // 「这是 Linux VM 方式」，否则失败文案会退回 WSL 的说法。
        dr.sshMode = true;
        say.say(I18n.t(Strings.STATUS_READING_PROFILES));
        final String problem = adoptSshTarget();
        if (problem != null) {
            dr.report = new EnvScanner.Report();
            dr.report.wslOk = false;
            dr.report.hints.add(problem);
            return dr;
        }
        final vmadapt.core.SshTarget target = sshTarget;
        dr.sshTarget = target;
        dr.chosen = displayNameOf(target);

        // 自动连接之前先问一次「虚拟机开没开机」。
        //
        // 不先问就直接 ssh，虚拟机关机时要等完整握手加可能的多次重试——用户看到
        // 的就是「插件一打开就卡住十几秒」。一次 TCP 连接几毫秒就能给出答案，
        // 失败也只需等到设定的上限，而且能说清是哪一种连不上。
        if (auto) {
            say.say(I18n.t(Strings.LOG_REACH_HEAD));
            vmadapt.core.Reachability.State st =
                    vmadapt.core.Reachability.check(target);
            dr.reachState = st;
            if (!vmadapt.core.Reachability.canProceed(st)) {
                dr.report = new EnvScanner.Report();
                dr.report.wslOk = false;
                return dr;
            }
        }

        say.say(I18n.t(Strings.STATUS_CONNECTING, target.toDisplay()));

        // The adapter translates between the Transport seam and vmadapt's own
        // SshTransport. It exposes user@host:port as the channel label, which is
        // what EnvScanner puts into its "not reachable" note.
        Transport bridge = new SshTransportAdapter(target);
        EnvScanner sc = new EnvScanner(bridge);
        say.say(I18n.t(Strings.STATUS_LOCATING_CONDA));
        dr.report = sc.discover();
        dr.scanner = sc;
        dr.bridge = bridge;
        if (!dr.report.condaOk) {
            // The scanner's own words say "WSL is not reachable", which is wrong for
            // this channel and would send the user looking for a WSL problem. Replace
            // the note and the hint with SSH-accurate text; the far side's stderr
            // lines (already appended by the scanner) are kept, since they are the
            // most specific thing anyone has said.
            rewriteReportForSsh(dr.report, target);
            return dr;
        }

        EnvRunner er = new EnvRunner(bridge, sc.getCondaRoot());
        dr.envRunner = er;
        dr.registry = new ToolRegistry(sc, er);

        // 先看缓存，和 WSL 路径保持同构。
        //
        // 这条路径以前是无条件 scan()，于是每次点 Detect 都是全量重扫——同一台
        // 虚拟机、环境一点没变、隔几秒再点，照样要把所有环境重新枚举一遍。
        // 用户感受就是「每次切过来都要等一次」，而缓存本来就是为了避免这件事。
        //
        // 缓存自带指纹和 distro 两重校验（见 ToolRegistry#cacheIsFresh）：环境变了
        // 或者换了一台机器都会照常重扫，不会拿旧数据糊弄。
        int restored = dr.registry.loadCache();
        if (restored > 0 && (auto || dr.registry.cacheIsFresh())) {
            say.say(I18n.t(Strings.STATUS_RESTORED, Integer.valueOf(restored)));
            return dr;
        }
        if (auto) {
            // 自动路径只做「连上 + 读缓存」这类一秒内完成的事。
            //
            // 缓存过时或不存在时要扫十几秒，而用户这次打开插件可能根本没打算用它。
            // 把重扫留给用户主动点，自动路径的耗时才有上限。
            dr.needsManualScan = true;
            return dr;
        }
        say.say(I18n.t(restored > 0
                ? Strings.STATUS_RESCANNING
                : Strings.STATUS_NO_CACHE_SCAN));
        dr.scan = dr.registry.scan();
        return dr;
    }

    /**
     * 把 {@link EnvScanner.Report} 里 WSL 专属的说法换成 SSH 的说法。
     *
     * <p>为什么在界面层做这个替换，而不是让 EnvScanner 去判断通道：EnvScanner
     * 已经能通过 {@code transport.getDistro()} 说出「探的是哪一台」，所以它给的
     * notes 里的地址是对的；只有「WSL」这个词和「装 WSL」这条建议是通道专属的。
     * 让扫描器为每种通道各写一套文案，会把通道判断扩散到 core；在唯一显示它的
     * 地方（这里）改写一次更省事，也不影响其它调用方。
     *
     * <p>只清 WSL 专属的 hint，保留扫描器从远端 stderr 抄回来的原话——那些是
     * 排查时最有价值的信息。
     */
    private static void rewriteReportForSsh(EnvScanner.Report rep,
                                            vmadapt.core.SshTarget target) {
        // Notes: swap the "WSL is not reachable" wording, keep any stderr lines.
        java.util.List<String> kept = new java.util.ArrayList<String>();
        for (String n : rep.notes) {
            // This prefix is matched against a string EnvScanner produces, not against
            // anything shown here. EnvScanner is still English-only, so translating
            // this literal would silently stop matching and the WSL wording would leak
            // through to a VM failure. It must stay in sync with EnvScanner, not with
            // the language pack.
            if (n != null && n.startsWith("WSL is not reachable")) {
                kept.add(I18n.t(Strings.NOTE_VM_UNREACHABLE, target.toDisplay()));
            } else {
                kept.add(n);
            }
            // 原实现里该行后面紧跟缩进的 stderr 行，同样以 "  " 开头，
            // 所以只按前缀判断首行即可，其余原样保留。
        }
        rep.notes.clear();
        rep.notes.addAll(kept);

        // Hints: drop WSL-only remediation, then add SSH-specific advice.
        java.util.List<String> sshHints = new java.util.ArrayList<String>();
        for (String h : rep.hints) {
            if (h == null) {
                continue;
            }
            // WSL-specific advice mentions WSL/wsl.exe/distro; anything else is
            // generic enough to keep (e.g. "the probe timed out").
            if (h.contains("WSL") || h.contains("wsl ")) {
                continue;
            }
            sshHints.add(h);
        }
        sshHints.add(I18n.t(Strings.HINT_SSH_SSHD));
        sshHints.add(I18n.t(Strings.HINT_SSH_PING, target.host));
        if (target.port != vmadapt.core.SshTarget.DEFAULT_PORT) {
            sshHints.add(I18n.t(Strings.HINT_SSH_PORT, Integer.valueOf(target.port)));
        }
        sshHints.add(I18n.t(Strings.HINT_SSH_KEY));
        // The key flag is assembled here and passed as one argument: whether it
        // exists depends on the target, and splitting it across two placeholders
        // would make the sentence's shape depend on data.
        String keyFlag = target.usesKey() ? " -i \"" + target.keyPath + "\"" : "";
        sshHints.add(I18n.t(Strings.HINT_SSH_CLI, Integer.valueOf(target.port),
                keyFlag, target.user, target.host));
        rep.hints.clear();
        rep.hints.addAll(sshHints);
    }

    private void startDiscovery() {
        startDiscovery(false);
    }

    /**
     * 开始一次发现。
     *
     * @param auto true 表示这是面板打开/切换目标时自动发起的，不是用户点的扫描。
     *             自动路径有两条硬约束：只恢复缓存、绝不重扫；失败只更新状态栏，
     *             不弹任何对话框。
     */
    private void startDiscovery(final boolean auto) {
        detectButton.setEnabled(false);
        logArea.setText("");
        final boolean sshMode = isSshMode();
        // Adopting the SSH target touches the config file, so it happens on the
        // worker below rather than here; what is checked now is only enough to fail
        // fast with a clear message before the button is even disabled for long.
        if (sshMode) {
            setStatus(I18n.t(Strings.STATUS_PREPARING_CONNECTION));
        } else {
            setStatus(I18n.t(Strings.STATUS_LOOKING_WSL));
        }
        // Takes over from whatever was loading: the panel may still be starting up,
        // and two reports interleaved in one log read as one printed twice.
        final int mine = ++loadGeneration;

        new SwingWorker<DiscoveryResult, String>() {
            @Override
            protected DiscoveryResult doInBackground() {
                DiscoveryResult dr = new DiscoveryResult();
                // The worker's own publish is the sink: lines are delivered by
                // process() on the event thread, exactly as before the split.
                Progress say = new Progress() {
                    @Override
                    public void say(String text) {
                        publish(text);
                    }
                };
                if (sshMode) {
                    return discoverOverSsh(dr, say, auto);
                }
                return discoverOverWsl(dr, say, auto);
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) {
                    setStatus(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                detectButton.setEnabled(true);
                if (mine != loadGeneration) {
                    return; // superseded by a newer load
                }
                try {
                    applyDiscovery(get());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    setStatus(I18n.t(Strings.STATUS_DISCOVERY_FAILED, c.getMessage()));
                    appendLog(I18n.t(Strings.LOG_ERROR_PREFIX) + c);
                }
            }
        }.execute();
    }

    private void applyDiscovery(DiscoveryResult dr) {
        showDistros(dr.distros, dr.chosen);

        EnvScanner.Report rep = dr.report;
        if (rep == null) {
            setStatus(I18n.t(Strings.STATUS_DISCOVERY_NO_RESULT));
            return;
        }
        if (!rep.condaOk) {
            // The channel this run actually used, not the one selected right now:
            // the user may have switched modes while this worker was in flight.
            final boolean sshMode = dr.sshMode;
            final boolean ssh = dr.sshTarget != null;
            applyDiscoveryReport(rep, null, null, sshMode, ssh);
            appendLog("");
            if (rep.wslOk) {
                // The channel answered, so conda is genuinely absent there. The
                // wording differs by channel because "inside WSL" is wrong advice
                // when the user pointed the panel at a virtual machine.
                appendLog(ssh
                        ? I18n.t(Strings.LOG_NO_CONDA_VM, dr.sshTarget.toDisplay())
                        : I18n.t(Strings.LOG_NO_CONDA_WSL));
            } else if (dr.reachState != null
                    && !vmadapt.core.Reachability.canProceed(dr.reachState)) {
                // 自动连接前的可达性检查没过。这条要排在通用的「连不上虚拟机」之前：
                // 我们根本没去连，说「连不上」不准确，而且这三种情况的下一步动作不同。
                //
                // 标题也写进日志：worker 里的 say() 只喂状态栏，一闪就没了，
                // 事后回看日志只见结论不见起因。
                appendLog(I18n.t(Strings.LOG_REACH_HEAD));
                appendLog(reachMessage(dr.reachState, dr.sshTarget));
                setStatus(I18n.t(Strings.STATUS_VM_NOT_REACHABLE));
            } else if (ssh) {
                // Connected, but something below conda failed.
                appendLog(I18n.t(Strings.LOG_UNREACHABLE_VM, dr.sshTarget.toDisplay()));
            } else if (sshMode) {
                // Linux VM mode, but no profile was selected, so nothing was tried.
                // Reporting "WSL unreachable" here - which is what used to happen,
                // because sshTarget is also null in this case - sends the user
                // looking at WSL while they are working with a virtual machine.
                appendLog(I18n.t(Strings.LOG_NO_PROFILE));
            } else {
                appendLog(I18n.t(Strings.LOG_UNREACHABLE_WSL));
            }
            nodeToTool.clear();
            treeRoot.removeAllChildren();
            treeModel.reload();
            return;
        }

        // The channel answered and conda was found in it: for an SSH run that means
        // the connection genuinely worked, so record it. This is one of the two
        // places that counts as "connected successfully" (the other is the dialog's
        // own Test connection button), and it is what stops the guidance dialog from
        // reappearing on every later switch into Linux VM mode.
        if (dr.sshTarget != null) {
            LinkState.markConnected();
            // 记住这一台，下次打开面板直接回到它。按配置名记，而不是按地址：
            // 用户在下拉框里看到的就是这个名字。
            targetMemory.rememberVmProfile(displayNameOf(dr.sshTarget));
        }

        // 自动路径发现这个目标还没扫过：不做长任务，退回让用户自己点一次。
        if (dr.needsManualScan) {
            appendLog(I18n.t(Strings.LOG_ENV_CHECK));
            appendLog(I18n.t(Strings.LOG_NO_CACHE_MANUAL));
            // 同 WSL 那条路径：这里没有扫描在跑，状态栏就不该说"正在扫描"。
            // SSH 的引导窗已在 refreshSshList 里按「有没有连过」打开过，所以这里
            // 只负责把话说准，不重复弹窗。
            setStatus(I18n.t(Strings.STATUS_NEEDS_FIRST_SCAN,
                    dr.sshTarget == null ? I18n.t(Strings.FORM_THIS_COMMAND)
                            : displayNameOf(dr.sshTarget)));
            return;
        }

        appendLog(I18n.t(Strings.LOG_ENV_CHECK));
        for (String n : rep.notes) {
            appendLog("  " + n);
        }
        adoptRegistry(dr.registry, dr.scanner, dr.bridge, dr.envRunner);
        // dr.scan 为 null 表示这一趟直接用缓存，没重扫（见 discoverOverSsh）。
        // 这时不能再报扫描统计，也不能拿 toolsKept 去拼状态文本。
        if (dr.scan != null) {
            reportScan(dr.scan);
        } else {
            appendLog(I18n.t(Strings.LOG_RESTORED_FOR, dr.chosen));
            appendLog(I18n.t(Strings.LOG_DETECT_FOR_NEW));
        }
        // 这份列表有多旧，用户得知道。自动恢复出来的可能是几小时前扫的，
        // 不写日期就没法判断值不值得点一次扫描——两种情况看起来一模一样。
        String when = scannedAtLine(dr.registry);
        if (!when.isEmpty()) {
            appendLog(when);
        }
        appendLog("");
        appendLog(I18n.t(Strings.LOG_PICK_COMMAND));

        refreshList();
        if (dr.scan != null) {
            setStatus(I18n.t(Strings.STATUS_READY_ACROSS,
                    Integer.valueOf(dr.scan.toolsKept),
                    Integer.valueOf(dr.scan.envsScanned)));
        } else {
            setStatus(I18n.t(Strings.STATUS_READY_COUNT,
                    Integer.valueOf(dr.registry == null
                            ? 0 : dr.registry.getTools().size()),
                    dr.chosen));
        }
    }

    // ------------------------------------------------------------------
    // Tool list
    // ------------------------------------------------------------------

    /**
     * Rebuilds the tree, grouping commands under the environment that provides
     * them and applying the filter to command names.
     */
    private void refreshList() {
        if (registry == null) {
            return;
        }
        String q = filterField.getText() == null ? "" : filterField.getText().trim()
                .toLowerCase();

        nodeToTool.clear();
        collapsedGroups.clear();
        treeRoot.removeAllChildren();

        Map<String, List<Tool>> grouped = registry.byEnvironment();
        int shown = 0;
        String keep = selected == null ? null : selected.command;

        for (Map.Entry<String, List<Tool>> e : grouped.entrySet()) {
            String envName = e.getKey();
            List<Tool> matching = new ArrayList<Tool>();
            for (Tool t : e.getValue()) {
                if (q.isEmpty() || t.command.toLowerCase().contains(q)) {
                    matching.add(t);
                }
            }
            if (matching.isEmpty()) {
                continue;
            }
            DefaultMutableTreeNode envNode = new DefaultMutableTreeNode(
                    envName + "  (" + matching.size() + ")");

            // Commands are grouped by the package that provided them, and a package
            // with many is shown as one collapsed row. A package may legitimately
            // ship hundreds of programs — EMBOSS installs several hundred — and
            // listing them flat buries every other environment in the tree.
            //
            // A package with few commands is not given a row of its own: an
            // environment holding one tool should read as one entry, not as a
            // folder containing one entry.
            java.util.LinkedHashMap<String, List<Tool>> byPackage =
                    new java.util.LinkedHashMap<String, List<Tool>>();
            for (Tool t : matching) {
                String pkg = t.packageName == null || t.packageName.isEmpty()
                        ? t.command : t.packageName;
                List<Tool> list = byPackage.get(pkg);
                if (list == null) {
                    list = new ArrayList<Tool>();
                    byPackage.put(pkg, list);
                }
                list.add(t);
            }

            // A filter overrides the collapse: the user is looking for something
            // specific, and hiding the matches would make the search appear to fail.
            boolean filtering = !q.isEmpty();

            for (Map.Entry<String, List<Tool>> p : byPackage.entrySet()) {
                List<Tool> group = p.getValue();
                boolean large = group.size() > ToolRegistry.PACKAGE_GROUP_THRESHOLD;
                if (large && !filtering) {
                    DefaultMutableTreeNode pkgNode = new DefaultMutableTreeNode(
                            p.getKey() + "  ("
                                    + I18n.t(Strings.TREE_PACKAGE_COUNT,
                                            Integer.valueOf(group.size()))
                                    + ")");
                    for (Tool t : group) {
                        String label = t.label();
                        DefaultMutableTreeNode n = new DefaultMutableTreeNode(label);
                        nodeToTool.put(label, t);
                        pkgNode.add(n);
                        shown++;
                    }
                    envNode.add(pkgNode);
                    collapsedGroups.add(new TreePath(
                            new Object[]{treeRoot, envNode, pkgNode}));
                } else {
                    for (Tool t : group) {
                        String label = t.label();
                        DefaultMutableTreeNode n = new DefaultMutableTreeNode(label);
                        nodeToTool.put(label, t);
                        envNode.add(n);
                        shown++;
                    }
                }
            }
            treeRoot.add(envNode);
        }

        treeModel.reload();

        // Expand every environment, but leave any oversized sub-group closed.
        for (int i = 0; i < treeRoot.getChildCount(); i++) {
            DefaultMutableTreeNode envNode = (DefaultMutableTreeNode) treeRoot.getChildAt(i);
            toolTree.expandPath(new TreePath(new Object[]{treeRoot, envNode}));
            if (keep != null) {
                for (int j = 0; j < envNode.getChildCount(); j++) {
                    DefaultMutableTreeNode n =
                            (DefaultMutableTreeNode) envNode.getChildAt(j);
                    Tool t = nodeToTool.get(String.valueOf(n.getUserObject()));
                    if (t != null && keep.equals(t.command)) {
                        toolTree.setSelectionPath(new TreePath(
                                new Object[]{treeRoot, envNode, n}));
                    }
                }
            }
        }
        for (TreePath p : collapsedGroups) {
            toolTree.collapsePath(p);
        }

        // Say when results are folded away, so a short list is never mistaken
        // for a complete one. Without this the user cannot tell whether a search
        // found nothing or merely found things inside a collapsed group.
        int folded = 0;
        for (TreePath p : collapsedGroups) {
            DefaultMutableTreeNode node =
                    (DefaultMutableTreeNode) p.getLastPathComponent();
            folded += node.getChildCount();
        }
        // Built from translated fragments rather than one template: the parts are
        // optional and independent (a filter may or may not be active, groups may or
        // may not be folded), and forcing them into one sentence would need a
        // template per combination.
        String filterPart = q.isEmpty() ? "" : I18n.t(Strings.STATUS_TREE_MATCHING, q);
        String foldedPart = folded > 0
                ? I18n.t(Strings.STATUS_TREE_FOLDED, Integer.valueOf(folded)) : "";
        setStatus(I18n.t(Strings.STATUS_TREE_SUMMARY,
                Integer.valueOf(shown), Integer.valueOf(grouped.size()))
                + filterPart + foldedPart);
    }

    private void onToolSelected(final Tool t) {
        selected = t;
        formPanel.showTool(t);
        // A hand-written command belongs to the tool it was written for, so a new
        // tool starts with the preview following the form again.
        previewEdited = false;
        updatePreviewHint();
        updatePreview();
        updateRunEnabled();

        // Introspect lazily: probing help costs process launches, so it happens
        // when a command is actually opened rather than for the whole list.
        if (t.schema != null || introspecting) {
            return;
        }
        introspecting = true;
        setStatus(I18n.t(Strings.STATUS_READING_HELP, t.command));
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                registry.introspect(t);
                return null;
            }

            @Override
            protected void done() {
                introspecting = false;
                if (selected != t) {
                    return;
                }
                formPanel.showTool(t);
                updatePreview();
                if (t.isOpaque()) {
                    setStatus(I18n.t(Strings.STATUS_HELP_UNPARSED, t.command));
                    appendLog("");
                    appendLog(I18n.t(Strings.LOG_HELP_UNPARSED, t.command));
                } else {
                    setStatus(I18n.t(Strings.STATUS_OPTIONS_FOUND, t.command,
                            Integer.valueOf(t.schema.options.size())
                                    + (t.isMultiLevel()
                                            ? I18n.t(Strings.STATUS_SUBCOMMAND_COUNT,
                                                    Integer.valueOf(
                                                            t.schema.subcommands.size()))
                                            : "")));
                }
                updateRunEnabled();
            }
        }.execute();
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    private void updatePreview() {
        if (selected == null || scanner == null) {
            shownPreview = null;
            setPreviewText("");
            return;
        }
        // Once the user has written the command themselves, it is theirs. Form
        // changes no longer touch it, because silently replacing a command someone
        // typed would be worse than showing a stale one.
        if (previewEdited) {
            return;
        }
        try {
            Map<String, bioenv.core.ParamValue> values = formPanel.collect();
            // Show only what the user expressed. The conda wrapper is how the
            // plugin reaches the tool, not part of the user's command, so it
            // stays out of the preview and appears in the log on run instead.
            //
            // Paths are shown as their last name: a path picked in the file
            // chooser is a full Windows path, and a line of them buries the
            // arguments it is supposed to make readable (DESIGN.md P13). The
            // full paths are restored before the command runs — see
            // expandPreviewText.
            bioenv.core.ConcisePreview shown = bioenv.core.ConcisePreview.of(
                    selected, formPanel.currentSchema(), formPanel.currentSubPath(),
                    values, formPanel.rawArgs());
            shownPreview = shown;
            setPreviewText(shown.text());
            commandArea.setCaretPosition(0);
        } catch (Exception e) {
            shownPreview = null;
            setPreviewText(I18n.t(Strings.LOG_CANNOT_BUILD, e.getMessage()));
        }
    }

    /**
     * Writes out the paths a hand-edited preview shows by name alone.
     *
     * <p>The pane is short because the form's paths are long, not because the
     * command is: running the shown text as it stands would send the tool to a
     * name relative to the working directory instead of the file the user chose.
     * The expansion happens here, at the one point where the preview's text
     * becomes a run, so the rest of the panel may treat the pane as a plain
     * command.
     */
    private String expandPreviewText(String text) {
        return shownPreview == null ? text : shownPreview.expand(text);
    }

    /**
     * Writes text into the preview without counting it as a user edit.
     *
     * <p>The document listener fires for the plugin's own writes too, so the guard
     * is raised around them. Without it the first fill-in would look like an edit
     * and the preview would freeze immediately.
     */
    private void setPreviewText(String text) {
        previewWriting = true;
        try {
            commandArea.setText(text);
        } finally {
            previewWriting = false;
        }
        // The options window shows the same command; keeping the two in step means
        // the window is never showing something the panel has moved past.
        formPanel.refreshWindowPreview();
    }

    /**
     * Applies initial divider positions once the panel has been laid out.' Records that the user has taken the preview over. */
    private void noteEdited() {
        if (previewWriting || previewEdited) {
            return;
        }
        previewEdited = true;
        updatePreviewHint();
        appendLog(I18n.t(Strings.HINT_PREVIEW_EDITED));
    }

    /**
     * Applies initial divider positions once the panel has been laid out.' Updates the preview's title to say which of the two states it is in. */
    private void updatePreviewHint() {
        if (commandPanel == null) {
            return;
        }
        refreshCommandPanelTitle();
        if (rebuildPreviewButton != null) {
            rebuildPreviewButton.setEnabled(previewEdited && selected != null);
        }
    }

    /**
     * The title key for the preview, which depends on whether it is hand-edited.
     *
     * <p>Extracted so the initial border and the language-switch refresh cannot
     * disagree about which state the preview is in.
     */
    private String previewTitleKey() {
        return previewEdited ? Strings.TITLE_PREVIEW_EDITED : Strings.TITLE_PREVIEW;
    }

    /**
     * Applies the current preview title to the border.
     *
     * <p>Sets the text on the existing {@link javax.swing.border.TitledBorder}
     * rather than replacing the border object: replacing it would drop any other
     * border decoration that had been composed onto the panel, and it also churns
     * the component tree on every keystroke.
     */
    private void refreshCommandPanelTitle() {
        if (commandBorder != null) {
            commandBorder.setTitle(I18n.t(previewTitleKey()));
        }
    }

    /**
     * Returns the preview to being generated from the form.
     *
     * <p>The way back matters: without it, one accidental keystroke would leave the
     * preview permanently detached from the form with no way to reconnect them.
     */
    private void rebuildPreviewFromForm() {
        previewEdited = false;
        updatePreview();
        updatePreviewHint();
        setStatus(I18n.t(Strings.STATUS_PREVIEW_REBUILT));
    }

    private void updateRunEnabled() {
        runButton.setEnabled(selected != null && runner != null);
        toolAssistantButton.setEnabled(selected != null);
    }

    private void startRun() {
        if (selected == null || runner == null) {
            return;
        }
        // The run is not gated on any option being filled in.
        //
        // Whether an option is mandatory is the program's own determination, so
        // the program is the one to report a missing argument — and it does so
        // accurately, including requirements that depend on other options, which
        // the plugin cannot see. Gating here also blocked calls that need no other
        // argument at all, such as a help or version invocation.
        formPanel.clearValidation();

        final Tool tool = selected;
        final Schema schema = formPanel.currentSchema();
        final String subPath = formPanel.currentSubPath();
        final Map<String, bioenv.core.ParamValue> values = formPanel.collect();
        final String raw = formPanel.rawArgs();
        final String condaRoot = scanner.getCondaRoot();
        // A command the user wrote by hand is the command that runs. They edited
        // the preview, so that text — not the form — is what they asked for. The
        // working directory is still taken from the form: it is a setting of the
        // run rather than part of the command text.
        //
        // The pane writes long paths as their last name, so the text is expanded
        // before it runs: what is shown short is only a way of reading the
        // command, never a shorter command.
        final String typed = previewEdited
                ? expandPreviewText(commandArea.getText()) : null;
        bioenv.core.ParamValue wdValue = values.get("__workdir__");
        final String workdir = (wdValue == null || !wdValue.contributes())
                ? null : wdValue.commandText();

        final Runner.Job job = new Runner.Job();
        currentJob = job;
        runButton.setEnabled(false);
        stopButton.setEnabled(true);
        setStatus(I18n.t(Strings.STATUS_RUNNING, tool.command));
        appendLog("");
        appendLog(I18n.t(Strings.LOG_RUN_STARTED, tool.command
                + (subPath.isEmpty() ? "" : " " + subPath)));

        new SwingWorker<Runner.RunResult, String[]>() {
            @Override
            protected Runner.RunResult doInBackground() {
                if (typed != null) {
                    return runner.runTyped(tool, typed, condaRoot, workdir, job,
                            new Transport.LineSink() {
                                @Override
                                public void line(String text, boolean isError) {
                                    publish(new String[]{text, isError ? "1" : "0"});
                                }
                            });
                }
                return runner.run(tool, schema, subPath, values, raw, condaRoot, job,
                        new Transport.LineSink() {
                            @Override
                            public void line(String text, boolean isError) {
                                publish(new String[]{text, isError ? "1" : "0"});
                            }
                        });
            }

            @Override
            protected void process(List<String[]> chunks) {
                StringBuilder sb = new StringBuilder();
                for (String[] c : chunks) {
                    sb.append(c[0]).append('\n');
                }
                appendLogRaw(sb.toString());
            }

            @Override
            protected void done() {
                runButton.setEnabled(true);
                stopButton.setEnabled(false);
                currentJob = null;
                try {
                    Runner.RunResult r = get();
                    String secs = String.format("%.1f", r.elapsedMillis / 1000.0);
                    if (r.cancelled) {
                        appendLog(I18n.t(Strings.LOG_RUN_CANCELLED, secs));
                        setStatus(I18n.t(Strings.STATUS_CANCELLED));
                    } else if (r.ok()) {
                        appendLog(I18n.t(Strings.LOG_RUN_FINISHED, secs));
                        setStatus(I18n.t(Strings.STATUS_DONE_IN, secs));
                    } else {
                        appendLog(I18n.t(Strings.LOG_RUN_EXITED,
                                Integer.valueOf(r.exitCode), secs));
                        setStatus(I18n.t(Strings.STATUS_EXITED_CODE,
                                Integer.valueOf(r.exitCode)));
                        appendLog(I18n.t(Strings.LOG_RUN_HINT));
                    }
                } catch (Exception ex) {
                    Throwable c = ex.getCause() == null ? ex : ex.getCause();
                    appendLog(I18n.t(Strings.LOG_ERROR_PREFIX) + c);
                    setStatus(I18n.t(Strings.STATUS_FAILED, c.getMessage()));
                }
            }
        }.execute();
    }

    /**
     * Shows the kinds this tool's parameters are set to, and lets the user change
     * or remove any of them.
     *
     * <p>Scoped to the selected tool on purpose. The list used to combine every
     * tool's entries with rules that applied everywhere, so a change made while
     * looking at one tool could alter another — a decision the user could neither
     * see coming nor trace afterwards. Each tool now shows only its own settings,
     * and a setting made here affects nothing else (DESIGN.md P23).
     */
    private void showLearnedRules() {
        if (registry == null || selected == null) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    I18n.t(Strings.DIALOG_SELECT_TOOL_FIRST));
            return;
        }
        final String command = selected.command;
        final Schema schema = formPanel.currentSchema();
        if (schema == null) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    I18n.t(Strings.DIALOG_PARAMS_UNAVAILABLE));
            return;
        }

        // Scoped to the tool and the subcommand being shown, which is what the form
        // is showing and what an edit should affect. A subcommand shares parameter
        // names with its siblings routinely, so the tool name alone is not enough.
        final String scope = formPanel.currentScopeKey();
        final ParameterEditorPanel editor = new ParameterEditorPanel(
                schema, scope, registry.getCustomOptions());
        editor.setCurrentSchema(schema);

        // A frame rather than a dialog, so the window carries the system's maximise
        // button and can be filled to the screen. A tool with sixty parameters wants
        // the room, and a dialog has only a close button.
        final javax.swing.JFrame window = new javax.swing.JFrame(
                I18n.t(Strings.DIALOG_PARAMS_TITLE, command));
        window.setName("bioenv.parameterEditor");
        window.setContentPane(editor);
        window.setSize(900, 660);
        window.setMinimumSize(new Dimension(520, 360));
        window.setLocationRelativeTo(this);
        window.setResizable(true);
        window.setDefaultCloseOperation(
                javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                // The title bar's close is a cancel: nothing had been written, and
                // the editor's own OK is the only way through.
                window.dispose();
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                // The editor registers a static language listener, so it must
                // unregister when the window goes away; otherwise every editor ever
                // opened keeps running its refresh on each language switch.
                editor.dispose();
            }
        });
        // What to do when OK is pressed, run from the button's own callback.
        //
        // Showing a frame returns at once, unlike a modal dialog, so there is no
        // point after setVisible at which the answer is known. Waiting to be asked
        // afterwards is what made every button in this panel appear to do nothing:
        // the answer was read as "not accepted" before the press had happened, and
        // the press then arrived with nobody listening.
        editor.setAcceptHandler(new ParameterEditorPanel.AcceptHandler() {
            @Override
            public void accepted(ParameterEditorPanel done) {
                applyParameterEdits(done);
            }
        });
        editor.setHostWindow(window);
        window.setVisible(true);
    }

    /**
     * Opens the tool assistant: the tool's help on one tab, the user's notes on
     * another.
     *
     * <p>A frame rather than a dialog, for the same reason the parameter editor is
     * one: help documents are wide, and maximising is the natural answer to a long
     * option table. A dialog offers only a close button.
     *
     * <p>The notes are saved per scope - the command, and the command plus its
     * subcommand when one is selected - so writing something about {@code bio
     * fasta} does not put it under {@code bio gff}, which routinely shares
     * parameter names.
     */
    private void openToolAssistant() {
        if (selected == null) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    I18n.t(Strings.DIALOG_SELECT_TOOL_FIRST));
            return;
        }
        final String command = selected.command;
        final Schema schema = formPanel.currentSchema();
        final String scope = formPanel.currentScopeKey();

        // An empty help tab would read as a broken window, so the two ways it can
        // be empty are spelled out separately: the help has not been read yet, or
        // the tool was read and printed nothing. The old viewer drew the same
        // distinction, and it is the difference between "wait" and "there is
        // nothing to wait for".
        String raw = formPanel.rawHelpText();
        final String helpText;
        if (schema == null) {
            helpText = I18n.t(Strings.FORM_NO_HELP_YET);
        } else if (raw.trim().isEmpty()) {
            helpText = I18n.t(Strings.FORM_NO_HELP_OUTPUT,
                    command.isEmpty() ? I18n.t(Strings.FORM_THIS_COMMAND) : command);
        } else {
            helpText = raw;
        }

        final ToolAssistantPanel assistant =
                new ToolAssistantPanel(command, helpText, scope, toolNotes);

        final javax.swing.JFrame window = new javax.swing.JFrame(
                I18n.t(Strings.ASSISTANT_TITLE, command));
        window.setName("bioenv.toolAssistantWindow");
        window.setContentPane(assistant);
        window.setSize(900, 660);
        window.setMinimumSize(new Dimension(520, 380));
        window.setLocationRelativeTo(this);
        window.setResizable(true);
        window.setDefaultCloseOperation(
                javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);

        assistant.setOnCancel(new Runnable() {
            @Override
            public void run() {
                window.dispose();
            }
        });
        assistant.setOnSave(new Runnable() {
            @Override
            public void run() {
                setStatus(I18n.t(Strings.ASSISTANT_SAVED, command));
                window.dispose();
            }
        });

        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                // The title bar's close is a cancel: the notes are only written by
                // the Save button, so a half-typed note is not kept by accident.
                window.dispose();
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                // The panel registered a language listener; without this every
                // assistant window ever opened would keep refreshing forever.
                assistant.dispose();
            }
        });
        window.setVisible(true);
    }

    /**
     * Applies initial divider positions once the panel has been laid out.' Writes the editor's result to the stores and shows it in the option list. */
    private void applyParameterEdits(ParameterEditorPanel editor) {
        if (editor.isReset()) {
            // Read the tool's help again. Asking the program is the only way to be
            // sure of getting its own parameters back: names, descriptions and
            // deletions all lived in a copy that editing had already overwritten.
            boolean ok = registry != null && selected != null
                    && registry.relearn(selected, editor.scope());
            if (ok) {
                refreshForm();
                setStatus(I18n.t(Strings.STATUS_HELP_REREAD));
            } else {
                setStatus(I18n.t(Strings.STATUS_HELP_REREAD_FAILED));
            }
            return;
        }
        String what = editor.applyChanges();

        // Deletions and renames are written into the help text itself by
        // applyChanges, which is what the list is built from; the cache holds the
        // same text, so it is written back. A change that only lived in memory would
        // return on restart.
        int deleted = editor.purgedFlags().size();
        int renamed = editor.renames().size();

        // The panel applies the edit to the schema it is showing and lays itself out
        // again. It is done there rather than here because the panel is the only
        // thing that knows what it has loaded; a caller holding a different object —
        // the tool's schema, or one from the cache — would change a list nothing is
        // displaying.
        formPanel.applyEditorResult(editor.chosenKinds());
        if ((deleted > 0 || renamed > 0) && registry != null) {
            registry.saveCache();
        }
        updatePreview();

        StringBuilder status = new StringBuilder();
        if (!what.isEmpty()) {
            status.append(what);
        }
        if (deleted > 0) {
            appendClause(status, I18n.t(Strings.STATUS_PARAMS_DELETED,
                    Integer.valueOf(deleted)));
        }
        if (renamed > 0) {
            appendClause(status, I18n.t(Strings.STATUS_PARAMS_RENAMED,
                    Integer.valueOf(renamed)));
        }
        if (status.length() == 0) {
            setStatus(I18n.t(Strings.STATUS_PARAMS_UPDATED));
        } else {
            appendLog(status.toString());
            setStatus(status.toString());
        }
    }

    private static void appendClause(StringBuilder sb, String clause) {
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(clause);
    }

    /**
     * Applies initial divider positions once the panel has been laid out.' Reloads the selected tool so stored settings take effect immediately. */
    private void refreshForm() {
        if (selected != null) {
            formPanel.showTool(selected);
        }
    }

    /**
     * Applies initial divider positions once the panel has been laid out.' Escapes the few characters that matter inside the dialog's HTML label. */
    private static String escapeForHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------

    private void setStatus(String text) {
        statusText.set(text == null ? "" : text);
        FileDialogs.onEdt(applyStatus);
    }

    /**
     * 这几个 Runnable <b>刻意在构造时就建好，而不是每次调用时 new</b>。
     *
     * <p>每次 {@code new Runnable(){...}} 都要按需加载一个新的匿名类。而 TBtools
     * 重装插件时是<b>先把 jar 换成新的</b>，旧实例此刻再想加载自己的类就取不到了：
     * 于是「只是刷新一下状态栏」这种动作会炸成未捕获异常，用户每次装插件都看到
     * 一大段栈——而真正该做的事（重开 TBtools）反而没人告诉他。
     *
     * <p>把实例预先建好之后，孤儿实例最多是状态栏或日志不再刷新，不会再抛。
     * 这不是绕过问题：一个已经没有 jar 支撑的实例确实什么都做不了，能安静地
     * 退场就是它应有的行为。
     */
    private final java.util.concurrent.atomic.AtomicReference<String> statusText =
            new java.util.concurrent.atomic.AtomicReference<String>("");
    private final Runnable applyStatus = new Runnable() {
        @Override
        public void run() {
            statusLabel.setText(statusText.get());
        }
    };

    /**
     * 待写入日志的行：{@code [是否先换行, 文本]}。
     *
     * <p><b>必须用队列，不能用单个槽位。</b>连续两次 appendLog 都发生在事件线程
     * 执行之前，若只存一份，第二次会把第一次的内容覆盖掉——日志于是丢行或重复。
     * 用队列则每条都保留，且仍然不需要每次 new 一个新类。
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<String[]> logQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<String[]>();
    private final Runnable applyLog = new Runnable() {
        @Override
        public void run() {
            String[] item;
            while ((item = logQueue.poll()) != null) {
                if ("1".equals(item[0]) && logArea.getDocument().getLength() > 0) {
                    logArea.append("\n");
                }
                logArea.append(item[1]);
            }
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }
    };

    private void appendLog(String text) {
        logQueue.add(new String[]{"1", text == null ? "" : text});
        FileDialogs.onEdt(applyLog);
    }

    private void appendLogRaw(String text) {
        logQueue.add(new String[]{"0", text == null ? "" : text});
        FileDialogs.onEdt(applyLog);
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}


