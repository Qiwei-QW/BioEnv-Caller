package bioenv.ai;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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

    private final JPanel panel;
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
        AiWebView v = null;
        try {
            Class<?> c = Class.forName(WEB_PANEL_CLASS, true,
                    AiWebView.class.getClassLoader());
            // 第二个参数 false = 隐藏导航栏：这一栏是插件的一部分，不是浏览器。
            JPanel p = (JPanel) c.getConstructor(String.class, boolean.class)
                    .newInstance(fileUrl, Boolean.FALSE);
            Object b = findBrowser(
                    Class.forName(BROWSER_CLASS, false, p.getClass().getClassLoader()), p);
            v = new AiWebView(p, b);
            if (b == null) {
                // 网页面板建起来了但没找到 Browser 对象：JxBrowser 的版本形状可能变了。
                // 这不算致命 —— 页面还是能显示，只是 Java 推不进去数据。
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
        if (panel == null || w <= 0 || h <= 0) {
            return;
        }
        applySize(panel, w, h);
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
