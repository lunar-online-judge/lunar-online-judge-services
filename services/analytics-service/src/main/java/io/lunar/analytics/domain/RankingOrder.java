package io.lunar.analytics.domain;

import io.lunar.analytics.domain.dto.ScoreAggregate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把 {@link RankingProperties} 的配置编译成一个可比较器。
 *
 * 编译发生在 Bean 构造期（启动时）：配置写错字段名或方向必须让服务起不来，而不是等到第一批学生交卷、
 * 榜单排出来的顺序没人能解释的时候才发现。这样做也让"排序"在重算路径上只是一次 sort，没有运行期解析。
 */
@Component
public class RankingOrder {

    /** 学生 ID 是唯一键，排在最后可保证比较是全序（否则同档次的两人顺序由排序算法决定）。 */
    private static final RankingField STABILITY_FIELD = RankingField.STUDENT_ID;

    private final List<ConfiguredField> fields;
    private final Comparator<ScoreAggregate> comparator;

    public RankingOrder(RankingProperties properties) {
        this.fields = parse(properties.getTieBreak());
        this.comparator = compose(this.fields);
    }

    /** 榜单排序用；调用方拿到后直接 {@code list.sort(comparator)}。 */
    public Comparator<ScoreAggregate> comparator() {
        return comparator;
    }

    /** 实际生效的档位（含被自动补上的稳定性兜底），供日志与运维核对配置是否真的生效。 */
    public List<String> effectiveTieBreak() {
        return fields.stream()
                .map(field -> field.field().key() + (field.descending() ? ":desc" : ":asc"))
                .toList();
    }

    private static List<ConfiguredField> parse(List<String> configuredTieBreak) {
        List<ConfiguredField> parsed = new ArrayList<>();
        for (String entry : configuredTieBreak) {
            parsed.add(parseEntry(entry));
        }
        boolean hasStabilityField = parsed.stream().anyMatch(field -> field.field() == STABILITY_FIELD);
        if (!hasStabilityField) {
            parsed.add(new ConfiguredField(STABILITY_FIELD, false));
        }
        return List.copyOf(parsed);
    }

    private static ConfiguredField parseEntry(String entry) {
        String[] parts = entry.split(":");
        if (parts.length != 2) {
            throw new IllegalStateException("analytics.ranking.tie-break 的档位格式应为 \"<字段>:<asc|desc>\"，实际为：" + entry);
        }
        RankingField field = RankingField.parse(parts[0].trim());
        if (field == null) {
            throw new IllegalStateException("analytics.ranking.tie-break 出现未知字段：" + parts[0].trim());
        }
        boolean descending = switch (parts[1].trim().toLowerCase()) {
            case "desc" -> true;
            case "asc" -> false;
            default -> throw new IllegalStateException(
                    "analytics.ranking.tie-break 的方向只能是 asc 或 desc，实际为：" + parts[1].trim());
        };
        return new ConfiguredField(field, descending);
    }

    private static Comparator<ScoreAggregate> compose(List<ConfiguredField> fields) {
        Comparator<ScoreAggregate> composed = null;
        for (ConfiguredField field : fields) {
            Comparator<ScoreAggregate> next =
                    (left, right) -> field.field().compare(left, right, field.descending());
            composed = composed == null ? next : composed.thenComparing(next);
        }
        return composed;
    }
}
