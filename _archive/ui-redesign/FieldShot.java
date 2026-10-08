import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.*;

/** 探针：装饰过的 JTextField 到底是谁在画底、谁在画线。 */
public class FieldShot {
    public static void main(String[] a) throws Exception {
        System.setProperty("bioenv.theme.readonly", "1");
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        final String theme = a[0];
        final String out = a[1];
        final BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override public void run() {
                bioenv.ui.UiTheme.set(theme);
                JPanel host = new JPanel(null);
                host.setBackground(new Color(0x222233));
                JTextField f = new JTextField("abcde");
                f.setBounds(20, 20, 200, 30);
                host.add(f);
                host.setSize(260, 80);
                bioenv.ui.UiTheme.decorate(host);
                host.validate();
                host.doLayout();
                System.out.println("theme      = " + theme);
                System.out.println("opaque     = " + f.isOpaque());
                System.out.println("ui         = " + f.getUI().getClass().getName());
                System.out.println("border     = " + f.getBorder().getClass().getName());
                System.out.println("background = " + String.format("%06X", f.getBackground().getRGB() & 0xFFFFFF));
                BufferedImage bi = new BufferedImage(260, 80, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = bi.createGraphics();
                g.setColor(new Color(0x222233));
                g.fillRect(0, 0, 260, 80);
                host.paint(g);
                g.dispose();
                img[0] = bi;
            }
        });
        ImageIO.write(img[0], "png", new File(out));
        BufferedImage im = img[0];
        System.out.println("--- 左上角 19..26 ---");
        for (int y = 19; y <= 26; y++) {
            StringBuilder sb = new StringBuilder("y=" + y + " ");
            for (int x = 19; x <= 26; x++) {
                sb.append(String.format("%06X ", im.getRGB(x, y) & 0xFFFFFF));
            }
            System.out.println(sb);
        }
    }
}
