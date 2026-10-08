package bioenv.ui;

import java.awt.Color;
import java.awt.Container;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;

/**
 * 方案 ③：把整套外观交给 {@code UIManager} 的默认值表。
 *
 * <h2>为什么换这条路</h2>
 *
 * <p>以前是"一套控件树 + 切换时逐个控件改外观 + 记住原值再还原"。这条路踩的坑分两类：
 *
 * <ul>
 *   <li><b>漏还原</b>：改了没改回去（滚动条、下拉框渲染器、分隔条边框）</li>
 *   <li><b>漏重刷</b>：控件是后来新建的，没被刷到（切中英文重建表单后按钮退回原生）</li>
 * </ul>
 *
 * <p>两类都出自同一个原因：外观分散在好几个地方写，每个属性都要写两遍。
 *
 * <p>这里改成：<b>整套外观只由一张表描述</b>。切换 = 换表 + 让 Swing 自己把控件重装一遍
 * （{@link SwingUtilities#updateComponentTreeUI}）。控件外观不再由我们的代码逐个设置，
 * 也就没有"忘了改回去"和"忘了刷"这回事。
 *
 * <p>表管不到的只有 app 专属的那几样（参数名标签的浅底、命令预览的圆角深底、工具树的
 * 自绘），它们在一个固定的收尾步骤里装回去，见 {@link #refresh}。
 */
final class UiThemeTables {

    private UiThemeTables() {
    }

    /** 我们接管的键。换主题时整表替换，没在表里的键不受影响。 */
    private static final String[] KEYS = {
        "Panel.background", "Panel.foreground",
        "Label.foreground", "Label.font", "Label.disabledForeground",
        "Button.background", "Button.foreground", "Button.font",
        "Button.disabledText", "Button.focus",
        "TextField.background", "TextField.foreground", "TextField.border",
        "TextField.font", "TextField.caretForeground", "TextField.inactiveForeground",
        "TextField.selectionBackground", "TextField.selectionForeground",
        "TextArea.background", "TextArea.foreground", "TextArea.font",
        "TextArea.caretForeground", "TextArea.inactiveForeground",
        "TextArea.selectionBackground", "TextArea.selectionForeground",
        "ComboBox.background", "ComboBox.foreground", "ComboBox.border",
        "ComboBox.font", "ComboBox.selectionBackground", "ComboBox.selectionForeground",
        "ScrollBar.background", "ScrollBar.thumb", "ScrollBar.track",
        "ScrollBar.thumbDarkShadow", "ScrollBar.thumbHighlight", "ScrollBar.thumbShadow",
        "ScrollBar.width",
        "SplitPane.background", "SplitPane.border",
        "Tree.background", "Tree.textBackground", "Tree.foreground",
        "Tree.selectionBackground", "Tree.selectionForeground", "Tree.line", "Tree.font",
        "Tree.selectionBorderColor", "Tree.hash",
        "TitledBorder.titleColor", "TitledBorder.titleFont", "TitledBorder.border",
        "CheckBox.foreground", "CheckBox.font", "CheckBox.background",
        "Spinner.background", "Spinner.foreground", "Spinner.border", "Spinner.font",
        "List.background", "List.foreground", "List.selectionBackground",
        "List.selectionForeground", "List.font",
    };

    /**
     * 经典那套：从原生 L&F 快照一次。
     *
     * <p><b>必须是类加载时就快照</b>（静态初始化），不能等到第一次 {@code install} 才做 ——
     * 那时候 UIManager 里已经有新表的值了，快照出来的"经典"其实是上一套主题的值，
     * 切回经典就会得到一屏深色。
     */
    private static final UIDefaults CLASSIC = snapshot();

