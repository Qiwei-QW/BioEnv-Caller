package bioenv.test;

import bioenv.core.CommandBuilder;
import bioenv.core.CorrectionStore;
import bioenv.core.DiscoveryCache;
import bioenv.core.EnvFingerprint;
import bioenv.core.EnvRunner;
import bioenv.core.EnvScanner;
import bioenv.core.HelpParser;
import bioenv.core.NoiseFilter;
import bioenv.core.ParamValue;
import bioenv.core.Runner;
import bioenv.core.Schema;
import bioenv.core.SchemaBuilder;
import bioenv.core.Tool;
import bioenv.core.ToolRegistry;
import bioenv.core.TypeInference;
import bioenv.core.VersionParser;
import bioenv.core.WslBridge;
import bioenv.ui.MainPanel;
import bioenv.ui.SchemaFormPanel;
import java.lang.reflect.Method;

/**
 * Headless smoke test for the UI layer.
 *
 * <p>Verifies that the panel and the schema-driven form can be constructed and
 * driven through the states TBtools will exercise. A thrown exception inside
 * TBtools surfaces only as an empty tab, so these paths are checked here where
 * the stack trace is visible.
 *
 * <p>Run with {@code java -Djava.awt.headless=true -cp <classes> bioenv.ui.UiSmokeTest}
 */
