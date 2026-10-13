package bioenv.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;

import bioenv.ai.AiWebView;
import bioenv.i18n.I18n;
import bioenv.i18n.Strings;

/**
 * 最左侧那一栏：插件内置的 AI 助手。
 *
 * <pre>
 *   ┌───────────────┬─────────────┬──────────────┬──────────┐
 *   │ AI 助手    ◀  │ 命令树       │ 表单 / 预览   │  输出     │
 *   │               │             │              │          │
 *   │  （对话网页）   │             │              │          │
 *   └───────────────┴─────────────┴──────────────┴──────────┘
 * </pre>
 *
 * <h2>为什么这栏是"网页"</h2>
 *
 * <p>TBtools 的内置模型只以 JS 桥（{@code window.tbtools.ai}）的形式给<b>插件页面</b>，
 * 纯 Swing 拿不到。所以这里嵌一个网页视图，Java 只负责把上下文推进去、并跟着主题和
 * 语言走。细节见 {@link AiWebView}。
 *
 * <h2>三条纪律</h2>
 *
 * <ol>
 *   <li><b>构造时绝不碰网页运行时</b> —— {@code plugin-contract.md} 要求"不许在构造函数
 *       / generatePanel 里抛"。网页在第一次真正显示时才创建（{@link #ensureLoaded()}）。</li>
 *   <li><b>不可用要说得明白</b> —— 版本不够、网页运行时没下下来、桥被拒，三种情况给三种
 *       话，而不是一个空白面板。</li>
 *   <li><b>推上下文失败不影响插件</b> —— 全部吞掉，界面照常。</li>
 * </ol>
 */
public final class AiAssistPanel extends JPanel {

    private final Runnable onToggle;
    private final JLabel title = new JLabel(I18n.t(Strings.AI_TITLE));
    private final JButton toggle = new JButton("◀");
    private final JPanel body = new JPanel(new BorderLayout());

    private AiWebView view;
    private boolean loaded;
    private boolean collapsed;

    /** 最后一次要推给页面的东西；页面加载完之前先存着，加载后补推。 */
    private String pendingContext = "";
    private String theme = "classic";
    private String lang = "zh";