    private static UIDefaults snapshot() {
        UIDefaults d = new UIDefaults();
        for (String k : KEYS) {
            Object v = UIManager.get(k);
            if (v != null) {
                d.put(k, v);
            }
        }
        // 快照拿不到边框时补一个空边框：TitledBorder 不显式给边框会退回 L&F 的框线，
        // 那是"框套框"的来源之一。
        if (d.get("TitledBorder.border") == null) {
            d.put("TitledBorder.border", BorderFactory.createEmptyBorder());
        }
        return d;
    }

    /** 把某套主题的取值表装进 UIManager。 */
    static synchronized void install(String id) {
        UIDefaults target;
        if (UiTheme.PERSONAL.equals(id)) {
            target = personal();
        } else if (UiTheme.DARK.equals(id)) {
            target = dark();
        } else {
            target = CLASSIC;
        }
        for (String k : KEYS) {
            Object v = target.get(k);
            if (v != null) {
                UIManager.put(k, v);
            }
        }
    }

    /** 新风格的公用取值：底色、圆角细边、文字色，两个主题只差一组颜色。 */
    private static UIDefaults build(Color appBg, Color surface, Color sunken,
                                    Color line, Color text, Color text2, Color text3,
                                    Color accent, Color accentSoft, Color accentLine,
                                    Color fieldBg, Color fieldText,
                                    Color termBg, Color termFg,
                                    boolean dark) {
        UIDefaults d = new UIDefaults();
        Font base = UiTheme.ui(13, false);
        Font small = UiTheme.ui(12, false);
        Font mono = UiTheme.mono(12, false);

        d.put("Panel.background", ui(appBg));
        d.put("Panel.foreground", ui(text));
        d.put("Label.foreground", ui(text2));
        if (base != null) {
            d.put("Label.font", new FontUIResource(base));
        }
        d.put("Label.disabledForeground", ui(text3));

        d.put("Button.background", ui(surface));
        d.put("Button.foreground", ui(text));
        if (small != null) {
            d.put("Button.font", new FontUIResource(small));
        }
        d.put("Button.disabledText", ui(text3));
        d.put("Button.focus", ui(new Color(0, 0, 0, 0)));

        d.put("TextField.background", ui(fieldBg));
        d.put("TextField.foreground", ui(fieldText));
        d.put("TextField.border", round(line));
        d.put("TextField.caretForeground", ui(fieldText));
        d.put("TextField.inactiveForeground", ui(text3));
        d.put("TextField.selectionBackground", ui(accentSoft));
        d.put("TextField.selectionForeground", ui(accent));
        if (small != null) {
            d.put("TextField.font", new FontUIResource(small));
        }

        d.put("TextArea.background", ui(fieldBg));
        d.put("TextArea.foreground", ui(fieldText));
        d.put("TextArea.border", round(line));
        d.put("TextArea.caretForeground", ui(fieldText));
        d.put("TextArea.inactiveForeground", ui(text3));
        d.put("TextArea.selectionBackground", ui(accentSoft));
        d.put("TextArea.selectionForeground", ui(accent));
        if (mono != null) {
            d.put("TextArea.font", new FontUIResource(mono));
        }

        d.put("ComboBox.background", ui(fieldBg));
        d.put("ComboBox.foreground", ui(fieldText));
        d.put("ComboBox.border", round(line));
        d.put("ComboBox.selectionBackground", ui(accentSoft));
        d.put("ComboBox.selectionForeground", ui(accent));
        if (small != null) {
            d.put("ComboBox.font", new FontUIResource(small));
        }

        // 滚动条：滑杆一根细的，滑道不画。
        d.put("ScrollBar.background", ui(sunken));
        d.put("ScrollBar.thumb", ui(line));
        d.put("ScrollBar.track", ui(sunken));
        d.put("ScrollBar.thumbDarkShadow", ui(line));
        d.put("ScrollBar.thumbHighlight", ui(line));
        d.put("ScrollBar.thumbShadow", ui(line));
        d.put("ScrollBar.width", Integer.valueOf(12));

        d.put("SplitPane.background", ui(surface));
        d.put("SplitPane.border", BorderFactory.createEmptyBorder());

        d.put("Tree.background", ui(surface));
        d.put("Tree.textBackground", ui(surface));
        d.put("Tree.foreground", ui(text2));
        d.put("Tree.selectionBackground", ui(accentSoft));
        d.put("Tree.selectionForeground", ui(accent));
        d.put("Tree.line", ui(accentLine));
        d.put("Tree.hash", ui(accentLine));
        d.put("Tree.selectionBorderColor", ui(accentSoft));
        if (base != null) {
            d.put("Tree.font", new FontUIResource(base));
        }

        d.put("TitledBorder.titleColor", ui(text3));
        d.put("TitledBorder.border", BorderFactory.createEmptyBorder());
        if (small != null) {
            d.put("TitledBorder.titleFont", new FontUIResource(small));
        }

        d.put("CheckBox.background", ui(surface));
        d.put("CheckBox.foreground", ui(text));
        if (small != null) {
            d.put("CheckBox.font", new FontUIResource(small));
        }

        d.put("Spinner.background", ui(fieldBg));
        d.put("Spinner.foreground", ui(fieldText));
        d.put("Spinner.border", round(line));
        if (small != null) {
            d.put("Spinner.font", new FontUIResource(small));
        }

        // 下拉弹层
        d.put("List.background", ui(fieldBg));
        d.put("List.foreground", ui(fieldText));
        d.put("List.selectionBackground", ui(accentSoft));
        d.put("List.selectionForeground", ui(accent));
        if (small != null) {
            d.put("List.font", new FontUIResource(small));
        }

        // 深色下把"终端"这类深底留给 app 自己画；这里只保证底色不互相打架。
        if (dark) {
            d.put("Panel.background", ui(appBg));
        }
        return d;
    }

