import javax.swing.*; import java.awt.*;
public class PopupProbe {
  public static void main(String[] a) throws Exception {
    System.setProperty("bioenv.theme.readonly","1");
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    final String th = a.length > 0 ? a[0] : "personal";
    SwingUtilities.invokeAndWait(new Runnable(){ public void run(){
      bioenv.ui.UiTheme.set(th);
      JFrame fr = new JFrame("probe"); fr.setSize(420,220);
      JComboBox<String> cb = new JComboBox<>(new String[]{"diamond","blastp","mmseqs","blastn","diamond_ultra_sens"});
      cb.setBounds(20,20,340,28);
      JPanel p = new JPanel(null); p.add(cb); fr.setContentPane(p);
      fr.setVisible(true);
      bioenv.ui.UiTheme.decorate(fr.getContentPane());
      System.out.println("PROBE ui=" + cb.getUI().getClass().getName());
      cb.setPopupVisible(true);
    }});
    Thread.sleep(1200);
    System.exit(0);
  }
}
