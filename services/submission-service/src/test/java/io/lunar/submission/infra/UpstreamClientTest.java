package io.lunar.submission.infra;

import io.lunar.common.api.ApiResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 适配层职责：解上游的 ApiResponse 信封、转发学生 Bearer 令牌。 */
class UpstreamClientTest {

    private final ContestServiceApi contestApi = mock(ContestServiceApi.class);
    private final ProblemServiceApi problemApi = mock(ProblemServiceApi.class);
    private final ProblemInternalApi problemInternalApi = mock(ProblemInternalApi.class);
    private final UpstreamClient client = new UpstreamClient(contestApi, problemApi, problemInternalApi);

    @Test
    void checkSessionUnwrapsEnvelopeAndForwardsBearerToken() {
        when(contestApi.checkSession("c1", "Bearer tok"))
                .thenReturn(ApiResponse.success("req-1", Map.of("canSubmit", true)));

        assertThat(client.checkSession("c1", "tok")).containsEntry("canSubmit", true);
    }

    @Test
    void currentProblemVersionUnwrapsEnvelope() {
        when(problemApi.currentVersion("p1", "Bearer tok"))
                .thenReturn(ApiResponse.success("req-2", Map.of("versionId", "v1")));

        assertThat(client.currentProblemVersion("p1", "Bearer tok")).containsEntry("versionId", "v1");
    }

    @Test
    void problemTestcasesUnwrapsEnvelopeAndForwardsBearerToken() {
        when(problemInternalApi.testcases("p1", "v1", "Bearer tok"))
                .thenReturn(ApiResponse.success("req-3", List.of(Map.of("name", "case1", "inputContent", "1 2"))));

        List<Map<String, Object>> testcases = client.problemTestcases("p1", "v1", "tok");

        assertThat(testcases).hasSize(1);
        assertThat(testcases.get(0)).containsEntry("name", "case1");
    }
}
