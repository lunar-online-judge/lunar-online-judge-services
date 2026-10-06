package io.lunar.problem.domain;

import io.lunar.problem.infra.ProblemEntity;
import io.lunar.problem.infra.ProblemMapper;
import io.lunar.problem.infra.ProblemVersionEntity;
import io.lunar.problem.infra.ProblemVersionMapper;
import io.lunar.problem.infra.TestcaseEntity;
import io.lunar.problem.infra.TestcaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    public record TestcasePayload(String name, String inputContent, String expectedOutput) {}

    public record ProblemView(String problemId, String title, String currentVersionId) {}

    public record VersionView(String versionId, String problemId, int versionNo, String title, String statement,
                              String sampleInput, String sampleOutput, int timeLimitMs, int memoryLimitKb,
                              int outputLimitKb, List<String> allowedLanguages, String status,
                              String createdBy, Instant createdAt, Instant publishedAt) {}

    public record TestcaseView(String name, String inputContent, String expectedOutput) {}

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
        requireProblem(problemId);
        ProblemVersionEntity base = versionMapper.selectById(baseVersionId);
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
            List<TestcaseEntity> baseCases = testcaseMapper.selectList(
                    new LambdaQueryWrapper<TestcaseEntity>().eq(TestcaseEntity::getVersionId, baseVersionId));
            for (TestcaseEntity tc : baseCases) {
                insertTestcase(created.getId(), tc.getName(), tc.getInputContent(), tc.getExpectedOutput());
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
        if (!"DRAFT".equals(version.getStatus())) {
            throw new ProblemException.VersionImmutable();
        }
        version.setStatus("PUBLISHED");
        version.setPublishedAt(Instant.now());
        versionMapper.updateById(version);

        ProblemEntity problem = problemMapper.selectById(problemId);
        problem.setCurrentVersionId(versionId);
        problem.setUpdatedAt(Instant.now());
        problemMapper.updateById(problem);
        return toView(version);
    }

    /** 题目详情（考生视角）：当前发布版本的题面/样例/限制/语言；无发布版本时 404。绝不返回测试点。 */
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
                .stream().map(tc -> new TestcaseView(tc.getName(), tc.getInputContent(), tc.getExpectedOutput()))
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
        version.setTimeLimitMs(timeLimitMs != null ? timeLimitMs : 2000);
        version.setMemoryLimitKb(memoryLimitKb != null ? memoryLimitKb : 262144);
        version.setOutputLimitKb(outputLimitKb != null ? outputLimitKb : 65536);
        version.setAllowedLanguages(allowedLanguages != null ? allowedLanguages : "c,cpp,java,python");
        version.setStatus("DRAFT");
        version.setCreatedBy(operatorId);
        version.setCreatedAt(Instant.now());
        versionMapper.insert(version);
        if (testcases != null) {
            for (TestcasePayload tc : testcases) {
                insertTestcase(version.getId(), tc.name(), tc.inputContent(), tc.expectedOutput());
            }
        }
        return version;
    }

    private void insertTestcase(String versionId, String name, String input, String expectedOutput) {
        TestcaseEntity tc = new TestcaseEntity();
        tc.setId(UUID.randomUUID().toString());
        tc.setVersionId(versionId);
        tc.setName(name);
        tc.setInputContent(input);
        tc.setExpectedOutput(expectedOutput);
        testcaseMapper.insert(tc);
    }

    private VersionView toView(ProblemVersionEntity v) {
        List<String> languages = v.getAllowedLanguages() == null ? List.of()
                : List.of(v.getAllowedLanguages().split(","));
        return new VersionView(v.getId(), v.getProblemId(), v.getVersionNo(), v.getTitle(), v.getStatement(),
                v.getSampleInput(), v.getSampleOutput(), v.getTimeLimitMs(), v.getMemoryLimitKb(),
                v.getOutputLimitKb(), languages, v.getStatus(), v.getCreatedBy(), v.getCreatedAt(),
                v.getPublishedAt());
    }
}
