package vmadapt.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import vmadapt.core.RemoteSetup;
import vmadapt.core.SshTarget;

/**
 * 独立验证窗口：把 {@link ConnectionPanel} 装进一个普通窗口。
 *
 * <p>存在的理由是「不依赖 TBtools 也能验证」。插件面板在真实宿主里跑起来要经过
 * 打包、加载、菜单注册一整套流程，光是为了看一眼布局就要走完这些太慢；这个窗口
 * 用命令行两行就能起来，改完界面马上能看效果。
 *
 * <p>无头环境处理：CI 或纯命令行环境里没有图形子系统，构造任何一个 Swing 窗口
 * 都会抛 {@link java.awt.HeadlessException}，而那个异常只会打印一段英文堆栈，
 * 对使用者毫无帮助。所以 {@link #main} 第一步就检查 headless，是就打印说明
 * 后正常退出（退出码 0）——「没有图形界面」不是程序错误。
 *
 * <p>语言：本窗口的文案也走 {@link LangProvider}。没有注入时用 {@link Text}
 * 的默认语言（中文），与插件托管时的默认语言一致；注入后窗口标题、说明、
 * 底部按钮、用法与无头提示都会跟着切换。
 * 无头提示走的是 {@code System.out}，语言同样在这里决定。
 */
