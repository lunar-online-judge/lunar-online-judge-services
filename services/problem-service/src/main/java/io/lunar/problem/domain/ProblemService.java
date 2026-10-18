package io.lunar.problem.domain;

import io.lunar.common.security.AccessScope;
import io.lunar.problem.config.TestdataProperties;
import io.lunar.problem.domain.dto.ProblemPage;
import io.lunar.problem.domain.dto.ProblemSummary;
import io.lunar.problem.domain.dto.ProblemView;
import io.lunar.problem.domain.dto.TestcasePayload;
import io.lunar.problem.domain.dto.TestcaseView;
import io.lunar.problem.domain.dto.VersionView;
import io.lunar.problem.infra.ProblemEntity;
import io.lunar.problem.infra.ProblemMapper;
import io.lunar.problem.infra.ProblemVersionEntity;
import io.lunar.problem.infra.ProblemVersionMapper;
import io.lunar.problem.infra.TestcaseEntity;
import io.lunar.problem.infra.TestcaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 题目管理（docs/services.md problem-service 节）：
 * 创建题目即创建首版本（v1，含测试点，同一事务）；版本发布后不可变，改动=新版本；
 * 发布时把 problems.current_version_id 切到该版本——考生查询始终看当前发布版本。
 * 安全约束：面向考生的查询绝不返回测试点内容（testcases 仅判题/管理接口可见）。
 */
