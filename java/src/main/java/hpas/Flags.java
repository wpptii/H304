package hpas;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把「模拟开关」从 Spring 环境暴露成系统属性，供 {@link Support#flag} 统一读取。
 *
 * <p>这样以下三种写法都能生效（推荐第一种）：
 * <pre>
 *   mvn spring-boot:run -Dspring-boot.run.arguments="--SIMULATE_PAYMENT=failed"
 *   在 application.properties 里写 SIMULATE_PAYMENT=failed
 *   set SIMULATE_PAYMENT=failed   （环境变量）
 * </pre>
 */
@Component
public class Flags {

    private static final List<String> KEYS = List.of(
            "SIMULATE_PAYMENT",
            "SIMULATE_NOTIFY_FAIL",
            "SIMULATE_SCHEDULING_FAIL_TIMES",
            "SIMULATE_INCOMPLETE_REFERRAL");

    public Flags(Environment environment) {
        for (String key : KEYS) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank()) {
                System.setProperty(key, value);
                Support.log("flag", Support.vars(key, value));
            }
        }
    }
}
