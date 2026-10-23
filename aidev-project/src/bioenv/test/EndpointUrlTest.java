package bioenv.test;

import bioenv.ai.EndpointUrl;

/**
 * 检查自定义模型接口地址的校验与归一化。
 *
 * <p>这里钉住的都是"用户真会填错"的形状：少协议、协议写错、只有协议没有主机、
 * 中间混进空格、以及把文档里的完整端点（{@code .../chat/completions}）整条贴进来。
 * 这些错误以前要等到发请求才以"404"的形式出现，跟用户填的那一行对不上号。
 *
 * <p>纯 main() 测试，无头可跑：
 * {@code java -Djava.awt.headless=true -cp <classes> bioenv.test.EndpointUrlTest}
 */
public final class EndpointUrlTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        line("=== 接口地址的校验与归一化 ===");
        line("");

        line("[合法的地址：只做归一化，不改内容]");
        checkEquals("去掉首尾空白和末尾斜杠",
                "https://api.deepseek.com/v1",
                EndpointUrl.normalize(" https://api.deepseek.com/v1/ "));
        checkEquals("完整端点回退到 base URL",
                "https://x/v1",
                EndpointUrl.normalize("https://x/v1/chat/completions"));
        checkEquals("完整端点带末尾斜杠也能回退",
                "https://x/v1",
                EndpointUrl.normalize("https://x/v1/chat/completions/"));
        checkEquals("只有末尾斜杠也去掉",
                "https://api.deepseek.com",
                EndpointUrl.normalize("https://api.deepseek.com/"));
        checkEquals("http 也是合法协议",
                "http://127.0.0.1:11434/v1",
                EndpointUrl.normalize("http://127.0.0.1:11434/v1"));
        checkEquals("没有路径也合法",
                "https://api.deepseek.com",
                EndpointUrl.normalize("https://api.deepseek.com"));
        line("");

        line("[不合法的地址：normalize 返回 null，error 给出中文原因]");
        checkRejected("缺协议", "api.deepseek.com/v1");
        checkRejected("协议不是 http(s)", "ftp://x");
        checkRejected("只有协议没有主机", "https://");
        checkRejected("地址中间有空格", "https://a b");
        checkRejected("空串", "");
        line("");

        line("[error 的话要能让用户知道下一步怎么做]");
        String missingScheme = EndpointUrl.error("api.deepseek.com/v1");
        check("缺协议的提示提到 http://", contains(missingScheme, "http://"));
        String badScheme = EndpointUrl.error("ftp://x");
        check("错协议的提示说明了只支持哪两种", contains(badScheme, "https://"));
        String noHost = EndpointUrl.error("https://");
        check("没有主机的提示给出示例", contains(noHost, "api.deepseek.com"));
        check("合法地址没有错误说明", EndpointUrl.error("https://api.deepseek.com/v1") == null);
        check("空地址也有说明，不是静默通过", EndpointUrl.error("") != null);
        line("");

        line(pass + " passed, " + fail + " failed");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static boolean contains(String s, String part) {
        return s != null && s.contains(part);
    }

    private static void checkEquals(String label, String want, String got) {
        check(label + "（期望 " + want + "，实际 " + got + "）",
                want.equals(got));
    }

    private static void checkRejected(String label, String raw) {
        check(label + " 判为不合法", EndpointUrl.normalize(raw) == null
                && EndpointUrl.error(raw) != null);
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            line("  PASS  " + label);
        } else {
            fail++;
            line("  FAIL  " + label);
        }
    }

    private static void line(String s) {
        System.out.println(s);
    }
}
