import bioenv.core.*;
public class KindProbe {
  public static void main(String[] a) throws Exception {
    String help = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(a[0])), "UTF-8");
    Schema s = HelpParser.parse(help, "orthofinder");
    System.out.println("参数总数 = " + s.options.size());
    System.out.println("---- 推断为 SWITCH（会显示成勾选框）的 ----");
    for (Schema.Option o : s.options) {
      if (o.kind == Schema.Option.Kind.SWITCH) {
        String d = o.description == null ? "" : o.description;
        if (d.length() > 46) d = d.substring(0, 46);
        System.out.printf("  flag=%-10s arg=%-10s takesValue=%-5s isSwitch=%-5s | %s%n",
            o.flag, o.arg == null ? "-" : o.arg, o.takesValue, o.isSwitch, d);
      }
    }
    System.out.println("---- 其余（前 12 个）----");
    int n = 0;
    for (Schema.Option o : s.options) {
      if (o.kind != Schema.Option.Kind.SWITCH && n++ < 12) {
        String d = o.description == null ? "" : o.description;
        if (d.length() > 40) d = d.substring(0, 40);
        System.out.printf("  flag=%-10s arg=%-10s kind=%-12s | %s%n", o.flag,
            o.arg == null ? "-" : o.arg, o.kind, d);
      }
    }
  }
}