@Service
public class ProblemService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_PUBLISHED = "PUBLISHED";

    /** 未显式指定时的默认限制（题面不传限制字段时使用）。 */
    private static final int DEFAULT_TIME_LIMIT_MS = 2000;
    private static final int DEFAULT_MEMORY_LIMIT_KB = 262144;
    private static final int DEFAULT_OUTPUT_LIMIT_KB = 65536;
    private static final String DEFAULT_ALLOWED_LANGUAGES = "c,cpp,java,python,javascript,typescript";

    private final ProblemMapper problemMapper;
    private final ProblemVersionMapper versionMapper;
    private final TestcaseMapper testcaseMapper;
    private final TestdataProperties testdataProperties;

    public ProblemService(ProblemMapper problemMapper, ProblemVersionMapper versionMapper,
                          TestcaseMapper testcaseMapper, TestdataProperties testdataProperties) {
        this.problemMapper = problemMapper;
        this.versionMapper = versionMapper;
        this.testcaseMapper = testcaseMapper;
        this.testdataProperties = testdataProperties;
    }

    /** 创建题目 + 首版本（versionNo=1，DRAFT）+ 测试点，同一事务。 */
    @Transactional
    public ProblemView create(String title, String statement, String sampleInput, String sampleOutput,
                              Integer timeLimitMs, Integer memoryLimitKb, Integer outputLimitKb,
                              String allowedLanguages, List<TestcasePayload> testcases, String operatorId) {
        String problemId = UUID.randomUUID().toString();
        ProblemEntity problem = new ProblemEntity();
        problem.setId(problemId);
        problem.setTitle(title);
        problem.setCreatedBy(operatorId);
        // 创建者即归属者（BE-01）：created_by 是事实，owner 是权限
        problem.setOwnerTeacherId(operatorId);
        problem.setCreatedAt(Instant.now());
        problem.setUpdatedAt(Instant.now());
        problemMapper.insert(problem);

        createVersion(problemId, 1, title, statement, sampleInput, sampleOutput,
                timeLimitMs, memoryLimitKb, outputLimitKb, allowedLanguages, testcases, operatorId);
        return new ProblemView(problemId, title, null);
    }

    /** 基于任意已有版本复制创建新版本（版本号 = 最大版本号 + 1，DRAFT），测试点一并复制后可改。 */
    @Transactional
    public VersionView createNewVersion(String problemId, String baseVersionId,
                                        String title, String statement, String sampleInput, String sampleOutput,
                                        Integer timeLimitMs, Integer memoryLimitKb, Integer outputLimitKb,
                                        String allowedLanguages, List<TestcasePayload> testcases, String operatorId) {
        ProblemEntity problem = requireProblem(problemId);
        // baseVersionId 缺省时基于题目当前版本（DTO 注释与 docs/api.md 的约定）；无当前版本时按"版本不存在"返回 404
        String resolvedBaseVersionId = (baseVersionId == null || baseVersionId.isBlank())
                ? problem.getCurrentVersionId()
                : baseVersionId;
        ProblemVersionEntity base = versionMapper.selectById(resolvedBaseVersionId);
        if (base == null || !problemId.equals(base.getProblemId())) {
            throw new ProblemException.VersionNotFound();
        }
        Integer maxNo = versionMapper.selectList(new LambdaQueryWrapper<ProblemVersionEntity>()
                        .eq(ProblemVersionEntity::getProblemId, problemId)
                        .select(ProblemVersionEntity::getVersionNo))
                .stream().map(ProblemVersionEntity::getVersionNo).max(Integer::compareTo)
                .orElse(0);
        ProblemVersionEntity created = createVersion(problemId, maxNo + 1,
                title != null ? title : base.getTitle(),
                statement != null ? statement : base.getStatement(),
                sampleInput != null ? sampleInput : base.getSampleInput(),
                sampleOutput != null ? sampleOutput : base.getSampleOutput(),
                timeLimitMs != null ? timeLimitMs : base.getTimeLimitMs(),
                memoryLimitKb != null ? memoryLimitKb : base.getMemoryLimitKb(),
                outputLimitKb != null ? outputLimitKb : base.getOutputLimitKb(),
                allowedLanguages != null ? allowedLanguages : base.getAllowedLanguages(),
                testcases, operatorId);
        // 未显式传测试点时复制基础版本的测试点
        if (testcases == null || testcases.isEmpty()) {
            List<TestcaseEntity> baseTestcases = testcaseMapper.selectList(
                    new LambdaQueryWrapper<TestcaseEntity>().eq(TestcaseEntity::getVersionId, resolvedBaseVersionId));
            for (TestcaseEntity testcase : baseTestcases) {
                insertTestcase(created.getId(), testcase.getName(), testcase.getInputContent(),
                        testcase.getExpectedOutput());
            }
        }
        return toView(created, true);
    }

    /** 发布版本：DRAFT → PUBLISHED（不可逆），并把题目当前版本切到该版本。 */
    @Transactional
    public VersionView publishVersion(String problemId, String versionId) {
        requireProblem(problemId);
        ProblemVersionEntity version = versionMapper.selectById(versionId);
        if (version == null || !problemId.equals(version.getProblemId())) {
            throw new ProblemException.VersionNotFound();
        }
        if (!STATUS_DRAFT.equals(version.getStatus())) {
            throw new ProblemException.VersionImmutable();
        }
        version.setStatus(STATUS_PUBLISHED);
        version.setPublishedAt(Instant.now());
        versionMapper.updateById(version);

        ProblemEntity problem = problemMapper.selectById(problemId);
        problem.setCurrentVersionId(versionId);
        problem.setUpdatedAt(Instant.now());
        problemMapper.updateById(problem);
        return toView(version, true);
    }

    /** 分页列表（管理端）：题库共享——老师可看到自己的全部题目 + 其他人已发布的题目。 */
    public ProblemPage list(String keyword, long page, long pageSize, AccessScope scope) {
        Page<ProblemEntity> pageRequest = new Page<>(page, pageSize);
        LambdaQueryWrapper<ProblemEntity> query = new LambdaQueryWrapper<ProblemEntity>()
                .orderByDesc(ProblemEntity::getCreatedAt);
        if (!scope.global()) {
            String ownerId = scope.ownerId();
            query.and(wrapper -> wrapper
                    .eq(ProblemEntity::getOwnerTeacherId, ownerId)
                    .or()
                    .isNotNull(ProblemEntity::getCurrentVersionId));
        }
        if (keyword != null && !keyword.isBlank()) {
            query.like(ProblemEntity::getTitle, keyword.trim());
        }
        Page<ProblemEntity> result = problemMapper.selectPage(pageRequest, query);
        List<ProblemSummary> items = result.getRecords().stream()
                .map(problem -> new ProblemSummary(problem.getId(), problem.getTitle(),
                        problem.getCurrentVersionId() == null ? "" : problem.getCurrentVersionId(),
                        problem.getCreatedAt(), scope.allows(problem.getOwnerTeacherId())))
                .toList();
        return new ProblemPage(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 管理端详情：题库共享后，老师既能看自己的草稿，也能看其他人已发布的题目。
     * 非归属者只返回当前发布版本（没有发布版本时按不存在处理）；归属者/管理员返回当前版本，
     * 未发布时回退到最新草稿。
     */
    public VersionView getForManagement(String problemId, AccessScope scope) {
        ProblemEntity problem = requireProblem(problemId);
        boolean canEdit = scope.allows(problem.getOwnerTeacherId());
        String currentVersionId = problem.getCurrentVersionId();

        if (currentVersionId == null) {
            if (!canEdit) {
                throw new ProblemException.VersionNotFound();
            }
            ProblemVersionEntity latest = versionMapper.selectOne(new LambdaQueryWrapper<ProblemVersionEntity>()
                    .eq(ProblemVersionEntity::getProblemId, problemId)
                    .orderByDesc(ProblemVersionEntity::getVersionNo)
                    .last("limit 1"));
            if (latest == null) {
                throw new ProblemException.VersionNotFound();
            }
            return toView(latest, true);
        }

        ProblemVersionEntity version = versionMapper.selectById(currentVersionId);
        if (version == null) {
            throw new ProblemException.VersionNotFound();
        }
        return toView(version, canEdit);
    }

    /**
     * 题目详情（考生视角）：当前发布版本的题面/样例/限制/语言；无发布版本时 404。绝不返回测试点。
     */
    public VersionView getCurrentPublished(String problemId) {
        ProblemEntity problem = requireProblem(problemId);
        if (problem.getCurrentVersionId() == null) {
            throw new ProblemException.VersionNotFound();
        }
        ProblemVersionEntity version = versionMapper.selectById(problem.getCurrentVersionId());
        if (version == null) {
            throw new ProblemException.VersionNotFound();
        }
        return toView(version, false);
    }

    /** 指定版本的详情（服务间，BE-04）：提交按考试锁定的版本校验语言与限制，而不是全局 current 版本。 */
    public VersionView getVersion(String problemId, String versionId) {
        requireProblem(problemId);
        ProblemVersionEntity version = versionMapper.selectById(versionId);
        if (version == null || !problemId.equals(version.getProblemId())) {
            throw new ProblemException.VersionNotFound();
        }
        return toView(version, false);
    }

    /** 版本列表（老师/管理员管理视图）：归属者看全部；共享题库的其他老师只看已发布版本。 */
    public List<VersionView> listVersions(String problemId, AccessScope scope) {
        ProblemEntity problem = requireProblem(problemId);
        boolean canEdit = scope.allows(problem.getOwnerTeacherId());
        LambdaQueryWrapper<ProblemVersionEntity> query = new LambdaQueryWrapper<ProblemVersionEntity>()
                .eq(ProblemVersionEntity::getProblemId, problemId)
                .orderByDesc(ProblemVersionEntity::getVersionNo);
        if (!canEdit) {
            query.eq(ProblemVersionEntity::getStatus, STATUS_PUBLISHED);
        }
        return versionMapper.selectList(query).stream()
                .map(version -> toView(version, canEdit))
                .toList();
    }

    /** 测试点（老师/管理员，或后续 judge-service 服务间）：仅判题与管理视图可见。 */
    public List<TestcaseView> listTestcases(String problemId, String versionId) {
        requireProblem(problemId);
        ProblemVersionEntity version = versionMapper.selectById(versionId);
        if (version == null || !problemId.equals(version.getProblemId())) {
            throw new ProblemException.VersionNotFound();
        }
        return testcaseMapper.selectList(new LambdaQueryWrapper<TestcaseEntity>()
                        .eq(TestcaseEntity::getVersionId, versionId)
                        .orderByAsc(TestcaseEntity::getName))
                .stream().map(testcase -> new TestcaseView(testcase.getName(), testcase.getInputContent(),
                        testcase.getExpectedOutput()))
                .toList();
    }

    /**
     * 判题链路取测试点（服务间接口，docs/services.md problem-service 节）：
     * 复用管理端查询，另加体积上限校验——测试点要随 submission.created 事件进 MQ，
     * 超限的消息永远发不出去（Outbox 每轮重试），所以在上游就拦住并给出明确错误。
     */
    public List<TestcaseView> listTestcasesForJudging(String problemId, String versionId) {
        List<TestcaseView> testcases = listTestcases(problemId, versionId);
        long totalBytes = testcases.stream()
                .mapToLong(testcase -> utf8Length(testcase.inputContent()) + utf8Length(testcase.expectedOutput()))
                .sum();
        if (totalBytes > testdataProperties.getMaxBytes()) {
            throw new ProblemException.TestdataTooLarge(totalBytes, testdataProperties.getMaxBytes());
        }
        return testcases;
    }

    private static long utf8Length(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private ProblemEntity requireProblem(String problemId) {
        ProblemEntity problem = problemMapper.selectById(problemId);
        if (problem == null) {
            throw new ProblemException.NotFound();
        }
        return problem;
    }

    /**
     * 管理端资源范围校验（BE-01）：题目不在调用者范围内时按"不存在"处理（404 隐藏存在）。
     * 版本、测试点、发布等入口都先过这一关，直接拿 ID 调子资源无法绕过。
     */
    public void requireOwned(String problemId, AccessScope scope) {
        if (!scope.allows(requireProblem(problemId).getOwnerTeacherId())) {
            throw new ProblemException.NotFound();
        }
    }

    private ProblemVersionEntity createVersion(String problemId, int versionNo,
                                               String title, String statement, String sampleInput, String sampleOutput,
                                               Integer timeLimitMs, Integer memoryLimitKb, Integer outputLimitKb,
                                               String allowedLanguages, List<TestcasePayload> testcases,
                                               String operatorId) {
        ProblemVersionEntity version = new ProblemVersionEntity();
        version.setId(UUID.randomUUID().toString());
        version.setProblemId(problemId);
        version.setVersionNo(versionNo);
        version.setTitle(title);
        version.setStatement(statement);
        version.setSampleInput(sampleInput);
        version.setSampleOutput(sampleOutput);
        version.setTimeLimitMs(timeLimitMs != null ? timeLimitMs : DEFAULT_TIME_LIMIT_MS);
        version.setMemoryLimitKb(memoryLimitKb != null ? memoryLimitKb : DEFAULT_MEMORY_LIMIT_KB);
        version.setOutputLimitKb(outputLimitKb != null ? outputLimitKb : DEFAULT_OUTPUT_LIMIT_KB);
        version.setAllowedLanguages(
                allowedLanguages != null ? allowedLanguages : DEFAULT_ALLOWED_LANGUAGES);
        version.setStatus(STATUS_DRAFT);
        version.setCreatedBy(operatorId);
        version.setCreatedAt(Instant.now());
        versionMapper.insert(version);
        if (testcases != null) {
            for (TestcasePayload testcase : testcases) {
                insertTestcase(version.getId(), testcase.name(), testcase.inputContent(), testcase.expectedOutput());
            }
        }
        return version;
    }

    private void insertTestcase(String versionId, String name, String inputContent, String expectedOutput) {
        TestcaseEntity testcase = new TestcaseEntity();
        testcase.setId(UUID.randomUUID().toString());
        testcase.setVersionId(versionId);
        testcase.setName(name);
        testcase.setInputContent(inputContent);
        testcase.setExpectedOutput(expectedOutput);
        testcaseMapper.insert(testcase);
    }

    private VersionView toView(ProblemVersionEntity version) {
        return toView(version, false);
    }

    private VersionView toView(ProblemVersionEntity version, boolean canEdit) {
        List<String> languages = version.getAllowedLanguages() == null ? List.of()
                : List.of(version.getAllowedLanguages().split(","));
        return new VersionView(version.getId(), version.getProblemId(), version.getVersionNo(), version.getTitle(),
                version.getStatement(), version.getSampleInput(), version.getSampleOutput(),
                version.getTimeLimitMs(), version.getMemoryLimitKb(), version.getOutputLimitKb(),
                languages, version.getStatus(), version.getCreatedBy(), version.getCreatedAt(),
                version.getPublishedAt(), canEdit);
    }
}
