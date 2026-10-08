package bioenv.ui;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicCheckBoxUI;

/**
 * 跟着主题画的勾选框。
 *
 * <p>原生勾选框是 Windows 风格的蓝色渐变方块，放在浅色/深色面板里非常突兀。
 * 但也不能像早先那样直接换成 {@link FlatButtonUI}：方框和勾是勾选框自己的 UI
 * 画的，换上通用按钮 UI 之后就没人画勾了（"能勾选，看不到 ✓"）。
 *
 * <p>所以这里<em>自己</em>当那个 UI：画一个主题色的圆角方框，选中时在里面画勾。
 * 它继承 {@code BasicCheckBoxUI}，保留原有的键盘操作、选中模型和无障碍属性，
 * 只接管"长什么样"。
 */
final class FlatCheckBoxUI extends BasicCheckBoxUI {

    private static final int BOX = 14;
    private static final int ARC = 4;

    /** 没文字，尺寸就是方框加一点余量 —— 铺满整行会变成一条长框。 */
    @Override
    public Dimension getPreferredSize(JComponent c) {
        return new Dimension(BOX + 8, BOX + 6);
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        if (!(c instanceof AbstractButton)) {
            super.paint(g, c);
            return;
        }
        AbstractButton b = (AbstractButton) c;

        int box = Math.min(BOX, Math.min(c.getWidth(), c.getHeight()) - 4);
        if (box < 6) {
            box = 6;
        }
        int x = 2;
        int y = (c.getHeight() - box) / 2;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        boolean on = b.getModel().isSelected();
        boolean hot = b.getModel().isRollover() || b.hasFocus();

        // 框：填"输入框"的底色，边线选中时用主色，否则用分隔线色。
        g2.setColor(UiTheme.fieldBg());
        g2.fillRoundRect(x, y, box, box, ARC, ARC);
        if (!b.isEnabled()) {
            g2.setColor(UiTheme.text3());
        } else if (on || hot) {
            g2.setColor(UiTheme.accent());
        } else {
            g2.setColor(UiTheme.line());
        }
        g2.drawRoundRect(x, y, box, box, ARC, ARC);

        // 勾。
        if (on) {
            g2.setColor(b.isEnabled() ? UiTheme.accent() : UiTheme.text3());
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            int p = Math.max(2, box / 5);
            g2.drawPolyline(
                    new int[] {x + p, x + box / 2, x + box - p},
                    new int[] {y + box / 2, y + box - p, y + p},
                    3);
        }
        g2.dispose();

        // 这些勾选框本来没有文字，但保留这条通路，免得将来加了文字反而看不见。
        String text = b.getText();
        if (text != null && !text.isEmpty()) {
            super.paintText(g, b,
                    new Rectangle(x + box + 5, 0, c.getWidth() - box - 5,
                            c.getHeight()),
                    text);
        }
    }
}
