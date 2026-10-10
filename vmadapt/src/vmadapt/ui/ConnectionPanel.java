package vmadapt.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

import vmadapt.core.ConnectionStore;
import vmadapt.core.KeyInstaller;
import vmadapt.core.RemoteSetup;
import vmadapt.core.SshAskpass;
import vmadapt.core.SshTarget;
import vmadapt.core.VmConnector;
import vmadapt.core.VmConnector.ConnectionReport;

/**
 * 虚拟机 SSH 连接配置面板。
 *
 * <p>这是一个可以直接塞进 TBtools 宿主插件的普通 {@link JPanel}，也可以被
 * {@link MainFrame} 单独装进窗口跑起来验证，两种用法都不依赖宿主提供的上下文。
 *
 * <p>它负责的事情只有三件：把连接参数收集成一个 {@link SshTarget}、把
 * {@link VmConnector} 的探测结果翻译成人话、以及在需要时调用外部的
 * ssh-keygen.exe 生成本地密钥。真正连不连得上是 core 包的事，这里只显示。
 *
 * <p>关于线程：本面板里所有会卡住的操作——连接探测、读取文件系统、等待
 * ssh-keygen 进程——都放在 {@link SwingWorker} 的 {@code doInBackground} 里。
 * 事件线程只做两件事：在开始时把按钮禁用掉，在结束时把结果画出来。SSH 连一台
 * 不通的机器会一直挂到超时（默认 15 秒，网络不通时更久），如果直接在事件线程里
 * 调 {@code connect()}，界面会整个冻住，连「取消」都点不动，用户只能去杀进程。
 */
