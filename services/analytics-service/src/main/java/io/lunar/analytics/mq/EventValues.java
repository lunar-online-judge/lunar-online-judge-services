package io.lunar.analytics.mq;

import com.fasterxml.jackson.databind.JsonNode;
import io.lunar.common.event.EventMessages;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * 事件里非字符串字段（标识与时间）的解析，读不出来一律返回 null 而不抛异常。
 *
 * 为什么不做"坏值即抛"：消费端的异常等价于"重投"，而字段格式错误重投多少次都不会自愈，
 * 会把消费组钉死在这条消息上。解析失败由调用方决定是丢弃整条（关键字段）还是放弃该字段（可空字段）。
 */
public final class EventValues {

    /** 标识字段的合理上限：再长不可能是 UUID，先挡住，避免把巨串送进 UUID.fromString 的解析路径。 */
    private static final int MAX_ID_LENGTH = 128;

    private EventValues() {
    }

    /** 校验并返回 UUID 文本；不是合法 UUID（含超长、带空白）时返回 null——非法值送进 uuid 列会被数据库直接拒绝。 */
    public static String readUuid(JsonNode payload, String fieldName) {
        String candidate = EventMessages.textOf(payload, fieldName);
        if (candidate == null || candidate.length() > MAX_ID_LENGTH) {
            return null;
        }
        try {
            UUID.fromString(candidate);
            return candidate;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 解析 ISO-8601 时间戳；缺失或格式不合法时返回 null。 */
    public static Instant readInstant(JsonNode payload, String fieldName) {
        return parseInstant(EventMessages.textOf(payload, fieldName));
    }

    /** 解析已经单独取出的时间戳文本（信封的 occurredAt 不在 payload 内，无法复用上面的读法）。 */
    public static Instant parseInstant(String candidate) {
        if (candidate == null) {
            return null;
        }
        try {
            return Instant.parse(candidate);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /**
     * 读取整数字段；缺失、null 或非数字都返回 null。
     *
     * 不用 {@code asInt()}：它对"abc"这类值静默返回 0，会把引擎结果悄悄变成 0 分，
     * 而 0 分与"引擎没上报"在业务上完全不同。
     */
    public static Integer readIntOrNull(JsonNode payload, String fieldName) {
        JsonNode fieldNode = payload.get(fieldName);
        if (fieldNode == null || !fieldNode.isNumber()) {
            return null;
        }
        return fieldNode.intValue();
    }
}
