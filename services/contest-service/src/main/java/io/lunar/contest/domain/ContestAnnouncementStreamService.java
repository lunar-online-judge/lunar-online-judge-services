package io.lunar.contest.domain;

import io.lunar.contest.domain.dto.AnnouncementView;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 考试公告 SSE 推送（单实例内存注册表）：
 * 每场考试维护一组长连接，发布公告后立即广播；每 20 秒发一次心跳，避免代理静默断链。
 *
 * <p>多实例部署时需换成 Redis Pub/Sub 或 MQ 广播，当前实现只覆盖单实例。</p>
 */
@Component
public class ContestAnnouncementStreamService {

    private static final long HEARTBEAT_SECONDS = 20L;

    private final Map<String, List<SseEmitter>> emittersByContest = new ConcurrentHashMap<>();

    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread heartbeatThread = new Thread(runnable, "announcement-sse-heartbeat");
                heartbeatThread.setDaemon(true);
                return heartbeatThread;
            });

    public ContestAnnouncementStreamService() {
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /** 建立某场考试的公告流；连接结束/超时/出错时自动从注册表移除。 */
    public SseEmitter subscribe(String contestId) {
        SseEmitter emitter = new SseEmitter(0L);
        emittersByContest.computeIfAbsent(contestId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable removeEmitter = () -> remove(contestId, emitter);
        emitter.onCompletion(removeEmitter);
        emitter.onTimeout(removeEmitter);
        emitter.onError(exception -> removeEmitter.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException exception) {
            removeEmitter.run();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    /** 向该场考试的所有订阅者广播新公告。 */
    public void broadcast(String contestId, AnnouncementView announcement) {
        List<SseEmitter> emitters = emittersByContest.get(contestId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("announcement")
                        .data(announcement, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException exception) {
                remove(contestId, emitter);
                emitter.completeWithError(exception);
            }
        }
    }

    private void sendHeartbeat() {
        emittersByContest.forEach((contestId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException exception) {
                    remove(contestId, emitter);
                    emitter.completeWithError(exception);
                }
            }
        });
    }

    private void remove(String contestId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByContest.get(contestId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByContest.remove(contestId);
        }
    }

    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
        emittersByContest.values().forEach(emitters ->
                emitters.forEach(SseEmitter::complete));
        emittersByContest.clear();
    }
}
