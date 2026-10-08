import java.awt.*; import java.awt.image.BufferedImage; import java.io.File;
import javax.imageio.ImageIO; import javax.swing.*;
public class ComboShot {
  static String hex(Color c){ return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }
  static void runs(BufferedImage im, int fixed, boolean row, String label){
    StringBuilder sb=new StringBuilder(); Color prev=null; int start=0;
    int n = row ? im.getWidth() : im.getHeight();
    for(int i=0;i<n;i++){
      Color c = row ? new Color(im.getRGB(i,fixed)) : new Color(im.getRGB(fixed,i));
      if(prev==null){ prev=c; start=i; }
      else if(!c.equals(prev)){ sb.append(String.format("%s[%d,%d) ", hex(prev), start, i)); prev=c; start=i; }
    }
    sb.append(String.format("%s[%d,%d)", hex(prev), start, n));
    System.out.println(label+" "+sb);
  }
  public static void main(String[] a) throws Exception {
    System.setProperty("bioenv.theme.readonly","1");
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    final String theme=a[0], out=a[1];
    final BufferedImage[] img=new BufferedImage[1];
    SwingUtilities.invokeAndWait(new Runnable(){ public void run(){
      bioenv.ui.UiTheme.set(theme);
      JPanel host=new JPanel(null);
      host.setBackground(new Color(0x222233));
      JComboBox<String> box=new JComboBox<>(new String[]{"","fast","slow","auto"});
      box.setEditable(true);                 // ← 关键：可编辑，内部有编辑框
      box.setBounds(20,20,200,28);
      host.add(box);
      box.setSelectedItem("WSL");
      host.setSize(260,80);
      bioenv.ui.UiTheme.decorate(host);
      host.validate(); host.doLayout();
      BufferedImage bi=new BufferedImage(260,80,BufferedImage.TYPE_INT_RGB);
      Graphics2D g=bi.createGraphics(); g.setColor(new Color(0x222233)); g.fillRect(0,0,260,80);
      host.paint(g); g.dispose(); img[0]=bi;
    }});
    ImageIO.write(img[0],"png",new File(out));
    BufferedImage im=img[0];
    System.out.println("=== "+theme+" 组合框 20,20,200,30 ===");
    runs(im, 35, true,  "横向 y=35 :");
    runs(im, 60, false, "纵向 x=60 :");
    runs(im, 25, false, "纵向 x=25 (左边框处):");
  }
}
