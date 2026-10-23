package bioenv.ai;

import bioenv.i18n.I18n;
import bioenv.i18n.Strings;

/**
 * 自定义模型的接口地址：校验与归一化。
 *
 * <h2>为什么单独一个类</h2>
 *
 * <p>地址填错时（少了 {@code https://}、末尾多抄了一段
 * {@code /chat/completions}），请求会直接失败，而错误来自服务端，说的是
 * "404 / 返回的不是 JSON"——跟用户填的那一行字完全看不出关系。所以要把问题
 * 在保存那一刻说清楚，而不是留到发请求时才炸。
 *
 * <h2>为什么不自动补协议</h2>
 *
 * <p>{@code api.deepseek.com/v1} 看起来像"只是少了 https://"，但补成
 * {@code https://} 还是 {@code http://} 是用户的决定：猜错了等于把 API key
 * 明文发到另一个协议上。所以缺协议一律判为不合法，由用户自己写全。
 *
 * <p>全是纯函数，能无头测（见 {@code bioenv.test.EndpointUrlTest}）。
 */
public final class EndpointUrl {

    /** OpenAI 兼容接口的完整端点后缀；用户照文档抄时经常把整条都贴进来。 */
    private static final String COMPLETIONS_SUFFIX = "/chat/completions";

    private EndpointUrl() {
    }

    /**
     * 归一化：去掉首尾空白、末尾多余的斜杠，以及末尾的
     * {@code /chat/completions}。
     *
     * <p>后一条不是洁癖：{@code AiModelStore} 里存的是 base URL，页面会把
     * {@code /chat/completions} 再拼一次。用户贴了完整端点而不处理的话，实际请求
     * 会打到 {@code .../chat/completions/chat/completions}，服务端只会回 404。
     *
     * @return 归一化后的地址；不合法时返回 {@code null}（原因见 {@link #error(String)}）
     */
    public static String normalize(String raw) {
        if (error(raw) != null) {
            return null;
        }
        String s = stripTrailingSlashes(raw.trim());
        // 先剥末尾斜杠再认后缀，这样 ".../chat/completions/" 也能认出来。
        if (s.length() > COMPLETIONS_SUFFIX.length()
                && s.endsWith(COMPLETIONS_SUFFIX)) {
            s = stripTrailingSlashes(
                    s.substring(0, s.length() - COMPLETIONS_SUFFIX.length()));
        }
        return s;
    }

    /**
     * 这个地址哪里不对。
     *
     * <p>规则只有三条，都是"一看就知道"的：协议必须是 {@code http://} 或
     * {@code https://}；中间不能有空白；主机名不能为空。更细的 URL 语法不在这里
     * 校验——那种错误服务端会给出比我们更准的话，在这里猜反而可能把合法的私有地址
     * 判死。
     *
     * @return 中文说明；地址合法时返回 {@code null}
     */
    public static String error(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return I18n.t(Strings.AI_URL_EMPTY);
        }
        String s = raw.trim();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // isWhitespace 不认不换行空格（\u00A0），从网页上复制地址时它很常见。
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                return I18n.t(Strings.AI_URL_SPACE);
            }
        }
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        final int schemeLength;
        if (lower.startsWith("https://")) {
            schemeLength = "https://".length();
        } else if (lower.startsWith("http://")) {
            schemeLength = "http://".length();
        } else if (lower.indexOf("://") >= 0) {
            return I18n.t(Strings.AI_URL_BAD_SCHEME);
        } else {
            return I18n.t(Strings.AI_URL_MISSING_SCHEME);
        }
        String rest = s.substring(schemeLength);
        // 主机名到路径/查询/片段之前为止；带端口（host:8080）也照收。
        int hostEnd = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                hostEnd = i;
                break;
            }
        }
        if (rest.substring(0, hostEnd).isEmpty()) {
            return I18n.t(Strings.AI_URL_NO_HOST);
        }
        return null;
    }

    /** 去掉末尾所有斜杠；只处理结尾，不碰协议里的 {@code //}。 */
    private static String stripTrailingSlashes(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }
}
