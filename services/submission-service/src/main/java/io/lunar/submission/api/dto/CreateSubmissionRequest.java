package io.lunar.submission.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提交请求体：幂等键走 Idempotency-Key 请求头，不在请求体内。
 *
 * 源码上限（第二批正确性项）：源码会随 created 事件原样进 RocketMQ，而 broker 默认拒收 >4MB 的消息——
 * 没有上限时，一条超大源码会让事件投递失败、转死信，提交**永久卡在 QUEUED**，考生还看不到任何原因。
 * 在入口就拦住，给一个明确的 400，比让一条提交躺在死信里好。
 * 64 KiB 的取值理由：正常算法题源码远小于此；与测试点上限（默认 1 MiB）相加仍远低于 4MB 消息上限。
 */
public record CreateSubmissionRequest(@NotBlank String contestId,
                                      @NotBlank String problemId,
                                      @NotBlank String language,
                                      @NotBlank @Size(max = 65536, message = "源码不能超过 65536 字符")
                                      String sourceCode) {}
