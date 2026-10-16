package io.lunar.problem.domain;

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
    private static final String DEFAULT_ALLOWED_LANGUAGES = "c,cpp,java,python";

    private final ProblemMapper problemMapper;
    private final ProblemVersionMapper versionMapper;
    private final TestcaseMapper testcaseMapper;

    public ProblemService(ProblemMapper problemMapper, ProblemVersionMapper versionMapper,
                          TestcaseMapper testcaseMapper) {
        this.problemMapper = problemMapper;
        this.versionMapper = versionMapper;
        this.testcaseMapper = testcaseMapper;
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
        return toView(created);
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
        return toView(version);
    }

    /** 分页列表（管理端）：按创建时间倒序，keyword 模糊匹配标题。 */
    public ProblemPage list(String keyword, long page, long pageSize) {
        Page<ProblemEntity> pageRequest = new Page<>(page, pageSize);
        LambdaQueryWrapper<ProblemEntity> query = new LambdaQueryWrapper<ProblemEntity>()
                .orderByDesc(ProblemEntity::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            query.like(ProblemEntity::getTitle, keyword.trim());
        }
        Page<ProblemEntity> result = problemMapper.selectPage(pageRequest, query);
        List<ProblemSummary> items = result.getRecords().stream()
                .map(problem -> new ProblemSummary(problem.getId(), problem.getTitle(),
                        problem.getCurrentVersionId() == null ? "" : problem.getCurrentVersionId(),
                        problem.getCreatedAt()))
                .toList();
        return new ProblemPage(items, result.getTotal(), result.getCurrent(), result.getSize());
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
        return toView(version);
    }

    /** 版本列表（老师/管理员管理视图）。 */
    public List<VersionView> listVersions(String problemId) {
        requireProblem(problemId);
        return versionMapper.selectList(new LambdaQueryWrapper<ProblemVersionEntity>()
                        .eq(ProblemVersionEntity::getProblemId, problemId)
                        .orderByDesc(ProblemVersionEntity::getVersionNo))
                .stream().map(this::toView).toList();
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

    private ProblemEntity requireProblem(String problemId) {
        ProblemEntity problem = problemMapper.selectById(problemId);
        if (problem == null) {
            throw new ProblemException.NotFound();
        }
        return problem;
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
        List<String> languages = version.getAllowedLanguages() == null ? List.of()
                : List.of(version.getAllowedLanguages().split(","));
        return new VersionView(version.getId(), version.getProblemId(), version.getVersionNo(), version.getTitle(),
                version.getStatement(), version.getSampleInput(), version.getSampleOutput(),
                version.getTimeLimitMs(), version.getMemoryLimitKb(), version.getOutputLimitKb(),
                languages, version.getStatus(), version.getCreatedBy(), version.getCreatedAt(),
                version.getPublishedAt());
    }
}
