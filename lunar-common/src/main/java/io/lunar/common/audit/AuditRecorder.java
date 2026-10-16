package io.lunar.common.audit;

import io.lunar.common.identity.ServiceTokenProvider;
import io.lunar.common.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 审计留痕（尽力而为）：把考试级敏感动作写进 audit-service 账本，**失败只告警、绝不阻断主流程**——
 * 账本暂不可用时业务动作照常完成，缺口由各服务既有的结构化日志（{@code AUDIT event=...}）兜底。
 *
 * 令牌来源：优先转发操作者令牌（账本要记真实 actor）；没有用户上下文时（定时任务、MQ 消费者）
 * 回落用 {@link ServiceTokenProvider} 换来的服务身份令牌（账本记 actor=服务名、role=SERVICE）。
 * 两者都拿不到就跳过并告警，同样不阻断业务。
 *
 * 调用方只需注入本类并调 {@link #record}，无需自己 try/catch。
 */
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditServiceApi auditServiceApi;
    private final ServiceTokenProvider serviceTokenProvider;

    /** 只转发用户令牌的调用方（auth / anti-cheat）用这个构造器。 */
    public AuditRecorder(AuditServiceApi auditServiceApi) {
        this(auditServiceApi, null);
    }

    /** 有"无用户上下文"写入点的调用方（contest 的定时任务、submission 的 MQ 消费者）额外提供服务身份。 */
    public AuditRecorder(AuditServiceApi auditServiceApi, ServiceTokenProvider serviceTokenProvider) {
        this.auditServiceApi = auditServiceApi;
        this.serviceTokenProvider = serviceTokenProvider;
    }

    /**
     * 写一条账本。
     *
     * @param action        动作名（如 STUDENT_ENROLLED），与查询过滤用的取值保持一致
     * @param targetType    目标类型（如 ENROLLMENT / STUDENT_ACCOUNT），可空
     * @param targetId      目标 ID，可空
     * @param contestId     所属竞赛，可空（uuid 列，务必传 null 而不是空串）
     * @param detail        可读细节（不含密码等敏感材料），可空
     * @param traceId       调用链 ID（无则 null，audit-service 自行生成）
     * @param operatorToken 操作者令牌；为空时回落到服务身份令牌
     */
    public void record(String action, String targetType, String targetId, String contestId,
                       String detail, String traceId, String operatorToken) {
        String token = resolveToken(action, targetId, operatorToken);
        if (token == null) {
            return;
        }
        try {
            auditServiceApi.record(bearer(token), resolveTraceId(traceId),
                    new AuditRecordCommand(action, targetType, targetId, contestId, detail));
        } catch (Exception exception) {
            log.warn("审计账本写入失败（不阻断主流程）action={} targetId={}：{}",
                    action, targetId, exception.getMessage());
        }
    }

    /**
     * 追踪 ID：显式传入优先；没传时取当前请求的 X-Request-Id（{@link RequestIdFilter} 写入的 MDC），
     * 避免"调用方传 null 导致账本链路断开"（BACKEND_IMPROVEMENT_PLAN BE-07）。
     * 定时任务/MQ 线程没有请求上下文，此时仍为 null，由 audit-service 自行生成。
     */
    private static String resolveTraceId(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        return MDC.get(RequestIdFilter.MDC_KEY);
    }

    /** 优先用户令牌；无用户上下文时用服务身份令牌；都拿不到返回 null（调用方跳过并告警）。 */
    private String resolveToken(String action, String targetId, String operatorToken) {
        if (operatorToken != null && !operatorToken.isBlank()) {
            return operatorToken;
        }
        if (serviceTokenProvider == null) {
            log.warn("审计留痕跳过（无操作者令牌且未配置服务身份）action={} targetId={}", action, targetId);
            return null;
        }
        try {
            return serviceTokenProvider.token();
        } catch (Exception exception) {
            log.warn("审计留痕跳过（服务令牌不可用）action={} targetId={}：{}",
                    action, targetId, exception.getMessage());
            return null;
        }
    }

    private static String bearer(String token) {
        String rawToken = token == null ? "" : token.replaceFirst("(?i)^Bearer\\s+", "");
        return "Bearer " + rawToken;
    }
}
