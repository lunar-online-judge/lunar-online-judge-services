package io.lunar.ai.domain;

import io.lunar.ai.domain.dto.CodeReviewAiResult;
import io.lunar.ai.domain.dto.CodeReviewRootCause;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把代码评审的模型产出规整成可落库的结论（{@link AiFindingDraft}）。
 *
 * 单独成一个组件而不是塞进处理器：这里做的是"AI 产出 → 领域结论"的映射与**取值校验**，
 * 与"取输入、调模型"是两件事；分开之后它不需要模型就能被单测覆盖（这是本模块唯一能被确定性测试的部分）。
 *
 * 代码评审不产生可疑度（severity/score 留空）：它是教学反馈，不是风险信号。
 * 把两者混在一个字段里，风险列表会被"学生写错了循环"这类结论污染。
 */
@Component
public class CodeReviewFindingFactory {

    /** 与提示词里的软约束一致，但这里必须硬性截断——模型可以不遵守提示词。 */
    private static final int MAX_ROOT_CAUSES = 5;
    private static final int MAX_IMPROVEMENTS = 5;

    /** 与 ai_findings.summary 的列长度一致。 */
    private static final int MAX_SUMMARY_LENGTH = 1000;

    private final ModelOutputParser outputParser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CodeReviewFindingFactory(ModelOutputParser outputParser) {
        this.outputParser = outputParser;
    }

    /** {@code verdictFallback} 用于模型没给说明时兜底，保证摘要在列表页始终可读。 */
    public AiFindingDraft toDraft(CodeReviewAiResult modelResult, String verdictFallback) {
        List<CodeReviewRootCause> rootCauses = outputParser.capped(modelResult.rootCauses(), MAX_ROOT_CAUSES);
        List<String> improvements = outputParser.capped(modelResult.improvements(), MAX_IMPROVEMENTS);
        CodeReviewAiResult normalized = new CodeReviewAiResult(
                outputParser.capped(modelResult.verdictExplanation(), MAX_SUMMARY_LENGTH),
                rootCauses,
                improvements,
                outputParser.capped(modelResult.complexityNote(), MAX_SUMMARY_LENGTH),
                Boolean.TRUE.equals(modelResult.hintOnly()));
        String summary = outputParser.firstNonBlank(
                "代码评审已完成（verdict=" + verdictFallback + "）", normalized.verdictExplanation());
        return new AiFindingDraft(null, null, summary, writeJson(normalized));
    }

    private String writeJson(CodeReviewAiResult normalized) {
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException exception) {
            // 序列化自己刚构造的 record 失败属编程错误，不该当作可重试的作业失败
            throw new IllegalStateException("代码评审结论序列化失败", exception);
        }
    }
}
