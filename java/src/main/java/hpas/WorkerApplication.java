package hpas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HPAS 作业 —— Camunda 8 Job Worker 启动类。
 *
 * <p>启动后 Spring 会扫描所有 {@code @JobWorker} 方法并自动订阅对应 topic，
 * 共 24 个 topic，覆盖 8 个流程模型里的全部 zeebe:taskDefinition type。
 */
@SpringBootApplication
public class WorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
