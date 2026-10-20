package hpas.db;

import java.util.Map;

/**
 * 安全读取 Camunda 流程变量的工具（永不抛异常）。
 *
 * <p>Worker 写库属于「附加副作用」：任何一个可选变量缺失、类型不对、或长度超限，
 * 都不能影响原来的流程行为（不能让作业变成 incident）。所以这里的每个方法
 * 都是 null 安全 + 解析失败返回默认值的写法。
 */
public final class Values {

    /** 数据库列宽上限的统一保护长度（细节字段按列宽自行截断）。 */
    public static final int MAX_DETAIL = 2000;

    private Values() {
    }

    /** 取字符串；空值 / 空白 / "null" 都返回 def。 */
    public static String str(Map<String, Object> vars, String key, String def) {
        if (vars == null) {
            return def;
        }
        Object o = vars.get(key);
        if (o == null) {
            return def;
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return def;
        }
        return s;
    }

    /** 依次尝试多个键名，返回第一个非空值（兼容不同流程用不同变量名的情况）。 */
    public static String first(Map<String, Object> vars, String def, String... keys) {
        for (String k : keys) {
            String v = str(vars, k, null);
            if (v != null) {
                return v;
            }
        }
        return def;
    }

    /** 读取嵌套上下文里的字段，例如 form=paymentForm, field=amount。 */
    public static String nested(Map<String, Object> vars, String contextKey, String fieldKey, String def) {
        if (vars == null) {
            return def;
        }
        Object ctx = vars.get(contextKey);
        if (ctx instanceof Map<?, ?> m) {
            Object o = m.get(fieldKey);
            if (o != null) {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                    return s;
                }
            }
        }
        return def;
    }

    /** 读数字（Integer），解析失败返回 null。 */
    public static Integer integer(Map<String, Object> vars, String key) {
        Object o = vars == null ? null : vars.get(key);
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 读布尔值；Boolean / "true" / "1" / "yes" 都算 true。 */
    public static Boolean bool(Map<String, Object> vars, String key) {
        Object o = vars == null ? null : vars.get(key);
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(o).trim().toLowerCase();
        if (s.isEmpty() || "null".equals(s)) {
            return null;
        }
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s)) {
            return Boolean.TRUE;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "n".equals(s)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 截断到列宽，避免因为超长文本导致 INSERT 失败。 */
    public static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** {@link #cut(String, int)} 的默认列宽版本。 */
    public static String cut(String s) {
        return cut(s, MAX_DETAIL);
    }
}