public final class ConnectionPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    // ------------------------------------------------------------------
    // 界面控件
    // ------------------------------------------------------------------

    /**
     * 连接参数。
     *
     * <p>这五个字段不再显示在面板上——填写搬进了 {@link SetupWizard}。留在这里是因为
     * 它们是 {@link #readTarget()} / {@link #writeTarget} 的数据源：独立版
     * {@code MainFrame} 通过那两个方法注入预设，向导每步填完也写回这里。
     * 也就是说面板与向导共用同一份数据，谁也不会覆盖谁。
     */
    private final PlaceholderTextField hostField = new PlaceholderTextField(null);
    private final JTextField portField = new JTextField("22", 6);
    private final PlaceholderTextField userField = new PlaceholderTextField(null);
    private final PlaceholderTextField keyField = new PlaceholderTextField(null);
    private final PlaceholderTextField nameField = new PlaceholderTextField(null);
    private final JTextField timeoutField = new JTextField("15", 5);

    /** 面板上显示"当前连的是哪台机器"。 */
    private final JLabel currentLabel = new JLabel();

    /** 面板上的一句话状态：还没配过 / 已保存 / 上次连接失败。 */
    private final JTextArea statusField = new JTextArea();

    /**
     * 上一次失败的原因，显示在面板上。
     *
     * <p>用意是"关掉对话框之后仍然知道发生过什么"：失败信息只在弹窗里出现过一次，
     * 用户关掉就没了。留在面板上，他回头还能看到上次为什么没连上。
     * 用 {@link #lastErrorIsHard} 区分"密码错"与"网络不通"之类，避免把可重试的
     * 提示一直挂着吓人。
     */
    private String lastError = "";
    private boolean lastErrorIsHard;

    /**
     * 唯一入口：打开向导。
     *
     * <p>面板上原来有八个按钮，现在填写与连接全在向导里完成，这里只负责开始。
     */
    private final JButton connectButton = new JButton();

    /** 「网络模式速查」页那块的标题边框。 */
    private final javax.swing.border.TitledBorder networkBorder;

    /** 选项卡容器，语言切换时要改标题与提示。 */
    private final JTabbedPane tabs = new JTabbedPane();

    /** 状态区标题行：成功绿、失败红。 */
    private final JLabel statusLabel = new JLabel();

    /** 状态区正文。用 JTextArea 而不是 JLabel，因为连接报告是多行的。 */
    private final JTextArea statusArea = new JTextArea();

    /** 网络模式速查的正文，静态文本，只在构造时填一次。 */
    private final JTextArea networkHelpArea = new JTextArea();

    /** 状态区背景为绿色时用的前景色。 */
    private static final Color OK_COLOR = new Color(0x1B, 0x7F, 0x3B);

    /** 状态区背景为红色时用的前景色。 */
    private static final Color FAIL_COLOR = new Color(0xB0, 0x1B, 0x1B);

    /** 状态区中性色。 */
    private static final Color NEUTRAL_COLOR = new Color(0x33, 0x33, 0x33);

    /**
     * 「这台机器连上了」的通知口。
     *
     * <p>存在的理由：宿主需要知道连接是否成功过（例如据决定还要不要自动弹出本窗口），
     * 而在此之前唯一的判据是**观察状态区背景色**——那是一个会随配色改动而静默失效的
     * 隐式契约。给一个显式回调，宿主就不必靠颜色猜。
     *
     * <p>刻意只报成功、不报失败：宿主要回答的问题是「还要不要再引导用户」，
     * 失败恰恰说明仍需要引导。
     */
    public interface ConnectedListener {

        /** 一次连接测试成功返回时调用。可能在非事件线程上被调用。 */
        void connected(SshTarget target);
    }

    /** 成功回调，可为 null。 */
    private volatile ConnectedListener connectedListener;

    /**
     * 注册连接成功回调；传 null 取消。
     *
     * <p>可重复调用，后注册的替换先前的（不是叠加）——宿主只有一个，叠加会重复通知。
     */
    public void setConnectedListener(ConnectedListener listener) {
        this.connectedListener = listener;
    }

    /** 通知宿主连接成功；回调抛异常不影响界面。 */
    private void fireConnected(SshTarget target) {
        ConnectedListener l = connectedListener;
        if (l == null) {
            return;
        }
        try {
            l.connected(target);
        } catch (RuntimeException e) {
            // 宿主自己的问题不该让连接结果显示不出来。
        }
    }

    private static final Color OK_BG = new Color(0xE8, 0xF6, 0xE9);
    private static final Color FAIL_BG = new Color(0xFD, 0xEC, 0xEA);
    private static final Color NEUTRAL_BG = new Color(0xF5, 0xF5, 0xF5);

    /** 占位提示文字的灰色。比正文的浅灰再浅一点，一眼能看出是提示而不是真实值。 */
    private static final Color PLACEHOLDER_FG = new Color(0x9E, 0x9E, 0x9E);

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    private final ConnectionStore store = new ConnectionStore();

    /** 同一时刻只允许一个耗时任务在跑，避免两次连接结果互相覆盖。 */
    private boolean busy;

    // ------------------------------------------------------------------
    // 语言
    // ------------------------------------------------------------------

    /**
     * 宿主的语言提供者，可为 null。
     *
     * <p>null 表示「没有宿主告诉我该说什么语言」，此时界面用 {@link Text} 的
     * 默认语言（中文）—— vmadapt 单独运行（{@code build.ps1} 的自检就是这样）
     * 时走的就是这条路。
     */
    private LangProvider langProvider;

    /** 注册给 provider 的回调，注销时要用同一个对象。 */
    private Runnable langListener;

    /** 上一次已知的语言代码，用于判断是否真的需要重刷。 */
    private String appliedLang;

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    public ConnectionPanel() {
        super(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        networkBorder = BorderFactory.createTitledBorder("");

        // 只有两页。曾经有第三页「连接结果」，但它已经不需要了：结果都以弹窗呈现，
        // 弹窗把"做完了"和"结果是什么"放在同一处——用户点完按钮不必再去别的页面找。
        // 一个专门用来看结果的页面，反而要求他先知道结果在哪一页。
        tabs.addTab("", buildFormTab());
        tabs.addTab("", buildNetworkHelpTab());
        add(tabs, BorderLayout.CENTER);

        // 先把文案铺一遍（此时没有 provider，即英文），再绑事件。
        applyLanguage();

        wireEvents();
        // 面板上显示当前目标与状态；没有已存配置时就显示"还没配过"。
        refreshPanelState();
    }

    // ------------------------------------------------------------------
    // 语言注入
    // ------------------------------------------------------------------

    /**
     * 注入语言提供者。
     *
     * <p>宿主（merged 侧的 BioEnv Caller）把 {@code bioenv.i18n.I18n} 适配成
     * {@link LangProvider} 后调用这里；适配器示例见类注释。
     *
     * <p>传 null 表示回到 {@link Text} 的默认语言（中文）。重复调用会先注销旧的
     * 监听器，不会累积。
     * 允许在 EDT 之外的线程调用（内部会切到 EDT 完成界面更新）。
     *
     * @param provider 语言提供者；null 表示使用默认语言（中文）
     */
    public void setLangProvider(final LangProvider provider) {
        runOnEdt(new Runnable() {
            @Override
            public void run() {
                if (langListener != null && langProvider != null) {
                    try {
                        removeListenerQuietly(langProvider, langListener);
                    } catch (RuntimeException ignored) {
                        // provider 的注销方法有问题不该影响界面；继续换新的。
                    }
                }
                langProvider = provider;
                langListener = null;
                appliedLang = null;

                if (provider != null) {
                    Runnable r = new Runnable() {
                        @Override
                        public void run() {
                            appliedLang = null;
                            applyLanguage();
                        }
                    };
                    langListener = r;
                    provider.addListener(r);
                }
                applyLanguage();
            }
        });
    }

    /** 取消注册，尽力而为：provider 实现有异常也不该让调用方炸掉。 */
    private static void removeListenerQuietly(LangProvider p, Runnable r) {
        try {
            java.lang.reflect.Method m = p.getClass().getMethod("removeListener", Runnable.class);
            m.invoke(p, r);
        } catch (Exception ignored) {
            // 接口里没有 removeListener 是正常情况（本接口刻意只要求三个方法）。
        }
    }

    /** 当前生效的语言代码。 */
    String currentLangCode() {
        return new Text(langProvider).langCode();
    }

    /**
     * 按当前语言刷新整块界面的文案。
     *
     * <p>这是唯一改文案的地方：所有控件都集中在这里重设，避免出现
     * 「按钮切了、标签没切」这种半吊子状态。placeholder 也在这里重设。
     */
    private void applyLanguage() {
        Text tx = new Text(langProvider);
        String code = tx.langCode();
        appliedLang = code;

        // 选项卡
        // 选项卡。只有两页了：0 连接配置、1 网络模式速查。
        tabs.setTitleAt(0, tx.get(UiText.TAB_CONNECTION));
        tabs.setTitleAt(1, tx.get(UiText.TAB_NETWORK));
        tabs.setToolTipTextAt(0, tx.get(UiText.TAB_CONNECTION_TIP));
        tabs.setToolTipTextAt(1, tx.get(UiText.TAB_NETWORK_TIP));

        // 面板上的当前目标与状态。
        connectButton.setText(tx.get(UiText.BTN_CONNECT));
        connectButton.setToolTipText(tx.get(UiText.BTN_CONNECT_TIP));
        refreshPanelState();

        // Network modes 正文
        networkHelpArea.setText(tx.get(UiText.NET_HELP));
        networkHelpArea.setCaretPosition(0);

        // 状态区：只有在「用户还没点过测试」时才覆盖，否则会把上一次的
        // 连接结果冲掉——切语言不该抹掉用户刚看到的结果。
        if (!statusShowsResult) {
            // 不弹窗：这只是一句"尚未测试"，不是某次操作的结果。
            paintStatusBusy(statusFg, statusBg, tx.get(UiText.STATUS_NOT_TESTED));
            statusArea.setText(tx.get(UiText.STATUS_INTRO));
            statusArea.setForeground(neutralFg());
        }
    }

    /**
     * 刷新面板上的"当前连哪台机器"与状态文字。
     *
     * <p>填写搬进向导之后，这个面板的作用变成了"告诉用户现在是什么情况"。所以每次
     * 连接参数变化（向导填完、{@link #writeTarget} 注入预设）都要重画一次，否则面板
     * 会一直显示上一次的内容。
     */
    private void refreshPanelState() {
        String host = hostField.getText().trim();
        String user = userField.getText().trim();
        String port = portField.getText().trim();
        String name = nameField.getText().trim();

        if (host.isEmpty()) {
            currentLabel.setText(text(UiText.PANEL_NO_TARGET));
            statusField.setText(text(UiText.PANEL_NO_TARGET_HINT));
            return;
        }
        String who = (user.isEmpty() ? "" : user + "@") + host
                + (port.isEmpty() ? "" : ":" + port);
        currentLabel.setText(name.isEmpty() ? who : name + "  (" + who + ")");

        if (lastError != null && !lastError.isEmpty()) {
            statusField.setText(lastError);
        } else {
            statusField.setText(text(UiText.PANEL_READY_HINT));
        }
    }

    /** 状态区是否正在显示某次结果（true 时语言切换不覆盖它）。 */
    private boolean statusShowsResult;

    /** 状态区当前的前景/背景，语言切换重绘标题时要保持颜色。 */
    private Color statusFg = NEUTRAL_COLOR;
    private Color statusBg = NEUTRAL_BG;

    private static Color neutralFg() {
        return NEUTRAL_COLOR;
    }


    /** 把 Runnable 派发到 EDT；已经在 EDT 上就直接执行。 */
    private static void runOnEdt(Runnable r) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try {
                javax.swing.SwingUtilities.invokeAndWait(r);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable c = e.getCause();
                if (c instanceof RuntimeException) {
                    throw (RuntimeException) c;
                }
                throw new RuntimeException(c);
            }
        }
    }

    // ------------------------------------------------------------------
    // 各标签页的布局
    // ------------------------------------------------------------------

    /**
     * 「连接配置」页：显示当前连的是哪台机器，并提供一个入口按钮。
     *
     * <h2>为什么不再是一个表单</h2>
     *
     * <p>这里曾经有六行输入和八个按钮，用户要自己判断先填什么、能点哪个。但这件事本身
     * 有固定顺序（地址 → 端口 → 用户名 → 密钥 → 连接），把顺序写进界面就不必再要求
     * 用户知道顺序。填写因此搬进了 {@link SetupWizard}，一步一步来。
     *
     * <p>面板上留下的只有"当前状态 + 一个入口"。参数本身仍然存在
     * {@link #hostField} 等字段里，因为它们同时是 {@link #readTarget()} /
     * {@link #writeTarget} 的数据源——独立版 {@code MainFrame} 依赖这两个方法，
     * 向导填完也写回这里，所以两边改的是同一份数据。
     */
    private JPanel buildFormTab() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

        // 当前目标：一行大字，让人一眼看出连的是哪台。
        currentLabel.setName("bioenv.currentTarget");
        currentLabel.setFont(currentLabel.getFont().deriveFont(Font.BOLD, 14f));
        root.add(currentLabel, BorderLayout.NORTH);

        // 状态：例如"已连接 / 还没配过"，以及一句可执行的下一步。
        statusField.setName("bioenv.panelState");
        statusField.setEditable(false);
        statusField.setLineWrap(true);
        statusField.setWrapStyleWord(true);
        statusField.setOpaque(false);
        statusField.setForeground(new Color(0x55, 0x5B, 0x64));
        statusField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        statusField.setPreferredSize(new Dimension(420, 60));
        root.add(statusField, BorderLayout.CENTER);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.add(connectButton);
        root.add(bar, BorderLayout.SOUTH);
        return root;
    }

    // ------------------------------------------------------------------
    // 评审用的布局变体已经选定并删除。
    //
    // 保留下来的是「向导」方案：填写不再放在这个面板上，而是像装软件那样一步步弹窗
    // （见 SetupWizard）。面板本身只显示当前连的是哪台机器，以及一个入口按钮。
    // ------------------------------------------------------------------

    // 「连接结果」页已经删掉（见构造函数里加标签页处的说明）。statusArea 仍然保留，
    // 但只是**文本缓冲**，不再挂在界面上：所有操作的报告都先写进它，
    // 再由相应的弹窗读出来展示。这样"报告内容"只有一份，弹窗和别处不会各说各话。

    /** 「网络模式速查」页：VMware 三种网络模式对 SSH 连不上的影响。 */
    private JPanel buildNetworkHelpTab() {
        JPanel root = new JPanel(new BorderLayout(0, 6));

        networkHelpArea.setEditable(false);
        networkHelpArea.setLineWrap(true);
        networkHelpArea.setWrapStyleWord(true);
        networkHelpArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JScrollPane scroll = new JScrollPane(networkHelpArea);
        scroll.setBorder(networkBorder);
        root.add(scroll, BorderLayout.CENTER);
        return root;
    }

    // ------------------------------------------------------------------
    // 事件绑定
    // ------------------------------------------------------------------

    /**
     * 面板上只剩一个入口：打开向导。
     *
     * <p>原来这里绑了八个按钮。填写与连接搬进向导之后，它们全部由向导负责——
     * 两个地方都能做同一件事，迟早会出现"在面板上点了没反应，因为向导那套才是生效的"
     * 这类问题。所以这里只留一个。
     */
    private void wireEvents() {
        connectButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openWizard();
            }
        });
    }

    // ------------------------------------------------------------------
    // 连接向导
    // ------------------------------------------------------------------

    /**
     * 打开向导：一步一个窗，像装软件那样走完。
     *
     * <p>把填写也搬进向导，是因为这件事本身有固定顺序（地址 → 端口 → 用户名 → 密钥
     * → 连接）。顺序写进界面之后，用户不必自己知道该先填什么；每一步只回答一个问题，
     * 也就没有"按钮是灰的，为什么"这种疑问。
     *
     * <p>每一步都从当前字段读初值，所以「上一步」是真的回填，而不是重填。
     * 每一步的结果又立刻写回字段，于是取消时保留已填的部分——重新打开向导不用从头来。
     */
    private void openWizard() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            // 自检环境没有窗口。状态写进缓冲，测试据此断言。
            statusArea.setText(text(UiText.PANEL_NO_TARGET_HINT));
            return;
        }
        ensureKeyPath();
        stepHost();
    }

    /**
     * 把「这次要用哪把密钥」定下来，写进 {@link #keyField}。
     *
     * <h2>为什么必须在向导一开始就做</h2>
     *
     * <p>{@code keyField} 不只是界面上的一个格子，它是 {@link #readTarget()} 的输入，
     * 而 {@code SshTarget.usesKey()} 决定后面每条 ssh 命令要不要带 {@code -i}。
     *
     * <p>漏掉这一步的后果很隐蔽：公钥照样能装到虚拟机上（装的那一步自己带了
     * {@code -i}），但随后的验证和真正连接都按<b>默认密钥名</b>去找——而我们的密钥叫
     * {@code id_rsa_tbvm}，不是 {@code id_rsa}，ssh 根本不会用它。于是表现为
     * 「公钥装好了，可连接仍然失败或仍然要密码」，几乎不可能猜到原因。
     *
     * <p>所以：用户自己填过就沿用他的；没填就用约定的 {@code ~/.ssh/id_rsa_tbvm}，
     * 并立刻写回 {@code keyField}，让"实际要用的密钥"和"存进配置的密钥"是同一个值。
     */
    private void ensureKeyPath() {
        String existing = SshTarget.normalizeKeyPath(keyField.getText());
        if (existing != null && !existing.isEmpty()) {
            return;
        }
        File sshDir = sshDirForSetup();
        if (sshDir == null) {
            return;
        }
        keyField.setText(new File(sshDir, "id_rsa_tbvm").getAbsolutePath());
    }

    /** 当前向导窗口，换步骤时复用它，避免一屏闪好几个窗。 */
    private javax.swing.JDialog wizardWindow;
    /** 向导各步共用的摘要文本，用于第 5 步回显。 */
    private String wizardSummary = "";

    private void showWizardStep(javax.swing.JPanel content, String title) {
        if (wizardWindow == null) {
            wizardWindow = new javax.swing.JDialog(
                    SwingUtilities.getWindowAncestor(this), title);
            wizardWindow.setName("bioenv.setupWizard");
            wizardWindow.setModal(false);
        }
        wizardWindow.setTitle(title);
        wizardWindow.setContentPane(content);
        wizardWindow.pack();
        wizardWindow.setLocationRelativeTo(this);
        wizardWindow.setVisible(true);
    }

    private void closeWizard() {
        if (wizardWindow != null) {
            wizardWindow.setVisible(false);
            wizardWindow.dispose();
            wizardWindow = null;
        }
    }

    /** 第 1 步：地址。 */
    private void stepHost() {
        final SetupWizard.FieldStep s = new SetupWizard.FieldStep(
                text(UiText.LBL_HOST), text(UiText.WIZ_HOST_HINT),
                text(UiText.BTN_CANCEL), text(UiText.BTN_NEXT));
        s.field().setText(hostField.getText());
        s.back().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                closeWizard();
            }
        });
        s.next().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String v = s.field().getText().trim();
                if (v.isEmpty()) {
                    warnStep(s.panel(), text(UiText.WIZ_NEED_HOST));
                    return;
                }
                hostField.setText(v);
                stepPort();
            }
        });
        showWizardStep(s.panel(), text(UiText.DIALOG_CONNECTION_TITLE_WIZARD));
    }

    /** 第 2 步：端口。 */
    private void stepPort() {
        final SetupWizard.FieldStep s = new SetupWizard.FieldStep(
                text(UiText.LBL_PORT), text(UiText.WIZ_PORT_HINT),
                text(UiText.BTN_BACK), text(UiText.BTN_NEXT));
        s.field().setText(portField.getText());
        s.back().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stepHost();
            }
        });
        s.next().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String v = s.field().getText().trim();
                if (!v.isEmpty()) {
                    try {
                        int p = Integer.parseInt(v);
                        if (p <= 0 || p > 65535) {
                            warnStep(s.panel(), text(UiText.ERR_PORT_NUMBER, v));
                            return;
                        }
                    } catch (NumberFormatException nfe) {
                        warnStep(s.panel(), text(UiText.ERR_PORT_NUMBER, v));
                        return;
                    }
                }
                portField.setText(v.isEmpty() ? "22" : v);
                stepUser();
            }
        });
        showWizardStep(s.panel(), text(UiText.DIALOG_CONNECTION_TITLE_WIZARD));
    }

    /** 第 3 步：用户名。 */
    private void stepUser() {
        final SetupWizard.FieldStep s = new SetupWizard.FieldStep(
                text(UiText.LBL_USER), text(UiText.WIZ_USER_HINT),
                text(UiText.BTN_BACK), text(UiText.BTN_NEXT));
        s.field().setText(userField.getText());
        s.back().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stepPort();
            }
        });
        s.next().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String v = s.field().getText().trim();
                if (v.isEmpty()) {
                    warnStep(s.panel(), text(UiText.WIZ_NEED_USER));
                    return;
                }
                userField.setText(v);
                stepKeyNotice();
            }
        });
        showWizardStep(s.panel(), text(UiText.DIALOG_CONNECTION_TITLE_WIZARD));
    }

    /**
     * 第 4 步：密钥须知。
     *
     * <p>这一步<b>不需要用户填任何东西</b>，所以它是一段说明而不是输入框：密钥路径由
     * 插件自己决定（生成到 {@code ~/.ssh/id_rsa_tbvm}，或沿用已有的），用户没有需要
     * 做选择的地方。给他的只是"接下来会发生什么"——会在本机生成密钥对、公钥会装到
     * 虚拟机上。
     *
     * <p>点「下一步」直接进密码步：原来中间还有一步"确认一下"把填过的内容摊出来核对，
     * 但前面四步每步只填了一项、每步都能返回，再复述一遍只是多一次点击。
     */
    private void stepKeyNotice() {
        final SetupWizard.Notice s = new SetupWizard.Notice(
                text(UiText.WIZ_KEY_NOTICE), text(UiText.BTN_BACK),
                text(UiText.BTN_NEXT));
        s.back().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stepUser();
            }
        });
        s.next().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // 走 beginConnect 而不是直接进密码步：它要先做两件事——
                //   ① 环境自检（没有 ssh、或版本低于 8.4 时改走手动那条路，
                //      而不是弹一个注定失败的密码框）；
                //   ② 看这台机器是不是已经免密了，是就不问密码，直接连。
                // 环境正常且还没免密时，它才转到 stepPassword，也就是「下一步」到
                // 密码框这一步。
                beginConnect();
            }
        });
        showWizardStep(s.panel(), text(UiText.DIALOG_CONNECTION_TITLE_WIZARD));
    }

    /** 每步共用的"还缺什么"提示，不换窗，就地告诉用户。 */
    private void warnStep(javax.swing.JPanel panel, String message) {
        javax.swing.JOptionPane.showMessageDialog(panel, message,
                text(UiText.DIALOG_CONNECTION_TITLE_WIZARD),
                javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    /** 向导总步数（不含密码那一步，它是按需出现的）。 */
    private static final int WIZARD_STEPS = 5;

    /**
     * 从第 5 步开始真正干活。
     *
     * <p>先做环境自检：ssh.exe 存不存在、版本够不够。不够就<b>不走密码通道</b>——
     * 那条路依赖 {@code SSH_ASKPASS_REQUIRE}，8.4 以下不认，弹一个注定失败的密码框
     * 只会让用户以为是自己密码打错了。这时改为显示公钥和一段可复制的命令。
     *
     * <p>环境没问题再看这台机器能不能免密登录：
     * <ul>
     *   <li>能 → 直接测试，<b>不问密码</b>（能连上就不该多问一句）；</li>
     *   <li>不能 → 弹密码框，输一次，用它装上公钥，之后永久免密。</li>
     * </ul>
     */
    private void beginConnect() {
        final String ssh = OpenSsh.locate("ssh.exe");
        if (ssh == null) {
            stepManual(text(UiText.MSG_NO_SSH), text(UiText.MSG_NO_SSH_REASON));
            return;
        }

        // ① 先看能不能直接免密登录。
        //
        // 顺序很要紧。版本门槛只是「用密码把公钥装上去」那条路的要求，与密钥登录本身
        // 无关——密钥登录任何版本都能用。
        //
        // 曾经是先判版本再探测，后果是：8.1p1 的机器上，哪怕密钥早就装好、免密登录
        // 明明工作得很好，向导也会直接跳到「OpenSSH 太旧」那一页，**永远不做连接测试**。
        // 而 Windows 10 自带的就是 8.1p1，走这条路的人很多，所以这不是边角情况：
        // 用户明明已经配好了，插件却一直说"你的 OpenSSH 太旧"。
        setBusy(true);
        final javax.swing.JDialog wait = progressDialog();
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return canLogInWithoutPassword(ssh);
            }

            @Override
            protected void done() {
                setBusy(false);
                if (wait != null) {
                    wait.dispose();
                }
                boolean ok = false;
                try {
                    ok = get().booleanValue();
                } catch (Exception ignored) {
                    // 探测本身失败按"还不能免密"处理，走密码那条路
                }
                if (ok) {
                    // 免密已可用：不需要密码，直接做一次完整测试。
                    finishConnect();
                } else {
                    // 免密不行，才轮到密码那条路；这时才需要问"这个 ssh 支持吗"。
                    needPassword(ssh);
                }
            }
        }.execute();
    }

    /**
     * 免密登录走不通时才到这里：先确认密码通道在当前 ssh 上可用，再弹密码框。
     *
     * <p>{@code SSH_ASKPASS_REQUIRE} 是 8.4 才有的，所以版本不够时密码这条路注定
     * 失败——那时不给密码框，改给手动安装页。这个判断放在这里（而不是
     * {@link #beginConnect()} 的开头）正是为了避免它把"本来能连上"的情况也拦掉。
     */
    private void needPassword(String ssh) {
        String ver = SshAskpass.versionOf(ssh);
        int major = SshAskpass.majorOf(ver);
        int minor = SshAskpass.minorOf(ver);
        if (!SshAskpass.supportsAskpassRequire(major, minor)) {
            stepManual(text(UiText.WIZ_OLD_SSH_TITLE),
                    text(UiText.WIZ_OLD_SSH_REASON, ver));
            return;
        }
        stepPassword();
    }

    /** 一次最小免密探测：能执行 echo 就说明公钥已经装好了。 */
    private boolean canLogInWithoutPassword(String ssh) {
        try {
            SshTarget t = readTarget();
            // 与 finishConnect 用同一个组装函数，免得两处对"要不要带 -i"各写一遍。
            List<String> cmd = KeyInstaller.verifyCommand(
                    ssh,
                    hostField.getText().trim(),
                    parseIntOr(portField.getText().trim(), SshTarget.DEFAULT_PORT),
                    userField.getText().trim(),
                    t.usesKey() ? t.keyPath : null,
                    timeoutSeconds());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = drainText(p.getInputStream());
            p.waitFor();
            return p.exitValue() == 0 && out.contains(KeyInstaller.LOGIN_MARKER);
        } catch (Exception e) {
            return false;
        }
    }

    private int timeoutSeconds() {
        return SshTarget.DEFAULT_TIMEOUT_SECONDS;
    }

    private static String drainText(java.io.InputStream in) {
        StringBuilder sb = new StringBuilder();
        try {
            byte[] b = new byte[512];
            int n;
            while ((n = in.read(b)) > 0) {
                sb.append(new String(b, 0, n, "UTF-8"));
            }
        } catch (Exception ignored) {
            // 读不到就当空输出
        }
        return sb.toString();
    }

    private javax.swing.JDialog progressDialog() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return null;
        }
        javax.swing.JDialog dlg = new javax.swing.JDialog(
                wizardWindow, text(UiText.DIALOG_CONNECTION_TITLE_WIZARD));
        javax.swing.JLabel l = new javax.swing.JLabel(
                "  " + text(UiText.WIZ_WORKING) + "  ");
        dlg.setContentPane(l);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
        return dlg;
    }

    /**
     * 密码步：免密还不行，让用户输一次密码，用它把公钥装上。
     *
     * <p>密码只经内存交给 {@code SSH_ASKPASS} 那条管道，不落盘、不进命令行、不进日志。
     * 这一步是"最后一次需要密码"——装完就永久免密。
     */
    private void stepPassword() {
        final SetupWizard.Password s = new SetupWizard.Password(
                text(UiText.LBL_PASSWORD), text(UiText.WIZ_PWD_HINT),
                text(UiText.BTN_BACK), text(UiText.BTN_START),
                text(UiText.BTN_CANCEL));
        s.back().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stepKeyNotice();
            }
        });
        s.cancel().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                closeWizard();
            }
        });
        s.connect().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                char[] pwd = s.field().getPassword();
                if (pwd == null || pwd.length == 0) {
                    warnStep(s.panel(), text(UiText.WIZ_NEED_PASSWORD));
                    return;
                }
                runSetup(new String(pwd));

                // 用完立刻清空输入框。
                //
                // 老实说明这里的边界：密码最终要交给 ssh，而 ssh 读的是<b>字符串</b>
                // （通过 askpass 的 stdout），所以无法全程只用 char[]。Java 的 String
                // 一旦创建就不能覆写，因此"内存里绝对没有副本"这件事做不到——我们只能
                // 保证：不落盘、不进命令行、不进日志、用完清掉输入框里这一份。
                //
                // 这比"让用户在终端里敲密码"并不更差：那种做法下密码同样在内存里，
                // 只是输入途径不同。
                java.util.Arrays.fill(pwd, '\0');
                s.field().setText("");
            }
        });
        showWizardStep(s.panel(), text(UiText.DIALOG_CONNECTION_TITLE_WIZARD));
    }

    /**
     * 环境不足时的替代页：把公钥和一段可复制的命令摆出来。
     *
     * <p>不弹密码框：没有可用的 ssh 或版本太旧时那条路注定失败。
     */
    private void stepManual(String reason, String detail) {
        String pub = publicKeyText();
        String cmd = "mkdir -p ~/.ssh && chmod 700 ~/.ssh\n"
                + "echo '" + (pub == null ? "<公钥内容>" : pub.trim())
                + "' >> ~/.ssh/authorized_keys\n"
                + "chmod 600 ~/.ssh/authorized_keys";
        final SetupWizard.Result s = new SetupWizard.Result(
                reason, detail + "\n\n" + cmd, false,
                text(UiText.BTN_CLOSE));
        s.close().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                closeWizard();
            }
        });
        showWizardStep(s.panel(), text(UiText.DIALOG_CONNECTION_TITLE_WIZARD));
    }

    /** 读公钥内容；没有就返回 null。 */
    private String publicKeyText() {
        try {
            File sshDir = sshDirForSetup();
            if (sshDir == null) {
                return null;
            }
            File pub = new File(new File(sshDir, "id_rsa_tbvm").getAbsolutePath() + ".pub");
            return pub.isFile() ? readTextFile(pub) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 真正的执行：生成密钥（若没有）→ 用密码装公钥 → 免密复测。
     *
     * @param password 一次性密码；装完就不再需要
     */
    private void runSetup(final String password) {
        final javax.swing.JDialog wait = progressDialog();
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    // ① 生成密钥（若还没有）。已有就不覆盖：覆盖会让装到别的机器上的
                    //    那把公钥失效。
                    File sshDir = sshDirForSetup();
                    if (sshDir == null) {
                        return text(UiText.MSG_NO_HOME_REASON);
                    }
                    File keyFile = new File(sshDir, "id_rsa_tbvm");
                    String priv = keyFile.getAbsolutePath();
                    if (!keyFile.isFile()) {
                        String keygen = OpenSsh.locate("ssh-keygen.exe");
                        if (keygen == null) {
                            return text(UiText.MSG_NO_KEYGEN_REASON);
                        }
                        if (!sshDir.isDirectory() && !sshDir.mkdirs()) {
                            return text(UiText.MSG_MKDIR_FAILED, sshDir.getAbsolutePath());
                        }
                        OpenSsh.Result r = OpenSsh.runViaCmd(
                                text(UiText.PFX_EXEC_FAILED), text(UiText.PFX_EXEC_CMD),
                                keygenCommandLine(keygen, keyComment(), priv));
                        if (r.exitCode != 0) {
                            return text(UiText.MSG_GEN_FAILED_CODE,
                                    String.valueOf(r.exitCode)) + "\n" + r.output;
                        }
                    }

                    // ② 用密码把公钥装上去。
                    String installed = installPublicKeyWithPassword(priv, password);
                    if (installed != null) {
                        return installed;    // 非 null 表示失败原因
                    }
                    return null;             // 成功
                } catch (Exception e) {
                    return String.valueOf(e);
                }
            }

            @Override
            protected void done() {
                if (wait != null) {
                    wait.dispose();
                }
                String problem = null;
                try {
                    problem = get();
                } catch (Exception e) {
                    problem = String.valueOf(e);
                }
                if (problem != null) {
                    lastError = problem;
                    lastErrorIsHard = true;
                    refreshPanelState();
                    stepFailed(problem);
                    return;
                }
                lastError = "";
                lastErrorIsHard = false;
                // 装好了 → 立刻做一次免密复测。这才是"测试连接"的真意：
                // 证明从今往后不需要密码也能连上。
                finishConnect();
            }
        }.execute();
    }

    /**
     * 用密码把公钥装到虚拟机。
     *
     * @return null 表示成功；否则是失败原因
     */
    private String installPublicKeyWithPassword(String privKeyPath, String password) {
        File priv = new File(privKeyPath);
        // 公钥优先读 .pub，读不到就从私钥现推。理由见 KeyInstaller.publicKeyOf：
        // .pub 可能缺失或为空（用户只拷了私钥、或 ssh-keygen 没能写出 .pub），
        // 而空公钥装上去只会让人以后每次都被拒。
        String publicKey = KeyInstaller.publicKeyOf(
                OpenSsh.locate("ssh-keygen.exe"), priv);
        if (publicKey == null || publicKey.isEmpty()) {
            return text(UiText.MSG_NO_PUBFILE_REASON,
                    new File(privKeyPath + ".pub").getAbsolutePath());
        }
        // 真正的机制在 vmadapt.core.KeyInstaller：拼命令、用密码认证、判断结果。
        // 放在 core 里是为了能对着真实 sshd 测——留在这里的话，测试只能把命令抄一遍，
        // 抄出来的那份和跑的那份可以各自漂移。
        KeyInstaller.Outcome r = KeyInstaller.install(
                OpenSsh.locate("ssh.exe"),
                hostField.getText().trim(),
                parseIntOr(portField.getText().trim(), SshTarget.DEFAULT_PORT),
                userField.getText().trim(),
                publicKey, password, timeoutSeconds());
        return r.ok() ? null : r.reason;
    }

    /** 端口文本转数字；不合法时回退默认值（向导那一步已经校验过，这里只兜底）。 */
    private static int parseIntOr(String text, int fallback) {
        try {
            int v = Integer.parseInt(text);
            return (v > 0 && v <= 65535) ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 装好之后（或本来就免密时）做一次完整验证，然后进入命名步。 */
    private void finishConnect() {
        final javax.swing.JDialog wait = progressDialog();
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                String ssh = OpenSsh.locate("ssh.exe");
                try {
                    // 命令由 KeyInstaller 组装：它保证"有密钥时带 -i"。
                    // 我们的密钥叫 id_rsa_tbvm，不在 ssh 默认密钥名里，漏掉 -i 就会
                    // 出现"公钥装好了却连不上"这种极难定位的现象。
                    SshTarget t = readTarget();
                    List<String> cmd = KeyInstaller.verifyCommand(
                            ssh,
                            hostField.getText().trim(),
                            parseIntOr(portField.getText().trim(), SshTarget.DEFAULT_PORT),
                            userField.getText().trim(),
                            t.usesKey() ? t.keyPath : null,
                            timeoutSeconds());
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    String out = drainText(p.getInputStream());
                    p.waitFor();
                    if (p.exitValue() == 0 && out.contains("TBVM_OK")) {
                        return null;
                    }
                    return out.trim();
                } catch (Exception e) {
                    return String.valueOf(e);
                }
            }

            @Override
            protected void done() {
                if (wait != null) {
                    wait.dispose();
                }
                String problem = null;
                try {
                    problem = get();
                } catch (Exception e) {
                    problem = String.valueOf(e);
                }
                if (problem != null) {
                    lastError = problem;
                    lastErrorIsHard = true;
                    refreshPanelState();
                    stepFailed(problem);
                    return;
                }
                lastError = "";
                refreshPanelState();
                stepName();
            }
        }.execute();
    }

    /** 失败页。 */
    private void stepFailed(final String detail) {
        final SetupWizard.Result s = new SetupWizard.Result(
                text(UiText.WIZ_FAIL_HINT), detail, false,
                text(UiText.BTN_CLOSE));
        s.close().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                closeWizard();
            }
        });
        showWizardStep(s.panel(), text(UiText.DIALOG_CONNECTION_TITLE_WIZARD));
    }

    /** 命名页：连上了，给这条配置起个名字。 */
    private void stepName() {
        final SetupWizard.FieldStep s = new SetupWizard.FieldStep(
                text(UiText.LBL_NAME), text(UiText.WIZ_NAME_NOTE),
                text(UiText.BTN_SKIP), text(UiText.BTN_SAVE));
        s.field().setText(nameField.getText());
        s.back().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // 「跳过」＝不保存这条配置。连接是成功的，只是下次要重填。
                closeWizard();
            }
        });
        s.next().addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String v = s.field().getText().trim();
                nameField.setText(v);
                saveCurrentProfile();
                closeWizard();
            }
        });
        showWizardStep(s.panel(), text(UiText.DIALOG_CONNECTION_TITLE_WIZARD));
    }

    /**
     * 保存当前这条配置。
     *
     * <p>名字留空就用 {@code 用户@主机:端口}：一台机器的人不必被迫起名字。
     * 同一台机器再次保存会<b>更新</b>那一条（{@code ConnectionStore.save} 按身份去重），
     * 不会产生重复条目。
     */
    private void saveCurrentProfile() {
        SshTarget t = readTarget();
        if (t.host.isEmpty()) {
            return;
        }
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            name = defaultLabelFor(t);
            nameField.setText(name);
        }
        t.label = name;
        final SshTarget snapshot = t;
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return Boolean.valueOf(store.save(snapshot));
            }

            @Override
            protected void done() {
                try {
                    if (get().booleanValue()) {
                        paintStatus(OK_COLOR, OK_BG,
                                text(UiText.MSG_SAVED_TITLE, snapshot.label));
                        statusArea.setText(text(UiText.MSG_SAVED_BODY, snapshot.label)
                                + "\n  " + snapshot.toDisplay() + "\n");
                        refreshPanelState();
                    } else {
                        reportFailure(text(UiText.MSG_SAVE_REJECTED),
                                text(UiText.MSG_SAVE_REJECTED_REASON, snapshot.label),
                                null);
                    }
                } catch (Exception ex) {
                    reportFailure(text(UiText.MSG_SAVE_FAILED),
                            String.valueOf(ex.getCause() == null ? ex : ex.getCause()),
                            null);
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------------
    // 表单 ↔ SshTarget
    // ------------------------------------------------------------------

    /** 取当前语言下的文案（按需构造，语言变了自然取到新的）。 */
    private String text(String key) {
        return new Text(langProvider).get(key);
    }

    /** 取当前语言下的文案并替换占位符。 */
    private String text(String key, Object... args) {
        return new Text(langProvider).get(key, args);
    }

    /** 把表单内容收集成一个 {@link SshTarget}。端口或超时填得不对时，由 {@link #takeInputProblem()} 给出原因。 */
    public SshTarget readTarget() {
        String host = hostField.getText().trim();
        String user = userField.getText().trim();
        String key = SshTarget.normalizeKeyPath(keyField.getText());

        int port = SshTarget.DEFAULT_PORT;
        String p = portField.getText().trim();
        if (!p.isEmpty()) {
            try {
                port = Integer.parseInt(p);
            } catch (NumberFormatException nfe) {
                // 端口栏里填了非数字。这里不能塞个负数就交给 SshTarget：
                // 它的构造会把「非正数」一律回退成 22，-1 进去 22 出来，
                // 于是 validate() 看到的是一个合法的端口，用户填的 "abc"
                // 会被悄悄当成 22 去连——连错机器还不知道为什么。
                // 所以非法值要在这里当场拦住。
                return invalidTarget(host, user, key, true,
                        text(UiText.ERR_PORT_NUMBER, p));
            }
        }

        SshTarget t = new SshTarget(host, port, user, key);
        t.label = nameField.getText().trim();
        int timeout = SshTarget.DEFAULT_TIMEOUT_SECONDS;
        String to = timeoutField.getText().trim();
        if (!to.isEmpty()) {
            try {
                timeout = Integer.parseInt(to);
            } catch (NumberFormatException nfe) {
                return invalidTarget(host, user, key, false,
                        text(UiText.ERR_TIMEOUT_NUMBER, to));
            }
        }
        t.timeoutSeconds = timeout;
        return t;
    }

    /**
     * 造一个「一定通不过校验」的占位目标，用来把界面上的输入错误带过校验这一步。
     *
     * <p>除了在 {@link #pendingInputProblem} 里记下原因，占位目标本身也必须
     * 真的不合法——否则一旦有人只调 {@code readTarget().validate()} 而不取
     * 待报错误，界面上的错误输入就会被当成合法参数悄悄放行。所以这里按出错的
     * 那一栏，把对应字段顶到不可能通过校验的值：端口越界、或超时越界。
     *
     * @param portBad true 表示端口栏出问题，false 表示超时栏出问题
     */
    private SshTarget invalidTarget(String host, String user, String key,
            boolean portBad, String problem) {
        SshTarget t = new SshTarget(host, portBad ? 65536 : SshTarget.DEFAULT_PORT,
                user, key);
        t.label = nameField.getText().trim();
        t.timeoutSeconds = portBad ? SshTarget.DEFAULT_TIMEOUT_SECONDS : 65536;
        this.pendingInputProblem = problem;
        return t;
    }

    /**
     * 上一次 {@link #readTarget()} 遇到的界面输入错误，null 表示没有。
     *
     * <p>读一次就清空：同一条错误不该在两次操作里重复生效。
     */
    private String pendingInputProblem;

    /** 取走当前待报的界面输入错误（取走即清空）。 */
    private String takeInputProblem() {
        String s = pendingInputProblem;
        pendingInputProblem = null;
        return s;
    }

    /**
     * 校验连接参数，并把 {@link SshTarget#validate()} 的英文原因翻成当前语言。
     *
     * <p>为什么需要这层包装：{@code SshTarget} 在 vmadapt.core 里，core 是纯逻辑层，
     * 不知道界面语言（它连 Swing 都不引用）。所以它返回的原因是固定英文。界面直接
     * 把它显示出来，中文用户就会在一个全中文的面板里看到一句英文——这正是本项目
     * 双语化要消除的东西。这里按 core 的判断顺序复刻一遍条件，命中时给出本地化文案；
     * 万一日后 core 新增了校验分支，下面的兜底分支会把它的原文照常显示，不会吞掉。
     *
     * @param t 待校验目标
     * @return null 表示合法；否则返回当前语言的原因
     */
    private String validateLocalized(SshTarget t) {
        String host = t.host == null ? "" : t.host.trim();
        if (host.isEmpty()) {
            return text(UiText.VAL_HOST_EMPTY);
        }
        if (host.indexOf(' ') >= 0 || host.indexOf('\t') >= 0) {
            return text(UiText.VAL_HOST_SPACE, host);
        }
        if (host.startsWith("ssh://")) {
            return text(UiText.VAL_HOST_PREFIX);
        }
        if (host.indexOf('@') >= 0) {
            return text(UiText.VAL_HOST_AT, host);
        }
        if (t.user == null || t.user.trim().isEmpty()) {
            return text(UiText.VAL_USER_EMPTY);
        }
        if (t.port <= 0 || t.port > 65535) {
            return text(UiText.VAL_PORT_RANGE, String.valueOf(t.port));
        }
        if (t.timeoutSeconds <= 0 || t.timeoutSeconds > 600) {
            return text(UiText.VAL_TIMEOUT_RANGE, String.valueOf(t.timeoutSeconds));
        }
        String key = t.keyPath;
        if (key != null && !key.trim().isEmpty()
                && !new File(key.trim()).isFile()) {
            return text(UiText.VAL_KEY_MISSING, key.trim());
        }
        // 兜底：交给 core 自己判断，命中未知分支时原样显示它的说明。
        return t.validate();
    }

    /** 把一条配置回填到表单。 */
    public void writeTarget(SshTarget t) {
        if (t == null) {
            return;
        }
        hostField.setText(t.host == null ? "" : t.host);
        portField.setText(String.valueOf(t.port > 0 ? t.port : SshTarget.DEFAULT_PORT));
        userField.setText(t.user == null ? "" : t.user);
        keyField.setText(t.keyPath == null ? "" : t.keyPath);
        nameField.setText(t.label == null ? "" : t.label);
        timeoutField.setText(String.valueOf(t.timeoutSeconds > 0
                ? t.timeoutSeconds : SshTarget.DEFAULT_TIMEOUT_SECONDS));
    }

    // ------------------------------------------------------------------
    // 按键：测试连接
    // ------------------------------------------------------------------


    /**
     * 把 {@link #paintStatus} 刚记下的那次结果弹成一个窗。
     *
     * <p>刻意<b>复用</b> {@code statusArea} 里已有的正文，而不是另拼一份：两处各写
     * 一份迟早会走样，而"弹窗说的和别处说的不一样"比不弹窗更糟。所以这里只读已渲染
     * 好的内容，调用方负责先把它写好。
     *
     * <p>无头环境（自检）下不弹：那里没有窗口可言，而正文已经写好了，测试断言正文即可。
     *
     * @param title 标题栏文字，由调用方按操作类型给（测试连接 / 保存 / 生成密钥…）
     * @param ok    true 用信息图标与绿色正文，false 用警告图标与红色正文
     */
    private void showResultDialog(String title, boolean ok) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        String body = statusArea.getText();
        if (body == null || body.trim().isEmpty()) {
            return;
        }
        JTextArea area = new JTextArea(body, 20, 74);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        area.setBackground(Color.WHITE);
        area.setForeground(ok ? OK_COLOR : FAIL_COLOR);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(700, 420));

        javax.swing.JOptionPane.showMessageDialog(
                SwingUtilities.getWindowAncestor(this), scroll,
                title == null ? "" : title,
                ok ? javax.swing.JOptionPane.INFORMATION_MESSAGE
                        : javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    /** 把一份 {@link ConnectionReport} 渲染到状态区：成功绿、失败红。 */
    private void showReport(SshTarget target, ConnectionReport report) {
        if (report == null) {
            reportFailure(text(UiText.MSG_NULL_REPORT),
                    text(UiText.MSG_NULL_REPORT_REASON), null);
            return;
        }
        if (report.ok) {
            StringBuilder sb = new StringBuilder();
            sb.append(text(UiText.MSG_CONNECTED, target.toDisplay())).append('\n');
            sb.append("──────────────────────────────\n");
            sb.append(text(UiText.RPT_OS,
                    blankTo(report.osName, text(UiText.RPT_UNRECOGNIZED)))).append('\n');
            // 「内核版本」显示的是 uname -r（kernelRelease），不是 uname -s。
            //
            // 这里曾经写成 report.kernel（= uname -s），于是界面把「Linux」当成
            // 内核版本显示出来，而真正的版本号 5.15.0-119-generic 从头到尾没露过面。
            // kernelRelease 为空时才退回 kernel，这样至少不会显示成空白。
            String release = blankTo(report.kernelRelease, report.kernel);
            sb.append(text(UiText.RPT_KERNEL,
                    blankTo(release, text(UiText.RPT_UNRECOGNIZED)))).append('\n');
            sb.append(text(UiText.RPT_HOME,
                    blankTo(report.homeDir, text(UiText.RPT_UNRECOGNIZED)))).append('\n');
            appendList(sb, text(UiText.RPT_NOTES), report.notes);
            appendList(sb, text(UiText.RPT_SUGGESTIONS), report.advices);
            sb.append("──────────────────────────────\n");
            sb.append(text(UiText.RPT_NEXT)).append('\n');

            // 先写正文，再 paintStatus：弹窗在 paintStatus 里发，读的是 statusArea。
            statusArea.setText(sb.toString());
            statusArea.setCaretPosition(0);
            statusArea.setForeground(OK_COLOR);
            paintStatus(OK_COLOR, OK_BG,
                    text(UiText.MSG_CONNECTED_SHORT, target.toDisplay()));
            markStatusResult();
            fireConnected(target);
        } else {
            reportFailure(text(UiText.MSG_CONNECT_FAILED, target.toDisplay()),
                    blankTo(report.error, text(UiText.MSG_NO_REASON)), report.advices,
                    report.rawErrors);
        }
    }

    /**
     * 统一的失败展示：红色标题 + 原因 + 建议列表。
     *
     * @param title   状态栏标题
     * @param reason  具体原因
     * @param advices 建议列表，可为 null（此时用一份通用建议兜底）
     */
    private void reportFailure(String title, String reason, List<String> advices) {
        reportFailure(title, reason, advices, null);
    }

    /**
     * 统一的失败展示：红色标题 + 原因 + 建议列表 +（可选）原始输出。
     *
     * <p>为什么要能把原始输出摆出来：有些失败的原因不在我们这边，而在远端到底回了什么。
     * 例如「连上了但探测结果不对」这一类，光看一句结论无从判断，必须能看到命令的真实输出。
     * 报告里早就在收集 rawErrors，但界面从不显示它，等于白收集——排查时只能靠猜。
     *
     * @param title    状态栏标题
     * @param reason   具体原因
     * @param advices  建议列表，可为 null（此时用一份通用建议兜底）
     * @param rawOutput 远端原始输出，可为 null 或空
     */
    private void reportFailure(String title, String reason, List<String> advices,
                               List<String> rawOutput) {
        StringBuilder sb = new StringBuilder();
        sb.append(text(UiText.RPT_REASON, blankTo(reason, text(UiText.RPT_UNKNOWN))))
                .append('\n');
        sb.append("──────────────────────────────\n");

        List<String> tips = advices;
        if (tips == null || tips.isEmpty()) {
            // core 没给建议时不要留白：把最常踩的坑直接摆出来，
            // 免得用户对着一个「失败」发呆。
            tips = new ArrayList<String>();
            tips.add(text(UiText.TIP_FALLBACK_1));
            tips.add(text(UiText.TIP_FALLBACK_2));
            tips.add(text(UiText.TIP_FALLBACK_3));
            tips.add(text(UiText.TIP_FALLBACK_4));
            tips.add(text(UiText.TIP_FALLBACK_5));
        }
        appendList(sb, text(UiText.RPT_SUGGESTIONS), tips);
        if (rawOutput != null && !rawOutput.isEmpty()) {
            appendList(sb, text(UiText.RPT_RAW), rawOutput);
        }
        sb.append("──────────────────────────────\n");
        sb.append(text(UiText.RPT_SEE_NETWORK)).append('\n');

        // 顺序要紧：先把正文写好，再 paintStatus —— 弹窗就是在那里面发的，
        // 它读的是 statusArea 的内容。先 paintStatus 会让弹窗弹出上一轮的文字。
        statusArea.setText(sb.toString());
        statusArea.setCaretPosition(0);
        statusArea.setForeground(FAIL_COLOR);
        paintStatus(FAIL_COLOR, FAIL_BG, title);
        markStatusResult();
    }

    private static void appendList(StringBuilder sb, String heading, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sb.append(heading).append(":\n");
        int i = 1;
        for (String s : items) {
            if (s == null || s.trim().isEmpty()) {
                continue;
            }
            sb.append("  ").append(i++).append(". ").append(s.trim()).append('\n');
        }
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.trim().isEmpty() ? fallback : s.trim();
    }

    // ------------------------------------------------------------------
    // 按键：保存 / 删除 / 载入配置
    // ------------------------------------------------------------------








    // ------------------------------------------------------------------
    // 按键：生成密钥
    // ------------------------------------------------------------------

    /**
     * 调用外部 ssh-keygen.exe 生成一对密钥。
     *
     * <p>为什么不用纯 Java 生成：JDK 自带的 KeyPairGenerator 虽然能造 RSA 密钥，
     * 但要自己拼 OpenSSH 的私钥封装格式和公钥单行格式，任何一处字节写错，
     * ssh.exe 都会报「invalid format」并且不给有用的提示。Windows 10 以后的
     * OpenSSH 客户端本身就在 C:\WINDOWS\System32\OpenSSH\ 里，直接调它最稳。
     */
    private void generateKey() {
        final String sshKeygen = OpenSsh.locate("ssh-keygen.exe");
        if (sshKeygen == null) {
            reportFailure(text(UiText.MSG_NO_KEYGEN),
                    text(UiText.MSG_NO_KEYGEN_REASON),
                    keygenAdvice());
            return;
        }

        String home = System.getProperty("user.home");
        if (home == null || home.trim().isEmpty()) {
            reportFailure(text(UiText.MSG_NO_HOME),
                    text(UiText.MSG_NO_HOME_REASON), keygenAdvice());
            return;
        }
        final File sshDir = new File(home, ".ssh");
        final File keyFile = new File(sshDir, "id_rsa_tbvm");
        final String comment = keyComment();

        if (keyFile.isFile()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    text(UiText.MSG_KEY_EXISTS, keyFile.getAbsolutePath()),
                    text(UiText.MSG_KEY_EXISTS_TITLE),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
            // 必须先把旧文件删干净，再去调 ssh-keygen。
            //
            // 否则 ssh-keygen 会自己再问一遍「Overwrite (y/n)?」，而插件的子进程
            // 没有 stdin，它就一直等下去——用户看到的是「点了生成密钥，几分钟
            // 没有任何反应」，面板永远停在运行中。实测确认过：
            //   文件不存在 → 4096 位密钥 625 ms 完成
            //   文件已存在 → 卡死在 "already exists." 后不再有任何输出
            //
            // 覆盖是用户刚刚亲口同意的，所以这里删掉并不额外改变语义。
            File pubFile = new File(keyFile.getAbsolutePath() + ".pub");
            if (!deleteKeyFile(keyFile) || !deleteKeyFile(pubFile)) {
                reportFailure(text(UiText.MSG_GEN_FAILED),
                        text(UiText.MSG_KEY_DELETE_FAILED, keyFile.getAbsolutePath()),
                        keygenAdvice());
                return;
            }
        }

        setBusy(true);
        // 进度提示，不弹窗；结果在下面完成时由 paintStatus 发出。
        paintStatusBusy(NEUTRAL_COLOR, NEUTRAL_BG, text(UiText.MSG_GEN_RUNNING));
        statusArea.setForeground(NEUTRAL_COLOR);
        statusArea.setText(text(UiText.MSG_GEN_RUNNING_BODY) + "\n  " + sshKeygen + "\n"
                + text(UiText.MSG_GEN_OUTFILES) + "\n  " + keyFile.getAbsolutePath() + "\n"
                + "  " + keyFile.getAbsolutePath() + ".pub\n");

        new SwingWorker<OpenSsh.Result, Void>() {
            @Override
            protected OpenSsh.Result doInBackground() {
                if (!sshDir.isDirectory() && !sshDir.mkdirs()) {
                    OpenSsh.Result r = new OpenSsh.Result();
                    r.exitCode = -1;
                    r.output = text(UiText.MSG_MKDIR_FAILED, sshDir.getAbsolutePath());
                    return r;
                }
                // 空口令必须写成 -N ""，而这一对引号只有经过 cmd.exe 才能活着到达：
                // Java 的 ProcessBuilder 在 Windows 上会静默丢掉空字符串参数，
                // 于是 "-N" 后面直接跟着 "-C"，ssh-keygen 报 "Too many arguments"
                // 并且一个文件都不生成。实测过：同一个空参数用 cmd.exe 包装就能
                // 正确送达（探针见 build/keygenprobe）。
                //
                // 为什么不带密码：面板本身就靠免密公钥登录，给密钥加密反而会让
                // ssh.exe 在 BatchMode 下无法自动登录。
                String line = keygenCommandLine(sshKeygen, comment, keyFile.getAbsolutePath());
                return OpenSsh.runViaCmd(text(UiText.PFX_EXEC_FAILED),
                        text(UiText.PFX_EXEC_CMD), line);
            }

            @Override
            protected void done() {
                setBusy(false);
                OpenSsh.Result r;
                try {
                    r = get();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    reportFailure(text(UiText.MSG_GEN_FAILED),
                            String.valueOf(cause), keygenAdvice());
                    return;
                }
                if (r.exitCode != 0) {
                    reportFailure(text(UiText.MSG_GEN_FAILED_CODE,
                            String.valueOf(r.exitCode)),
                            r.output, keygenAdvice());
                    return;
                }
                File pub = new File(keyFile.getAbsolutePath() + ".pub");
                keyField.setText(keyFile.getAbsolutePath());

                paintStatus(OK_COLOR, OK_BG, text(UiText.MSG_GEN_OK_TITLE));
                statusArea.setForeground(OK_COLOR);
                statusArea.setText(text(UiText.MSG_GEN_OK_BODY) + "\n"
                        + "  " + text(UiText.MSG_GEN_PRIVKEY, keyFile.getAbsolutePath()) + "\n"
                        + "  " + text(UiText.MSG_GEN_PUBKEY, pub.getAbsolutePath()) + "\n"
                        + "  " + text(UiText.MSG_GEN_PASSPHRASE) + "\n"
                        + "\n" + text(UiText.MSG_GEN_TAIL) + "\n"
                        + "\n" + text(UiText.MSG_GEN_OUTPUT) + "\n" + r.output + "\n");
                statusArea.setCaretPosition(0);
                if (afterGenerate != null) {
                    Runnable next = afterGenerate;
                    afterGenerate = null;
                    next.run();
                }
            }
        }.execute();
    }

    /**
     * 生成成功后要接着做的事，由 {@link #setupKey()} 设置。
     *
     * <p>存在的原因只有一个：「设置密钥」要在密钥生成<b>之后</b>再把它传到虚拟机，
     * 而生成是异步的。用回调而不是让 setupKey 轮询或等待，是因为等待会占住事件线程。
     * 一次用完即清，避免影响下次单独点「生成密钥」。
     */
    private Runnable afterGenerate;

    /**
     * 「设置密钥」：一步把密钥准备好并装到虚拟机上。
     *
     * <h2>为什么可以合并</h2>
     *
     * <p>两件事本来就是一条链：生成密钥写在<b>固定路径</b> {@code ~/.ssh/id_rsa_tbvm}，
     * 并把它填进密钥框；复制公钥读的正是密钥框里的路径 + 表单里的 IP/用户名/端口。
     * 所以生成完必然知道要传哪个文件，用户不需要在两步之间做任何选择。
     *
     * <p>分三种情况：
     * <ul>
     *   <li>密钥不存在 → 生成，然后自动传，一步到位；</li>
     *   <li>密钥已存在 → 不覆盖（覆盖会让之前已经装到别的机器的公钥失效），直接传；</li>
     *   <li>表单填得不全 → 明确说缺什么，什么都不做。</li>
     * </ul>
     *
     * <p>传之前需要 IP/用户名/端口，所以先把表单校验跑一遍——否则会先生成了一把
     * 密钥、再告诉你地址没填，白做一步。
     */
    private void setupKey() {
        final SshTarget target = readTarget();
        String problem = takeInputProblem();
        if (problem == null) {
            problem = validateLocalized(target);
        }
        if (problem != null) {
            reportFailure(text(UiText.MSG_SETUP_NEED_FORM), problem, null);
            return;
        }

        File sshDir = sshDirForSetup();
        if (sshDir == null) {
            return;   // 已经报过错
        }
        File keyFile = new File(sshDir, "id_rsa_tbvm");
        if (keyFile.isFile()) {
            // 已有密钥：不重新生成，直接把它装上去。
            copyPublicKeyToRemote();
            return;
        }
        afterGenerate = new Runnable() {
            @Override
            public void run() {
                copyPublicKeyToRemote();
            }
        };
        generateKey();
    }

    /** 「设置密钥」用的 .ssh 目录；拿不到用户目录时报错并返回 null。 */
    private File sshDirForSetup() {
        String home = System.getProperty("user.home");
        if (home == null || home.trim().isEmpty()) {
            reportFailure(text(UiText.MSG_NO_HOME), text(UiText.MSG_NO_HOME_REASON),
                    keygenAdvice());
            return null;
        }
        return new File(home, ".ssh");
    }

    /** 生成密钥时用的注释，写清来源方便日后在 authorized_keys 里认出来。 */
    private String keyComment() {
        String user = userField.getText().trim();
        String host = hostField.getText().trim();
        StringBuilder sb = new StringBuilder("tbvm");
        if (!user.isEmpty()) {
            sb.append('-').append(user);
        }
        if (!host.isEmpty()) {
            sb.append('@').append(host);
        }
        return sb.toString();
    }

    /**
     * 按 cmd.exe 的规则给一个参数加引号。
     *
     * <p>为什么需要它：ssh-keygen 要经 cmd.exe 启动（理由见
     * {@code OpenSsh#runViaCmd}），而 cmd 会把空格切开，所以路径和注释都得包起来。
     * 双引号一律先剔除——Windows 路径里本来就非法，注释也已做过白名单清理，
     * 这里再挡一道，避免任何形式的引号逃逸。
     */
    private static String cmdQuote(String s) {
        String v = s == null ? "" : s.replace("\"", "");
        return "\"" + v + "\"";
    }

    /**
     * 把密钥注释清理成 cmd 安全的形式。
     *
     * <p>注释由用户在「用户名 / 主机」里输入的内容拼成，其中可能含 {@code & | > < ^ %}
     * 这类 cmd 元字符——它们夹在双引号里虽然大多无害，但 {@code %} 会被 cmd 展开。
     * 注释只是给人看的标识，所以直接白名单化最省心。
     */
    private static String cleanComment(String s) {
        if (s == null || s.trim().isEmpty()) {
            return "tbvm";
        }
        return s.trim().replaceAll("[^A-Za-z0-9._@-]", "_");
    }

    /**
     * 拼出生成密钥的完整命令行（供 {@code cmd.exe /c} 执行）。
     *
     * <p>抽成静态方法是为了可测：这条命令行的正确性无法靠"看着对"来判断——
     * 关键在于 {@code -N ""} 这一对引号必须原样出现在命令行里，而不是被当成
     * 一个空参数在别处传参。单元测试会真的把它跑一遍并检查两个文件都生成。
     *
     * @param sshKeygen ssh-keygen 的绝对路径
     * @param comment   注释，会被清理成 cmd 安全字符
     * @param keyFile   私钥输出路径（公钥自动是同名 .pub）
     * @return 已按 cmd 规则加引号的命令行
     */
    public static String keygenCommandLine(String sshKeygen, String comment, String keyFile) {
        return cmdQuote(sshKeygen)
                + " -t rsa -b 4096 -N \"\""
                + " -C " + cmdQuote(cleanComment(comment))
                + " -f " + cmdQuote(keyFile);
    }

    /**
     * 名字留空时用的标签：{@code 用户@主机:端口}。
     *
     * <p>刻意和界面别处显示的写法保持一致，这样留空与否看到的都是同一串字，
     * 不会出现「存的时候叫 A、显示的时候叫 B」。
     */
    private static String defaultLabelFor(SshTarget t) {
        String host = t.host == null ? "" : t.host.trim();
        int port = t.port > 0 ? t.port : SshTarget.DEFAULT_PORT;
        String user = t.user == null ? "" : t.user.trim();
        return user.isEmpty() ? host + ":" + port : user + "@" + host + ":" + port;
    }

    /**
     * 删除一个密钥文件；文件本来就不存在也算成功。
     *
     * <p>返回 false 只发生在「删过但它还在」——那说明文件被占用或权限不对。
     * 这种情况必须立刻停下：再调 ssh-keygen 一定会卡在覆盖提问上，而子进程
     * 没有 stdin，用户就只会看到一个永远转不完的「运行中」。
     */
    private static boolean deleteKeyFile(File f) {
        if (f == null || !f.exists()) {
            return true;
        }
        // 先删再看：delete() 的返回值不如「文件是否真的消失」可靠。
        f.delete();
        return !f.exists();
    }

    /** 生成密钥失败时的排查建议。实例方法：要按当前语言取文案。 */
    private List<String> keygenAdvice() {        List<String> tips = new ArrayList<String>();
        tips.add(text(UiText.TIP_KEYGEN_1));
        tips.add(text(UiText.TIP_KEYGEN_2));
        tips.add(text(UiText.TIP_KEYGEN_3));
        tips.add(text(UiText.TIP_KEYGEN_4));
        return tips;
    }

    // ------------------------------------------------------------------
    // 按键：复制公钥到远端
    // ------------------------------------------------------------------

    /**
     * 把公钥追加到远端 {@code ~/.ssh/authorized_keys}。
     *
     * <p>Windows 没有 ssh-copy-id（那是 Linux 上的 shell 脚本），所以用等价做法：
     * 把公钥内容通过管道喂给远端 shell，让它在远端自己追加。公钥里没有单引号，
     * 因此用单引号包住整段内容即可，不需要处理转义。
     */
    private void copyPublicKeyToRemote() {
        final SshTarget target = readTarget();
        String problem = takeInputProblem();
        if (problem == null) {
            problem = validateLocalized(target);
        }
        if (problem != null) {
            reportFailure(text(UiText.MSG_INCOMPLETE_COPY), problem, null);
            return;
        }
        String keyPath = target.keyPath;
        if (keyPath == null || keyPath.trim().isEmpty()) {
            reportFailure(text(UiText.MSG_NO_PRIVKEY),
                    text(UiText.MSG_NO_PRIVKEY_REASON),
                    copyKeyAdvice());
            return;
        }
        final File keyFile = new File(keyPath.trim());
        final File pubFile = new File(keyFile.getAbsolutePath() + ".pub");
        if (!pubFile.isFile()) {
            reportFailure(text(UiText.MSG_NO_PUBFILE),
                    text(UiText.MSG_NO_PUBFILE_REASON, pubFile.getAbsolutePath()),
                    copyKeyAdvice());
            return;
        }

        String sshExe = OpenSsh.locate("ssh.exe");
        if (sshExe == null) {
            reportFailure(text(UiText.MSG_NO_SSH),
                    text(UiText.MSG_NO_SSH_REASON),
                    copyKeyAdvice());
            return;
        }

        // 这里不再弹确认框。
        //
        // 曾经弹一次「确定把 X 装到 Y 吗」。但地址、用户名、端口和密钥都是用户刚在
        // 表单里填好的，再问一句等于让他把刚填的东西读一遍确认——而这一步的目的
        // （免密登录）本来就是他按这个按钮想要的结果。多一次点击换不到额外的判断。
        //
        // 想核对的话，"测试连接"会告诉你能不能连上；而这里写入的是追加（不是覆盖）
        // authorized_keys，重复安装同一个公钥也不会破坏别的登录方式。
        final String ssh = sshExe;
        setBusy(true);
        // 进度提示，不弹窗。
        paintStatusBusy(NEUTRAL_COLOR, NEUTRAL_BG,
                text(UiText.MSG_COPYING, target.toDisplay()));
        statusArea.setForeground(NEUTRAL_COLOR);
        statusArea.setText(text(UiText.MSG_COPYING_BODY,
                String.valueOf(target.timeoutSeconds)) + "\n");

        new SwingWorker<OpenSsh.Result, Void>() {
            @Override
            protected OpenSsh.Result doInBackground() {
                String publicKey;
                try {
                    publicKey = readTextFile(pubFile);
                } catch (Exception ex) {
                    OpenSsh.Result r = new OpenSsh.Result();
                    r.exitCode = -1;
                    r.output = text(UiText.MSG_READ_PUB_FAILED, String.valueOf(ex));
                    return r;
                }
                publicKey = publicKey.trim();
                if (publicKey.isEmpty()) {
                    OpenSsh.Result r = new OpenSsh.Result();
                    r.exitCode = -1;
                    r.output = text(UiText.MSG_PUB_EMPTY, pubFile.getAbsolutePath());
                    return r;
                }

                // 远端要跑的脚本。分段写清楚，失败时输出好读。
                String remote = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && "
                        + "touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && "
                        + "grep -qxF '" + publicKey + "' ~/.ssh/authorized_keys || "
                        + "echo '" + publicKey + "' >> ~/.ssh/authorized_keys; "
                        + "echo TBVM_KEY_INSTALLED; wc -l < ~/.ssh/authorized_keys";

                List<String> cmd = new ArrayList<String>();
                cmd.add(ssh);
                cmd.add("-o");
                cmd.add("BatchMode=yes");
                cmd.add("-o");
                cmd.add("StrictHostKeyChecking=accept-new");
                cmd.add("-o");
                cmd.add("ConnectTimeout=" + Math.max(3, target.timeoutSeconds));
                cmd.add("-p");
                cmd.add(String.valueOf(target.port));
                if (target.keyPath != null && !target.keyPath.trim().isEmpty()) {
                    cmd.add("-i");
                    cmd.add(target.keyPath.trim());
                }
                cmd.add(target.user + "@" + target.host);
                cmd.add(remote);
                return OpenSsh.runWithPrefixes(text(UiText.PFX_EXEC_FAILED),
                        text(UiText.PFX_EXEC_CMD), cmd.toArray(new String[0]));
            }

            @Override
            protected void done() {
                setBusy(false);
                OpenSsh.Result r;
                try {
                    r = get();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    reportFailure(text(UiText.MSG_COPY_FAILED),
                            String.valueOf(cause), copyKeyAdvice());
                    return;
                }
                if (r.exitCode == 0 && r.output.contains("TBVM_KEY_INSTALLED")) {
                    paintStatus(OK_COLOR, OK_BG,
                            text(UiText.MSG_COPY_OK_TITLE, target.toDisplay()));
                    statusArea.setForeground(OK_COLOR);
                    statusArea.setText(text(UiText.MSG_COPY_OK_BODY) + "\n"
                            + "\n" + text(UiText.MSG_CMD_OUTPUT) + "\n" + r.output + "\n");
                } else {
                    reportFailure(text(UiText.MSG_COPY_FAILED_CODE,
                            String.valueOf(r.exitCode)),
                            r.output, copyKeyAdvice());
                }
                statusArea.setCaretPosition(0);
            }
        }.execute();
    }

    /** 复制公钥失败时的排查建议。实例方法：要按当前语言取文案。 */
    private List<String> copyKeyAdvice() {
        List<String> tips = new ArrayList<String>();
        tips.add(text(UiText.TIP_COPY_1));
        tips.add(text(UiText.TIP_COPY_2));
        tips.add(text(UiText.TIP_COPY_3));
        tips.add(text(UiText.TIP_COPY_4));
        tips.add(text(UiText.TIP_COPY_5));
        return tips;
    }

    /** 读一个小文本文件（公钥只有几百字节，直接全读即可）。 */
    private static String readTextFile(File f) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            r.close();
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 忙碌状态与状态区
    // ------------------------------------------------------------------

    /**
     * 切换忙碌状态。
     *
     * <p>忙碌时禁用入口按钮并换成「正在连接…」：否则用户会连点几次，几个后台任务
     * 并发跑，最后到达的那个结果覆盖前面的，界面显示的可能是任意一次的结果——比等
     * 一会儿更让人困惑。
     */
    private void setBusy(boolean value) {
        busy = value;
        connectButton.setEnabled(!value);
        connectButton.setText(value
                ? text(UiText.BTN_TESTING)
                : text(UiText.BTN_CONNECT));
        setCursor(value ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
    }

    /**
     * 记下状态标题与配色，并安排把这次结果弹出来。
     *
     * <p>这里是**所有**操作结果的唯一出口：测试连接、保存、删除、载入、生成密钥、
     * 复制公钥都经过它。所以弹窗也挂在它上面——「连接结果」页删掉之后，如果只在个别
     * 地方弹窗，另外几个操作的结果就哪儿也看不到了。
     *
     * <p>弹窗用 {@code invokeLater} <b>推迟到本次事件处理结束之后</b>，而不是立即弹。
     * 原因是调用顺序不统一：有的地方先 paintStatus 再写正文，有的反过来（本文件里
     * 两种都有）。立即弹的话，前者会把<b>上一次</b>的文字弹出来——而且不会报错，
     * 只是内容对不上。推迟一拍，正文一定已经写完，顺序就不再是问题。
     *
     * <p><b>进行中的提示不弹</b>：调用方在处理开头会用 {@link #paintStatusBusy} 说一句
     * "正在连接…"，那是进度而不是结果。否则用户点一下会先收到一个"正在连接"的窗，
     * 操作完再收到一个结果窗，等于被打断两次。
     *
     * <p>顺带记下颜色：语言切换时要用同一组颜色重画标题，不能因为切了语言就把
     * 「上次失败是红的」这件事忘掉。
     */
    private void paintStatus(Color fg, Color bg, String text) {
        statusFg = fg;
        statusBg = bg;
        statusLabel.setForeground(fg);
        statusLabel.setBackground(bg);
        statusLabel.setText(text);
        final String title = text;
        final boolean ok = (fg == OK_COLOR);
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                showResultDialog(title, ok);
            }
        });
    }

    /**
     * 记下状态，但<b>不</b>弹窗——用于"正在做某事"这类进度提示。
     *
     * <p>与 {@link #paintStatus} 只差弹不弹窗，所以共用同一份颜色记录逻辑。
     */
    private void paintStatusBusy(Color fg, Color bg, String text) {
        statusFg = fg;
        statusBg = bg;
        statusLabel.setForeground(fg);
        statusLabel.setBackground(bg);
        statusLabel.setText(text);
    }

    /**
     * 标记状态区已显示某次操作的结果。
     *
     * <p>语言切换时不覆盖这类内容：切语言是「换个说法看同样的东西」，
     * 把用户刚看到的连接结果抹掉换成一句「尚未测试连接」是明显的行为倒退。
     */
    private void markStatusResult() {
        statusShowsResult = true;
    }

    // ------------------------------------------------------------------
    // 内部小工具
    // ------------------------------------------------------------------

    /** 定位 Windows 自带 OpenSSH 工具的辅助方法。 */
    public static final class OpenSsh {

        /** OpenSSH 客户端的标准安装位置（Windows 10 1809 以后系统自带）。 */
        private static final String[] DIRS = {
            "C:\\WINDOWS\\System32\\OpenSSH\\",
            "C:\\Program Files\\OpenSSH\\",
            "C:\\Program Files (x86)\\OpenSSH\\",
        };

        private OpenSsh() {
        }

        /**
         * 在多个候选里选出要用的 ssh.exe，并把结果缓存下来。
         *
         * <h2>为什么不能"第一个找到就用"</h2>
         *
         * <p>原来 {@link #locate} 是按 {@link DIRS} 的顺序返回第一个存在的文件，而
         * System32 排在最前。这带来一个很别扭的后果：<b>用户按微软的官方做法升级
         * OpenSSH 之后，插件仍然用旧的。</b>
         *
         * <p>微软的文档写得很清楚：系统自带的那份在
         * {@code C:\Windows\System32\OpenSSH}（Windows 10 上经常是 7.7p1 / 8.1p1），
         * 而 GitHub 上的 Win32-OpenSSH 装到 {@code C:\Program Files\OpenSSH}，
         * 版本新得多。于是"装了新版却仍然用旧版"几乎必然发生——用户升级完发现插件的
         * 提示一点没变，只会以为升级没成功。
         *
         * <p>所以改成：<b>在候选里挑版本最高的那个</b>。Windows 10 自带 8.1p1 而用户
         * 装了 9.x 时，选 9.x；什么都只有一份时，行为与以前一致。
         *
         * <p>拿不到版本的一律当作最低优先，但不能因此让"唯一存在的那个"落选——
         * 所以只在<b>确实比不出来</b>时保留先找到的。
         */
        private static String sshExe;
        private static boolean sshExeResolved;

        static synchronized String sshExe() {
            if (sshExeResolved) {
                return sshExe;
            }
            sshExeResolved = true;
            String best = null;
            int bestMajor = -1;
            int bestMinor = -1;
            for (String dir : candidates()) {
                File f = new File(dir, "ssh.exe");
                if (!f.isFile()) {
                    continue;
                }
                String ver = SshAskpass.versionOf(f.getAbsolutePath());
                int major = SshAskpass.majorOf(ver);
                int minor = SshAskpass.minorOf(ver);
                if (best == null || newer(major, minor, bestMajor, bestMinor)) {
                    best = f.getAbsolutePath();
                    bestMajor = major;
                    bestMinor = minor;
                }
            }
            sshExe = best;
            return sshExe;
        }

        /** 版本比较；解析不出（-1,-1）时视为最旧。 */
        private static boolean newer(int major, int minor, int bestMajor, int bestMinor) {
            if (major > bestMajor) {
                return true;
            }
            if (major < bestMajor) {
                return false;
            }
            return minor > bestMinor;
        }

        /** 所有候选目录：标准安装位置 + PATH 的各个目录。 */
        private static List<String> candidates() {
            List<String> out = new ArrayList<String>();
            for (String d : DIRS) {
                out.add(d);
            }
            String path = System.getenv("PATH");
            if (path != null) {
                for (String part : path.split(File.pathSeparator)) {
                    if (part != null && !part.trim().isEmpty()) {
                        out.add(part.trim());
                    }
                }
            }
            return out;
        }

        /**
         * 找一个 openssh 可执行文件的绝对路径。
         *
         * <p>对 {@code ssh.exe} 之外的工具（典型是 {@code ssh-keygen.exe}），
         * <b>优先取与 ssh 同一个目录里的那份</b>：两者必须同源。混用不同版本的
         * ssh 与 ssh-keygen 至少是没道理的，而新老版本的密钥格式、默认参数都可能不同。
         *
         * <p>取不到才回退到标准目录 / PATH 的顺序查找。返回 null 表示没找到。
         *
         * @param exe 可执行文件名，例如 ssh.exe
         */
        static String locate(String exe) {
            // 先用与选定 ssh 同目录的那份。
            if (!"ssh.exe".equalsIgnoreCase(exe)) {
                String ssh = sshExe();
                if (ssh != null) {
                    File sibling = new File(new File(ssh).getParentFile(), exe);
                    if (sibling.isFile()) {
                        return sibling.getAbsolutePath();
                    }
                }
            } else {
                String ssh = sshExe();
                if (ssh != null) {
                    return ssh;
                }
            }
            for (String dir : DIRS) {
                File f = new File(dir, exe);
                if (f.isFile()) {
                    return f.getAbsolutePath();
                }
            }
            // 回退：在 PATH 的各个目录里找。
            String path = System.getenv("PATH");
            if (path != null) {
                for (String part : path.split(File.pathSeparator)) {
                    if (part == null || part.trim().isEmpty()) {
                        continue;
                    }
                    File f = new File(part.trim(), exe);
                    if (f.isFile()) {
                        return f.getAbsolutePath();
                    }
                }
            }
            return null;
        }

        /** 一次外部命令的执行结果。 */
        public static final class Result {
            public int exitCode = -1;
            public String output = "";
            /**
             * 是否因为超过等待上限被强制终止。
             *
             * <p>与「命令自己返回了非零」区分开：前者说明命令卡住了（最典型的是
             * 转为交互提问而 GUI 又没有 stdin），后者说明命令跑了并给出了结论。
             * 界面据此给不同的提示。
             */
            public boolean timedOut;

            @Override
            public String toString() {
                return "exit=" + exitCode + (timedOut ? " (timed out)" : "")
                        + "\n" + output;
            }
        }

        /**
         * 外部命令的等待上限。
         *
         * <p>没有上限是不行的：实测 ssh-keygen 在目标文件已存在时会转为交互提问
         * 「Overwrite (y/n)?」，而插件的子进程没有 stdin，于是它一直等下去——
         * 用户看到的是「点了生成密钥，三分钟没有任何反应」，面板也永远停在运行中。
         * 生成 4096 位密钥本身只要几百毫秒，所以这个上限给得很宽松，只用来兜底。
         */
        private static final long TIMEOUT_MS_DEFAULT = 60000L;

        /**
         * 当前生效的等待上限，单位毫秒。
         *
         * <p>可写是为了自检能在秒级验证超时路径：真的等满 60 秒会让测试变得
         * 没人愿意跑，而「卡住的命令最终能被强制结束」正是这条路径最该被测的
         * 性质。生产代码不会去改它。
         */
        public static volatile long timeoutMs = TIMEOUT_MS_DEFAULT;

        /**
         * 同步执行一条外部命令并把 stdout+stderr 合并返回。
         *
         * <p>只应在后台线程调用。合并 stderr 是故意的：ssh-keygen 和 ssh 都把
         * 有用的错误写在 stderr 上，分开流会让界面显示不出原因。
         *
         * <p>本类不知道当前语言（它是静态嵌套类，没有面板实例），所以
         * 启动失败时的那句提示由调用方按当前语言通过
         * {@link #run(String, String, String...)} 传进来。
         */
        static Result run(String... cmd) {
            return runWithPrefixes("Execution failed: ", "Command: ", cmd);
        }

        /**
         * 把一整条命令行交给 {@code cmd.exe /c} 执行。
         *
         * <p>存在的唯一理由是<b>空参数</b>：Java 的 {@code ProcessBuilder} 在
         * Windows 上会把空字符串参数静默丢掉。{@code ssh-keygen -N ""} 需要的
         * 恰恰是一个空参数——少了它，{@code -N} 就会把下一个选项当成口令，
         * ssh-keygen 于是报 {@code Too many arguments} 且不生成任何文件。
         *
         * <p>实测数据（build/keygenprobe）：
         * <pre>
         *   ProcessBuilder: ["-N", "", "-C", x] → 子进程收到 ["-N", "-C", x]  ← 空参数消失
         *   cmd.exe /c     : 命令行里的 "" → 子进程收到真正的空参数        ← 正确
         * </pre>
         *
         * <p>调用方负责给各参数加引号，见 {@code cmdQuote}。
         *
         * @param commandLine 已按 cmd 规则拼好引号的完整命令行
         */
        public static Result runViaCmd(String failPrefix, String cmdPrefix, String commandLine) {
            // 为什么是 "/s"：cmd.exe 有一条不显眼的规则（cmd /? 里的 rule 2）——
            // 当 /c 之后的命令串以引号开头时，它会把首尾两个引号剥掉。
            // 我们的命令行正是以引号开头（程序路径被引起来），于是被剥成
            //   C:\...\ssh-keygen.exe" -t rsa ... -f "C:\...\key
            // 两端各坏一个引号，cmd 报「文件名、目录名或卷标语法不正确」。
            // /s 明确要求「剥掉外层引号、其余原样」，正好抵消这条规则。
            //
            // 实测（build/keygenprobe/Matrix）：不加 /s 在含空格或中文的路径下失败，
            // 加了 /s 在 ASCII / 含空格 / 中文含空格 三种路径下都成功。
            return runWithPrefixes(failPrefix, cmdPrefix,
                    "cmd.exe", "/s", "/c", "\"" + commandLine + "\"");
        }

        /**
         * 同 {@link #run(String...)}，但可以指定失败提示的措辞。
         *
         * <p>单独用一个方法名（而不是重载）是为了避免
         * {@code run(prefix, prefix, cmd...)} 与 {@code run(String...)}
         * 在调用点产生歧义。
         *
         * @param failPrefix 「执行失败：」这类前缀，调用方已按语言取好
         * @param cmdPrefix  「命令：」这类前缀，调用方已按语言取好
         * @param cmd        命令行
         */
        public static Result runWithPrefixes(String failPrefix, String cmdPrefix, String... cmd) {
            Result r = new Result();
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = null;
            try {
                p = pb.start();
                // 输出必须在另一个线程上读。
                //
                // 不能像以前那样在本线程 readLine 到 EOF 再 waitFor：子进程一旦
                // 不结束（典型是转为交互提问），readLine 就永远不返回，超时也就
                // 永远轮不到——这条路径曾经把界面永久卡在「运行中」。
                final Process proc = p;
                final StringBuilder sb = new StringBuilder();
                Thread pump = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        java.io.BufferedReader br = null;
                        try {
                            br = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(proc.getInputStream(),
                                            java.nio.charset.StandardCharsets.UTF_8));
                            String line;
                            while ((line = br.readLine()) != null) {
                                sb.append(line).append('\n');
                            }
                        } catch (Exception ignored) {
                            // 进程被强杀时读线程必然抛异常，属预期
                        } finally {
                            if (br != null) {
                                try {
                                    br.close();
                                } catch (Exception ignored) {
                                    // 关不掉也无妨，线程是 daemon
                                }
                            }
                        }
                    }
                });
                pump.setDaemon(true);
                pump.start();

                if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    r.timedOut = true;
                    r.exitCode = -1;
                    r.output = cmdPrefix + String.join(" ", cmd);
                    return r;
                }
                // 进程已退出，给读线程一点时间把最后几行取完。
                pump.join(2000L);
                r.exitCode = p.exitValue();
                r.output = sb.toString();
            } catch (Exception ex) {
                r.exitCode = -1;
                r.output = failPrefix + ex + "\n" + cmdPrefix + String.join(" ", cmd);
            } finally {
                if (p != null && p.isAlive()) {
                    p.destroyForcibly();
                }
            }
            return r;
        }
    }

    /**
     * 带灰色占位提示的文本框。
     *
     * <p><b>关键：占位文字只在 {@link #paintComponent} 里画，绝不写进
     * {@code getText()}。</b> 表单是靠 {@code getText()} 读真实值的
     * （见 {@link #readTarget()}），一旦把提示文字用 {@code setText()} 塞进去，
     * 用户什么都没填也会被当成填了——插件会拿着 "e.g. ubuntu" 这种字符串去连机器。
     * 所以这里只画不改，未输入时 {@code getText()} 仍然是空串。
     *
     * <p>有焦点时不画：光标就在那儿，再叠一层提示文字会看不清。
     */
    static final class PlaceholderTextField extends JTextField {

        private static final long serialVersionUID = 1L;

        /** 提示文案，null 表示不显示。可随语言切换而更新。 */
        private String placeholder;

        PlaceholderTextField(String placeholder) {
            this.placeholder = placeholder;
        }

        /**
         * 换一条提示文案。
         *
         * <p>语言切换时由 {@link #applyLanguage()} 调用。只改这个字段并重绘，
         * <b>不碰 {@code getText()}</b>——提示文字永远不是真实值。
         */
        void setPlaceholder(String text) {
            String next = text == null ? "" : text;
            if (!next.equals(placeholder)) {
                this.placeholder = next;
                repaint();
            }
        }

        /** 当前提示文案（自检用）。 */
        String placeholder() {
            return placeholder;
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            if (placeholder == null || placeholder.isEmpty()) {
                return;
            }
            // 有真实内容（包括用户输入的空格）或有焦点时不画提示。
            if (!getText().isEmpty() || isFocusOwner()) {
                return;
            }
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(PLACEHOLDER_FG);
                g2.setFont(getFont());
                Insets in = getInsets();
                java.awt.FontMetrics fm = g2.getFontMetrics();
                int y = in.top + fm.getAscent()
                        + Math.max(0, (getHeight() - in.top - in.bottom - fm.getHeight()) / 2);
                g2.drawString(placeholder, in.left + 2, y);
            } finally {
                g2.dispose();
            }
        }
    }

    /** 简化两方法的 DocumentListener，省掉三个都要实现的样板。 */
    private abstract static class SimpleDocumentListener
            implements javax.swing.event.DocumentListener {
        @Override
        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            changed();
        }

        @Override
        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            changed();
        }

        @Override
        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            changed();
        }

        public abstract void changed();
    }
}
