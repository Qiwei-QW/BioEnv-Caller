import java.awt.*; import java.awt.image.BufferedImage; import java.io.File;
import java.lang.reflect.*; import javax.imageio.ImageIO; import javax.swing.*;
public class WinShot {
  static void injectTool(JPanel panel) throws Exception {
    java.lang.reflect.Field fRoot=panel.getClass().getDeclaredField("treeRoot");
    java.lang.reflect.Field fModel=panel.getClass().getDeclaredField("treeModel");
    java.lang.reflect.Field fMap=panel.getClass().getDeclaredField("nodeToTool");
    java.lang.reflect.Field fTree=panel.getClass().getDeclaredField("toolTree");
    fRoot.setAccessible(true); fModel.setAccessible(true); fMap.setAccessible(true); fTree.setAccessible(true);
    javax.swing.tree.DefaultMutableTreeNode root=(javax.swing.tree.DefaultMutableTreeNode)fRoot.get(panel);
    javax.swing.tree.DefaultTreeModel model=(javax.swing.tree.DefaultTreeModel)fModel.get(panel);
    java.util.Map map=(java.util.Map)fMap.get(panel);
    javax.swing.JTree tree=(javax.swing.JTree)fTree.get(panel);
    bioenv.core.Tool t=new bioenv.core.Tool(); t.command="orthofinder"; t.envName="orthofinder_env";
    bioenv.core.Schema s=new bioenv.core.Schema(); s.parsed=true; s.rawHelp="x";
    String[][] defs={{"-t","<int>","Threads"},{"-a","<int>","Analyse"},{"-s","<file>","Tree"}};
    for(String[] dd:defs){ bioenv.core.Schema.Option o=new bioenv.core.Schema.Option();
      o.flag=dd[0]; o.arg=dd[1]; o.description=dd[2];
      o.kind=dd[1].equals("<int>")?bioenv.core.Schema.Option.Kind.NUMBER:bioenv.core.Schema.Option.Kind.TEXT;
      o.takesValue=true; s.options.add(o); }
    t.schema=s;
    root.removeAllChildren();
    javax.swing.tree.DefaultMutableTreeNode env=new javax.swing.tree.DefaultMutableTreeNode("orthofinder_env  (3)");
    javax.swing.tree.DefaultMutableTreeNode leaf=new javax.swing.tree.DefaultMutableTreeNode("orthofinder");
    env.add(leaf); root.add(env); model.reload(); map.clear(); map.put("orthofinder",t);
    tree.expandPath(new javax.swing.tree.TreePath(new Object[]{root,env}));
    tree.setSelectionPath(new javax.swing.tree.TreePath(new Object[]{root,env,leaf}));
  }
  static Object g(Object o,String f) throws Exception { Field x=o.getClass().getDeclaredField(f); x.setAccessible(true); return x.get(o); }
  public static void main(String[] a) throws Exception {
    System.setProperty("bioenv.theme.readonly","1");
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    final String theme=a[0], out=a[1];
    final JPanel[] host=new JPanel[1]; final Window[] win=new Window[1];
    SwingUtilities.invokeAndWait(new Runnable(){ public void run(){
      try {
        bioenv.ui.UiTheme.set(theme);
        JPanel p=(JPanel)Class.forName("bioenv.ui.MainPanel").getDeclaredConstructor().newInstance();
        injectTool(p);
        Object form=g(p,"formPanel");
        Method m=form.getClass().getDeclaredMethod("openOptionsWindow"); m.setAccessible(true); m.invoke(form);
        win[0]=(Window)g(form,"optionsWindow");
        host[0]=p;
      } catch(Throwable t){ t.printStackTrace(); }
    }});
    for(int i=0;i<14;i++){ SwingUtilities.invokeAndWait(new Runnable(){ public void run(){} }); Thread.sleep(40); }
    final BufferedImage[] img=new BufferedImage[1];
    SwingUtilities.invokeAndWait(new Runnable(){ public void run(){
      JFrame f=(JFrame)win[0];
      Container c=f.getContentPane();
      f.setSize(1400,900); f.validate();
      BufferedImage bi=new BufferedImage(1400,900,BufferedImage.TYPE_INT_RGB);
      Graphics2D gr=bi.createGraphics(); gr.setColor(Color.WHITE); gr.fillRect(0,0,1400,900);
      c.setSize(1400,900); c.doLayout(); c.paint(gr); gr.dispose(); img[0]=bi;
    }});
    ImageIO.write(img[0],"png",new File(out)); System.out.println("wrote "+out);
    System.exit(0);
  }
}
