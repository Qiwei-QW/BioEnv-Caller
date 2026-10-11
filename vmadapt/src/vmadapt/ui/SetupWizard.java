package vmadapt.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * 「连接虚拟机」向导：一步一个窗，像装软件那样走完。
 *
 * <h2>为什么是向导</h2>
 *
 * <p>原来是一个表单加八个按钮，用户要自己判断先填什么、能点哪个。而这件事本身有固定
 * 顺序（地址 → 端口 → 用户名 → 密钥 → 连接），把顺序写进界面，用户就不必知道顺序。
 *
 * <h2>版式</h2>
 *
 * <ul>
 *   <li><b>只有一行「标签 + 输入框」</b>，标签紧贴输入框：两者是一体的，隔开一段空白
 *       会让人犹豫该看哪边；</li>
 *   <li><b>不要进度条、不要"第 N / M 步"</b>：五个短步骤不值得一条进度条，而"第几步"
 *       是给向导自己看的；</li>
 *   <li><b>不要疑问句标题</b>（"主机 / IP："而不是"虚拟机在哪里？"）：问句读起来像在
 *       考用户，而这只是让他填一项；</li>
 *   <li><b>长说明做成悬停提示</b>，不常驻界面。常驻会把窗口撑成一大段字，而多数用户
 *       填对了就不需要看；需要的人悬停一下就有。</li>
 * </ul>
 *
 * <p>每个步骤是一个 {@link JPanel}，不直接开窗，这样无头环境也能构造、绘制、断言。
 */
public final class SetupWizard {

    /** 所有步骤共用的宽度。 */
    public static final int WIDTH = 440;

    /**
     * 所有步骤共用的总高度（含四周留白）。
     *
     * <h2>为什么要有这个数</h2>
     *
     * <p>原先每一步各自 {@code pack()}，于是窗口高度随内容跳：只填一个「主机 / IP」的
     * 步是 86，须知页是 132，换步时窗口会明显地一伸一缩。统一成固定高度之后，整个
     * 向导从第一步到最后一步都是同一个框，看着稳。
     *
     * <p>132 是其中<b>最高的一步</b>（须知页）的实际需要，不是拍的：量过每一个步的
     * preferredSize，输入类是 86、须知是 132。取最大值就不会裁掉任何一步。
     */
    public static final int HEIGHT = 132;

    /**
     * 结果页的高度，比 {@link #HEIGHT} 高一截。
     *
     * <p>它是唯一不合适的例外：结果页要显示一段<b>长度不定</b>的详情（成功信息或报错
     * 输出），而且带滚动区。压到 132 会把它挤成一条缝，等于把用户最需要看的东西裁掉。
     * 所以这里留出它实际需要的高度（218），其余各步一律 132。
     */
    public static final int RESULT_HEIGHT = 218;

    private static final int FIELD_H = 26;

    /** 标签列宽：够放下「配置名称：」这类最长标签，又不会把输入框推太远。 */
    private static final int LABEL_W = 84;

    private static final Color DESC_FG = new Color(0x44, 0x4A, 0x54);
    private static final Color ACCENT = new Color(0x1A, 0x5F, 0xB4);

    private SetupWizard() {
    }

    // ------------------------------------------------------------------
    // 步骤基类
    // ------------------------------------------------------------------

    /** 一个步骤的外壳：一行「标签 + 输入框」，下面一排按钮。 */
    public abstract static class Step {

        private final JPanel root = new JPanel(new BorderLayout(0, 0));
        private final JPanel center = new JPanel(new BorderLayout(0, 10));
        private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

        protected Step() {
            root.setName("bioenv.wizard");
            root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
            root.setBackground(Color.WHITE);
            center.setOpaque(false);
            buttons.setOpaque(false);
            root.add(center, BorderLayout.CENTER);
            root.add(buttons, BorderLayout.SOUTH);
            // 固定尺寸放在基类里，是为了"不漏"：将来加新步不用记得去设，
            // 而忘了设的那一步就会成为唯一一个尺寸不同的。
            root.setPreferredSize(new Dimension(WIDTH + 40, HEIGHT));
        }

        /** 给某一步单独的高度（目前只有结果页用）。 */
        protected void setFixedHeight(int height) {
            root.setPreferredSize(new Dimension(WIDTH + 40, height));
        }