    public AiAssistPanel(Runnable onToggleCollapse) {
        this.onToggle = onToggleCollapse;
        setLayout(new BorderLayout());
        setMinimumSize(new Dimension(0, 0));

        JPanel head = new JPanel(new BorderLayout(4, 0));
        head.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 4));
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        UiTheme.role(title, "accent");
        head.add(title, BorderLayout.WEST);

        toggle.setMargin(new java.awt.Insets(0, 6, 0, 6));
        toggle.setFocusable(false);
        toggle.setToolTipText(I18n.t(Strings.AI_COLLAPSE));
        toggle.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (AiAssistPanel.this.onToggle != null) {
                    AiAssistPanel.this.onToggle.run();
                }
            }
        });
        head.add(toggle, BorderLayout.EAST);

        add(head, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);

        // 先把"还没加载"这一句放上；真正显示时会被替换。
        showMessage(I18n.t(Strings.AI_LOADING));
    }

    /** 折叠状态的箭头方向由外部决定（它知道分隔条的位置）。 */
    /** 收起时只留窄窄一条（标题 + 箭头）。留一条而不是缩到 0：缩到 0 就点不开了。 */
    public static final int COLLAPSED_WIDTH = 30;
    /** 展开时的默认宽度。 */
    public static final int EXPANDED_WIDTH = 360;

    public void setCollapsed(boolean value) {
        this.collapsed = value;
        toggle.setText(value ? "▶" : "◀");
        toggle.setToolTipText(I18n.t(value ? Strings.AI_EXPAND : Strings.AI_COLLAPSE));
        if (body != null) {
            body.setVisible(!value);
        }
        setPreferredSize(new Dimension(value ? COLLAPSED_WIDTH : EXPANDED_WIDTH, 0));
        revalidate();
        repaint();
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /**
     * 第一次真正显示时创建网页。
     *
     * <p>刻意不在构造器里做：网页运行时一旦缺失或出问题，构造函数抛异常会让<b>整个插件
     * 加载失败</b>，而这里失败最多是"AI 这一栏显示一句解释"。
     */
    public void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!AiWebView.available()) {
            showMessage(AiWebView.unavailableReason());
            return;
        }
        File html = locatePage();
        if (html == null) {
            showMessage(I18n.t(Strings.AI_NO_PAGE));
            return;
        }
        AiWebView v;
        try {
            // 用 Path.toUri() 而不是 File.toURI()：前者给 file:///D:/...，后者给
            // file:/D:/...。minisvg（唯一已知能跑的同类插件）用的是前者，这里照抄 ——
            // 在"为什么我的页面里 JS 不执行"还没定案之前，先和已知能跑的那个保持一致。
            v = AiWebView.open(html.toPath().toUri().toString());
        } catch (Throwable t) {
            v = null;
        }
        if (v == null || v.panel() == null) {
            showMessage(v != null && !v.problem().isEmpty()
                    ? v.problem() : I18n.t(Strings.AI_NO_PAGE));
            return;
        }
        view = v;
        body.removeAll();
        body.add(view.panel(), BorderLayout.CENTER);
        body.revalidate();
        body.repaint();

        // 补推：主题、语言、以及期间攒下来的上下文。
        pushTheme(theme);
        pushLang(lang);
        if (!pendingContext.isEmpty()) {
            pushContext(pendingContext);
        }
    }

    /** 把当前工具 / 命令 / 上次运行等推给页面（JSON 字符串）。 */
    public void pushContext(String json) {
        pendingContext = json == null ? "" : json;
        if (view != null) {
            view.run("window.tbvAi && window.tbvAi.setContext(" + jsString(pendingContext) + ")");
        }
    }

    /** 跟着插件的界面风格走（classic / light / dark）。 */
    public void pushTheme(String name) {
        theme = name == null ? "classic" : name;
        if (view != null) {
            view.run("window.tbvAi && window.tbvAi.setTheme(" + jsString(theme) + ")");
        }
    }

    /** 跟着插件的语言走（zh / en）。 */
    public void pushLang(String code) {
        lang = "en".equalsIgnoreCase(code) ? "en" : "zh";
        title.setText(I18n.t(Strings.AI_TITLE));
        toggle.setToolTipText(I18n.t(collapsed ? Strings.AI_EXPAND : Strings.AI_COLLAPSE));
        if (view != null) {
            view.run("window.tbvAi && window.tbvAi.setLang(" + jsString(lang) + ")");
        }
    }

    /** 让 Java 侧主动问一句（留给以后的"一键解释报错"）。 */
    public void ask(String text) {
        ensureLoaded();
        if (view != null) {
            view.run("window.tbvAi && window.tbvAi.ask(" + jsString(text == null ? "" : text) + ")");
        }
    }

    // ------------------------------------------------------------------

    private void showMessage(String message) {
        body.removeAll();
        JTextArea area = new JTextArea(message);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        UiTheme.role(area, "muted");
        body.add(area, BorderLayout.NORTH);
        body.revalidate();
        body.repaint();
    }

    /**
     * 找 {@code ai.html}。
     *
     * <p>优先插件根目录（和主 jar 同级）—— 那才是 TBtools 认的位置。找不到就退回
     * classpath 里的副本（开发/测试时用，那里没有"插件根目录"）。
     */
    private static File locatePage() {
        try {
            java.security.CodeSource cs =
                    AiAssistPanel.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File self = new File(cs.getLocation().toURI());
                File dir = self.isFile() ? self.getParentFile() : self;
                File f = new File(dir, "ai.html");
                if (f.isFile()) {
                    return f;
                }
            }
        } catch (Throwable t) {
            // 退回 classpath 副本。
        }
        try {
            java.net.URL u = AiAssistPanel.class.getResource("/ai.html");
            if (u != null) {
                File f = new File(u.toURI());
                if (f.isFile()) {
                    return f;
                }
            }
        } catch (Throwable t) {
            // 没有就没有，调用方会显示一句解释。
        }
        return null;
    }

    /** Java 字符串 → JS 字符串字面量。 */
    static String jsString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<':  sb.append("\\u003c"); break;   // 免得内容里的 </script> 截断注入
                case '>':  sb.append("\\u003e"); break;
                case '&':  sb.append("\\u0026"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
