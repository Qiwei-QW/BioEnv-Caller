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
 * 把「连接虚拟机」向导的每一步画成图，供评审。
 *
 * <p>不是示意图：每步都是真的 {@link SetupWizard} 面板、真的控件、真的文案。
 * 评审措辞与布局时，这比读代码有效——上一版就是因为能画出来，才发现命名窗的
 * 标签和输入框挤成了一团。
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
                    int total = 5;
                    // 1 主机
                    SetupWizard.FieldStep s1 = new SetupWizard.FieldStep(
                            1, total, "虚拟机在哪里？",
                            "填虚拟机的 IP 地址。在虚拟机的终端里执行 ip addr 可以看到，"
                                    + "通常是 192.168.x.x。",
                            "主机 / IP：", "例：192.168.1.100",
                            "取消", "下一步");
                    s1.field().setText("192.168.1.100");
                    shot(s1.panel(), out, "wiz1-host.png");

                    // 2 端口
                    SetupWizard.FieldStep s2 = new SetupWizard.FieldStep(
                            2, total, "用哪个端口？",
                            "SSH 默认是 22。只有在虚拟机上改过 sshd 配置时才需要改这里。",
                            "端口：", "默认 22",
                            "上一步", "下一步");
                    s2.field().setText("22");
                    shot(s2.panel(), out, "wiz2-port.png");

                    // 3 用户名
                    SetupWizard.FieldStep s3 = new SetupWizard.FieldStep(
                            3, total, "用哪个账号登录？",
                            "虚拟机里你的登录名。装 Ubuntu 时设的那个，常见是 ubuntu。",
                            "用户名：", "例：ubuntu",
                            "上一步", "下一步");
                    s3.field().setText("ubuntu");
                    shot(s3.panel(), out, "wiz3-user.png");

                    // 4 密钥
                    SetupWizard.FieldStep s4 = new SetupWizard.FieldStep(
                            4, total, "用哪个密钥？",
                            "可以留空——插件会在 ~/.ssh 下生成一把专用密钥。"
                                    + "已经有自己的密钥时，填它的完整路径。",
                            "密钥路径：", "留空则使用默认密钥（推荐）",
                            "上一步", "下一步");
                    shot(s4.panel(), out, "wiz4-key.png");

                    // 5 确认
                    SetupWizard.Confirm s5 = new SetupWizard.Confirm(
                            5, total, "确认一下",
                            "点「开始连接」后：会在本机生成 SSH 密钥对，私钥留在本机，"
                                    + "公钥追加到虚拟机上。",
                            "上一步", "开始连接");
                    s5.setSummary("主机 / IP：  192.168.1.100\n"
                            + "端口：       22\n"
                            + "用户名：     ubuntu\n"
                            + "密钥路径：   （使用默认密钥）");
                    shot(s5.panel(), out, "wiz5-confirm.png");

                    // 密码（仅当还没有公钥）
                    SetupWizard.Password pw = new SetupWizard.Password(
                            6, 7, "请输入虚拟机登录密码",
                            "这是最后一次需要密码。装上公钥之后，以后连接都用免密。",
                            "只用于这一次安装公钥，密码不会被保存。",
                            "上一步", "开始连接", "取消");
                    shot(pw.panel(), out, "wiz6-password.png");

                    // 进行中
                    SetupWizard.Progress pr = new SetupWizard.Progress(
                            6, 6, "正在连接…", "请稍候，这一步通常几秒钟。");
                    pr.append("生成密钥对 … 完成");
                    pr.append("把公钥装到 192.168.1.100 … 完成");
                    pr.append("测试免密登录 …");
                    shot(pr.panel(), out, "wiz7-progress.png");

                    // 命名
                    SetupWizard.Name nm = new SetupWizard.Name(
                            6, 6, "连接成功",
                            "给这台机器起个名字，下次就能直接选它。",
                            "配置名称：", "留空则用「ubuntu@192.168.1.100:22」。",
                            "跳过", "保存");
                    nm.field().setText("测序分析机");
                    shot(nm.panel(), out, "wiz8-name.png");

                    // 失败
                    SetupWizard.Result bad = new SetupWizard.Result(
                            6, 6, "连接失败",
                            "密码不对，或者这台机器还不允许你的密钥登录。",
                            "Permission denied (publickey,password).\n\n"
                                    + "请检查密码是否输错，然后重试。",
                            false, "关闭");
                    shot(bad.panel(), out, "wiz9-fail.png");

                    System.out.println("wrote 9 images to " + out);
                } catch (Exception e) {
                    System.out.println("failed: " + e);
                    e.printStackTrace();
                }
            }
        });
    }

    private static void shot(Component c, File dir, String name) throws Exception {
        c.setSize(c.getPreferredSize());
        if (c instanceof Container) {
            layoutRecursively((Container) c);
        }
        int w = Math.max(1, c.getWidth());
        int h = Math.max(1, c.getHeight());
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, w, h);
        c.paint(g);
        g.dispose();
        ImageIO.write(img, "png", new File(dir, name));
        System.out.println("  " + name + "  " + w + "x" + h);
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
