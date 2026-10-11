package bioenv.ui;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JComponent;

import bioenv.i18n.I18n;
import bioenv.i18n.Strings;

/**
 * 风格切换器：把三套风格平铺出来，点一下就换。
 *
 * <p>为什么平铺而不是下拉框：只有三个选项，平铺能一眼看见"现在在哪一套、另外两套是什么"；
 * 下拉框要先点开才知道有什么。
 *
 * <p>为什么不用三个 {@code JButton}：那样每个按钮都会带自己的 L&F 外观，而这个控件
 * 本身就该跟着主题走 —— 自己画一遍最省事，也最不容易在切换时露出破绽。
 *
 * <h2>名字为什么在这里取，而不是在 {@link UiTheme}</h2>
 *
 * <p>三套风格的名字原来写死在 {@code UiTheme.label()} 里，于是英文界面上这三格仍然是
 * 「经典 / 浅色 / 深色」——旁边就是语言切换按钮，切过去之后这里还留着中文。
 *
 * <p>但把 {@link I18n} 塞进 {@code UiTheme} 并不合适：它是纯画法与取值的工具，被各处
 * 引用，一直刻意不依赖 i18n。名字只有这一个控件在用（查过，全项目就这一处），所以
 * 取名字这件事跟着控件走，{@code UiTheme} 保持原样。
 */
public final class ThemeSwitcher extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final int SEG_W = 62;
    private static final int H = 28;

    private int hover = -1;

    /** 风格 id → 界面文案的键。 */
    private static String labelKey(String id) {
        if (UiTheme.PERSONAL.equals(id)) {
            return Strings.THEME_LIGHT;
        }
        if (UiTheme.DARK.equals(id)) {
            return Strings.THEME_DARK;
        }
        return Strings.THEME_CLASSIC;
    }

    private static String label(String id) {
        return I18n.t(labelKey(id));
    }

    public ThemeSwitcher() {
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(I18n.t(Strings.THEME_TOOLTIP));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                hover = -1;
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int i = indexAt(e.getX());
                if (i >= 0) {
                    UiTheme.set(UiTheme.all()[i]);
                }
            }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int i = indexAt(e.getX());
                if (i != hover) {
                    hover = i;
                    repaint();
                }
            }
        });
        UiTheme.addListener(new Runnable() {
            @Override
            public void run() {
                repaint();
            }
        });
        // 语言变了也要跟着换。这个名字是自绘的，没人替它重设文字——不重画就一直是
        // 上一种语言。工具提示同样是构造时定的，一并刷新。
        I18n.addListener(new Runnable() {
            @Override
            public void run() {
                setToolTipText(I18n.t(Strings.THEME_TOOLTIP));
                repaint();
            }
        });
    }

    private int indexAt(int x) {
        String[] ids = UiTheme.all();
        int pad = 2;
        int seg = (getWidth() - pad * 2) / ids.length;
        if (seg <= 0) {
            return -1;
        }
        int i = (x - pad) / seg;
        return (i >= 0 && i < ids.length) ? i : -1;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(UiTheme.all().length * SEG_W + 4, H);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        String[] ids = UiTheme.all();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        int pad = 2;
        // 底盘：一颗胶囊
        g2.setColor(UiTheme.sunken());
        g2.fillRoundRect(0, 0, w - 1, h - 1, h, h);
        g2.setColor(UiTheme.line());
        g2.drawRoundRect(0, 0, w - 1, h - 1, h, h);

        int seg = (w - pad * 2) / ids.length;
        String cur = UiTheme.current();
        for (int i = 0; i < ids.length; i++) {
            int x = pad + i * seg;
            boolean on = ids[i].equals(cur);
            if (on) {
                g2.setColor(UiTheme.surface());
                g2.fillRoundRect(x, pad, seg, h - pad * 2, h - pad * 2, h - pad * 2);
                g2.setColor(UiTheme.line());
                g2.drawRoundRect(x, pad, seg, h - pad * 2, h - pad * 2, h - pad * 2);
            } else if (i == hover) {
                g2.setColor(UiTheme.surface());
                g2.fillRoundRect(x, pad, seg, h - pad * 2, h - pad * 2, h - pad * 2);
            }
            String s = label(ids[i]);
            java.awt.Font f = UiTheme.flat() ? UiTheme.ui(12, on) : null;
            if (f == null) {
                f = getFont() != null
                        ? getFont().deriveFont(on ? java.awt.Font.BOLD
                                : java.awt.Font.PLAIN, 12f)
                        : new java.awt.Font(java.awt.Font.SANS_SERIF,
                                on ? java.awt.Font.BOLD : java.awt.Font.PLAIN, 12);
            }
            g2.setFont(f);
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(on ? UiTheme.text() : UiTheme.text2());
            g2.drawString(s, x + (seg - fm.stringWidth(s)) / 2,
                    (h + fm.getAscent() - fm.getDescent()) / 2);
        }
        g2.dispose();
    }
}
