package vmadapt.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * 「连接虚拟机」向导：一步一个窗，像装软件那样走完。
 *
 * <h2>为什么改成向导</h2>
 *
 * <p>原来是一个表单加八个按钮，用户要自己判断先填什么、能点哪个。而这件事本身是有
 * 固定顺序的：地址 → 端口 → 用户名 → 密钥 → 连接 → 起名字。把顺序写进界面，用户
 * 就不再需要知道顺序；每一步只回答一个问题，也就没有"按钮是灰的，为什么"这种疑问。
 *
 * <h2>每一步都能返回</h2>
 *
 * <p>除了第一步，每个窗都有「上一步」，并且<b>把上一步填的值回填</b>——否则返回等于
 * 重填，那就不叫返回了。
 *
 * <h2>为评审而做成面板</h2>
 *
 * <p>每个步骤是一个 {@link JPanel}，不直接开窗。这样无头环境下也能构造、绘制、断言，
 * 评审时看到的是真实控件而不是设想——上一版就是因为能画出来，才发现命名窗的标签和
 * 输入框挤成了一团。
 */
public final class SetupWizard {

    /** 所有步骤共用的文字宽度，窗与窗之间才不会忽宽忽窄。 */
    public static final int WIDTH = 460;

    private SetupWizard() {
    }

    // ------------------------------------------------------------------
    // 步骤基类：统一"标题 + 说明 + 内容 + 按钮行"的骨架
    // ------------------------------------------------------------------

    /** 一个步骤的外壳。子类只填中间那块内容。 */
    public abstract static class Step {

        private final JPanel root = new JPanel(new BorderLayout(0, 0));
        private final JPanel content = new JPanel(new BorderLayout(0, 10));
        private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        private final JLabel stepLabel = new JLabel();
        private final JLabel titleLabel = new JLabel();
        private final JTextArea hint = new JTextArea();
        private final JProgressBar progress = new JProgressBar();

        protected Step(int stepNo, int total, String title, String hintText) {
            root.setName("bioenv.wizard");
            root.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
            root.setBackground(Color.WHITE);
            content.setOpaque(false);
            buttons.setOpaque(false);

            JPanel head = new JPanel(new BorderLayout(0, 4));
            head.setOpaque(false);
            stepLabel.setText("第 " + stepNo + " / " + total + " 步");
            stepLabel.setForeground(new Color(0x6C, 0x72, 0x7C));
            stepLabel.setFont(stepLabel.getFont().deriveFont(Font.PLAIN, 12f));
            head.add(stepLabel, BorderLayout.NORTH);

            titleLabel.setText(title);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 15f));
            head.add(titleLabel, BorderLayout.CENTER);

            hint.setText(hintText);
            hint.setEditable(false);
            hint.setLineWrap(true);
            hint.setWrapStyleWord(true);
            hint.setOpaque(false);
            hint.setForeground(new Color(0x55, 0x5B, 0x64));
            hint.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            // 占位尺寸必须有：可换行 JTextArea 在 BorderLayout 里没有宽度来源，
            // 不给就会压成一列竖排的字（命名窗踩过这个）。
            hint.setPreferredSize(new Dimension(WIDTH, 32));
            head.add(hint, BorderLayout.SOUTH);

            JPanel top = new JPanel(new BorderLayout(0, 10));
            top.setOpaque(false);
            top.add(head, BorderLayout.NORTH);
            progress.setMaximum(total);
            progress.setValue(stepNo);
            progress.setPreferredSize(new Dimension(WIDTH, 6));
            progress.setBorderPainted(false);
            top.add(progress, BorderLayout.SOUTH);