        /**
         * 放进「标签 + 输入框」那一行。
         *
         * @param hint 悬停提示；null 表示不给。长说明走这里而不是常驻界面。
         */
        protected void setFieldRow(Component label, Component field, String hint) {
            if (hint != null && !hint.trim().isEmpty()) {
                String html = wrapHtml(hint);
                applyTooltip(label, html);
                applyTooltip(field, html);
            }
            JPanel row = new JPanel(new GridBagLayout());
            row.setOpaque(false);

            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0;
            lc.gridy = 0;
            lc.anchor = GridBagConstraints.LINE_END;
            lc.insets = new Insets(0, 0, 0, 6);
            row.add(label, lc);

            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1;
            fc.gridy = 0;
            fc.weightx = 1.0;
            fc.fill = GridBagConstraints.HORIZONTAL;
            row.add(field, fc);

            JPanel holder = new JPanel(new GridBagLayout());
            holder.setOpaque(false);
            GridBagConstraints hc = new GridBagConstraints();
            hc.gridx = 0;
            hc.gridy = 0;
            hc.weightx = 1.0;
            hc.fill = GridBagConstraints.HORIZONTAL;
            holder.add(row, hc);
            center.add(holder, BorderLayout.CENTER);
        }

        /** 只放一块正文，没有「标签 + 字段」那一行（须知页、结果页用它）。 */
        protected void setNoticeBody(Component body) {
            center.add(body, BorderLayout.CENTER);
        }

        /** 提示按 HTML 排，长句才会自动换行；否则会撑成一条看不到头的细条。 */
        private static String wrapHtml(String text) {
            String body = text.replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\n", "<br>");
            return "<html><div width='380'>" + body + "</div></html>";
        }

        /** 递归设提示：组合面板里真正的输入框也要能悬停看到。 */
        private static void applyTooltip(Component c, String tip) {
            if (c instanceof JComponent) {
                ((JComponent) c).setToolTipText(tip);
            }
            if (c instanceof java.awt.Container) {
                for (Component k : ((java.awt.Container) c).getComponents()) {
                    applyTooltip(k, tip);
                }
            }
        }

        /** 字段标签，宽度固定，保证各类字段对齐。 */
        protected static JLabel fieldLabel(String text) {
            JLabel l = new JLabel(text);
            l.setPreferredSize(new Dimension(LABEL_W, FIELD_H));
            l.setHorizontalAlignment(JLabel.RIGHT);
            l.setFont(l.getFont().deriveFont(Font.PLAIN, 13f));
            return l;
        }

        /** 统一样式的单行输入框。 */
        protected static JTextField newField() {
            JTextField f = new JTextField();
            f.setPreferredSize(new Dimension(WIDTH - LABEL_W - 12, FIELD_H));
            return f;
        }

        /** 一段只读说明，用于须知页与结果页。 */
        protected static JTextArea notice(String text, Color fg, int height) {
            JTextArea a = new JTextArea(text);
            a.setEditable(false);
            a.setLineWrap(true);
            a.setWrapStyleWord(true);
            a.setOpaque(false);
            a.setForeground(fg);
            a.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            // 占位尺寸必须有：可换行 JTextArea 在 BorderLayout 里没有宽度来源，
            // 不给就会被压成一列竖排的字。
            a.setPreferredSize(new Dimension(WIDTH, height));
            return a;
        }

        protected JButton addButton(String text, String name) {
            JButton b = new JButton(text);
            b.setName(name);
            buttons.add(b);
            return b;
        }

        protected static void primary(JButton b) {
            b.setForeground(ACCENT);
        }

        public JPanel panel() {
            return root;
        }

        JButton button(String name) {
            return find(panel(), name);
        }
    }

    // ------------------------------------------------------------------
    // 单字段步骤（地址 / 端口 / 用户名 / 名称）
    // ------------------------------------------------------------------

    /** 标签 + 输入框 + 一排按钮。长说明挂在悬停提示上。 */
    public static final class FieldStep extends Step {

        private final JTextField field = newField();
        private final String backName;
        private final String nextName;

        public FieldStep(String label, String tooltip, String back, String next) {
            super();
            setFieldRow(fieldLabel(label), field, tooltip);
            backName = back == null ? null : "bioenv.wizardBack";
            if (backName != null) {
                addButton(back, backName);
            }
            nextName = "bioenv.wizardNext";
            primary(addButton(next, nextName));
        }

        public JTextField field() {
            return field;
        }

        public JButton back() {
            return backName == null ? null : button(backName);
        }

        public JButton next() {
            return button(nextName);
        }
    }

    // ------------------------------------------------------------------
    // 须知步骤（密钥那一步）
    // ------------------------------------------------------------------

