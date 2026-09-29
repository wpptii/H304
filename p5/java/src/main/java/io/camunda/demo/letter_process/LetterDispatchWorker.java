package io.camunda.demo.letter_process;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * External Worker for the "letter-dispatch" Service Task.
 *
 * <p>流程：分发信函 —— 把批准后的信函通过通信服务分发（邮寄 / 数字渠道），并把「是否在
 * 7 天内完成」的判定结果写回流程变量 {@code dispatchedWithin7Days}（对应 BR-27 的时间目标）。
 *
 * <p>判定规则：
 * <ul>
 *   <li>时间起点 = {@code intakeDate}（治疗 / 预约完成日期，ISO 日期，必填）</li>
 *   <li>时间终点 = {@code draftCompletedAt}（ISO 日期时间，可空；留空则用当前时间计算）</li>
 *   <li>终点与起点相差 ≤ {@code deadlineDays} 天 => 按时（true），否则逾期（false）</li>
 * </ul>
 */
@Component
public class LetterDispatchWorker {

    private final static Logger LOG = LoggerFactory.getLogger(LetterDispatchWorker.class);

    /** 默认期限天数，若任务头未提供 deadlineDays 则使用。 */
    private static final int DEFAULT_DEADLINE_DAYS = 7;

    @JobWorker(type = "letter-dispatch")
    public Map<String, Object> dispatchLetter(final ActivatedJob job) {
        LOG.info("Processing letter-dispatch job: {}", job.getKey());

        Map<String, Object> variables = job.getVariablesAsMap();

        String caseId = String.valueOf(variables.getOrDefault("caseId", ""));
        String intakeDateRaw = variables.get("intakeDate") == null
                ? null
                : String.valueOf(variables.get("intakeDate"));
        String draftCompletedAtRaw = variables.get("draftCompletedAt") == null
                ? null
                : String.valueOf(variables.get("draftCompletedAt"));

        // 期限天数：优先读任务头 header，未提供则回退到默认值
        int deadlineDays = DEFAULT_DEADLINE_DAYS;
        String header = job.getCustomHeaders().get("deadlineDays");
        if (header != null) {
            try {
                deadlineDays = Integer.parseInt(header.trim());
            } catch (NumberFormatException e) {
                LOG.warn("Invalid deadlineDays header '{}', falling back to {}",
                        header, DEFAULT_DEADLINE_DAYS);
            }
        }

        boolean dispatchedWithin7Days = computeWithinDeadline(intakeDateRaw, draftCompletedAtRaw, deadlineDays);

        LOG.info("letter-dispatch job completed for case {}: dispatchedWithin7Days={}",
                caseId, dispatchedWithin7Days);

        return Map.of("dispatchedWithin7Days", dispatchedWithin7Days);
    }

    /**
     * 计算从 intakeDate 到 dispatch 是否在 deadlineDays 天内完成。
     */
    private boolean computeWithinDeadline(String intakeDateRaw, String draftCompletedAtRaw, int deadlineDays) {
        if (intakeDateRaw == null || intakeDateRaw.isBlank()) {
            LOG.warn("intakeDate is missing, assuming dispatch is overdue");
            return false;
        }

        LocalDate intakeDate;
        try {
            intakeDate = LocalDate.parse(intakeDateRaw.trim());
        } catch (Exception e) {
            LOG.warn("Cannot parse intakeDate '{}', assuming dispatch is overdue", intakeDateRaw);
            return false;
        }

        Instant dispatchTime;
        if (draftCompletedAtRaw == null || draftCompletedAtRaw.isBlank()) {
            // 表单说明：留空则由分发 Worker 用当前时间计算
            dispatchTime = Instant.now();
        } else {
            try {
                dispatchTime = Instant.parse(draftCompletedAtRaw.trim());
            } catch (Exception e) {
                LOG.warn("Cannot parse draftCompletedAt '{}', using current time", draftCompletedAtRaw);
                dispatchTime = Instant.now();
            }
        }

        LocalDate dispatchDate = dispatchTime.atZone(ZoneId.systemDefault()).toLocalDate();
        long daysBetween = ChronoUnit.DAYS.between(intakeDate, dispatchDate);
        return daysBetween <= deadlineDays;
    }
}
