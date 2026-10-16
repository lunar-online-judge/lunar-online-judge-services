package io.lunar.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审计留痕（尽力而为）：把考试级敏感动作写进 audit-service 账本，**失败只告警、绝不阻断主流程**——
 * 账本暂不可用时业务动作照常完成，缺口由各服务既有的结构化日志（{@code AUDIT event=...}）兜底。
 *
 * 令牌按原样转发（去掉可能重复的 Bearer 前缀）；traceId 作为 X-Request-Id 透传，账本里的链路不断。
 * 调用方只需注入本类并调 {@link #record}，无需自己 try/catch。
 */
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditServiceApi auditServiceApi;

    public AuditRecorder(AuditServiceApi auditServiceApi) {
        this.auditServiceApi = auditServiceApi;
    }

    /**
     * 写一条账本。
     *
     * @param action        动作名（如 STUDENT_ENROLLED），与查询过滤用的取值保持一致
     * @param targetType    目标类型（如 ENROLLMENT / STUDENT_ACCOUNT），可空
     * @param targetId      目标 ID，可空
     * @param contestId     所属竞赛，可空
     * @param detail        可读细节（不含密码等敏感材料），可空
     * @param traceId       调用链 ID（无则 null，audit-service 自行生成）
     * @param operatorToken 操作者令牌（服务间接口要求已认证）
     */
    public void record(String action, String targetType, String targetId, String contestId,
                       String detail, String traceId, String operatorToken) {
        if (operatorToken == null || operatorToken.isBlank()) {
            // 账本接口要求已认证：没有令牌就别发注定 401 的请求，缺口留给结构化日志
            log.warn("审计留痕跳过（缺少操作者令牌）action={} targetId={}", action, targetId);
            return;
        }
        try {
            auditServiceApi.record(bearer(operatorToken), traceId,
                    new AuditRecordCommand(action, targetType, targetId, contestId, detail));
        } catch (Exception exception) {
            log.warn("审计账本写入失败（不阻断主流程）action={} targetId={}：{}",
                    action, targetId, exception.getMessage());
        }
    }

    private static String bearer(String token) {
        String rawToken = token == null ? "" : token.replaceFirst("(?i)^Bearer\\s+", "");
        return "Bearer " + rawToken;
    }
}
