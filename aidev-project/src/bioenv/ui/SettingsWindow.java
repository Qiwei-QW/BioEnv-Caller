package bioenv.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import bioenv.i18n.I18n;
import bioenv.i18n.Strings;

/**
 * 设置浮窗：一块居中的面板，顶上是标题栏和三个窗控按钮。
 *
 * <h2>为什么要做成浮窗而不是一条侧栏</h2>
 *
 * <p>设置原来是左栏里一条 380px 宽的竖列，连接方式、发行版、扫描、风格、语言五样
 * 层级不同的东西从上往下堆在一起，读起来没有条理。改成浮窗之后它有自己的地盘，
 * 才放得下"左边导航、右边内容"那种排法。
 *
 * <h2>三个按钮的分工</h2>
 *
 * <ul>
 *   <li><b>─ 最小化</b>：收成只剩这条标题栏，再点一次展开。这是三者里唯一有独立
 *       含义的 —— 它让用户把窗腾开又不丢掉位置。</li>
 *   <li><b>□ 最大化</b>：铺满整个内容区，再点还原。由调用方决定怎么摆，这里只
 *       负责把"我要最大化"这件事转达出去。</li>
 *   <li><b>× 关闭</b>：回主界面。</li>
 * </ul>
 *
 * <p>窗口本身不处理拖动和位置 —— 它由外面居中对齐，尺寸也是外面给的。
 */
public final class SettingsWindow extends JPanel {

    /** 默认大小。够放下左边一列导航和右边一块内容，又不至于占满整页。 */
    private static final Dimension DEFAULT_SIZE = new Dimension(720, 470);

    /** 最小化之后只剩这条标题栏那么高。 */
    private static final int BAR_HEIGHT = 34;

    private final JComponent content;
    private final JPanel bar;
    private final JButton minButton;
    private boolean minimized;

    /**
     * @param body    要放进去的设置内容
     * @param onClose 点 × 时调用
     * @param onMax   点 □ 时调用；传要实现"最大化/还原"的那个动作
     */
    public SettingsWindow(JComponent body, Runnable onClose, final Runnable onMax) {
        super(new BorderLayout());
        this.content = body;
        setName("settingsWindow");
        setOpaque(true);
        UiTheme.role(this, "surface");
        setBorder(BorderFactory.createLineBorder(new java.awt.Color(0xB8, 0xB8, 0xB8)));
        setPreferredSize(DEFAULT_SIZE);

        bar = new JPanel(new BorderLayout());
        bar.setOpaque(true);
        UiTheme.role(bar, "panel");
        bar.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 6));
        bar.setPreferredSize(new Dimension(0, BAR_HEIGHT));

        JLabel title = new JLabel(I18n.t(Strings.SETTINGS_TITLE));
        title.setFont(title.getFont().deriveFont(title.getFont().getStyle(), 13f));
        bar.add(title, BorderLayout.WEST);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 3));
        controls.setOpaque(false);

        minButton = windowButton("\u2013", I18n.t(Strings.SETTINGS_CLOSE));
        minButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                setMinimized(!minimized);
            }
        });

        JButton maxButton = windowButton("\u25a1", I18n.t(Strings.SETTINGS_TITLE));
        maxButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (onMax != null) {
                    onMax.run();
                }
            }
        });

        JButton closeButton = windowButton("\u00d7", I18n.t(Strings.SETTINGS_CLOSE));
        closeButton.addActionListener(new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (onClose != null) {
                    onClose.run();
                }
            }
        });

        controls.add(minButton);
        controls.add(maxButton);
        controls.add(closeButton);
        bar.add(controls, BorderLayout.EAST);

        add(bar, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
    }

    /**
     * 收起 / 展开。
     *
     * <p>收起时只把内容那一块藏起来，标题栏留着 —— 用户还得能再点一次把它放回来。
     */
    public void setMinimized(boolean value) {
        if (minimized == value || content == null) {
            return;
        }
        minimized = value;
        content.setVisible(!value);
        // 收起时窗口高度缩到标题栏；展开时交回默认大小。
        Dimension want = value
                ? new Dimension(DEFAULT_SIZE.width, BAR_HEIGHT + 2)
                : DEFAULT_SIZE;
        setPreferredSize(want);
        setMinimumSize(want);
        updateMinimizeTip();
        revalidate();
        repaint();
    }

    /** 现在是不是收起了。 */
    public boolean isMinimized() {
        return minimized;
    }

    /** 语言换了之后，标题和提示要跟着换。 */
    public void applyLanguage() {
        java.awt.Component first = bar.getComponent(0);
        if (first instanceof JLabel) {
            ((JLabel) first).setText(I18n.t(Strings.SETTINGS_TITLE));
        }
        updateMinimizeTip();
    }

    private void updateMinimizeTip() {
        minButton.setToolTipText(minimized
                ? I18n.t(Strings.SETTINGS_TITLE)
                : I18n.t(Strings.SETTINGS_CLOSE));
    }

    /**
     * 标题栏上那种小按钮：没有边框、不占地方，字稍微大一点。
     *
     * <p>三个窗控按钮都走这里 —— 它们看起来必须是一套的。
     */
    private static JButton windowButton(String glyph, String tip) {
        JButton b = new JButton(glyph);
        b.setName("settingsWindow." + glyph);
        b.setFocusable(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setMargin(new Insets(0, 0, 0, 0));
        b.setPreferredSize(new Dimension(26, 24));
        b.setToolTipText(tip);
        b.setFont(b.getFont().deriveFont(java.awt.Font.PLAIN, 14f));
        return b;
    }
}
