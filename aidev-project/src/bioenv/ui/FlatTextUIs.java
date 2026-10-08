package bioenv.ui;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.plaf.basic.BasicTextFieldUI;

/**
 * 让输入框的底色是圆角的。
 *
 * <p>为什么不能交给边框去画：绘制顺序是 {@code paintComponent()}（文字、光标）
 * 之后才 {@code paintBorder()}。边框画的填充会盖在文字上面 ——
 * 表现是"敲进去的字和光标都看不见，但值确实进了命令"。
 *
 * <p>为什么不能只把线画圆：填充是方的，方角会从圆线里露出来，看着仍是直角长方形。
 *
 * <p>所以填充要画在 {@code update()} 里 —— 它在 {@code paint()} 之前，
 * 也就是在文字之前。这样圆角和可输入两个要求同时成立。
 *
 * <p>边框那边只负责画圆角细线，不再填色。
 */
final class FlatTextUIs {

    private FlatTextUIs() {
    }

    /**
     * 提示态的底色。
     *
     * <p>不能用 {@code UiTheme.sunken()}：深色主题里它和输入框底色几乎一样
     * （都接近 #20202A），画出来看不出区别 —— 那就等于没做。这里用明确能分辨的灰：
     * 深色下比卡片亮一点，浅色下比白暗一点。
     */
    static java.awt.Color hintFill() {
        if (!UiTheme.flat()) {
            return UiTheme.sunken();
        }
        boolean dark = UiTheme.appBg().getRed() < 128;
        return dark ? new java.awt.Color(0x2B2B36) : new java.awt.Color(0xECECF1);
    }

    /** 圆角填充。调用方负责判断是否处于新风格。 */
    static void fill(Graphics g, JComponent c) {
        if (c.getWidth() <= 0 || c.getHeight() <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        int w = c.getWidth();
        int h = c.getHeight();
        int r = UiTheme.radius();

        // 先把整个矩形铺成"下面那一层"的颜色，再盖圆角填充。
        //
        // 圆角的四个角是"透明"的 —— 谁在下面就会露出来。实测露出来的是一层白色
        // （某个未接管的容器），点别的控件触发重绘时特别明显，看着像两端各多一块白。
        // 与其逐个去找是哪一层，不如把角自己补上：铺一层父容器的底色，
        // 圆角之外就是父容器的颜色，视觉上和周围连成一片。
        java.awt.Container p = c.getParent();
        java.awt.Color under = UiTheme.surface();
        if (p instanceof JComponent && p.isOpaque()) {
            under = p.getBackground();
        }
        g2.setColor(under);
        g2.fillRect(0, 0, w, h);

        // 提示文字的"灰"是颜色问题，不是底色问题 —— 底色照常。
        //
        // HintText 给提示文字设了灰色（#999999），但主题装饰时那句 fg(c, text())
        // 会把它覆盖成正文色，于是深色下描述是白的、浅色下是黑的，跟真正输入的字
        // 一模一样。这里在重绘时按状态校正前景色：fill() 在 paint() 之前运行，
        // 文字随后才画，所以设置立即生效。
        Object showing = c.getClientProperty("bioenv.showingHint");
        if (c instanceof javax.swing.text.JTextComponent) {
            ((javax.swing.text.JTextComponent) c).setForeground(
                    Boolean.TRUE.equals(showing) ? UiTheme.text3() : UiTheme.text());
        }
        g2.setColor(c.getBackground());
        g2.fillRoundRect(0, 0, w, h, r, r);
        g2.dispose();
    }

    /** 单行输入框。 */
    static final class Field extends BasicTextFieldUI {
        @Override
        public void update(Graphics g, JComponent c) {
            if (UiTheme.flat()) {
                fill(g, c);
            }
            paint(g, c);
        }
    }

    /** 多行文本域。 */
    static final class Area extends BasicTextAreaUI {
        @Override
        public void update(Graphics g, JComponent c) {
            if (UiTheme.flat()) {
                fill(g, c);
            }
            paint(g, c);
        }
    }
}
