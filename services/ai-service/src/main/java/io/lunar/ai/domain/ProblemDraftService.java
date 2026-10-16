package io.lunar.ai.domain;

import io.lunar.ai.domain.dto.AiPage;
import io.lunar.ai.domain.dto.ProblemDraftAiResult;
import io.lunar.ai.domain.dto.ProblemDraftSpec;
import io.lunar.ai.domain.dto.ProblemDraftView;
import io.lunar.ai.infra.AiJobEntity;
import io.lunar.ai.infra.ProblemDraftEntity;
import io.lunar.ai.infra.ProblemDraftMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 出题草稿的落库与读取。
 *
 * {@link #writeFromCompletedJob} **刻意不带 {@code @Transactional}**：它由作业收尾流程调用，
 * 必须加入那个事务，草稿才能与 finding、事件同生共死（否则会出现"有结论但没草稿"或反之）。
 */
@Service
public class ProblemDraftService {

    private final ProblemDraftMapper draftMapper;
    private final ModelOutputParser outputParser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProblemDraftService(ProblemDraftMapper draftMapper, ModelOutputParser outputParser) {
        this.draftMapper = draftMapper;
        this.outputParser = outputParser;
    }

    /**
     * 作业成功收尾时落草稿。
     *
     * 非出题作业直接返回；同一作业已有草稿也直接返回——作业失败重试（租约过期被重新领取）
     * 不该产出第二份草稿，否则教师会看到重复题目。
     */
    public void writeFromCompletedJob(AiJobEntity job, String findingDetailJson) {
        if (!AiJobType.PROBLEM_DRAFT.name().equals(job.getJobType()) || existsForJob(job.getId())) {
            return;
        }
        ProblemDraftAiResult aiResult = readAiResult(findingDetailJson);
        ProblemDraftEntity draft = new ProblemDraftEntity();
        draft.setId(UUID.randomUUID().toString());
        draft.setJobId(job.getId());
        draft.setContestId(job.getContestId());
        draft.setTitle(outputParser.firstNonBlank("未命名草稿", aiResult.title()));
        draft.setStatement(aiResult.statement() == null ? "" : aiResult.statement());
        draft.setSampleInput(aiResult.sampleInput());
        draft.setSampleOutput(aiResult.sampleOutput());
        draft.setSuggestedTestcases(writeJson(aiResult.testcases()));
        draft.setKnowledgeTags(writeJson(aiResult.knowledgeTags()));
        draft.setDifficulty(aiResult.difficulty());
        draft.setLanguageHint(languageHintOf(job.getInputPayload()));
        draft.setReviewStatus("DRAFT");
        draft.setCreatedAt(Instant.now());
        draftMapper.insert(draft);
    }

    public AiPage<ProblemDraftView> list(String contestId, long page, long pageSize) {
        LambdaQueryWrapper<ProblemDraftEntity> query = new LambdaQueryWrapper<ProblemDraftEntity>()
                .orderByDesc(ProblemDraftEntity::getCreatedAt);
        if (contestId != null && !contestId.isBlank()) {
            query.eq(ProblemDraftEntity::getContestId, contestId);
        }
        Page<ProblemDraftEntity> result = draftMapper.selectPage(new Page<>(page, pageSize), query);
        return new AiPage<>(result.getRecords().stream().map(ProblemDraftView::of).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public ProblemDraftView view(String draftId) {
        ProblemDraftEntity draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new AiException.ResourceNotFound();
        }
        return ProblemDraftView.of(draft);
    }

    private boolean existsForJob(String jobId) {
        return draftMapper.selectCount(new LambdaQueryWrapper<ProblemDraftEntity>()
                .eq(ProblemDraftEntity::getJobId, jobId)) > 0;
    }

    private ProblemDraftAiResult readAiResult(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            // 走到这里说明处理器没按约定产出结构化结果，属编程错误（不该当可重试的作业失败）
            throw new IllegalStateException("出题作业缺少结构化产出，无法落草稿");
        }
        try {
            return objectMapper.readValue(detailJson, ProblemDraftAiResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("出题草稿解析失败", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("出题草稿序列化失败", exception);
        }
    }

    /** 语言提示存在作业输入里；解析不了一律当未指定，不因为一个展示字段让落库失败。 */
    private String languageHintOf(String inputPayload) {
        if (inputPayload == null || inputPayload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(inputPayload, ProblemDraftSpec.class).languageHint();
        } catch (JsonProcessingException exception) {
            return null;
        }
    }
}
