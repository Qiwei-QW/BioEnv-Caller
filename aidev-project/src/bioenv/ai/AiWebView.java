package bioenv.ai;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * 内嵌的网页视图：把 {@code ai.html} 装进 TBtools 的网页运行时，并给 Java 一条
 * 往里推数据的路。
 *
 * <h2>为什么这里是"网页"而不是 Swing 控件</h2>
 *
 * <p>TBtools 的内置模型只以 {@code window.tbtools.ai} 这个 <b>JS 桥</b>的形式提供给
 * <b>插件页面</b>。纯 Swing 面板拿不到它。所以 AI 那一栏必须是一个内嵌网页 ——
 * 这也是第三方插件 minisvg 的做法。
 *
 * <h2>⚠ 这套接口是"实测契约"，不是官方文档</h2>
 *
 * <p>用法是从 minisvg 的实际实现反推出来的（它由 TBtools 作者本人适配过），
 * <b>没有官方文档</b>。升级 TBtools 之后，<b>这里必须重新验证</b>：
 *
 * <ul>
 *   <li>{@code biocjava.GUIexcutors.WebGuiApp.WebGuiJPanel(String, boolean)} 还在不在；</li>
 *   <li>{@code window.tbtools.ai} 的方法名（chat / chatImage / abort）有没有变；</li>
 *   <li>JxBrowser 的版本（现在是 7.37.1）与 {@code mainFrame().executeJavaScript} 的形状。</li>
 * </ul>
 *
 * <h2>三条防御</h2>
 *
 * <ol>
 *   <li><b>全部反射</b>：JxBrowser 不在编译期 classpath 上（它按需下载，在
 *       {@code TBtools/win64/} 下），所以碰它只能靠反射。</li>
 *   <li><b>懒加载</b>：{@code plugin-contract.md} 明确要求"不许在构造函数 /
 *       generatePanel 里抛"。这个类<b>只在真正要显示网页时才被加载</b>，
 *       加载失败也只是 {@link #available()} 返回 false。</li>
 *   <li><b>只认 Throwable</b>：链接错误（{@code NoClassDefFoundError}）不是 Exception，
 *       漏了它就会在事件线程上炸掉整个面板。</li>
 * </ol>
 */
public final class AiWebView {

    /** TBtools 的网页面板；编译期在宿主 jar 里，但只在运行时懒加载时碰它。 */
    private static final String WEB_PANEL_CLASS =
            "biocjava.GUIexcutors.WebGuiApp.WebGuiJPanel";
    /** 网页运行时里代表一个浏览器的接口；不在编译期 classpath 上，只能反射。 */
    private static final String BROWSER_CLASS =
            "com.teamdev.jxbrowser.browser.Browser";
    /** 真正要挂进布局的那个 Swing 组件；我们直接建它，不经过 WebGuiJPanel。 */
    private static final String VIEW_CLASS =
            "com.teamdev.jxbrowser.view.swing.BrowserView";

    /**
     * 上一次建出来的那一份。
     *
     * <p>TBtools 会多次构造 {@code MainPanel}（换标签、重载插件都会），每次都会建一个
     * 网页视图。旧的那些不关就会一直留着 —— 实测同时挂了三个页面。
     * 同一时刻只有一块 AI 面板是有意义的，所以新的建起来时把旧的关掉。
     */
    private static AiWebView previous;

    private final JPanel panel;
    /** 我们自己建的那个 Swing 组件，用来按目标尺寸摆它。 */
    private JComponent view;
    private Object browser;
    private Object frame;
    private String problem = "";

    private AiWebView(JPanel panel, Object browser) {
        this.panel = panel;
        this.browser = browser;
    }

    /** 网页那一栏本身，加进布局用；不可用时返回 null。 */
    public JPanel panel() {
        return panel;
    }

    /** 创建失败的原因；成功时为空串。给界面用来显示"为什么没有 AI"。 */
    public String problem() {
        return problem;
    }

    /**
     * 现在能不能用：TBtools 有没有网页运行时、JxBrowser 在不在。
     *
     * <p>不创建任何控件，所以可以在构造阶段安全调用（用于决定那一栏显示什么）。
     */
    public static boolean available() {
        try {
            ClassLoader cl = AiWebView.class.getClassLoader();
            Class.forName(WEB_PANEL_CLASS, false, cl);
            Class.forName(BROWSER_CLASS, false, cl);
            return true;
        } catch (Throwable t) {
            // Throwable：NoClassDefFoundError / LinkageError 都算不可用，不算异常。
            return false;
        }
    }

    /** 不可用时给用户看的一句话（中英由调用方决定，这里只给英文技术描述）。 */
    public static String unavailableReason() {
        try {
            ClassLoader cl = AiWebView.class.getClassLoader();
            Class.forName(WEB_PANEL_CLASS, false, cl);
        } catch (Throwable t) {
            return "TBtools 没有提供网页面板（需要 TBtools 2.558 或更高）";
        }
        return "网页运行时（JxBrowser）未就绪 —— 它由 TBtools 按需下载，请先在 TBtools 里"
                + "打开任意一个网页型插件让它下载完，再回来重试。";
    }

    /**
     * 打开一个本地网页。
     *
     * @param fileUrl 形如 {@code file:///D:/.../ai.html}
     * @return 视图；失败返回 null（调用方显示降级文案）
     */
    public static AiWebView open(String fileUrl) {
        // ★ 先试"自己建视图"这条路。
        //
        // 为什么不继续用 WebGuiJPanel：它的设计假定是"我就是插件面板本身" ——
        // TBtools 把它直接放进标签容器，之后调 pack() 问它的首选尺寸。svguest
        // （唯一已知能跑通的同类插件）正是那么用的，它的 generatePanel() 直接
        // return new WebGuiJPanel(...)。
        //
        // 我们把它塞进 JSplitPane 左侧、再套 CardLayout、再套两层 JPanel —— 四个
        // 父容器都在改它的尺寸。实测它内部的浏览器组件因此固定在一个自己定的
        // 尺寸上（1050x832），外面怎么设都不认，屏幕上就是一片空白。
        //
        // svguest 的技能文档自己写明了这条出路：用 WebGuiJPanel.getEngine() 拿到
        // 那个共享引擎，然后自己 newBrowser() + BrowserView.newInstance(browser)。
        // 尺寸从此由我们直接给 BrowserView，不再经过那四层假定。
        // ★ 结论：自己建 BrowserView 这条路走不通，已经实测排除。
        //
        // 技能文档写明 WebGuiJPanel 用的是共享静态引擎，且是 OFF_SCREEN 渲染模式。
        // 那个模式的意思是"网页渲染到一张离屏位图上"，它不往 BrowserView 里画 ——
        // BrowserView 在这个引擎上永远出不来东西。WebGuiJPanel 之所以能显示，
        // 正是因为它自己去取那张位图来画，这是它作为一个自定义类存在的理由。
        //
        // 实测印证：自己建的 BrowserView 报 own=true、cls=BrowserView、size=260x73x
        // —— 类、尺寸、可见性都对，屏幕上一片空白。
        //
        // 所以退回 WebGuiJPanel。诊断行留着，它会告诉我们在这个四层嵌套里
        // WebGuiJPanel 到底有没有尺寸、在不在屏幕上。
        return buildViaHostPanel(fileUrl);
    }

    /**
     * 自己建 {@code Browser} + {@code BrowserView}，挂在一个朴素的面板里。
     *
     * <p>失败一律返回 {@code null}，由 {@link #open} 退回封装那条路。
     */
    /**
     * 自己建 {@code Browser} + {@code BrowserView}。
     *
     * <p><b>实测排除，不再调用。</b>引擎是 OFF_SCREEN 模式，那个模式下网页渲染到
     * 离屏位图，{@code BrowserView} 拿不到画面 —— 建出来的视图类对、尺寸对、
     * 可见性也对，就是不显示。留着这段是因为它能说明当时试过什么，以及为什么
     * 不能那么做。
     */
    private static AiWebView buildOwnView(String fileUrl) {
        try {
            ClassLoader cl = AiWebView.class.getClassLoader();
            Class<?> panelClass = Class.forName(WEB_PANEL_CLASS, true, cl);
            Class<?> browserClass = Class.forName(BROWSER_CLASS, false, cl);
            Class<?> viewClass = Class.forName(VIEW_CLASS, false, cl);

            // 引擎是 TBtools 的共享单例，授权由它注入；我们自己再设一遍是防御性的，
            // 因为"注入"发生在哪个环节（静态块还是构造函数）没有文档保证。
            license();
            Object engine = panelClass.getMethod("getEngine").invoke(null);
            if (engine == null) {
                return null;
            }

            Object browser = call(engine, "newBrowser");
            if (browser == null) {
                return null;
            }
            Method newInstance = viewClass.getMethod("newInstance", browserClass);
            JComponent view = (JComponent) newInstance.invoke(null, browser);

            JPanel host = new JPanel(new java.awt.BorderLayout());
            host.setMinimumSize(new java.awt.Dimension(0, 0));
            host.add(view, java.awt.BorderLayout.CENTER);

            Object nav = call(browser, "navigation");
            call(nav, "loadUrl", fileUrl);

            AiWebView v = new AiWebView(host, browser);
            v.view = view;
            handOver(v);
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 自己建失败时的退路：用 TBtools 现成的网页面板。 */
    private static AiWebView buildViaHostPanel(String fileUrl) {
        AiWebView v;
        try {
            Class<?> c = Class.forName(WEB_PANEL_CLASS, true, AiWebView.class.getClassLoader());
            JPanel p = (JPanel) c.getConstructor(String.class, boolean.class)
                    .newInstance(fileUrl, Boolean.FALSE);
            p.setPreferredSize(new java.awt.Dimension(320, 480));
            Object b = findBrowser(
                    Class.forName(BROWSER_CLASS, false, p.getClass().getClassLoader()), p);
            v = new AiWebView(p, b);
            handOver(v);
            if (b == null) {
                v.problem = "没能在网页面板里找到 Browser 对象（JxBrowser 版本可能变了）";
            }
        } catch (Throwable t) {
            v = null;
        }
        if (v == null) {
            AiWebView stub = new AiWebView(null, null);
            stub.problem = unavailableReason();
            return stub;
        }
        return v;
    }

    /** 新的这一份接手：把上一次那个浏览器关掉。 */
    private static synchronized void handOver(AiWebView fresh) {
        AiWebView old = previous;
        previous = fresh;
        if (old != null && old != fresh) {
            old.close();
        }
    }

    /**
     * 关掉这个视图背后的浏览器。
     *
     * <p>失败就算了：关不掉最多是留着资源，不该因此影响界面。
     */
    public void close() {
        try {
            if (browser != null) {
                call(browser, "close");
            }
        } catch (Throwable t) {
            // 关不掉不是致命问题。
        }
        browser = null;
        frame = null;
    }

    /** 防御性地把 JxBrowser 授权设上；拿不到就算了，TBtools 自己多半已经设过。 */
    private static void license() {
        try {
            if (System.getProperty("jxbrowser.license.key") != null) {
                return;
            }
            ClassLoader cl = AiWebView.class.getClassLoader();
            Class<?> hub = Class.forName("toolsKit.LicenseHub$GetLicense", false, cl);
            Object key = hub.getMethod("getJxBroswerLicense").invoke(null);
            if (key != null) {
                System.setProperty("jxbrowser.license.key", String.valueOf(key));
            }
        } catch (Throwable t) {
            // 拿不到就不设；引擎创建会告诉我们行不行。
        }
    }

    /**
     * 按名字调一个公开方法。
     *
     * <p>JxBrowser 的公开 API 多在接口上，实现类可能是包私有的，所以沿类层次
     * 逐级 {@code getMethod}（它会一并找到接口继承来的公开方法）。
     */
    private static Object call(Object target, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? Object.class : args[i].getClass();
        }
        Method m = null;
        for (Class<?> k = target.getClass(); m == null && k != null; k = k.getSuperclass()) {
            try {
                m = k.getMethod(name, types);
            } catch (NoSuchMethodException e) {
                // 继续往上找。
            }
        }
        if (m == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        }
        return m.invoke(target, args);
    }

    /**
     * 往页面里执行一段 JS。
     *
     * <p>用 {@code mainFrame()} 而不是 {@code browser.executeJavaScript}：
     * JxBrowser 7 把执行入口放在了 Frame 上（7.x 的 API 形状，已核对字节码）。
     *
     * @return 成功返回 true；失败返回 false 并把原因记进 {@link #problem}
     */
    public boolean run(String js) {
        if (browser == null) {
            return false;
        }
        try {
            if (frame == null) {
                Object opt = browser.getClass().getMethod("mainFrame").invoke(browser);
                frame = unwrapOptional(opt);
            }
            if (frame == null) {
                problem = "页面还没有主框架（可能还没加载完）";
                return false;
            }
            Method ex = frame.getClass().getMethod("executeJavaScript", String.class);
            ex.invoke(frame, js);
            return true;
        } catch (Throwable t) {
            problem = "执行 JS 失败：" + t;
            return false;
        }
    }

    /**
     * 把网页视图按给定尺寸摆一遍，<b>连同它内部的子组件</b>。
     *
     * <p>为什么要自己走一遍：外面那个 {@code WebGuiJPanel} 服从布局，但它内部真正的
     * 浏览器组件不服从 —— 实测它死死固定在一个自己定的尺寸上（1050×832），
     * 容器多宽它都不变，屏幕上就是一片空白，父容器的 revalidate/repaint 也无效。
     *
     * <p>所以这里显式地把整棵子树都设成目标尺寸。只在容器真的显示出来之后调用，
     * 否则等于把尺寸设成了一个当时还不对的值。
     */
    public void resizeTo(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (view != null) {
            // 自己建的视图：直接告诉它多大，这一条就够。
            view.setPreferredSize(new java.awt.Dimension(w, h));
            view.setSize(w, h);
            view.revalidate();
            view.repaint();
            return;
        }
        if (panel != null) {
            // 退回封装那条路：里面那个组件不服从布局，只能整棵子树逐个设。
            applySize(panel, w, h);
        }
    }

    private static void applySize(java.awt.Component c, int w, int h) {
        c.setSize(w, h);
        c.setPreferredSize(new java.awt.Dimension(w, h));
        if (c instanceof java.awt.Container) {
            java.awt.Component[] kids = ((java.awt.Container) c).getComponents();
            for (int i = 0; i < kids.length; i++) {
                applySize(kids[i], w, h);
            }
        }
    }

    // ------------------------------------------------------------------

    /** {@code Optional} 取值；不是 Optional 就原样返回。 */
    private static Object unwrapOptional(Object o) {
        if (o == null) {
            return null;
        }
        try {
            Method isPresent = o.getClass().getMethod("isPresent");
            Object yes = isPresent.invoke(o);
            if (Boolean.FALSE.equals(yes)) {
                return null;
            }
            return o.getClass().getMethod("get").invoke(o);
        } catch (Throwable t) {
            return o;
        }
    }

    /**
     * 在组件树里找那个 JxBrowser 的 {@code Browser} 对象。
     *
     * <p>网页面板不把它暴露成公开方法，所以只能扫字段 —— 这也是 minisvg 的办法
     * （它用 {@code getDeclaredFields()} + {@code isAssignableFrom} 递归找）。
     */
    private static Object findBrowser(Class<?> browserClass, Component root) {
        List<Component> queue = new ArrayList<Component>();
        queue.add(root);
        for (int i = 0; i < queue.size() && i < 200; i++) {
            Component c = queue.get(i);
            Object hit = scanFields(browserClass, c);
            if (hit != null) {
                return hit;
            }
            if (c instanceof Container) {
                Component[] kids = ((Container) c).getComponents();
                for (int k = 0; k < kids.length; k++) {
                    queue.add(kids[k]);
                }
            }
        }
        return null;
    }

    /** 扫一个对象（含父类）的字段，找 Browser 实例。 */
    private static Object scanFields(Class<?> browserClass, Object target) {
        Class<?> c = target == null ? null : target.getClass();
        for (int depth = 0; c != null && depth < 8; depth++, c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (int i = 0; i < fields.length; i++) {
                if (!browserClass.isAssignableFrom(fields[i].getType())) {
                    continue;
                }
                try {
                    fields[i].setAccessible(true);
                    Object v = fields[i].get(target);
                    if (v != null) {
                        return v;
                    }
                } catch (Throwable t) {
                    // 拿不到就试下一个字段。
                }
            }
        }
        return null;
    }
}
