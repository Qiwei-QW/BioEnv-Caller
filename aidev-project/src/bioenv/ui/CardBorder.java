package bioenv.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

import javax.swing.border.Border;

/**
 * 圆角卡片边框：画一个圆角矩形（可选填充 + 描边）。
 *
 * <p>为什么自己画而不是用 {@code createLineBorder(fill, 1, true)}：Swing 没有现成的
 * "圆角 + 填充"边框。深色终端这块要的正是"圆角深底"，所以留一个能填色的实现。
 *
 * <p>填色只在控件自己不透明时才有效 —— 调用方负责把控件设成非透明，否则它的方角
 * 底色会盖住圆角。
 */
public final class CardBorder implements Border {

    private final Color fill;
    private final Color line;
    private final int radius;
    private final Insets insets;

    public CardBorder(Color fill, int radius, Color line) {
        this(fill, radius, line, new Insets(0, 0, 0, 0));
    }

    public CardBorder(Color fill, int radius, Color line, Insets insets) {
        this.fill = fill;
        this.radius = Math.max(0, radius);
        this.line = line;
        this.insets = insets != null ? insets : new Insets(0, 0, 0, 0);
    }

    @Override
    public Insets getBorderInsets(Component c) {
        return insets;
    }

    @Override
    public boolean isBorderOpaque() {
        return false;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int d = radius * 2;
            if (fill != null) {
                g2.setColor(fill);
                g2.fillRoundRect(x, y, w - 1, h - 1, d, d);
            }
            if (line != null) {
                g2.setColor(line);
                g2.drawRoundRect(x, y, w - 1, h - 1, d, d);
            }
        } finally {
            g2.dispose();
        }
    }
}
