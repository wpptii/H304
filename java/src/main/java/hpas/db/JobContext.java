package hpas.db;

import io.camunda.client.api.response.ActivatedJob;

/**
 * 写库时需要的「流程坐标」（从 {@link ActivatedJob} 提取，避免持久层直接依赖客户端 API）。
 *
 * @param processInstanceKey   流程实例键
 * @param processDefinitionId  BPMN process id（processDefinitionId）
 * @param processDefinitionKey 流程定义键
 * @param elementId            当前服务任务元素 id
 * @param jobType              作业类型（31 个 job type 之一）
 * @param jobKey               作业键
 * @param worker               Worker 名称（{@code @JobWorker} 未命名时为 service）
 */
public record JobContext(
        Long processInstanceKey,
        String processDefinitionId,
        Long processDefinitionKey,
        String elementId,
        String jobType,
        Long jobKey,
        String worker) {

    /** 从 ActivatedJob 提取；任何字段异常都不抛出。 */
    public static JobContext of(ActivatedJob job, String workerName) {
        if (job == null) {
            return new JobContext(null, null, null, null, null, null, workerName);
        }
        Long pi = null;
        Long pd = null;
        Long jk = null;
        String pdId = null;
        String el = null;
        String type = null;
        try {
            pi = job.getProcessInstanceKey();
            pd = job.getProcessDefinitionKey();
            jk = job.getKey();
            pdId = Values.cut(job.getBpmnProcessId(), 200);
            el = Values.cut(job.getElementId(), 128);
            type = Values.cut(job.getType(), 128);
        } catch (RuntimeException ignore) {
            // 极端情况下 SDK 抛异常也不能影响 Worker
        }
        return new JobContext(pi, pdId, pd, el, type, jk,
                Values.cut(workerName == null ? "hpas-workers" : workerName, 128));
    }

    /** 审计用的简短描述。 */
    public String describe() {
        return "pi=" + processInstanceKey + " job=" + jobKey + " type=" + jobType
                + " element=" + elementId + " pd=" + processDefinitionId;
    }
}