public final class MainFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    /** 面板实例，保留引用以便「打开远端 conda 安装指引」之类的按钮调用。 */
    private final ConnectionPanel connectionPanel = new ConnectionPanel();

    /** 语言提供者，可为 null（用 {@link Text} 的默认语言，即中文）。 */
    private final LangProvider langProvider;

    /** 注册给 provider 的回调，注销时要复用同一个对象。 */
    private final Runnable langListener;

    private final JLabel headTitle = new JLabel();
    private final JLabel headSub = new JLabel();
    private final JButton guideButton = new JButton();
    private final JLabel envLabel = new JLabel();

    /**
     * 没有语言提供者时的默认构造。
     *
     * <p>默认中文：这是插件面向用户的语言，也是 host 未注入 {@link LangProvider}
     * 时唯一的兜底。早先这里回退英文，结果独立运行 MainFrame 时（以及任何忘记注入
     * provider 的调用方）用户看到的是全英文界面，与产品默认不一致。默认语言的判定
     * 集中在 {@link Text#langCode()} 一处，这里不再重复。
     */
    public MainFrame() {
        this(null);
    }

    /**
     * 带语言提供者的构造。
     *
     * @param provider 语言提供者；null 表示用内置默认语言（中文）
     */
    public MainFrame(LangProvider provider) {
        super();
        this.langProvider = provider;

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        add(buildHeader(), BorderLayout.NORTH);
        add(connectionPanel, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        if (provider != null) {
            connectionPanel.setLangProvider(provider);
        }

        Runnable r = new Runnable() {
            @Override
            public void run() {
                applyLanguage();
            }
        };
        langListener = r;
        if (provider != null) {
            provider.addListener(r);
        }

        applyLanguage();

        pack();
        setMinimumSize(new Dimension(760, 560));
        setLocationRelativeTo(null);
    }

    /** 取当前语言下的文案。 */
    private String text(String key) {
        return new Text(langProvider).get(key);
    }

    /** 取当前语言下的文案并替换占位符。 */
    private String text(String key, Object... args) {
        return new Text(langProvider).get(key, args);
    }

    /** 按当前语言刷新本窗口自己的文案（连接面板由它自己刷新）。 */
    private void applyLanguage() {
        setTitle(text(UiText.FRAME_TITLE));
        headTitle.setText(text(UiText.FRAME_HEAD_TITLE));
        headSub.setText("<html>" + escapeHtml(text(UiText.FRAME_HEAD_SUB)) + "</html>");
        guideButton.setText(text(UiText.FRAME_GUIDE_BTN));
        envLabel.setText(environmentSummary());
    }

    /** 简单 HTML 转义，避免文案里的 & < > 破坏 JLabel 的 html 渲染。 */
    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 注销语言回调，供宿主在销毁窗口时调用。 */
    public void disposeLangListener() {
        if (langProvider != null && langListener != null) {
            try {
                java.lang.reflect.Method m = langProvider.getClass()
                        .getMethod("removeListener", Runnable.class);
                m.invoke(langProvider, langListener);
            } catch (Exception ignored) {
                // 接口不要求 removeListener，取不到就算了。
            }
        }
    }

    /** 顶部说明条，讲清楚这个窗口是干什么的。 */
    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));

        headTitle.setFont(headTitle.getFont().deriveFont(Font.BOLD, 15f));
        p.add(headTitle, BorderLayout.NORTH);

        headSub.setForeground(new java.awt.Color(0x55, 0x55, 0x55));
        p.add(headSub, BorderLayout.SOUTH);
        return p;
    }

    /** 底部一行：conda 安装指引 + 环境自检信息。 */
    private JPanel buildFooter() {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(4, 10, 8, 10));

        guideButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showGuide();
            }
        });

        JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        left.add(guideButton);
        p.add(left, BorderLayout.WEST);

        envLabel.setForeground(new java.awt.Color(0x66, 0x66, 0x66));
        envLabel.setFont(envLabel.getFont().deriveFont(Font.PLAIN, 11f));
        p.add(envLabel, BorderLayout.EAST);
        return p;
    }

    /**
     * 弹窗显示远端安装 conda 的指引。
     *
     * <p>指引文本来自 core 包的 {@link RemoteSetup#condaInstallGuide()}，界面不重复
     * 维护一份：命令会随版本变化，只留一个来源才不会出现文档和界面对不上的情况。
     */
    private void showGuide() {
        String body;
        try {
            body = RemoteSetup.condaInstallGuide();
        } catch (Throwable t) {
            // core 还没就绪或方法实现有问题时，不要让整个窗口崩掉。
            body = text(UiText.FRAME_GUIDE_FAILED, String.valueOf(t));
        }
        if (body == null || body.trim().isEmpty()) {
            body = text(UiText.FRAME_GUIDE_EMPTY);
        }

        JTextArea area = new JTextArea(body, 24, 76);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(area);
        JOptionPane.showMessageDialog(this, scroll, text(UiText.FRAME_GUIDE_TITLE),
                JOptionPane.INFORMATION_MESSAGE);
    }

    /** 一行环境自检：JDK 版本、SSH 工具是否就位。排查问题时有这一行省很多事。 */
    private String environmentSummary() {
        String javaVersion = System.getProperty("java.version");
        String ssh = locateSsh();
        return text(UiText.FRAME_ENV, javaVersion,
                ssh == null ? text(UiText.FRAME_ENV_NO_SSH) : ssh);
    }

    /** 同样先看标准目录再回退 PATH，与连接面板里的策略保持一致。 */
    private static String locateSsh() {
        String[] dirs = {
            "C:\\WINDOWS\\System32\\OpenSSH\\",
            "C:\\Program Files\\OpenSSH\\",
        };
        for (String dir : dirs) {
            File f = new File(dir, "ssh.exe");
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String part : path.split(File.pathSeparator)) {
                if (part == null || part.trim().isEmpty()) {
                    continue;
                }
                File f = new File(part.trim(), "ssh.exe");
                if (f.isFile()) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /**
     * 程序入口。
     *
     * @param args 支持 {@code --host=IP}、{@code --user=NAME}、{@code --port=N}、
     *             {@code --key=PATH} 预填表单，方便重复调试同一台虚拟机
     */
    public static void main(String[] args) {
        // 必须在任何 Swing 组件被创建之前判断。一旦创建了组件再检查就晚了：
        // 构造 JFrame 本身就会抛 HeadlessException。
        if (GraphicsEnvironment.isHeadless()) {
            printHeadlessNotice(null);
            return;
        }

        final SshTarget preset = parseArgs(args);

        try {
            // 跟随宿主系统的外观。失败也无所谓：某些精简 JDK 不带 Windows LAF，
            // 那就用默认的 Metal，界面依然可用。
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Throwable ignored) {
            // 刻意吞掉：外观只是锦上添花，不值得为它中断启动。
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                MainFrame frame = new MainFrame();
                if (preset != null) {
                    frame.connectionPanel.writeTarget(preset);
                }
                frame.setVisible(true);
            }
        });
    }

    /**
     * 解析命令行参数，得到一份预填用的 {@link SshTarget}。
     *
     * @return 没有识别到任何参数时返回 null
     */
    private static SshTarget parseArgs(String[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        String host = "";
        String user = "";
        String key = null;
        String label = null;
        int port = SshTarget.DEFAULT_PORT;
        boolean any = false;

        for (String a : args) {
            if (a == null) {
                continue;
            }
            String s = a.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.startsWith("--host=")) {
                host = s.substring("--host=".length()).trim();
                any = true;
            } else if (s.startsWith("--user=")) {
                user = s.substring("--user=".length()).trim();
                any = true;
            } else if (s.startsWith("--port=")) {
                try {
                    port = Integer.parseInt(s.substring("--port=".length()).trim());
                } catch (NumberFormatException ignored) {
                    // 参数写错就当没写，让界面里的默认值生效。
                }
                any = true;
            } else if (s.startsWith("--key=")) {
                key = s.substring("--key=".length()).trim();
                any = true;
            } else if (s.startsWith("--name=")) {
                label = s.substring("--name=".length()).trim();
                any = true;
            } else if ("--help".equals(s) || "-h".equals(s)) {
                printUsage(null);
                System.exit(0);
            }
        }

        if (!any) {
            return null;
        }
        SshTarget t = new SshTarget(host, port, user, key);
        t.label = label;
        return t;
    }

    private static void printUsage(LangProvider provider) {
        Text tx = new Text(provider);
        System.out.println(tx.get(UiText.USAGE_TITLE));
        System.out.println(tx.get(UiText.USAGE_LINE1));
        System.out.println();
        System.out.println(tx.get(UiText.USAGE_OPTS));
        System.out.println(tx.get(UiText.USAGE_HOST));
        System.out.println(tx.get(UiText.USAGE_PORT));
        System.out.println(tx.get(UiText.USAGE_USER));
        System.out.println(tx.get(UiText.USAGE_KEY));
        System.out.println(tx.get(UiText.USAGE_NAME));
        System.out.println(tx.get(UiText.USAGE_HELP));
        System.out.println();
        System.out.println(tx.get(UiText.USAGE_EXAMPLE));
        System.out.println("  java -cp build\\classes vmadapt.ui.MainFrame "
                + "--host=127.0.0.1 --port=2222 --user=ubuntu");
    }

    /**
     * 无图形界面时的提示。走到这里说明环境不支持，不是用户填错了什么。
     *
     * @param provider 语言提供者；null 表示用默认语言（中文）
     */
    private static void printHeadlessNotice(LangProvider provider) {
        Text tx = new Text(provider);
        String banner = tx.get(UiText.HL_BANNER);
        System.out.println(banner);
        System.out.println(tx.get(UiText.HL_TITLE));
        System.out.println(banner);
        System.out.println(tx.get(UiText.HL_BODY));
        System.out.println();
        System.out.println(tx.get(UiText.HL_NOT_ERROR));
        System.out.println(tx.get(UiText.HL_CAUSES));
        System.out.println(tx.get(UiText.HL_CAUSE_1));
        System.out.println(tx.get(UiText.HL_CAUSE_2));
        System.out.println(tx.get(UiText.HL_CAUSE_3));
        System.out.println();
        System.out.println(tx.get(UiText.HL_TESTS));
        System.out.println(tx.get(UiText.HL_TESTS_EXAMPLE));
        System.out.println();
        System.out.println(tx.get(UiText.HL_RETRY));
        System.out.println(banner);
    }
}
