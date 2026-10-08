package bioenv.ui;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Color;
import javax.swing.border.AbstractBorder;

/**
 * 只画一条圆角细线的边框。
 *
 * <p>存在的理由：{@code BorderFactory.createLineBorder(color, 1, true)} 里那个
 * {@code rounded} 参数<b>并不使用我们的圆角半径</b> —— 它的圆弧直径固定是
 * {@code 厚度 × 2}，也就是 2 像素。所以无论把 {@link UiTheme#radius()} 调多大，
 * 那条线看上去永远是直角（用户反复报的"还是直角"就是它）。
 *
 * <p>这里自己用 {@code drawRoundRect} 画，圆弧取 {@link UiTheme#radius()}，
 * 于是圆角真正跟着主题走。
 *
 * <p>它<b>不填色</b>：填色不能由边框负责（边框是在文字之后才画的，会盖住内容），
 * 圆角填充由各自的 {@code update()} 负责。
 */
final class RoundBorder extends AbstractBorder {

    private final Color line;
    private final int arc;
    private final Insets insets;

    RoundBorder(Color line, int arc, Insets insets) {
        this.line = line;
        this.arc = arc;
        this.insets = insets;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(line);
        // -1：把线画在组件内侧，不然右边和下边会被剪掉一半
        g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
        g2.dispose();
    }

    @Override
    public Insets getBorderInsets(Component c) {
        return insets;
    }

    @Override
    public Insets getBorderInsets(Component c, Insets into) {
        into.top = insets.top;
        into.left = insets.left;
        into.bottom = insets.bottom;
        into.right = insets.right;
        return into;
    }

    @Override
    public boolean isBorderOpaque() {
        return false;
    }
}
