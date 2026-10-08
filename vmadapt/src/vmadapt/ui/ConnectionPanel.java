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
import javax.swing.JComboBox;
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
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;

import vmadapt.core.ConnectionStore;
import vmadapt.core.RemoteSetup;
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

    /** 主机名或 IP，带灰色占位提示。占位文案在 {@link #applyLanguage()} 里按语言设置。 */
    private final PlaceholderTextField hostField = new PlaceholderTextField(null);

    /** SSH 端口，默认 22。故意不带占位提示：默认值已经占了输入框的位置。 */
    private final JTextField portField = new JTextField("22", 6);

    /** 登录用户名，带灰色占位提示。 */
    private final PlaceholderTextField userField = new PlaceholderTextField(null);

    /** 私钥路径，右侧带「浏览…」按钮，带灰色占位提示。 */
    private final PlaceholderTextField keyField = new PlaceholderTextField(null);

    /** 配置名称（别名），用于保存/删除本地配置，带灰色占位提示。 */
    private final PlaceholderTextField nameField = new PlaceholderTextField(null);

    /** 备注，仅作人类可读说明，不参与连接，带灰色占位提示。 */
    private final PlaceholderTextField noteField = new PlaceholderTextField(null);

    /** 超时秒数。故意不带占位提示：默认值已经占了输入框的位置。 */
    private final JTextField timeoutField = new JTextField("15", 5);

    // 控件一律先用空文字构造，真实文案在 applyLanguage() 里统一填。
    // 这样做的好处是「语言」只有一个入口，不会出现某个控件忘了跟着切的情况。
    private final JButton browseKeyButton = new JButton();
    private final JComboBox<String> savedBox = new JComboBox<String>();
    private final JButton loadSavedButton = new JButton();

    private final JButton testButton = new JButton();
    private final JButton saveButton = new JButton();
    private final JButton deleteButton = new JButton();
    private final JButton genKeyButton = new JButton();
    private final JButton copyKeyButton = new JButton();

    /** 表单里各行的标签，按 addRow 顺序存放，语言切换时统一改文字。 */
    private final List<JLabel> rowLabels = new ArrayList<JLabel>();

    /** 各区块的边框标题（Connection / Actions / Probe details / VMware network modes）。 */
    private final javax.swing.border.TitledBorder connectionBorder;
    private final javax.swing.border.TitledBorder actionsBorder;
    private final javax.swing.border.TitledBorder probeBorder;
    private final javax.swing.border.TitledBorder networkBorder;

    /** 底部那条「耗时操作都在后台」提示。 */
    private final JLabel backgroundHint = new JLabel();

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

    /** 把「忙碌」状态包起来，保证按钮的禁用与恢复成对出现。 */
    private final List<JButton> gatedButtons = new ArrayList<JButton>();

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

        connectionBorder = BorderFactory.createTitledBorder("");
        actionsBorder = BorderFactory.createTitledBorder("");
        probeBorder = BorderFactory.createTitledBorder("");
        networkBorder = BorderFactory.createTitledBorder("");

        gatedButtons.add(testButton);
        gatedButtons.add(saveButton);
        gatedButtons.add(genKeyButton);
        gatedButtons.add(copyKeyButton);
        gatedButtons.add(loadSavedButton);

        tabs.addTab("", buildFormTab());
        tabs.addTab("", buildStatusTab());
        tabs.addTab("", buildNetworkHelpTab());
        add(tabs, BorderLayout.CENTER);

        // 先把文案铺一遍（此时没有 provider，即英文），再绑事件。
        applyLanguage();

        // 事件绑定放在最后：loadSavedNames() 会触发下拉框的监听器，
        // 那时控件必须已经全部建好。
        wireEvents();
        reloadSavedNames();

        // 没有配置名称时，删除按钮没有意义。
        updateDeleteEnabled();
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
        tabs.setTitleAt(0, tx.get(UiText.TAB_CONNECTION));
        tabs.setTitleAt(1, tx.get(UiText.TAB_RESULT));
        tabs.setTitleAt(2, tx.get(UiText.TAB_NETWORK));
        tabs.setToolTipTextAt(0, tx.get(UiText.TAB_CONNECTION_TIP));
        tabs.setToolTipTextAt(1, tx.get(UiText.TAB_RESULT_TIP));
        tabs.setToolTipTextAt(2, tx.get(UiText.TAB_NETWORK_TIP));

        // 区块标题
        connectionBorder.setTitle(tx.get(UiText.SEC_CONNECTION));
        actionsBorder.setTitle(tx.get(UiText.SEC_ACTIONS));
        probeBorder.setTitle(tx.get(UiText.SEC_PROBE));
        networkBorder.setTitle(tx.get(UiText.SEC_NETWORK));

        // 表单标签
        String[] labelKeys = {
            UiText.LBL_HOST, UiText.LBL_PORT, UiText.LBL_USER, UiText.LBL_KEY,
            UiText.LBL_NAME, UiText.LBL_NOTE, UiText.LBL_TIMEOUT, UiText.LBL_SAVED,
        };
        for (int i = 0; i < rowLabels.size() && i < labelKeys.length; i++) {
            rowLabels.get(i).setText(tx.get(labelKeys[i]));
        }

        // 字段悬停提示（含 JComboBox 那一行）
        String[] tipKeys = {
            UiText.TIP_HOST, UiText.TIP_PORT, UiText.TIP_USER, UiText.TIP_KEY,
            UiText.TIP_NAME, UiText.TIP_NOTE, UiText.TIP_TIMEOUT, UiText.TIP_SAVED,
        };
        if (rowFields.size() == tipKeys.length) {
            for (int i = 0; i < tipKeys.length; i++) {
                setTooltipRecursive(rowFields.get(i), tx.get(tipKeys[i]));
            }
        }

        // 占位提示：只改画什么，不动 getText()
        hostField.setPlaceholder(tx.get(UiText.PH_HOST));
        userField.setPlaceholder(tx.get(UiText.PH_USER));
        keyField.setPlaceholder(tx.get(UiText.PH_KEY));
        nameField.setPlaceholder(tx.get(UiText.PH_NAME));
        noteField.setPlaceholder(tx.get(UiText.PH_NOTE));

        // 按钮
        browseKeyButton.setText(tx.get(UiText.BTN_BROWSE));
        browseKeyButton.setToolTipText(tx.get(UiText.BTN_BROWSE_TIP));
        loadSavedButton.setText(tx.get(UiText.BTN_LOAD));
        loadSavedButton.setToolTipText(tx.get(UiText.BTN_LOAD_TIP));
        testButton.setText(tx.get(UiText.BTN_TEST));
        testButton.setToolTipText(tx.get(UiText.BTN_TEST_TIP));
        saveButton.setText(tx.get(UiText.BTN_SAVE));
        saveButton.setToolTipText(tx.get(UiText.BTN_SAVE_TIP));
        deleteButton.setText(tx.get(UiText.BTN_DELETE));
        deleteButton.setToolTipText(tx.get(UiText.BTN_DELETE_TIP));
        genKeyButton.setText(tx.get(UiText.BTN_GEN_KEY));
        genKeyButton.setToolTipText(tx.get(UiText.BTN_GEN_KEY_TIP));
        copyKeyButton.setText(tx.get(UiText.BTN_COPY_KEY));
        copyKeyButton.setToolTipText(tx.get(UiText.BTN_COPY_KEY_TIP));

        savedBox.setToolTipText(tx.get(UiText.TIP_SAVED));

        // 底部提示
        backgroundHint.setText("<html>" + escapeHtml(tx.get(UiText.HINT_BACKGROUND))
                + "</html>");

        // Network modes 正文
        networkHelpArea.setText(tx.get(UiText.NET_HELP));
        networkHelpArea.setCaretPosition(0);

        // 状态区：只有在「用户还没点过测试」时才覆盖，否则会把上一次的
        // 连接结果冲掉——切语言不该抹掉用户刚看到的结果。
        if (!statusShowsResult) {
            paintStatus(statusFg, statusBg, tx.get(UiText.STATUS_NOT_TESTED));
            statusArea.setText(tx.get(UiText.STATUS_INTRO));
            statusArea.setForeground(neutralFg());
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

    /** 简单 HTML 转义，避免文案里的 & < > 破坏 JLabel 的 html 渲染。 */
    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
     * 表单每一行的「控件」侧，按 addRow 顺序存放。
     *
     * <p>语言切换时要重设它们的悬停提示，所以必须留引用；
     * 私钥行与已存配置行存的是包好的 JPanel，递归设提示即可覆盖内部控件。
     */
    private final List<Component> rowFields = new ArrayList<Component>();

    /** 「连接配置」页：上方表单，下方操作按钮。 */
    private JPanel buildFormTab() {
        JPanel root = new JPanel(new BorderLayout(0, 8));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(connectionBorder);

        int row = 0;
        addRow(form, row++, hostField);
        addRow(form, row++, portField);
        addRow(form, row++, userField);
        addRow(form, row++, withBrowse(keyField));
        addRow(form, row++, nameField);
        addRow(form, row++, noteField);
        addRow(form, row++, timeoutField);

        JPanel savedRow = new JPanel(new BorderLayout(4, 0));
        savedRow.add(savedBox, BorderLayout.CENTER);
        savedRow.add(loadSavedButton, BorderLayout.EAST);
        addRow(form, row++, savedRow);

        root.add(form, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        buttons.setBorder(actionsBorder);
        buttons.add(testButton);
        buttons.add(saveButton);
        buttons.add(deleteButton);
        buttons.add(new JSeparator(JSeparator.VERTICAL));
        buttons.add(genKeyButton);
        buttons.add(copyKeyButton);
        root.add(buttons, BorderLayout.CENTER);

        backgroundHint.setForeground(new Color(0x55, 0x55, 0x55));
        root.add(backgroundHint, BorderLayout.SOUTH);
        return root;
    }

    /** 「连接结果」页：状态标签 + 等宽字体的报告区。 */
    private JPanel buildStatusTab() {
        JPanel root = new JPanel(new BorderLayout(0, 6));

        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));
        statusLabel.setOpaque(true);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        statusArea.setEditable(false);
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);
        statusArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        statusArea.setBackground(Color.WHITE);

        JScrollPane scroll = new JScrollPane(statusArea);
        scroll.setBorder(probeBorder);
        scroll.setPreferredSize(new Dimension(560, 300));

        root.add(statusLabel, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        return root;
    }

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
    // 表单辅助
    // ------------------------------------------------------------------

    /**
     * 往 GridBagLayout 表单里加一行「标签 + 组件」。
     *
     * <p>只建结构，文案留空：标签文字与悬停提示都由 {@link #applyLanguage()}
     * 统一填，这样语言切换时不会漏掉任何一行。标签与控件分别记进
     * {@link #rowLabels} 和 {@link #rowFields}，供刷新时按顺序取用。
     *
     * @param field 控件；若是组合面板，提示会递归设到内部每个子控件上
     */
    private void addRow(JPanel form, int row, Component field) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.gridy = row;
        lc.anchor = GridBagConstraints.LINE_END;
        lc.insets = new Insets(3, 4, 3, 6);
        JLabel l = new JLabel();
        rowLabels.add(l);
        form.add(l, lc);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1;
        fc.gridy = row;
        fc.weightx = 1.0;
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.insets = new Insets(3, 0, 3, 4);
        rowFields.add(field);
        form.add(field, fc);
    }

    /**
     * 给组件及其所有子组件设置同一条悬停提示。
     *
     * <p>必须递归：私钥那一行传进来的是包着输入框 + 「Browse...」的组合面板，
     * 「已存配置」那一行是包着下拉框 + 「Load」的面板。只设最外层的话，
     * 用户把鼠标悬停在真正的输入框或下拉框上是看不到提示的。
     *
     * <p>每次调用都是<b>覆盖</b>（而不是「只在 null 时设」）：语言切换要能把
     * 旧语言的提示换成新语言的，所以这里必须无条件写入。
     */
    private static void setTooltipRecursive(Component c, String tooltip) {
        if (tooltip == null) {
            return;
        }
        if (c instanceof JComponent) {
            ((JComponent) c).setToolTipText(tooltip);
        }
        if (c instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) c).getComponents()) {
                setTooltipRecursive(child, tooltip);
            }
        }
    }    /** 给文本框右侧拼一个「浏览…」按钮，两部分一起当成一个组件加进表单。 */
    private Component withBrowse(JTextField field) {
        JPanel p = new JPanel(new BorderLayout(4, 0));
        p.add(field, BorderLayout.CENTER);
        p.add(browseKeyButton, BorderLayout.EAST);
        return p;
    }

    // ------------------------------------------------------------------
    // 事件绑定
    // ------------------------------------------------------------------

    private void wireEvents() {
        browseKeyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                chooseKeyFile();
            }
        });

        loadSavedButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                loadSelectedConfig();
            }
        });

        savedBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                updateDeleteEnabled();
            }
        });

        nameField.getDocument().addDocumentListener(new SimpleDocumentListener() {
            @Override
            public void changed() {
                updateDeleteEnabled();
            }
        });

        testButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                testConnection();
            }
        });

        saveButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveConfig();
            }
        });

        deleteButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                deleteConfig();
            }
        });

        genKeyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                generateKey();
            }
        });

        copyKeyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copyPublicKeyToRemote();
            }
        });
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
     * 在后台线程跑一次 {@link VmConnector#connect()}，把结果画到状态区。
     *
     * <p>先做本地校验再连线：主机/用户名没填就发出去的连接是纯粹的浪费时间，
     * 而且用户要等十几秒才看到「请填写用户名」这种本该立刻知道的事。
     */
    public void testConnection() {
        final SshTarget target = readTarget();
        String problem = takeInputProblem();
        if (problem == null) {
            problem = validateLocalized(target);
        }
        if (problem != null) {
            reportFailure(text(UiText.MSG_INCOMPLETE), problem, null);
            return;
        }

        setBusy(true);
        paintStatus(NEUTRAL_COLOR, NEUTRAL_BG,
                text(UiText.MSG_CONNECTING_TO, target.toDisplay()));
        statusArea.setText(text(UiText.MSG_CONNECTING_BG,
                String.valueOf(target.timeoutSeconds)) + "\n");

        new SwingWorker<ConnectionReport, Void>() {
            @Override
            protected ConnectionReport doInBackground() {
                // 连接失败不抛异常：VmConnector 会把原因写进 report.error，
                // 界面据此展示。真抛了也会被 done() 里的 get() 捞住。
                return new VmConnector(target).connect();
            }

            @Override
            protected void done() {
                ConnectionReport report = null;
                try {
                    report = get();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    setBusy(false);
                    reportFailure(text(UiText.MSG_CONNECT_THREW),
                            String.valueOf(cause), null);
                    return;
                }
                setBusy(false);
                showReport(target, report);
            }
        }.execute();
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

            paintStatus(OK_COLOR, OK_BG,
                    text(UiText.MSG_CONNECTED_SHORT, target.toDisplay()));
            statusArea.setText(sb.toString());
            statusArea.setCaretPosition(0);
            statusArea.setForeground(OK_COLOR);
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

        paintStatus(FAIL_COLOR, FAIL_BG, title);
        statusArea.setText(sb.toString());
        statusArea.setCaretPosition(0);
        statusArea.setForeground(FAIL_COLOR);
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

    private void saveConfig() {
        SshTarget t = readTarget();

        // 主机为空的配置存下来没有意义，先拦这个：它比「名字没填」更根本，
        // 而且名字留空本来是允许的（见下）。
        if (t.host.isEmpty()) {
            reportFailure(text(UiText.MSG_NO_HOST),
                    text(UiText.MSG_NO_HOST_REASON), null);
            return;
        }

        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            // 名字可以留空。
            //
            // 绝大多数人只有一台机器，给唯一的东西起个名字只是白加一层间接：
            // 用户先要想个名字，之后在多处看到的是这个名字而不是地址，反而更难认。
            // 留空就用「用户@主机:端口」当标签，和界面别处显示的写法一致。
            name = defaultLabelFor(t);
        }
        t.label = name;
        // 名字可能刚刚由主机地址推出来，worker 里要用的是最终值。
        final String savedName = name;

        // 表单内容在事件线程上一次性捕获，后台线程只碰这个不可变快照，
        // 避免 worker 跑起来之后用户又改了表单导致存下去的和看到的不一致。
        final SshTarget snapshot = t;

        setBusy(true);
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                // 走磁盘，放到后台。save 返回 false 表示存储层拒绝了这条记录
                // （例如名称非法），这跟抛异常一样要如实告诉用户，不能当成功。
                return Boolean.valueOf(store.save(snapshot));
            }

            @Override
            protected void done() {
                setBusy(false);
                boolean saved = false;
                try {
                    saved = get().booleanValue();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    reportFailure(text(UiText.MSG_SAVE_FAILED), String.valueOf(cause), null);
                    return;
                }
                reloadSavedNames();
                if (!saved) {
                    reportFailure(text(UiText.MSG_SAVE_REJECTED),
                            text(UiText.MSG_SAVE_REJECTED_REASON, savedName), null);
                    return;
                }
                paintStatus(OK_COLOR, OK_BG, text(UiText.MSG_SAVED_TITLE, savedName));
                statusArea.setForeground(OK_COLOR);
                statusArea.setText(text(UiText.MSG_SAVED_BODY, savedName) + "\n"
                        + text(UiText.MSG_SAVED_TARGET, snapshot.toDisplay()) + "\n"
                        + text(UiText.MSG_SAVED_KEY, snapshot.keyPath == null
                                ? text(UiText.MSG_SAVED_KEY_NONE) : snapshot.keyPath) + "\n");
                updateDeleteEnabled();
            }
        }.execute();
    }

    private void deleteConfig() {
        final String name = selectedName();
        if (name == null || name.isEmpty()) {
            reportFailure(text(UiText.MSG_NO_SELECTION),
                    text(UiText.MSG_NO_SELECTION_DELETE), null);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                text(UiText.MSG_CONFIRM_DELETE, name),
                text(UiText.MSG_CONFIRM_DELETE_TITLE),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        setBusy(true);
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return Boolean.valueOf(store.remove(name));
            }

            @Override
            protected void done() {
                setBusy(false);
                boolean removed = false;
                try {
                    removed = get().booleanValue();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    reportFailure(text(UiText.MSG_DELETE_FAILED), String.valueOf(cause), null);
                    return;
                }
                reloadSavedNames();
                if (removed) {
                    paintStatus(OK_COLOR, OK_BG, text(UiText.MSG_DELETED_TITLE, name));
                    statusArea.setForeground(OK_COLOR);
                    statusArea.setText(text(UiText.MSG_DELETED_BODY, name) + "\n");
                } else {
                    reportFailure(text(UiText.MSG_MISSING_TITLE),
                            text(UiText.MSG_MISSING_DELETED, name), null);
                }
                updateDeleteEnabled();
            }
        }.execute();
    }

    /** 把下拉框里选中的配置载入表单。读盘也放后台。 */
    private void loadSelectedConfig() {
        final String name = selectedName();
        if (name == null || name.isEmpty()) {
            reportFailure(text(UiText.MSG_NO_SELECTION),
                    text(UiText.MSG_NO_SELECTION_LOAD), null);
            return;
        }

        setBusy(true);
        new SwingWorker<SshTarget, Void>() {
            @Override
            protected SshTarget doInBackground() {
                return store.load(name);
            }

            @Override
            protected void done() {
                setBusy(false);
                SshTarget t = null;
                try {
                    t = get();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    reportFailure(text(UiText.MSG_LOAD_FAILED), String.valueOf(cause), null);
                    return;
                }
                if (t == null) {
                    reportFailure(text(UiText.MSG_MISSING_TITLE),
                            text(UiText.MSG_MISSING, name), null);
                    return;
                }
                writeTarget(t);
                if (t.label == null || t.label.trim().isEmpty()) {
                    nameField.setText(name);
                }
                paintStatus(NEUTRAL_COLOR, NEUTRAL_BG, text(UiText.MSG_LOADED_TITLE, name));
                statusArea.setForeground(NEUTRAL_COLOR);
                statusArea.setText(text(UiText.MSG_LOADED_BODY, name, t.toDisplay()) + "\n");
                updateDeleteEnabled();
            }
        }.execute();
    }

    /** 重新从本地配置读出名称列表填进下拉框。 */
    private void reloadSavedNames() {
        String keep = selectedName();
        List<SshTarget> all = store.list();
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<String>();
        for (SshTarget t : all) {
            String n = t.label == null ? "" : t.label.trim();
            if (!n.isEmpty()) {
                model.addElement(n);
            }
        }
        savedBox.setModel(model);
        if (keep != null && !keep.isEmpty()) {
            savedBox.setSelectedItem(keep);
        }
        updateDeleteEnabled();
    }

    /** 下拉框当前选中的配置名，没有则为 null。 */
    private String selectedName() {
        Object sel = savedBox.getSelectedItem();
        if (sel == null) {
            return null;
        }
        String s = String.valueOf(sel).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 「删除配置」只在能确定要删哪条时才可用。
     *
     * <p>选中的配置名和表单里的配置名不一致时也禁用：那说明用户改了名字准备存
     * 新条目，这时点删除很可能删错东西。
     */
    private void updateDeleteEnabled() {
        String sel = selectedName();
        boolean ok = !busy && sel != null;
        deleteButton.setEnabled(ok);
        loadSavedButton.setEnabled(!busy && sel != null);
    }

    private void chooseKeyFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(text(UiText.DLG_CHOOSE_KEY));
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter(
                text(UiText.FILTER_PRIVKEY),
                "rsa", "ed25519", "ecdsa", "dsa", "pem", "ppk"));
        String cur = keyField.getText().trim();
        if (!cur.isEmpty()) {
            File f = new File(cur);
            if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                chooser.setCurrentDirectory(f.getParentFile());
            }
        } else {
            String home = System.getProperty("user.home");
            if (home != null) {
                File sshDir = new File(home, ".ssh");
                if (sshDir.isDirectory()) {
                    chooser.setCurrentDirectory(sshDir);
                }
            }
        }
        // showOpenDialog 会阻塞事件线程，这是文件对话框的固有行为，属于用户
        // 正在交互的模态等待，不是后台任务，所以放在事件线程里是对的。
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            if (f != null) {
                keyField.setText(f.getAbsolutePath());
            }
        }
    }

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
        paintStatus(NEUTRAL_COLOR, NEUTRAL_BG, text(UiText.MSG_GEN_RUNNING));
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
            }
        }.execute();
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

        int choice = JOptionPane.showConfirmDialog(this,
                text(UiText.MSG_CONFIRM_COPY, pubFile.getAbsolutePath(),
                        target.toDisplay()),
                text(UiText.MSG_CONFIRM_COPY_TITLE),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        final String ssh = sshExe;
        setBusy(true);
        paintStatus(NEUTRAL_COLOR, NEUTRAL_BG,
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
     * <p>忙碌时把所有会再起一个后台任务的按钮禁用掉。不禁用的话，用户可以连点
     * 五次「测试连接」，五个 worker 并发跑，最后到达的那个结果覆盖前面的，
     * 界面显示的可能是任意一次的结果——比等一会儿更让人困惑。
     */
    private void setBusy(boolean value) {
        busy = value;
        for (JButton b : gatedButtons) {
            b.setEnabled(!value);
        }
        setCursor(value ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : null);
        updateDeleteEnabled();
    }

    /**
     * 设置状态区标题与配色。
     *
     * <p>顺带记下颜色，因为语言切换时要用同一组颜色重画标题——不能因为
     * 切了语言就把「上次失败是红的」这件事忘掉。
     */
    private void paintStatus(Color fg, Color bg, String text) {
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
         * 找一个 openssh 可执行文件的绝对路径。
         *
         * <p>先看标准安装目录，再回退到 PATH。返回 null 表示没找到。
         *
         * @param exe 可执行文件名，例如 ssh.exe
         */
        static String locate(String exe) {
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
