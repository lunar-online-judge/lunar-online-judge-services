package io.lunar.ai.domain;

import io.lunar.ai.config.AiWorkerProperties;
import io.lunar.ai.domain.dto.AiFindingView;
import io.lunar.ai.infra.AiJobEntity;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 作业执行器：按并发上限把队列里的作业领出来执行。
 *
 * 为什么要有它（而不是在 controller 里同步调模型）：LLM 调用是**秒级到分钟级**的慢操作，
 * 同步执行会把 HTTP 请求线程和配额一起拖住；而且同步路径没有重试余地，
 * Provider 抖一下用户就看到失败。异步化后：请求立即返回作业号、可重试、可限流、可观测。
 *
 * 并发与领取都靠数据库：`for update skip locked` 保证多实例下同一个作业只被领走一次
 * （重复领取 = 重复烧配额 + 两份内容不同的结论）。
 *
 * 线程池在构造期按 {@code ai.worker.concurrency} 固定：线程池大小无法热改，
 * 改这个值需要重启（application.yml 已注明）。
 */
@Component
public class AiJobWorker {

    private static final Logger log = LoggerFactory.getLogger(AiJobWorker.class);

    private final AiJobService jobService;
    private final Map<AiJobType, AiJobHandler> handlers;
    private final AiWorkerProperties workerProperties;
    private final ExecutorService executor;
    private final AtomicInteger inFlight = new AtomicInteger();

    public AiJobWorker(AiJobService jobService, List<AiJobHandler> handlers, AiWorkerProperties workerProperties) {
        this.jobService = jobService;
        this.workerProperties = workerProperties;
        this.handlers = handlers.stream()
                .collect(Collectors.toUnmodifiableMap(AiJobHandler::jobType, Function.identity()));
        this.executor = Executors.newFixedThreadPool(Math.max(1, workerProperties.getConcurrency()), runnable -> {
            Thread workerThread = new Thread(runnable, "ai-job-worker");
            // 守护线程：进程退出时不被未完成的模型调用拖住（未完成的作业由租约回收）
            workerThread.setDaemon(true);
            return workerThread;
        });
        log.info("AI 作业执行器已启动：并发 {}，已注册处理器 {}", workerProperties.getConcurrency(),
                this.handlers.keySet());
    }

    @Scheduled(fixedDelayString = "${ai.worker.poll-interval-ms:3000}")
    public void poll() {
        int availableSlots = Math.max(0, workerProperties.getConcurrency() - inFlight.get());
        for (int slot = 0; slot < availableSlots; slot++) {
            // 先占槽位再提交：否则轮询每 3 秒就会往线程池里堆任务，队列长度失控
            inFlight.incrementAndGet();
            executor.submit(this::runOneJob);
        }
    }

    private void runOneJob() {
        AiJobEntity job = null;
        try {
            job = jobService.claimNext();
            if (job == null) {
                return;
            }
            AiJobHandler handler = handlerFor(job);
            if (handler == null) {
                jobService.markFailed(job, AiException.INVALID_REQUEST_CODE,
                        "没有注册处理该类型的处理器：" + job.getJobType(), job.getTraceId());
                return;
            }
            AiFindingView finding = jobService.markSucceeded(job, handler.execute(job), job.getTraceId());
            log.info("AI 作业完成 jobId={} type={} findingId={}", job.getId(), job.getJobType(), finding.findingId());
        } catch (AiException exception) {
            failQuietly(job, exception.code(), exception.getMessage());
        } catch (Exception exception) {
            failQuietly(job, "INTERNAL_ERROR", "AI 作业执行异常");
            log.error("AI 作业执行异常 jobId={}", job == null ? null : job.getId(), exception);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private AiJobHandler handlerFor(AiJobEntity job) {
        try {
            return handlers.get(AiJobType.valueOf(job.getJobType()));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 标记失败本身再失败就只能记日志：异常若逃出这里，作业会停在 RUNNING 直到租约过期才被回收。 */
    private void failQuietly(AiJobEntity job, String errorCode, String detail) {
        if (job == null || job.getId() == null) {
            log.warn("作业未领取即失败（无 jobId 可标记）：{} {}", errorCode, detail);
            return;
        }
        try {
            jobService.markFailed(job, errorCode, detail, job.getTraceId());
        } catch (Exception exception) {
            log.error("标记作业失败时又出错 jobId={}", job.getId(), exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
