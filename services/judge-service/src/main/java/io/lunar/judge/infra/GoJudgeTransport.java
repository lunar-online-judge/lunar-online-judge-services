package io.lunar.judge.infra;

import java.util.List;

/** 对 go-judge HTTP API 的最小抽象，供单元测试替换。 */
interface GoJudgeTransport {

    List<GoJudgeModels.RunResult> run(GoJudgeModels.RunRequest request);

    String version();
}