            root.add(top, BorderLayout.NORTH);
            root.add(content, BorderLayout.CENTER);
            root.add(buttons, BorderLayout.SOUTH);
        }

        /** 子类把内容放进这里（CENTER）。 */
        protected void setBody(Component c) {
            content.add(c, BorderLayout.CENTER);
        }

        /** 子类加按钮；顺序按加入顺序从左到右。 */
        protected JButton addButton(String text, String name) {
            JButton b = new JButton(text);
            b.setName(name);
            buttons.add(b);
            return b;
        }

        /** 把某个按钮标成主操作（蓝色文字）。 */
        protected static void primary(JButton b) {
            b.setForeground(new Color(0x1A, 0x5F, 0xB4));
        }

        public JPanel panel() {
            return root;
        }

        public String title() {
            return titleLabel.getText();
        }

        public String hint() {
            return hint.getText();
        }

        public String stepText() {
            return stepLabel.getText();
        }
    }

    // ------------------------------------------------------------------
    // 第一步：主机
    // ------------------------------------------------------------------

    /** 填一个文本框的简单步骤，端口/用户名/密钥/名称都用它。 */
    public static final class FieldStep extends Step {

        private final JTextField field = new JTextField();

        public FieldStep(int no, int total, String title, String hintText,
                         String label, String placeholder,
                         String back, String next) {
            super(no, total, title, hintText);

            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            JLabel l = new JLabel(label);
            l.setPreferredSize(new Dimension(96, 24));
            row.add(l, BorderLayout.WEST);
            field.setName("bioenv.wizardField");
            field.setPreferredSize(new Dimension(WIDTH - 110, 26));
            row.add(field, BorderLayout.CENTER);

            JPanel box = new JPanel(new BorderLayout(0, 6));
            box.setOpaque(false);
            box.add(row, BorderLayout.NORTH);
            if (placeholder != null && !placeholder.isEmpty()) {
                JLabel ph = new JLabel(placeholder);
                ph.setForeground(new Color(0x8A, 0x90, 0x99));
                ph.setFont(ph.getFont().deriveFont(Font.PLAIN, 12f));
                box.add(ph, BorderLayout.CENTER);
            }
            setBody(box);

            if (back != null) {
                addButton(back, "bioenv.wizardBack");
            }
            JButton nx = addButton(next, "bioenv.wizardNext");
            primary(nx);
        }

        public JTextField field() {
            return field;
        }

        public JButton back() {
            return buttonNamed("bioenv.wizardBack");
        }

        public JButton next() {
            return buttonNamed("bioenv.wizardNext");
        }

        JButton buttonNamed(String name) {
            return find(panel(), name);
        }
    }

    // ------------------------------------------------------------------
    // 连接前确认
    // ------------------------------------------------------------------

    /** 确认页：把前几步填的摘要列出来，让用户核对一眼。 */
    public static final class Confirm extends Step {

        private final JTextArea summary = new JTextArea();

        public Confirm(int no, int total, String title, String hintText,
                       String back, String next) {
            super(no, total, title, hintText);
            summary.setName("bioenv.wizardSummary");
            summary.setEditable(false);
            summary.setOpaque(false);
            summary.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            summary.setPreferredSize(new Dimension(WIDTH, 96));
            setBody(summary);

            if (back != null) {
                addButton(back, "bioenv.wizardBack");
            }
            JButton nx = addButton(next, "bioenv.wizardNext");
            primary(nx);
        }

        public void setSummary(String text) {
            summary.setText(text);
        }

        public String summary() {
            return summary.getText();
        }

        public JButton back() {
            return find(panel(), "bioenv.wizardBack");
        }

        public JButton next() {
            return find(panel(), "bioenv.wizardNext");
        }
    }

    // ------------------------------------------------------------------
    // 密码
    // ------------------------------------------------------------------

    /** 密码页。密码只留在 JPasswordField 里。 */
    public static final class Password extends Step {

        private final JPasswordField field = new JPasswordField();

        public Password(int no, int total, String title, String hintText,
                        String note, String back, String connect, String cancel) {
            super(no, total, title, hintText);

            JPanel box = new JPanel(new BorderLayout(0, 6));
            box.setOpaque(false);
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            JLabel l = new JLabel("登录密码：");
            l.setPreferredSize(new Dimension(96, 24));
            row.add(l, BorderLayout.WEST);
            field.setName("bioenv.wizardPassword");
            field.setPreferredSize(new Dimension(WIDTH - 110, 26));
            row.add(field, BorderLayout.CENTER);
            box.add(row, BorderLayout.NORTH);

            JLabel tip = new JLabel(note);
            tip.setForeground(new Color(0x6C, 0x72, 0x7C));
            tip.setFont(tip.getFont().deriveFont(Font.PLAIN, 12f));
            box.add(tip, BorderLayout.CENTER);
            setBody(box);

            if (back != null) {
                addButton(back, "bioenv.wizardBack");
            }
            if (cancel != null) {
                addButton(cancel, "bioenv.wizardCancel");
            }
            JButton c = addButton(connect, "bioenv.wizardConnect");
            primary(c);
        }

        public JPasswordField field() {
            return field;
        }

        public JButton back() {
            return find(panel(), "bioenv.wizardBack");
        }

        public JButton cancel() {
            return find(panel(), "bioenv.wizardCancel");
        }

        public JButton connect() {
            return find(panel(), "bioenv.wizardConnect");
        }
    }

    // ------------------------------------------------------------------
    // 进行中
    // ------------------------------------------------------------------

    /** 执行页：把正在做的一步一步列出来，让等待有内容可看。 */
    public static final class Progress extends Step {

        private final JTextArea log = new JTextArea();

        public Progress(int no, int total, String title, String hintText) {
            super(no, total, title, hintText);
            log.setName("bioenv.wizardLog");
            log.setEditable(false);
            log.setOpaque(false);
            log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            log.setPreferredSize(new Dimension(WIDTH, 110));
            setBody(new JScrollPane(log));
            // 执行中没有按钮：不可取消，取消到一半的公钥安装状态更糟。
        }

        public void append(String line) {
            log.append(line + "\n");
        }

        public String log() {
            return log.getText();
        }
    }

    // ------------------------------------------------------------------
    // 命名并保存
    // ------------------------------------------------------------------

    /** 收尾页：给这条配置起名字。 */
    public static final class Name extends Step {

        private final JTextField field = new JTextField();

        public Name(int no, int total, String title, String hintText,
                    String label, String note, String skip, String save) {
            super(no, total, title, hintText);

            JPanel box = new JPanel(new BorderLayout(0, 6));
            box.setOpaque(false);
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            JLabel l = new JLabel(label);
            l.setPreferredSize(new Dimension(96, 24));
            row.add(l, BorderLayout.WEST);
            field.setName("bioenv.wizardName");
            field.setPreferredSize(new Dimension(WIDTH - 110, 26));
            row.add(field, BorderLayout.CENTER);
            box.add(row, BorderLayout.NORTH);

            JLabel tip = new JLabel(note);
            tip.setForeground(new Color(0x6C, 0x72, 0x7C));
            tip.setFont(tip.getFont().deriveFont(Font.PLAIN, 12f));
            box.add(tip, BorderLayout.CENTER);
            setBody(box);

            if (skip != null) {
                addButton(skip, "bioenv.wizardSkip");
            }
            JButton s = addButton(save, "bioenv.wizardSave");
            primary(s);
        }

        public JTextField field() {
            return field;
        }

        public JButton skip() {
            return find(panel(), "bioenv.wizardSkip");
        }

        public JButton save() {
            return find(panel(), "bioenv.wizardSave");
        }
    }

    // ------------------------------------------------------------------
    // 结束页（成功 / 失败共用）
    // ------------------------------------------------------------------

    /** 结果页：一句话结论 + 详情 + 一个关闭按钮。 */
    public static final class Result extends Step {

        private final JTextArea detail = new JTextArea();

        public Result(int no, int total, String title, String hintText,
                      String detailText, boolean ok, String close) {
            super(no, total, title, hintText);
            detail.setName("bioenv.wizardDetail");
            detail.setEditable(false);
            detail.setLineWrap(true);
            detail.setWrapStyleWord(true);
            detail.setOpaque(false);
            detail.setForeground(ok ? new Color(0x1B, 0x7F, 0x3B)
                    : new Color(0xB2, 0x2D, 0x2D));
            detail.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            detail.setPreferredSize(new Dimension(WIDTH, 120));
            detail.setText(detailText);
            setBody(new JScrollPane(detail));
            addButton(close, "bioenv.wizardClose");
        }

        public void setDetail(String text) {
            detail.setText(text);
        }

        public String detail() {
            return detail.getText();
        }

        public JButton close() {
            return find(panel(), "bioenv.wizardClose");
        }
    }

    // ------------------------------------------------------------------

    /** 按名字在控件树里找一个按钮。测试与渲染都要用，所以公开。 */
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
