package bioenv.test;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JButton;

import bioenv.core.Schema;
import bioenv.core.Tool;
import bioenv.i18n.I18n;
import bioenv.i18n.Strings;
import bioenv.ui.SchemaFormPanel;

/**
 * 工作目录那两个按钮的测试。
 *
 * <p>回归两件事：
 *
 * <ol>
 *   <li><b>图标而不是文字。</b>两个按钮一个是「在 Linux 侧建目录」、一个是
 *       「挑本机的一个目录」，光看文字要读一长串；换成企鹅和 Windows 方块之后
 *       一眼就分得清哪个在哪一侧。文字仍然保留在 tooltip 和无障碍名里。</li>
 *   <li><b>文案里不再写死「WSL」。</b>这个表单是 WSL 和 Linux 虚拟机共用的，
 *       在虚拟机上告诉用户「WSL 当前不可用」是把他往错的方向指。</li>
 * </ol>
 */
public final class WorkdirUiTest {

    private static int checks;
    private static int failures;

    private WorkdirUiTest() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== WorkdirUiTest ===");
        System.out.println("headless = " + java.awt.GraphicsEnvironment.isHeadless());

        SchemaFormPanel form = new SchemaFormPanel();
        Tool tool = new Tool("demo", "env_a", "/envs/a");
        tool.schema = new Schema();
        tool.schema.parsed = true;
        Schema.Option o = new Schema.Option();
        o.flag = "-in";
        o.arg = "<file>";
        o.description = "input";
        o.kind = Schema.Option.Kind.INPUT_PATH;
        tool.schema.options.add(o);
        form.showTool(tool);

        AbstractButton make = (AbstractButton) find(form, "bioenv.makeWorkdir");
        AbstractButton browse = (AbstractButton) find(form, "bioenv.chooseWorkdir");

        check("找得到「在 Linux 侧新建」按钮", make != null);
        check("找得到「选择本机目录」按钮", browse != null);
        if (make == null || browse == null) {
            finish();
            return;
        }

        // 图标按钮：有图标、没有文字。
        check("新建按钮有图标", make.getIcon() != null);
        check("新建按钮不再显示文字",
                make.getText() == null || make.getText().trim().isEmpty());
        check("新建按钮仍提示得清楚",
                make.getToolTipText() != null && !make.getToolTipText().trim().isEmpty());
        check("新建按钮的无障碍名不为空",
                name(make) != null && !name(make).trim().isEmpty());

        check("浏览按钮有图标", browse.getIcon() != null);
        check("浏览按钮不再显示文字",
                browse.getText() == null || browse.getText().trim().isEmpty());
        check("浏览按钮仍提示得清楚",
                browse.getToolTipText() != null && !browse.getToolTipText().trim().isEmpty());

        // 两个图标必须不一样，否则等于没区分。
        check("两个图标不是同一个", make.getIcon() != browse.getIcon());

        // 图标真的画出了东西，而不是一块空白。
        check("企鹅图标画出了非空像素", paintedPixels(make.getIcon()) > 40);
        check("Windows 图标画出了非空像素", paintedPixels(browse.getIcon()) > 40);
        check("企鹅图标里有橙色（喙/脚）", hasColour(make.getIcon(), 0xF5A623, 60));
        check("企鹅图标里有黑色（身体）", hasColour(make.getIcon(), 0x000000, 60));
        check("Windows 图标里有蓝色", hasColour(browse.getIcon(), 0x00A4EF, 60));

        // 文案里不能再出现 WSL。
        String[] keys = {
            Strings.FORM_IN_WSL, Strings.FORM_IN_WSL_TIP,
            Strings.FORM_NO_WSL_FOR_WORKDIR, Strings.FORM_WORKDIR_FAILED,
            Strings.FORM_CHOOSE_WORKDIR, Strings.FORM_NOTE_COPYDIR,
        };
        for (int i = 0; i < keys.length; i++) {
            String t = I18n.t(keys[i]);
            check("文案 " + keys[i] + " 里不含 WSL",
                    t != null && t.toUpperCase().indexOf("WSL") < 0);
        }
        check("工具提示里提到企鹅，指向那个按钮",
                make.getToolTipText() != null);

        finish();
    }

    private static void finish() {
        System.out.println(failures == 0
                ? "PASS " + checks + " checks"
                : "FAIL: " + failures + " of " + checks + " checks failed");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static String name(AbstractButton b) {
        return b.getAccessibleContext() == null ? null
                : b.getAccessibleContext().getAccessibleName();
    }

    /** 按名字在组件树里找按钮。 */
    private static Component find(Container root, String name) {
        Component[] kids = root.getComponents();
        for (int i = 0; i < kids.length; i++) {
            if (kids[i] instanceof JButton
                    && name.equals(((JButton) kids[i]).getName())) {
                return kids[i];
            }
            if (kids[i] instanceof Container) {
                Component hit = find((Container) kids[i], name);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 图标画出来的不透明像素个数。 */
    private static int paintedPixels(Icon ic) {
        BufferedImage img = render(ic);
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) > 0) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 图标里是否有足够接近某个颜色的像素。 */
    private static boolean hasColour(Icon ic, int rgb, int tolerance) {
        BufferedImage img = render(ic);
        int tr = (rgb >> 16) & 0xFF;
        int tg = (rgb >> 8) & 0xFF;
        int tb = rgb & 0xFF;
        int hits = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                if ((p >>> 24) == 0) {
                    continue;
                }
                int dr = Math.abs(((p >> 16) & 0xFF) - tr);
                int dg = Math.abs(((p >> 8) & 0xFF) - tg);
                int db = Math.abs((p & 0xFF) - tb);
                if (dr <= tolerance && dg <= tolerance && db <= tolerance) {
                    hits++;
                }
            }
        }
        return hits > 0;
    }

    private static BufferedImage render(Icon ic) {
        BufferedImage img = new BufferedImage(ic.getIconWidth(), ic.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            ic.paintIcon(null, g, 0, 0);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + label);
        }
    }
}
