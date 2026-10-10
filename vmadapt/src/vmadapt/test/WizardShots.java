package vmadapt.test;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import vmadapt.ui.SetupWizard;

/**
 * 画出向导各步，供评审。
 *
 * <h2>文案一律从生产代码里取，不在本文件里硬编码</h2>
 *
 * <p>这里曾经把每句说明都抄在工具里。后果是：改了 {@code UiText} 之后，画出来的图
 * 还是旧的，而看图的人以为那就是程序会显示的样子——评审因此评的是一份不存在的界面。
 * 这个坑已经踩过一次（另一处：图是中文、代码按 provider 解析成了英文）。
 *
 * <p>{@code Text} 是包级私有，所以只能用反射取。取不到就抛异常，绝不退回硬编码——
 * 静默退回等于把刚修掉的毛病又装回去。
 *
 * <p>用法：{@code WizardShots <输出目录>}
 */
public final class WizardShots {

    /** 生产文案解析器实例（{@code vmadapt.ui.Text}），语言留空即默认中文。 */
    private static Object textHolder;
    private static Method textGet;
    private static Method textGetArgs;

    public static void main(String[] args) throws Exception {
        final File out = new File(args.length > 0 ? args[0] : ".");
        out.mkdirs();
        setUpTextLookup();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    // ① 地址
                    String hintHost = real("wiz.host.hint");
                    SetupWizard.FieldStep s1 = new SetupWizard.FieldStep(
                            real("lbl.host"), hintHost,
                            real("btn.cancel"), real("btn.next"));
                    s1.field().setText("192.168.1.100");
                    shot(SetupWizard.framed(s1.panel(), real("dialog.wizardTitle")),
                            out, "w1-host.png", hintHost);

                    // ② 端口
                    String hintPort = real("wiz.port.hint");
                    SetupWizard.FieldStep s2 = new SetupWizard.FieldStep(
                            real("lbl.port"), hintPort,
                            real("btn.back"), real("btn.next"));
                    s2.field().setText("22");
                    shot(SetupWizard.framed(s2.panel(), real("dialog.wizardTitle")),
                            out, "w2-port.png", hintPort);

                    // ③ 用户名
                    String hintUser = real("wiz.user.hint");
                    SetupWizard.FieldStep s3 = new SetupWizard.FieldStep(
                            real("lbl.user"), hintUser,
                            real("btn.back"), real("btn.next"));
                    s3.field().setText("ubuntu");
                    shot(SetupWizard.framed(s3.panel(), real("dialog.wizardTitle")),
                            out, "w3-user.png", hintUser);

                    // ④ 密钥须知（这段是常驻的，不是提示）
                    SetupWizard.Notice s4 = new SetupWizard.Notice(
                            real("wiz.keyNotice"), real("btn.back"), real("btn.next"));
                    shot(SetupWizard.framed(s4.panel(), real("dialog.wizardTitle")),
                            out, "w4-key-notice.png", null);

                    // ⑤ 密码
                    String hintPwd = real("wiz.pwd.hint");
                    SetupWizard.Password s5 = new SetupWizard.Password(
                            real("lbl.password"), hintPwd,
                            real("btn.back"), real("btn.start"), real("btn.cancel"));
                    shot(SetupWizard.framed(s5.panel(), real("dialog.wizardTitle")),
                            out, "w5-password.png", hintPwd);

                    // ⑥ 命名
                    String hintName = real("wiz.name.note");
                    SetupWizard.FieldStep s6 = new SetupWizard.FieldStep(
                            real("lbl.name"), hintName,
                            real("btn.skip"), real("btn.save"));
                    s6.field().setText("测序分析机");
                    shot(SetupWizard.framed(s6.panel(), real("dialog.wizardTitle")),
                            out, "w6-name.png", hintName);

                    // ⑦ 失败
                    SetupWizard.Result s7 = new SetupWizard.Result(
                            real("wiz.fail.hint"),
                            "Permission denied (publickey,password).\n\n"
                                    + "请检查密码是否输错，然后重试。",
                            false, real("btn.close"));
                    shot(SetupWizard.framed(s7.panel(), real("dialog.wizardTitle")),
                            out, "w7-fail.png", null);

                    // ⑧ 环境不足（手动安装）
                    SetupWizard.Result s8 = new SetupWizard.Result(
                            real("wiz.oldSsh.title"),
                            real("wiz.oldSsh.reason", "OpenSSH_for_Windows_8.1p1"),
                            false, real("btn.close"));
                    shot(SetupWizard.framed(s8.panel(), real("dialog.wizardTitle")),
                            out, "w8-manual.png", null);

                    System.out.println("wrote 8 images to " + out);
                } catch (Exception e) {
                    System.out.println("failed: " + e);
                    throw new RuntimeException(e);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // 取生产文案
    // ------------------------------------------------------------------

    private static void setUpTextLookup() throws Exception {
        Class<?> textClass = Class.forName("vmadapt.ui.Text");
        Class<?> providerClass = Class.forName("vmadapt.ui.LangProvider");
        Constructor<?> ctor = textClass.getDeclaredConstructor(providerClass);
        ctor.setAccessible(true);
        textHolder = ctor.newInstance(new Object[] { null });
        textGet = textClass.getDeclaredMethod("get", String.class);
        textGet.setAccessible(true);
        textGetArgs = textClass.getDeclaredMethod("get", String.class, Object[].class);
        textGetArgs.setAccessible(true);
    }

    /** 按当前语言取一条生产文案；取不到直接失败，不退回硬编码。 */
    private static String real(String key) throws Exception {
        return require(key, textGet.invoke(textHolder, key));
    }

    /** 同上，带占位符参数。 */
    private static String real(String key, Object... args) throws Exception {
        return require(key, textGetArgs.invoke(textHolder, key, args));
    }

    private static String require(String key, Object value) {
        String s = (String) value;
        if (s == null || s.isEmpty()) {
            throw new IllegalStateException("no text for key: " + key);
        }
        return s;
    }

    // ------------------------------------------------------------------

    private static void shot(Component c, File dir, String name, String tooltip)
            throws Exception {
        c.setSize(c.getPreferredSize());
        if (c instanceof Container) {
            layoutRecursively((Container) c);
        }
        int w = Math.max(1, c.getWidth());
        int panelH = Math.max(1, c.getHeight());

        // 悬停提示在静态图里天然看不到（要鼠标移上去才出现），但它的文字恰恰是评审的
        // 重点之一。所以把它作为图注画在窗口下面，并标明"这是提示"，免得看图的人
        // 以为那是界面里的字。
        java.util.List<String> caption = new java.util.ArrayList<String>();
        if (tooltip != null && !tooltip.isEmpty()) {
            caption.add("鼠标移到输入框上时显示的提示：");
            caption.addAll(wrap(tooltip, 46));
        }
        int capH = caption.isEmpty() ? 0 : (caption.size() * 20 + 14);
        int h = panelH + capH;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        c.paint(g);
        if (capH > 0) {
            g.setColor(new java.awt.Color(0xE8, 0xEE, 0xF6));
            g.fillRect(0, panelH, w, capH);
            g.setColor(new java.awt.Color(0x33, 0x44, 0x55));
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF,
                    java.awt.Font.PLAIN, 12));
            int y = panelH + 20;
            for (String line : caption) {
                g.drawString(line, 10, y);
                y += 20;
            }
        }
        g.dispose();
        ImageIO.write(img, "png", new File(dir, name));
        System.out.println("  " + name + "  " + w + "x" + h);
    }

    /**
     * 按显示宽度粗略折行（中文按 2 个宽度算）。
     *
     * <p>不在 ASCII 单词中间断开：第一版只按宽度切，把 {@code VMnet8} 切成了
     * {@code VMne} / {@code 18}，看图的人会以为文案里真有个换行。
     */
    private static java.util.List<String> wrap(String text, int maxWidth) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            int width = 0;
            for (int i = 0; i < para.length(); i++) {
                char ch = para.charAt(i);
                int cw = (ch > 0x2E80) ? 2 : 1;
                if (width + cw > maxWidth && line.length() > 0) {
                    // 让出整行，再从最近一个空格处断开；找不到空格就整行照发，
                    // 只对一个超长的英文串做硬切。
                    String s = line.toString();
                    int cut = s.lastIndexOf(' ');
                    if (cut > 0 && ch <= 0x2E80 && isAscii(para.charAt(i - 1))) {
                        out.add(s.substring(0, cut));
                        line.setLength(0);
                        line.append(s.substring(cut + 1));
                        width = line.length();
                    } else {
                        out.add(s);
                        line.setLength(0);
                        width = 0;
                    }
                }
                line.append(ch);
                width += cw;
            }
            if (line.length() > 0) {
                out.add(line.toString());
            }
        }
        return out;
    }

    private static boolean isAscii(char c) {
        return c <= 0x7F;
    }

    private static void layoutRecursively(Container c) {
        c.doLayout();
        for (Component ch : c.getComponents()) {
            if (ch instanceof Container) {
                layoutRecursively((Container) ch);
            }
        }
        c.doLayout();
    }
}
