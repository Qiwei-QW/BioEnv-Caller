package vmadapt.ui;

/**
 * 文案解析：把「当前 LangProvider + 内置双语表」合成一次可用的查询。
 *
 * <p>存在的理由是让 {@link ConnectionPanel} 里每一处取值都只有一种写法：
 * {@code text(MY_KEY)} 或 {@code text(MY_KEY, arg)}，不必在每个调用点重复
 * 「provider 是不是 null」这种判断。判断只在这里做一次。
 *
 * <h2>provider 为 null 时的行为</h2>
 *
 * <p>回退到 {@link UiText#ZH 中文}，与产品默认语言一致。这是刻意的默认值：
 * vmadapt 既能脱离 TBtools 单独运行（{@code 虚拟机适配\build.ps1} 就是这么
 * 编译和自检的），也能被插件托管，两条路径上的默认语言必须相同——否则
 * 「独立跑 MainFrame」会看到全英文界面，与用户在插件里看到的中文对不上。
 *
 * <h2>占位符</h2>
 *
 * <p>用 {@code {0}}、{@code {1}} 形式，不用 {@code MessageFormat}——后者把单引号
 * 当转义符，而本项目文案里单引号很多（{@code VM's}、{@code "Saved profiles"}），
 * 会造成占位符被吞掉且不报错。
 */
final class Text {

    /** 当前语言提供者，可为 null。 */
    private final LangProvider provider;

    Text(LangProvider provider) {
        this.provider = provider;
    }

    /**
     * 当前语言代码；没有 provider 时为 {@link UiText#ZH}。
     *
     * <p>默认中文，与产品默认语言一致：这个模块既能被插件托管，也能独立运行，
     * 两条路径上的默认语言必须相同，否则「独立跑 MainFrame」会看到全英文界面，
     * 与用户在插件里看到的中文对不上。
     *
     * <p>provider 返回 null 或抛异常时也按默认语言处理：界面文案取不到不该让
     * 整个面板崩掉。
     */
    String langCode() {
        if (provider == null) {
            return UiText.ZH;
        }
        try {
            String code = provider.langCode();
            return code == null ? UiText.ZH : code;
        } catch (RuntimeException e) {
            return UiText.ZH;
        }
    }

    /**
     * 取文案。
     *
     * <p>先问 provider（宿主可能自己维护了一份文案，例如与插件其它界面共用
     * 同一套键时），provider 没有或返回 null 时回退到内置双语表。
     *
     * @param key 键名
     * @return 文案；永不返回 null（provider 返回 null/空串时回退内置表）
     */
    String get(String key) {
        if (provider != null) {
            try {
                String s = provider.t(key);
                if (s != null && !s.isEmpty()) {
                    return s;
                }
            } catch (RuntimeException e) {
                // provider 出错就退回内置表，界面照常显示。
            }
        }
        return UiText.get(key, langCode());
    }

    /** 取文案并替换 {@code {0}}、{@code {1}} 等占位符。 */
    String get(String key, Object... args) {
        return substitute(get(key), args);
    }

    /** 是否中文。 */
    boolean isChinese() {
        return UiText.ZH.equals(langCode());
    }

    /**
     * 简单的占位符替换。
     *
     * <p>越界的占位符原样保留（而不是替换成空），这样调用点少传参数时
     * 界面上会出现 {@code {3}} 这种明显异常，一眼能看出问题所在。
     */
    static String substitute(String template, Object... args) {
        if (template == null) {
            return "";
        }
        if (args == null || args.length == 0 || template.indexOf('{') < 0) {
            return template;
        }
        StringBuilder sb = new StringBuilder(template.length() + 32);
        int i = 0;
        int n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c != '{') {
                sb.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            while (j < n && Character.isDigit(template.charAt(j))) {
                j++;
            }
            boolean wellFormed = (j > i + 1) && (j < n) && (template.charAt(j) == '}');
            if (!wellFormed) {
                sb.append(c);
                i++;
                continue;
            }
            int index;
            try {
                index = Integer.parseInt(template.substring(i + 1, j));
            } catch (NumberFormatException e) {
                sb.append(template, i, j + 1);
                i = j + 1;
                continue;
            }
            if (index >= 0 && index < args.length) {
                Object a = args[index];
                sb.append(a == null ? "" : a.toString());
            } else {
                sb.append(template, i, j + 1);
            }
            i = j + 1;
        }
        return sb.toString();
    }
}
