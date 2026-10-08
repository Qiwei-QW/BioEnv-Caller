import javax.swing.*;
public class Diag3 {
  public static void main(String[] a) throws Exception {
    System.setProperty("bioenv.theme.readonly","1");
    bioenv.ui.UiTheme.set("personal");
    JPanel p = (JPanel) Class.forName("bioenv.ui.MainPanel").getDeclaredConstructor().newInstance();
    for (int i=0;i<10;i++){ SwingUtilities.invokeAndWait(new Runnable(){ public void run(){} }); Thread.sleep(30); }
    System.out.println("切语言前 current=" + bioenv.ui.UiTheme.current() + " flat=" + bioenv.ui.UiTheme.flat());
    bioenv.i18n.I18n.toggle();
    for (int i=0;i<10;i++){ SwingUtilities.invokeAndWait(new Runnable(){ public void run(){} }); Thread.sleep(30); }
    System.out.println("切一次后 current=" + bioenv.ui.UiTheme.current() + " flat=" + bioenv.ui.UiTheme.flat());
    bioenv.i18n.I18n.toggle();
    for (int i=0;i<10;i++){ SwingUtilities.invokeAndWait(new Runnable(){ public void run(){} }); Thread.sleep(30); }
    System.out.println("切两次后 current=" + bioenv.ui.UiTheme.current() + " flat=" + bioenv.ui.UiTheme.flat());
    System.exit(0);
  }
}
