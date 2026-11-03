package io.lunar.contest.domain;

import io.lunar.common.security.AccessScope;
import io.lunar.contest.infra.ContestEntity;
import io.lunar.contest.infra.ContestProblemEntity;
import io.lunar.contest.infra.ContestProblemMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 版本锁定闭环（BE-04）：**考试发布后，题目清单与锁定版本不可再改**。
 *
 * 为什么这条必须钉死：锁定版本的意义就是"这场考试用哪一版题面与测试数据"。发布后还能改，
 * 就会出现"考生看旧题面、判题用新测试点"，或者"已移出的题仍可提交、分配记录还在"——
 * 成绩不可信，事后也无法解释。
 */
class ContestProblemServiceTest {

    private static final String CONTEST = "contest-1";
    private static final String PROBLEM = "problem-1";
    private static final String VERSION = "version-1";

    private final ContestProblemMapper contestProblemMapper = mock(ContestProblemMapper.class);
    private final ContestService contestService = mock(ContestService.class);
    private final ContestProblemService service = new ContestProblemService(contestProblemMapper, contestService);

    private void contestInStatus(String status) {
        ContestEntity contest = new ContestEntity();
        contest.setId(CONTEST);
        contest.setStatus(status);
        when(contestService.getOwned(any(), any())).thenReturn(contest);
    }

    @Test
    void cannotLockProblemAfterPublish() {
        contestInStatus("PUBLISHED");

        assertThrows(ContestException.ProblemsLocked.class,
                () -> service.lock(CONTEST, PROBLEM, VERSION, new AccessScope(true, null)));

        verify(contestProblemMapper, never()).insert(any(ContestProblemEntity.class));
    }

    @Test
    void cannotUnlockProblemAfterPublish() {
        contestInStatus("RUNNING");

        assertThrows(ContestException.ProblemsLocked.class,
                () -> service.unlock(CONTEST, PROBLEM, new AccessScope(true, null)));

        verify(contestProblemMapper, never()).deleteById(any(String.class));
    }

    @Test
    void canLockAndUnlockWhileDraft() {
        contestInStatus("DRAFT");

        assertDoesNotThrow(() -> service.lock(CONTEST, PROBLEM, VERSION, new AccessScope(true, null)));
        verify(contestProblemMapper).insert(any(ContestProblemEntity.class));
    }

    @Test
    void canLockWhileReviewing() {
        contestInStatus("REVIEWING");

        assertDoesNotThrow(() -> service.lock(CONTEST, PROBLEM, VERSION, new AccessScope(true, null)));
    }

    @Test
    void lockedProblemKeepsTheRequestedVersion() {
        contestInStatus("DRAFT");

        ContestProblemEntity locked = service.lock(CONTEST, PROBLEM, VERSION, new AccessScope(true, null));

        assertEquals(VERSION, locked.getProblemVersionId(),
                "锁定的是调用方指定的版本，不是题目的当前发布版本");
        assertEquals(CONTEST, locked.getContestId());
        assertEquals(PROBLEM, locked.getProblemId());
    }
}
