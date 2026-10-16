package io.lunar.submission.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 提交频率限制参数（docs/exam-process.md §7：学生可无限次提交，但服务端仍做频率限制与反滥用控制）。
 *
 * 放在 Nacos 配置中心（docs/middleware.md"配置中心"节），改值后无需重启即生效：
 * 用可变 JavaBean，热更新依赖就地重绑同一个实例，持有该引用的 SubmissionService 才能读到新值。
 * 代码里的默认值只保证"能跑"（每 60 秒 30 次），考试实际阈值由运行配置决定。
 */
@ConfigurationProperties("exam.submission")
public class SubmissionLimitProperties {

    /** 统计窗口长度（秒）。 */
    private int windowSeconds = 60;

    /** 单个学生在窗口内允许的提交次数上限，达到即返回 429 SUBMISSION_LIMIT_EXCEEDED。 */
    private int maxPerWindow = 30;

    public int getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }

    public int getMaxPerWindow() { return maxPerWindow; }
    public void setMaxPerWindow(int maxPerWindow) { this.maxPerWindow = maxPerWindow; }
}
