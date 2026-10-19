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

    private final JPanel body = new JPanel(new BorderLayout());

    /** 网页视图。轮询线程也会碰它，所以是 volatile（写它的只有事件线程）。 */
    private volatile AiWebView view;
    private boolean loaded;
    private boolean collapsed;

    /** 最后一次要推给页面的东西；页面加载完之前先存着，加载后补推。轮询线程会读。 */
    private volatile String pendingContext = "";
    private volatile String theme = "classic";
    private volatile String lang = "zh";

    public AiAssistPanel() {
        setLayout(new BorderLayout());
        setMinimumSize(new Dimension(0, 0));

        // ★ 这一行是整个面板能不能显示的分水岭。
        //
        // body 是装内容的地方：网页视图进它的 CENTER，提示文字进它的 NORTH。
        // 它以前从来没有被加进来过 —— 于是它没有父容器、尺寸恒为 0x0、
        // isDisplayable 为 false，里面放什么都画不出来。
        //
        // 后果是这一栏从第一版起就一个字都没显示过，连"加载中…"都没有。
        // 那本身就是线索：从不显示任何 Swing 内容，而不只是网页画不出来 ——
        // 我却一直以为是网页视图的问题，查了六轮。
        //
        // 诊断行给出的铁证：self=260x735 而 body=0x0。
        add(body, BorderLayout.CENTER);

        // 这里刻意没有任何标题栏或收起按钮。
        //
        // 开关统一在左边那条图标栏上：点 🤖 展开、再点一次收起。面板自己再放一个
        // "收起"只是在同一个位置重复第二遍同样的动作，还白占一行对话的高度。
        showMessage(I18n.t(Strings.AI_LOADING));

        // ★ 等这一栏真正显示在屏幕上之后再建网页。
        //
        // 这是从 minisvg 的字节码里学来的：它建完 WebGuiJPanel 之后挂了一个
        // addHierarchyListener，等面板真的可显示了才做后续设置。
        //
        // 原因：网页视图要靠一个真实的绘制表面，而那是"显示出来"之后才有的。
        // 在卡片还没切到前面时把它建好，它拿不到表面 —— 实测就是页面报告一个固定
        // 尺寸（1050×832）、而容器只有三百多像素，屏幕上则是一片空白，
        // 连父容器的 revalidate/repaint 都叫不动它。
        addHierarchyListener(new java.awt.event.HierarchyListener() {
            private boolean done;

            @Override
            public void hierarchyChanged(java.awt.event.HierarchyEvent e) {
                if (done || !isShowing() || getWidth() <= 0) {
                    return;
                }
                done = true;
                javax.swing.SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        ensureLoaded();
                        onShown();
                        // 这一栏以后每次被改尺寸，也要跟着摆 —— 内层的浏览器组件
                        // 不会自己响应外层布局（见 AiWebView.resizeTo）。
                        addComponentListener(new java.awt.event.ComponentAdapter() {
                            @Override
                            public void componentResized(java.awt.event.ComponentEvent e) {
                                if (view != null) {
                                    view.resizeTo(getWidth(), getHeight());
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    /** 折叠状态的箭头方向由外部决定（它知道分隔条的位置）。 */
    /**
     * 收起时的宽度：<b>0</b>。
     *
     * <p>以前这里是 30 —— 因为那时这一栏自己就是入口，缩到 0 就没法点开了。
     * 现在入口是左边那条常驻的 {@link LeftRail}，所以收起就该真的收干净，
     * 只在最左留那条图标栏。
     */
    public static final int COLLAPSED_WIDTH = 0;
    /** 展开时的默认宽度。 */
    public static final int EXPANDED_WIDTH = 360;

    /**
     * 收起 / 展开。
     *
     * <p>收起是<b>整块藏掉</b>，不是"缩窄"：入口已经是最左那条常驻的图标栏，
     * 这一栏收起来就该什么都不剩。以前只藏正文、留着表头，结果收起后还挂着一行
     * "AI 助手 ◀" —— 既多余又占宽度。
     */
    public void setCollapsed(boolean value) {
        this.collapsed = value;
        setVisible(!value);
        setPreferredSize(new Dimension(value ? 0 : EXPANDED_WIDTH, 0));
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

        // 文件对话框：JxBrowser 不会自己弹，插件不装就永远弹不出来。
        // 装不上就把页面上的 ⊕ 藏掉 —— 一个点了没反应的按钮比没有更糟。
        attachOk = view.installFileDialogs();


        // 补推一次。此时页面多半还没跑完脚本，所以这次多半是石沉大海 ——
        // 真正的重推在下面的轮询里：它先探 window.tbvAi 有没有出现。
        deliverPending();
        startWatch();
    }

    /** 导出回调：会话名 + Markdown 正文，交给外面去弹保存对话框。 */
    private java.util.function.BiConsumer<String, String> exportSink;

    /** 附件按钮能不能用；见 {@link AiWebView#installFileDialogs()}。 */
    private volatile boolean attachOk = true;

    /** 页面请求打开设置窗口时的去处（模型菜单最后一行"管理模型…"）。 */
    private Runnable settingsSink;

    /**
     * 设置「从面板里打开设置窗口」的去处。
     *
     * <p>模型列表是<b>在设置里</b>改的，而页面上原本没有任何入口通到那里：
     * 列表为空时用户只看到一个"内置模型"，不知道下一步该干什么。
     */
    public void setSettingsSink(Runnable sink) {
        settingsSink = sink;
    }

    /**
     * 设置「导出对话」的去处。
     *
     * <p>页面把整篇 Markdown 写进 localStorage，这边轮询取走。存文件这件事
     * 留给外面（它才知道该弹哪个对话框、上一次导到哪）。
     */
    public void setExportSink(java.util.function.BiConsumer<String, String> sink) {
        exportSink = sink;
    }

    /**
     * 页面里的 {@code window.tbvAi} 出现了没有。
     *
     * <p>★ 这是这一整块最容易出错的地方。网页是<b>异步</b>加载的：插件刚启动、
     * 面板第一次显示的时候，{@link #ensureLoaded()} 立刻就把模型列表、上下文、
     * 主题、语言推过去 —— 而那时页面的脚本一行都还没跑，{@code window.tbvAi}
     * 还不存在，每一次 {@code run()} 都是静默的空操作。
     *
     * <p>症状不是"不工作"，而是"**要先进一次设置、随便编辑保存一下才好**"：
     * 唯一还会再推一次模型列表的地方就是设置里的保存。别的地方（切工具会重推
     * 上下文）刚好掩盖了这个问题。
     */
    private volatile boolean pageReady;

    /** 有东西要补推。页面就绪前推过的、以及刚就绪的，都算。 */
    private volatile boolean dirty;

    /** 轮询定时器（在事件线程上跳，只负责派活）。 */
    private javax.swing.Timer watchTimer;

    /**
     * 真正干活的那条线程。
     *
     * <p>★ 轮询<b>不能</b>在 Swing 事件线程上跑。
     *
     * <p>{@code executeJavaScript} 是<b>阻塞</b>的：它等网页把 JS 跑完。而网页有
     * 好几种时候会停在那里不动 —— 最典型的是 {@code <input type=file>} 点了之后，
     * 渲染线程在等文件选择框的结果。只要那一趟轮询还压着事件线程，整个界面就跟着
     * 冻住，连文件框本身都弹不出来（它要在事件线程上弹）。
     *
     * <p>放到这条线程上之后，网页再怎么卡，卡的也只是它自己；事件线程一直能响应用户。
     * 连续失败/卡住时 {@code polling} 会一直为真，所以也不会越堆越多。
     */
    private final java.util.concurrent.ExecutorService poller =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "bioenv-ai-poll");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    /** 上一次派出去的活还没回来。 */
    private volatile boolean polling;

    private void startWatch() {
        if (watchTimer != null || view == null) {
            return;
        }
        watchTimer = new javax.swing.Timer(700, new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (view == null || !isShowing() || polling) {
                    return;
                }
                polling = true;
                try {
                    poller.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                pollPage();
                            } catch (Throwable t) {
                                // 轮询失败不能影响界面：下一跳再试。
                            } finally {
                                polling = false;
                            }
                        }
                    });
                } catch (Throwable t) {
                    polling = false;
                }
            }
        });
        watchTimer.start();
    }

    /**
     * 每 700ms 问一次页面：脚本起来了没有、有没有东西要交出来。
     *
     * <p><b>跑在 {@code poller} 线程上</b>（见那里）。所以：
     * <ul>
     *   <li>碰 JxBrowser 的调用可以直接做（它是线程安全的）；</li>
     *   <li>碰 Swing 的一律 {@code EventQueue.invokeLater} 回事件线程。</li>
     * </ul>
     *
     * <p>干三件事，顺序有讲究：
     * <ol>
     *   <li>页面没就绪就只探一下，别的都不做（推过去也是白推）；</li>
     *   <li>刚就绪时<b>把所有待推的东西重推一遍</b> —— 页面加载期间攒下的模型列表、
     *       上下文、主题、语言都在这一步补上；</li>
     *   <li>取走页面写下的导出载荷、以及"打开设置"的请求。</li>
     * </ol>
     */
    void pollPage() {
        AiWebView v = view;
        if (v == null) {
            return;
        }
        // 文件框开着的时候网页停在那里等结果，什么都推不进去 ——
        // 这一跳整个跳过，**不要**把 dirty 清掉，等框关了再推。
        if (v.dialogOpen()) {
            return;
        }
        if (!pageReady) {
            if (!"object".equals(v.evalString("typeof window.tbvAi"))) {
                return;
            }
            pageReady = true;
            dirty = true;
        }
        if (dirty) {
            deliverPending();
            dirty = false;
        }
        final java.util.function.BiConsumer<String, String> ex = exportSink;
        if (ex != null) {
            final String[] part = bioenv.ai.AiExport.decode(take(v, "tbv.export"));
            if (part != null) {
                // 存文件要弹对话框 —— 那是 Swing，回事件线程。
                java.awt.EventQueue.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        ex.accept(part[0], part[1]);
                    }
                });
            }
        }
        final Runnable st = settingsSink;
        if (st != null && take(v, "tbv.openSettings") != null) {
            java.awt.EventQueue.invokeLater(st);
        }
    }

    /**
     * 读一个 key，<b>读到就立刻清掉</b>。
     *
     * <p>不清的话下一轮会把同一份数据再处理一遍 —— 导出会第二次弹出保存对话框。
     */
    private String take(AiWebView v, String key) {
        String raw = v.evalString("localStorage.getItem('" + key + "') || ''");
        if (raw == null || raw.trim().length() == 0) {
            return null;
        }
        v.run("try{localStorage.removeItem('" + key + "')}catch(e){}");
        return raw;
    }

    /** 模型列表；网页还没建的时候先放这里。 */
    private volatile String pendingModels = "[]";

    /**
     * 把模型列表推给页面。
     *
     * <p>页面拿不到 Java 那边的存盘，只能推。这里面带 API key ——
     * 请求是页面发的，没有它发不出去；同一进程里没有更安全的通道。
     * 这一点在设置界面上写清楚了。
     */
    public void setModels(String json) {
        pendingModels = (json == null || json.trim().length() == 0) ? "[]" : json;
        dirty = true;
        sendModels();
    }

    /**
     * 把待推的东西全部再推一遍。轮询判定"页面刚就绪"时调。
     *
     * <p>只用已经存下的 pending 值，不动标记 —— 调用方负责清 dirty。
     */
    private void deliverPending() {
        sendTheme();
        sendLang();
        sendContext();
        sendModels();
        sendAttach();
    }

    /**
     * 告诉页面附件按钮能不能用。
     *
     * <p>页面默认以为能用；Java 装文件对话框失败时把它藏掉。
     */
    private void sendAttach() {
        if (view != null) {
            view.run("window.tbvAi && window.tbvAi.setAttach("
                    + (attachOk ? "true" : "false") + ")");
        }
    }

    private void sendModels() {
        if (view != null) {
            view.run("window.tbvAi && window.tbvAi.setModels(" + jsString(pendingModels) + ")");
        }
    }

    public void pushContext(String json) {
        pendingContext = json == null ? "" : json;
        dirty = true;
        sendContext();
    }

    private void sendContext() {
        if (view != null) {
            view.run("window.tbvAi && window.tbvAi.setContext("
                    + jsString(pendingContext) + ")");
        }
    }

    /** 跟着插件的界面风格走（classic / light / dark）。 */
    public void pushTheme(String name) {
        theme = name == null ? "classic" : name;
        dirty = true;
        sendTheme();
    }

    private void sendTheme() {
        if (view != null) {
            view.run("window.tbvAi && window.tbvAi.setTheme(" + jsString(theme) + ")");
        }
    }

    /** 跟着插件的语言走（zh / en）。 */
    public void pushLang(String code) {
        lang = "en".equalsIgnoreCase(code) ? "en" : "zh";
        dirty = true;
        sendLang();
    }

    private void sendLang() {
        if (view != null) {
            view.run("window.tbvAi && window.tbvAi.setLang(" + jsString(lang) + ")");
        }
    }

    /**
     * 卡片刚被切到前面时叫一次。
     *
     * <p>为什么必须这么做：内嵌的网页视图是<b>重量级组件</b>，而且它是在被加进
     * CardLayout 的时候按<b>当时</b>的大小建起来的。之后卡片被切出来、容器尺寸变了，
     * 父容器的 revalidate/repaint <b>叫不醒它</b> —— 它继续按旧尺寸画，画在一块
     * 尺寸对不上的地方，看上去就是一片空白（实测：页面自己报告 1050×832，而那一列
     * 只有三百多像素宽）。
     *
     * <p>所以这里显式把它的边界摆成父容器现在的大小，再让它自己重排一次。
     */
    public void onShown() {
        if (view != null) {
            view.resizeTo(getWidth(), getHeight());
        }
        if (view != null && view.panel() != null) {
            java.awt.Container parent = view.panel().getParent();
            if (parent != null && parent.getWidth() > 0 && parent.getHeight() > 0) {
                view.panel().setBounds(0, 0, parent.getWidth(), parent.getHeight());
            }
            view.panel().revalidate();
            view.panel().repaint();
        }
        revalidate();
        repaint();
    }

    /**
     * 面板被移出界面时停掉轮询。
     *
     * <p>定时器会把面板一直吊住（Swing Timer 持有监听器），插件关掉之后
     * 它还会每 700ms 醒一次。页面的按钮和它没关系，别让它在后台空转。
     */
    @Override
    public void removeNotify() {
        if (watchTimer != null) {
            watchTimer.stop();
            watchTimer = null;
        }
        super.removeNotify();
    }

    /** 重新挂回界面时把轮询接回去（见 removeNotify）。 */
    @Override
    public void addNotify() {
        super.addNotify();
        if (loaded) {
            startWatch();
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
