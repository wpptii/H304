package hpas;

import io.camunda.client.CamundaClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Job Worker 共用的工具方法（不依赖 Spring，便于单元测试与复用）。
 */
public final class Support {

    private Support() {
    }

    /** 通知方式 -> 实际渠道（BR-11）。 */
    public static final Map<String, String> CHANNEL = Map.of(
            "paper", "纸质信件（邮寄）",
            "digital", "数字渠道（短信 / 邮件 / 患者门户）",
            "translation", "翻译后通知",
            "representative", "授权代表代收");

    /** 可含 null 值的 Map 构造（Map.of 不接受 null）。 */
    public static Map<String, Object> vars(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public static String str(Map<String, Object> v, String key, String def) {
        Object o = v.get(key);
        if (o == null) {
            return def;
        }
        String s = String.valueOf(o);
        return s.isBlank() ? def : s;
    }

    /** 读取嵌套上下文里的字段，例如 form=supplementForm, field=providedItems。 */
    public static String nestedStr(Map<String, Object> v, String contextKey, String fieldKey, String def) {
        Object ctx = v.get(contextKey);
        if (ctx instanceof Map<?, ?> m) {
            Object o = m.get(fieldKey);
            if (o != null) {
                String s = String.valueOf(o);
                return s.isBlank() ? def : s;
            }
        }
        return def;
    }

    public static int num(Map<String, Object> v, String key) {
        Object o = v.get(key);
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 读取模拟开关：先看系统属性（-Dkey=value 或 --key=value），再看环境变量。 */
    public static String flag(String name, String def) {
        String v = System.getProperty(name);
        if (v == null || v.isBlank()) {
            v = System.getenv(name);
        }
        return (v == null || v.isBlank()) ? def : v;
    }

    public static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        try {
            return LocalDate.parse(t);
        } catch (Exception ignore) {
            // 继续尝试其它格式
        }
        try {
            return OffsetDateTime.parse(t).toLocalDate();
        } catch (Exception ignore) {
            // 继续尝试其它格式
        }
        try {
            return Instant.parse(t).atZone(ZoneId.systemDefault()).toLocalDate();
        } catch (Exception ignore) {
            return null;
        }
    }

    /** 假的外部系统调用；接真实系统时把这里换成 HTTP / SDK 调用。 */
    public static void callExternalSystem(String system, Map<String, Object> payload) {
        log(system, payload);
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 投递 BPMN 消息（跨 Pool 通信）。
     *
     * <p>与范例一致：messageName 对应捕获事件的 Message Name，
     * correlationKey 必须等于该消息 zeebe:subscription correlationKey 求值结果。
     */
    public static void publish(CamundaClient camundaClient, String messageName, String correlationKey,
                               Map<String, Object> variables) {
        log("publish:" + messageName, vars("correlationKey", correlationKey, "variables", variables));
        try {
            camundaClient.newPublishMessageCommand()
                    .messageName(messageName)
                    .correlationKey(correlationKey == null ? "" : correlationKey)
                    .variables(variables == null ? Map.of() : variables)
                    .send()
                    .join();
        } catch (Exception e) {
            // 该消息没有订阅者时投递失败属正常（例如只声明未使用的消息），记录但不阻断流程
            log("publish-skipped:" + messageName, vars("reason", String.valueOf(e.getMessage())));
        }
    }

    public static void log(String tag, Object data) {
        System.out.println("[" + Instant.now() + "] [" + tag + "] " + data);
        System.out.flush();
    }

    public static String stamp() {
        return Instant.now().toString();
    }
}