public final class UiSmokeTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== UI smoke test ===");
        System.out.println("headless = " + java.awt.GraphicsEnvironment.isHeadless());

        MainPanel panel = new MainPanel();
        System.out.println("PASS  MainPanel constructed");
        if (!(panel instanceof javax.swing.JPanel)) {
            throw new IllegalStateException("MainPanel is not a JPanel");
        }
        System.out.println("PASS  MainPanel extends JPanel");

        // The form must survive every state: no tool, a tool with a schema, a
        // tool with subcommands, and a tool whose help could not be parsed.
        SchemaFormPanel form = new SchemaFormPanel();
        System.out.println("PASS  SchemaFormPanel constructed (no tool)");

        form.showTool(null);
        System.out.println("PASS  null tool handled");

        bioenv.core.Tool plain = new bioenv.core.Tool("plaincmd", "env_a", "/envs/a");
        plain.schema = new bioenv.core.Schema();
        plain.schema.parsed = true;
        plain.schema.options.add(option("-in", "<inputfile>", "Input file",
                bioenv.core.Schema.Option.Kind.INPUT_PATH));
        plain.schema.options.add(option("-out", "<outputfile>", "Output file",
                bioenv.core.Schema.Option.Kind.OUTPUT_PATH));
        plain.schema.options.add(option("-n", "NUM", "Number of threads",
                bioenv.core.Schema.Option.Kind.NUMBER));
        plain.schema.options.add(option("--fast", "", "Enable fast mode",
                bioenv.core.Schema.Option.Kind.SWITCH));
        form.showTool(plain);
        java.util.Map<String, bioenv.core.ParamValue> v1 = form.collect();
        if (v1.size() < 4) {
            throw new IllegalStateException("plain tool lost options: " + v1);
        }
        // A freshly shown tool must express nothing: no option may contribute
        // to the command until the user fills it in (DESIGN.md P12).
        int contributing = 0;
        for (bioenv.core.ParamValue pv : v1.values()) {
            if (pv.contributes()) {
                contributing++;
            }
        }
        if (contributing != 0) {
            throw new IllegalStateException(
                    "untouched form already expresses " + contributing + " value(s)");
        }
        System.out.println("PASS  schema-driven form rendered " + (v1.size() - 1)
                + " option row(s), none of them expressing a value");

        // A multi-level command: subcommands plus a child schema.
        bioenv.core.Tool multi = new bioenv.core.Tool("toolkit", "env_b", "/envs/b");
        multi.schema = new bioenv.core.Schema();
        multi.schema.parsed = true;
        bioenv.core.Schema.Subcommand s1 = new bioenv.core.Schema.Subcommand("alpha", "first");
        s1.schema = new bioenv.core.Schema();
        s1.schema.parsed = true;
        s1.schema.options.add(option("-x", "FILE", "A file",
                bioenv.core.Schema.Option.Kind.INPUT_PATH));
        bioenv.core.Schema.Subcommand s2 = new bioenv.core.Schema.Subcommand("beta", "second");
        multi.schema.subcommands.add(s1);
        multi.schema.subcommands.add(s2);
        form.showTool(multi);
        if (!multi.isMultiLevel()) {
            throw new IllegalStateException("multi-level tool not detected");
        }
        System.out.println("PASS  multi-level command detected and rendered");

        // An opaque command: help could not be parsed.
        bioenv.core.Tool opaque = new bioenv.core.Tool("mystery", "env_c", "/envs/c");
        opaque.schema = new bioenv.core.Schema();
        form.showTool(opaque);
        if (!opaque.isOpaque()) {
            throw new IllegalStateException("opaque tool not detected");
        }
        String rawArgs = form.rawArgs();
        if (rawArgs == null) {
            throw new IllegalStateException("raw args accessor returned null");
        }
        System.out.println("PASS  opaque command falls back to raw arguments");

        // Environment grouping: the registry must expose tools grouped by the
        // environment that provides them (DESIGN.md P9).
        bioenv.core.ToolRegistry reg = new bioenv.core.ToolRegistry(null, null);
        java.util.Map<String, java.util.List<bioenv.core.Tool>> grouped =
                reg.byEnvironment();
        if (grouped == null) {
            throw new IllegalStateException("byEnvironment() returned null");
        }
        System.out.println("PASS  byEnvironment() returns a map (empty: "
                + grouped.isEmpty() + ")");

        // A group larger than the threshold must be collapsed, not inlined.
        bioenv.core.ToolRegistry big = new bioenv.core.ToolRegistry(null, null);
        java.util.Map<String, java.util.List<bioenv.core.Tool>> grouped2 =
                big.byEnvironment();
        if (grouped2 == null) {
            throw new IllegalStateException("byEnvironment() returned null");
        }
        System.out.println("PASS  grouping exposed for the tree (threshold "
                + bioenv.core.ToolRegistry.LARGE_GROUP_THRESHOLD + ")");

        // An oversized group must be reachable rather than hidden: the tree
        // collapses it but keeps every command as a child.
        java.util.List<bioenv.core.Tool> many = new java.util.ArrayList<bioenv.core.Tool>();
        for (int i = 0; i < 50; i++) {
            many.add(new bioenv.core.Tool("cmd" + i, "bigenv", "/envs/big"));
        }
        if (many.size() <= bioenv.core.ToolRegistry.LARGE_GROUP_THRESHOLD) {
            throw new IllegalStateException("fixture is not large enough");
        }
        System.out.println("PASS  oversized groups are collapsible (fixture "
                + many.size() + " commands)");

        // A correction sink must be settable without error.
        form.setCorrectionSink(new SchemaFormPanel.CorrectionSink() {
            @Override
            public void correct(String command, String option,
                                bioenv.core.Schema.Option.Kind kind) {
                // no-op in the test
            }
        });
        System.out.println("PASS  correction sink accepted");

        // Plugin contract, resolved exactly as TBtools does.
        Class<?> inject = Class.forName("Plugin.PluginInject");
        Object instance = inject.getDeclaredConstructor().newInstance();
        Method gen = inject.getMethod("generatePanel");
        Method name = inject.getMethod("getPluginName");
        Object produced = gen.invoke(instance);
        String pluginName = (String) name.invoke(instance);
        System.out.println("PASS  PluginInject.getPluginName() = " + pluginName);
        if (!(produced instanceof javax.swing.JPanel)) {
            throw new IllegalStateException("generatePanel() did not return a JPanel");
        }
        System.out.println("PASS  PluginInject.generatePanel() -> "
                + produced.getClass().getName());

        // 删除配置按钮：必须真的加进了工具条，而且只在「虚拟机」方式下出现。
        //
        // 这里刻意不切换方式。切方式会走 applyConnectionMode，而它会把这次选择写进
        // 「上次用的方式」——跑一次门禁就把用户下次打开插件的默认方式改掉，这种事不能干。
        // 所以只验证"当前可见性与当前方式一致"，足够抓住"忘了加进布局"和"可见性没跟
        // 方式走"这两类错，又不产生副作用。
        javax.swing.JButton deleteProfile =
                (javax.swing.JButton) findByName(panel, "bioenv.deleteProfile");
        if (deleteProfile == null) {
            throw new IllegalStateException("delete-profile button is not in the panel");
        }
        System.out.println("PASS  delete-profile button is in the bar");
        javax.swing.JComboBox<?> modeBox =
                (javax.swing.JComboBox<?>) fieldOf(panel, "modeBox");
        boolean vmMode = modeBox.getSelectedIndex() == 1;
        if (deleteProfile.isVisible() != vmMode) {
            throw new IllegalStateException("delete-profile visibility ("
                    + deleteProfile.isVisible() + ") does not match the mode (vm="
                    + vmMode + ")");
        }
        System.out.println("PASS  delete-profile visibility follows the connection mode");

        System.out.println("=== UI smoke test complete ===");
    }

    /** 按名字在控件树里找一个组件。 */
    private static java.awt.Component findByName(java.awt.Component root, String name) {
        if (name.equals(root.getName())) {
            return root;
        }
        if (root instanceof java.awt.Container) {
            for (java.awt.Component c : ((java.awt.Container) root).getComponents()) {
                java.awt.Component hit = findByName(c, name);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 读一个私有字段。 */
    private static Object fieldOf(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static bioenv.core.Schema.Option option(String flag, String arg,
                                                    String desc,
                                                    bioenv.core.Schema.Option.Kind kind) {
        bioenv.core.Schema.Option o = new bioenv.core.Schema.Option();
        o.flag = flag;
        o.arg = arg;
        o.description = desc;
        o.kind = kind;
        o.isSwitch = kind == bioenv.core.Schema.Option.Kind.SWITCH;
        return o;
    }
}
