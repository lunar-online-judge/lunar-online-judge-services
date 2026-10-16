package io.lunar.analytics.mq;

import com.fasterxml.jackson.databind.JsonNode;
import io.lunar.common.event.EventMessages;

/**
 * 事件字段的读取与规格化。
 *
 * 为什么消费端必须自己兜住长度：载荷来自别的服务，本服务的列宽（event_type 64、severity 32 之类）
 * 是本地约束；超长值会以 22001 直接写失败，而"数据本身错"这种失败重投一百次也不会变好，
 * 只会把消费组卡在同一个 offset 上。截断保留可读前缀，比整条丢弃更接近事实。
 */
public final class EventText {

    /** verdict 列宽：与 submission_facts.verdict / risk_event_facts.review_status 的 varchar(32) 对齐。 */
    public static final int MAX_VERDICT_LENGTH = 32;

    /** language 列宽：与 submission_facts.language 的 varchar(32) 对齐。 */
    public static final int MAX_LANGUAGE_LENGTH = 32;

    /** event_type 列宽：与 risk_event_facts.event_type 的 varchar(64) 对齐。 */
    public static final int MAX_EVENT_TYPE_LENGTH = 64;

    /** severity 列宽：与 risk_event_facts.severity 的 varchar(32) 对齐。 */
    public static final int MAX_SEVERITY_LENGTH = 32;

    /** AI 结论摘要列宽：与 ai_finding_facts.summary 的 varchar(1000) 对齐。 */
    public static final int MAX_SUMMARY_LENGTH = 1000;

    /** 模型名列宽：与 ai_finding_facts.model 的 varchar(128) 对齐。 */
    public static final int MAX_MODEL_LENGTH = 128;

    /** 提示词版本列宽：与 ai_finding_facts.prompt_version 的 varchar(64) 对齐。 */
    public static final int MAX_PROMPT_VERSION_LENGTH = 64;

    private EventText() {
    }

    /**
     * 读取字符串字段；缺失、null 或纯空白都归为"没有"（复用 EventMessages 的判定，避免两处口径漂移）。
     * 无论字段是否存在，{@code payload} 都不能为 null——判断调用方在取 payload 时已经确认过。
     */
    public static String read(JsonNode payload, String fieldName) {
        return EventMessages.textOf(payload, fieldName);
    }

    /** 按列宽截断；null 原样返回——"没有这个字段"与"空内容"都由 null 表达，不需要在这一层强加区分。 */
    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
