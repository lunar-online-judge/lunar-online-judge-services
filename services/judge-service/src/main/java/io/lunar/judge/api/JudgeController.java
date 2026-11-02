package io.lunar.judge.api;

import org.springframework.security.access.prepost.PreAuthorize;
import io.lunar.common.api.ApiResponse;
import io.lunar.common.judge.JudgeEngine;
import io.lunar.common.judge.JudgeSubmissionCommand;
import io.lunar.common.judge.JudgeSubmissionResult;
import io.lunar.judge.api.dto.SubmitRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 判题 HTTP 入口：把提交转发给当前激活的 {@link JudgeEngine}，返回引擎提交句柄。 */
/**
 * 判题入口：**只允许 SERVICE 与 ADMIN**。
 *
 * 为什么现在就加角色限制：judge 当前没有接 oauth2 resource server（jwk-set-uri 未配置），
 * 所以所有请求都因"无认证方式"恒 401 —— 这条限制看起来是多余的。但一旦将来补上验签，
 * 没有这行的话，**任何已认证用户**都能让沙箱执行任意代码（judge 的入参就是源码与测试点）。
 * 安全边界不能依赖"恰好还没接认证"这种偶然状态。
 */
@RestController
@PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
@RequestMapping("/api/v1/judge")
public class JudgeController {

    private final JudgeEngine judgeEngine;

    public JudgeController(JudgeEngine judgeEngine) {
        this.judgeEngine = judgeEngine;
    }

    @PostMapping("/submissions")
    public ApiResponse<JudgeSubmissionResult> submit(@RequestBody SubmitRequest request) {
        JudgeSubmissionResult result = judgeEngine.submit(new JudgeSubmissionCommand(
                request.submissionId(), request.language(), request.sourceCode(),
                Map.of(), request.cpuTimeLimitMs(), request.memoryLimitKb()));
        return ApiResponse.success("pending", result);
    }
}
