package io.lunar.ai.domain;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型产出的**取值规整**。
 *
 * 边界要说清楚：结构化输出由 LangChain4j 保证**结构**（字段存在、类型正确、JSON 可解析），
 * 但框架不知道"这个字段在我们的业务里意味着什么"——例如可疑度必须是 0..100、
 * 理由条数不能无限增长（提示词说了最多 5 条，模型可以不遵守）。
 * 这类"业务含义"的校验只在我们这边，因此单独留一个组件承接，供所有 AI 接口共用。
 *
 * 名字沿用早期实现：那时它负责解析模型返回的 JSON 文本；改用结构化输出后文本解析归框架，
 * 只剩下这里的取值规整（**待重命名为 ModelOutputGuard 之类**，当前无删除文件权限，见 PR 说明）。
 */
@Component
public class ModelOutputParser {

    /**
     * 分数夹取到 0..100。
     *
     * 取值为空时返回 {@code fallback}：AI 结论里的分数只用于排序展示，
     * 因为它缺失就让整个作业失败，性价比太低（真正的失败原因应该由结构化输出解析去报）。
     */
    public int scoreWithinRange(Integer score, int fallback) {
        if (score == null) {
            return fallback;
        }
        return Math.max(0, Math.min(100, score));
    }

    /** 列表截断：提示词里的"最多 N 条"是软约束，模型可能给 20 条。 */
    public <T> List<T> capped(List<T> items, int maxItems) {
        if (items == null) {
            return List.of();
        }
        return items.size() <= maxItems ? List.copyOf(items) : List.copyOf(items.subList(0, maxItems));
    }

    /** 文本截断：与库列长度对齐，避免一次超长输出把事务打断。 */
    public String capped(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /** 取第一个非空白文本，全空时返回 {@code fallback}——摘要为空会让列表页无法阅读。 */
    public String firstNonBlank(String fallback, String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return fallback;
    }
}