    private static UIDefaults personal() {
        return build(new Color(0xEAEAF1), new Color(0xFFFFFF), new Color(0xF7F7FA),
                new Color(0xE7E7EE), new Color(0x15151A), new Color(0x63636F),
                new Color(0x9A9AA6), new Color(0x4F46E5), new Color(0xEEF0FF),
                new Color(0xDCDEFB), new Color(0xFFFFFF), new Color(0x15151A),
                new Color(0x0E1526), new Color(0xE8ECF5), false);
    }

    private static UIDefaults dark() {
        return build(new Color(0x101014), new Color(0x191920), new Color(0x20202A),
                new Color(0x2B2B36), new Color(0xEFEFF5), new Color(0xA0A0AE),
                new Color(0x6E6E7E), new Color(0x8B84FF), new Color(0x22203C),
                new Color(0x3A3766), new Color(0x20202A), new Color(0xEFEFF5),
                new Color(0x0A0F1C), new Color(0xE8ECF5), true);
    }

    private static ColorUIResource ui(Color c) {
        return new ColorUIResource(c);
    }

    private static javax.swing.border.Border round(Color line) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(line, 1, true),
                BorderFactory.createEmptyBorder(4, 8, 4, 8));
    }

    /**
     * 换完表之后，让 Swing 把所有控件按新表重装一遍。
     *
     * <p>{@code updateComponentTreeUI} 会把控件的外观整个重建 —— 这正是我们要的（不需要
     * 我们逐个改），但它同时也会冲掉两类东西，必须在之后补回来：
     *
     * <ol>
     *   <li>我们自绘的 {@code TreeUI}（工具树的圆点与引导线）；</li>
     *   <li>app 专属样式（参数名标签的浅底、命令预览的圆角深底）。</li>
     * </ol>
     *
     * <p>补回来的动作由调用方在做完这一步之后统一执行一次，不再散落在各处。
     */
    static void refresh(Container root) {
        install(UiTheme.current());
        if (root != null) {
            SwingUtilities.updateComponentTreeUI(root);
        }
    }
}
