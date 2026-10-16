package io.lunar.ai.domain;

import io.lunar.ai.domain.dto.CodeReviewAiResult;
import io.lunar.ai.domain.dto.CodeReviewRootCause;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取值规整是**结构化输出之外**的那层保障：LangChain4j 能保证字段存在且类型正确，
 * 但保证不了"可疑度必须在 0..100""理由最多 5 条"这类业务含义。这里钉住后者。
 */
class ModelOutputParserTest {

    private final ModelOutputParser outputParser = new ModelOutputParser();

    @Test
    void scoreIsClampedIntoRange() {
        assertThat(outputParser.scoreWithinRange(105, 0)).isEqualTo(100);
        assertThat(outputParser.scoreWithinRange(-3, 0)).isZero();
        assertThat(outputParser.scoreWithinRange(42, 0)).isEqualTo(42);
    }

    /** 分数缺失时用兜底值，而不是让整个作业失败：分数在这些结论里只用于排序展示。 */
    @Test
    void missingScoreFallsBackInsteadOfFailing() {
        assertThat(outputParser.scoreWithinRange(null, 7)).isEqualTo(7);
    }

    @Test
    void listIsCappedToMaxItems() {
        List<String> manyItems = IntStream.range(0, 20).mapToObj(index -> "item-" + index).toList();

        assertThat(outputParser.capped(manyItems, 5)).hasSize(5).first().isEqualTo("item-0");
    }

    @Test
    void nullListBecomesEmptyList() {
        // 必须显式声明类型：capped 有两个重载（List 与 String），裸 null 字面量会被判为歧义
        List<String> nullList = null;

        assertThat(outputParser.capped(nullList, 5)).isEmpty();
    }

    @Test
    void textIsTruncatedToColumnLength() {
        assertThat(outputParser.capped("x".repeat(1200), 1000)).hasSize(1000);
    }

    @Test
    void firstNonBlankSkipsBlankCandidates() {
        assertThat(outputParser.firstNonBlank("兜底", "  ", null, "有内容")).isEqualTo("有内容");
        assertThat(outputParser.firstNonBlank("兜底", "  ", null)).isEqualTo("兜底");
    }

    /** 摘要必须始终可读：模型没给说明时用兜底文案，而不是留空。 */
    @Test
    void codeReviewDraftKeepsSummaryReadableWhenModelOmitsExplanation() {
        CodeReviewFindingFactory findingFactory = new CodeReviewFindingFactory(outputParser);
        CodeReviewAiResult modelResult = new CodeReviewAiResult("  ", List.of(
                new CodeReviewRootCause("EDGE_CASE", "n=0 时未处理")), List.of("补 n=0 分支"), null, null);

        AiFindingDraft draft = findingFactory.toDraft(modelResult, "WRONG_ANSWER");

        assertThat(draft.summary()).contains("WRONG_ANSWER");
        assertThat(draft.detailJson()).contains("n=0 时未处理");
        // 代码评审是教学反馈，不带风险信号：把两者混在一个字段里会污染风险列表
        assertThat(draft.severity()).isNull();
        assertThat(draft.score()).isNull();
    }
}
