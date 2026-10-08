package bioenv.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicButtonUI;

/**
 * 扁平按钮外观：圆角、实心或描边，没有原生的立体边框。
 *
 * <p>为什么是<b>顶层 public 类</b>而不是内部类：装配回去的时候 Swing 会按类名反射
 * 构造它，内部类的名字里带 {@code $}，加载会静默失败然后退回原生外观 —— 表现就是
 * "明明设了却没生效"，而且不报任何错。顶层类没有这个问题。
 *
 * <p>切换回经典时要把它改过的几项（边框、contentAreaFilled…）原样还回去，否则原生按钮
 * 会因为 {@code contentAreaFilled=false} 变成一片空白。原来的值存在客户属性里，
 * 见 {@link #uninstall}。
 */
public final class FlatButtonUI extends BasicButtonUI {

    private static final String SAVED = "bioenv.flatButton.saved";

    private final boolean primary;
    private final boolean ghost;

    /** 无参构造：Swing 反射实例化时用。 */
    public FlatButtonUI() {
        this(false, false);
    }

    public FlatButtonUI(boolean primary) {
        this(primary, false);
    }

    public FlatButtonUI(boolean primary, boolean ghost) {
        this.primary = primary;
        this.ghost = ghost;
    }

    @Override
    public void installUI(JComponent c) {
        super.installUI(c);
        AbstractButton b = (AbstractButton) c;
        if (b.getClientProperty(SAVED) == null) {
            b.putClientProperty(SAVED, Boolean.TRUE);
            b.putClientProperty(SAVED + ".border", b.getBorder());
            b.putClientProperty(SAVED + ".contentAreaFilled",
                    Boolean.valueOf(b.isContentAreaFilled()));
            b.putClientProperty(SAVED + ".borderPainted",
                    Boolean.valueOf(b.isBorderPainted()));
            b.putClientProperty(SAVED + ".focusPainted",
                    Boolean.valueOf(b.isFocusPainted()));
            b.putClientProperty(SAVED + ".opaque", Boolean.valueOf(b.isOpaque()));
            b.putClientProperty(SAVED + ".fg", b.getForeground());
            b.putClientProperty(SAVED + ".font", b.getFont());
        }
        b.setBorder(BorderFactory.createEmptyBorder(primary ? 7 : 6, 14, primary ? 7 : 6, 14));
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setForeground(primary ? Color.WHITE : UiTheme.text());
        Font f = UiTheme.ui(12, primary);
        if (f != null) {
            b.setFont(f);
        }
    }

    /** 把按钮还原成装上来之前的样子（切回经典时调用）。 */
    @SuppressWarnings("unchecked")
    public static void uninstall(AbstractButton b) {
        if (!Boolean.TRUE.equals(b.getClientProperty(SAVED))) {
            return;
        }
        Object border = b.getClientProperty(SAVED + ".border");
        b.setBorder(border instanceof javax.swing.border.Border
                ? (javax.swing.border.Border) border : null);
        b.setContentAreaFilled(bool(b.getClientProperty(SAVED + ".contentAreaFilled"), true));
        b.setBorderPainted(bool(b.getClientProperty(SAVED + ".borderPainted"), true));
        b.setFocusPainted(bool(b.getClientProperty(SAVED + ".focusPainted"), true));
        b.setOpaque(bool(b.getClientProperty(SAVED + ".opaque"), false));
        Object fg = b.getClientProperty(SAVED + ".fg");
        if (fg instanceof Color) {
            b.setForeground((Color) fg);
        }
        Object font = b.getClientProperty(SAVED + ".font");
        if (font instanceof Font) {
            b.setFont((Font) font);
        }
        b.putClientProperty(SAVED, null);
    }

    private static boolean bool(Object v, boolean fallback) {
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : fallback;
    }

    /**
     * 禁用态的文字要用普通画法。
     *
     * <p>{@code BasicButtonUI} 对禁用按钮会走"带阴影"那条路（{@code BasicGraphicsUtils.drawString}），
     * 那是原生 3D 外观留下的效果：文字旁边会多画一层浅色副本 —— 在扁平风格里看
     * 就是"重影"。（用户截图里"停止"按钮那个重影就是它。）
     */
    @Override
    protected void paintText(Graphics g, AbstractButton b, java.awt.Rectangle textRect,
                             String text) {
        if (b.isEnabled() || !UiTheme.flat()) {
            super.paintText(g, b, textRect, text);
            return;
        }
        java.awt.FontMetrics fm = g.getFontMetrics();
        g.setColor(UiTheme.text3());
        g.drawString(text, textRect.x, textRect.y + fm.getAscent());
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        AbstractButton b = (AbstractButton) c;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        int w = c.getWidth();
        int h = c.getHeight();
        int r = Math.min(9, h);
        Color fill;
        boolean armed = b.getModel().isArmed() && b.getModel().isPressed();
        boolean hot = b.getModel().isRollover() || armed;
        // 幽灵按钮：平时什么都不画，只留文字；指上去才浮一层浅底。
        // 顶部那排「查看帮助原文／编辑参数…」用它，免得和卡片边框抢。
        if (ghost && !hot && b.isEnabled()) {
            super.paint(g, c);
            return;
        }
        if (!b.isEnabled()) {
            fill = UiTheme.sunken();
        } else if (primary) {
            fill = armed ? UiTheme.accent().darker() : UiTheme.accent();
        } else if (b.getModel().isRollover()) {
            fill = UiTheme.sunken();
        } else {
            fill = UiTheme.surface();
        }
        g2.setColor(fill);
        g2.fillRoundRect(0, 0, w - 1, h - 1, r, r);
        if (!primary) {
            g2.setColor(b.getModel().isRollover() ? UiTheme.line() : UiTheme.hair());
            g2.drawRoundRect(0, 0, w - 1, h - 1, r, r);
        }
        g2.dispose();
        // 文字／图标交给父类；底色已经画好了。
        super.paint(g, c);
    }
}
