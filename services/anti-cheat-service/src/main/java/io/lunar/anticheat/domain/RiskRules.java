package io.lunar.anticheat.domain;

import java.util.Map;
import java.util.Set;

/**
 * 风险等级规则表（v1 硬编码，可配置化后续）：
 * severity 由服务端按事件类型计算，客户端上报值一律不采信（docs/exam-process.md §6）；
 * 未知事件类型保守按 MEDIUM 收下（风控不丢事件）；
 * 单一事件（无论等级）不能自动判定作弊——处置必须走人工审核。
 */
public final class RiskRules {

    public static final Set<String> KNOWN_TYPES = Set.of(
            "FOCUS_LOST", "CLIPBOARD_ACCESS", "SUSPICIOUS_PROCESS", "DEBUGGER_DETECTED",
            "INJECTION_ATTEMPT", "UNAUTHORIZED_MODULE", "MEDIA_STREAM_LOST", "CAMERA_MISSING",
            "JUDGE_LIMIT_VIOLATION");

    private static final Map<String, String> SEVERITY_BY_TYPE = Map.of(
            "DEBUGGER_DETECTED", "HIGH",
            "INJECTION_ATTEMPT", "HIGH",
            "UNAUTHORIZED_MODULE", "HIGH",
            "SUSPICIOUS_PROCESS", "MEDIUM",
            "MEDIA_STREAM_LOST", "MEDIUM",
            "JUDGE_LIMIT_VIOLATION", "MEDIUM",
            "FOCUS_LOST", "LOW",
            "CAMERA_MISSING", "LOW",
            "CLIPBOARD_ACCESS", "LOW");

    private RiskRules() {}

    /** 事件类型 → 风险等级；未知类型一律 MEDIUM（保守收下）。 */
    public static String severityOf(String eventType) {
        return SEVERITY_BY_TYPE.getOrDefault(eventType, "MEDIUM");
    }

    public static boolean isKnown(String eventType) {
        return KNOWN_TYPES.contains(eventType);
    }
}