    /**
     * 只有一段说明、没有输入框的步骤。
     *
     * <p>密钥那一步用它：不需要用户填任何东西，只需要他知道接下来会发生什么。所以这
     * 段文字<b>常驻</b>，不做成悬停提示——它是这一步的全部内容，藏起来就等于空窗口。
     */
    public static final class Notice extends Step {

        private final String backName;
        private final String nextName;

        public Notice(String text, String back, String next) {
            super();
            setNoticeBody(notice(text, DESC_FG, 72));
            backName = back == null ? null : "bioenv.wizardBack";
            if (backName != null) {
                addButton(back, backName);
            }
            nextName = "bioenv.wizardNext";
            primary(addButton(next, nextName));
        }

        public JButton back() {
            return backName == null ? null : button(backName);
        }

        public JButton next() {
            return button(nextName);
        }
    }

    // ------------------------------------------------------------------
    // 密码
    // ------------------------------------------------------------------

    /** 密码步。密码只留在 {@link JPasswordField} 里。 */
    public static final class Password extends Step {

        private final JPasswordField field = new JPasswordField();
        private final String backName;
        private final String connectName;
        private final String cancelName;

        public Password(String label, String description,
                        String back, String connect, String cancel) {
            super();
            field.setPreferredSize(new Dimension(WIDTH - LABEL_W - 12, FIELD_H));
            setFieldRow(fieldLabel(label), field, description);

            backName = back == null ? null : "bioenv.wizardBack";
            if (backName != null) {
                addButton(back, backName);
            }
            cancelName = cancel == null ? null : "bioenv.wizardCancel";
            if (cancelName != null) {
                addButton(cancel, cancelName);
            }
            connectName = "bioenv.wizardConnect";
            primary(addButton(connect, connectName));
        }

        public JPasswordField field() {
            return field;
        }

        public JButton back() {
            return backName == null ? null : button(backName);
        }

        public JButton connect() {
            return button(connectName);
        }

        public JButton cancel() {
            return cancelName == null ? null : button(cancelName);
        }
    }

    // ------------------------------------------------------------------
    // 结果（成功 / 失败共用）
    // ------------------------------------------------------------------

    /** 结果步：一段结论 + 详情 + 一个关闭按钮。 */
    public static final class Result extends Step {

        private final JTextArea detail = new JTextArea();
        private final String closeName = "bioenv.wizardClose";

        public Result(String description, String detailText, boolean ok,
                      String close) {
            super();
            detail.setName("bioenv.wizardDetail");
            detail.setEditable(false);
            detail.setLineWrap(true);
            detail.setWrapStyleWord(true);
            detail.setOpaque(false);
            detail.setForeground(ok ? new Color(0x1B, 0x7F, 0x3B)
                    : new Color(0xB2, 0x2D, 0x2D));
            detail.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            detail.setText(detailText);

            JPanel body = new JPanel(new BorderLayout(0, 8));
            body.setOpaque(false);
            body.add(notice(description, DESC_FG, 40), BorderLayout.NORTH);
            JScrollPane sc = new JScrollPane(detail);
            sc.setPreferredSize(new Dimension(WIDTH, 110));
            body.add(sc, BorderLayout.CENTER);
            setNoticeBody(body);
            addButton(close, closeName);
            // 结果页比其它步高（要放一段可滚动的详情）。
            setFixedHeight(RESULT_HEIGHT);
        }

        public String detail() {
            return detail.getText();
        }

        public JButton close() {
            return button(closeName);
        }
    }

    // ------------------------------------------------------------------

    /** 按名字在控件树里找一个按钮。 */
    static JButton find(Component root, String name) {
        if (root instanceof JButton && name.equals(root.getName())) {
            return (JButton) root;
        }
        if (root instanceof java.awt.Container) {
            for (Component c : ((java.awt.Container) root).getComponents()) {
                JButton hit = find(c, name);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 给评审图用的窗口外框。真实窗口用 JDialog，标题是「虚拟机连接配置」。 */
    public static Component framed(JPanel inner, String windowTitle) {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Color.WHITE);
        root.setBorder(BorderFactory.createLineBorder(new Color(0xAA, 0xB0, 0xB8)));
        JLabel title = new JLabel("  " + windowTitle);
        title.setOpaque(true);
        title.setBackground(new Color(0xF0, 0xF2, 0xF5));
        title.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        root.add(title, BorderLayout.NORTH);
        root.add(inner, BorderLayout.CENTER);
        return root;
    }
}
