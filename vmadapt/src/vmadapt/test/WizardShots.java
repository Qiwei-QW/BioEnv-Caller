package vmadapt.test;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import vmadapt.ui.SetupWizard;

/**
 * 按最终版式画出向导各步，供评审。
 *
 * <p>不是示意图：每步都是真的 {@link SetupWizard} 面板、真的控件、真的文案。
 *
 * <p>版式：只有一行「标签 + 输入框」，长说明挂在悬停提示上（图里看不到提示，
 * 所以每张图另外打印它的提示文字，方便对照）。
 *
 * <p>用法：{@code WizardShots <输出目录>}
 */
public final class WizardShots {

    public static void main(String[] args) throws Exception {
        final File out = new File(args.length > 0 ? args[0] : ".");
        out.mkdirs();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    String hintHost = "虚拟机的 IP 取决于它的网络模式：\n"
                            + "NAT：宿主机填 127.0.0.1，并在 VMware 里配好端口转发；"
                            + "虚拟机自己的 IP 从宿主机访问不到。\n"
                            + "桥接：虚拟机在局域网里有独立 IP，用 ip addr 查到即可。\n"
                            + "仅主机：虚拟机在 vmnet1 网段，同样用 ip addr 查。";

                    SetupWizard.FieldStep s1 = new SetupWizard.FieldStep(
                            "主机 / IP：", hintHost, "取消", "下一步");
                    s1.field().setText("127.0.0.1");
                    shot(SetupWizard.framed(s1.panel(), "虚拟机连接配置"),
                            out, "w1-host.png", hintHost);

                    String hintPort = "SSH 默认是 22。只有在虚拟机上改过 sshd 配置时才需要改这里。";
                    SetupWizard.FieldStep s2 = new SetupWizard.FieldStep(
                            "端口：", hintPort, "上一步", "下一步");
                    s2.field().setText("22");
                    shot(SetupWizard.framed(s2.panel(), "虚拟机连接配置"),
                            out, "w2-port.png", hintPort);

                    String hintUser = "虚拟机里你的登录名。装系统时设的那个，常见是 ubuntu。";
                    SetupWizard.FieldStep s3 = new SetupWizard.FieldStep(
                            "用户名：", hintUser, "上一步", "下一步");
                    s3.field().setText("ubuntu");
                    shot(SetupWizard.framed(s3.panel(), "虚拟机连接配置"),
                            out, "w3-user.png", hintUser);

                    // 密钥须知：这一步的文字是常驻的，不是提示
                    SetupWizard.Notice s4 = new SetupWizard.Notice(
                            "此操作将在本机生成 SSH 密钥对，私钥用于身份验证，"
                                    + "公钥将自动追加到你的虚拟机中。\n\n"
                                    + "继续请点击「下一步」。",
                            "上一步", "下一步");
                    shot(SetupWizard.framed(s4.panel(), "虚拟机连接配置"),
                            out, "w4-key-notice.png", "(常驻显示，无提示)");

                    String hintPwd =
                            "请输入虚拟机登陆密码，以便发送公钥到虚拟机。密码不会被保存。";
                    SetupWizard.Password s5 = new SetupWizard.Password(
                            "密码：", hintPwd, "上一步", "开始连接", "取消");
                    shot(SetupWizard.framed(s5.panel(), "虚拟机连接配置"),
                            out, "w5-password.png", hintPwd);

                    String hintName = "留空则用「用户名@主机:端口」。以后可以随时改。";
                    SetupWizard.FieldStep s6 = new SetupWizard.FieldStep(
                            "配置名称：", hintName, "跳过", "保存");
                    s6.field().setText("测序分析机");
                    shot(SetupWizard.framed(s6.panel(), "虚拟机连接配置"),
                            out, "w6-name.png", hintName);

                    SetupWizard.Result s7 = new SetupWizard.Result(
                            "连接失败。虚拟机上没有做任何改动。",
                            "Permission denied (publickey,password).\n\n"
                                    + "请检查密码是否输错，然后重试。",
                            false, "关闭");
                    shot(SetupWizard.framed(s7.panel(), "虚拟机连接配置"),
                            out, "w7-fail.png", "(常驻显示)");

                    SetupWizard.Result s8 = new SetupWizard.Result(
                            "这台电脑的 OpenSSH 版本太旧，无法自动安装公钥。",
                            "插件需要 OpenSSH 8.4 或更新版本。\n\n"
                                    + "请把下面这段贴到虚拟机的终端里执行，然后重试：\n"
                                    + "mkdir -p ~/.ssh && chmod 700 ~/.ssh && \\\n"
                                    + "  echo 'ssh-rsa AAAAB3Nza...tbvm' >> ~/.ssh/authorized_keys",
                            false, "关闭");
                    shot(SetupWizard.framed(s8.panel(), "虚拟机连接配置"),
                            out, "w8-manual.png", "(常驻显示)");

                    System.out.println("wrote 8 images to " + out);
                } catch (Exception e) {
                    System.out.println("failed: " + e);
                    e.printStackTrace();
                }
            }
        });
    }

    private static void shot(Component c, File dir, String name, String tooltip)
            throws Exception {
        c.setSize(c.getPreferredSize());
        if (c instanceof Container) {
            layoutRecursively((Container) c);
        }
        int w = Math.max(1, c.getWidth());
        int panelH = Math.max(1, c.getHeight());

        // 悬停提示在静态图里天然看不到（要鼠标移上去才出现），但它的文字恰恰是这轮
        // 评审的重点——说明从界面搬进了提示。所以把它作为图注画在窗口下面，并在图上
        // 明确标出"悬停提示"，免得看图的人以为那是界面里的字。
        java.util.List<String> caption = new java.util.ArrayList<String>();
        if (tooltip != null && !tooltip.isEmpty()) {
            caption.add("鼠标移到输入框上时显示的提示：");
            for (String line : wrap(tooltip, 46)) {
                caption.add(line);
            }
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

    /** 按显示宽度粗略折行（中文按 2 个宽度算）。 */
    private static java.util.List<String> wrap(String text, int maxWidth) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            int width = 0;
            for (int i = 0; i < para.length(); i++) {
                char ch = para.charAt(i);
                int cw = (ch > 0x2E80) ? 2 : 1;
                if (width + cw > maxWidth) {
                    out.add(line.toString());
                    line.setLength(0);
                    width = 0;
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
