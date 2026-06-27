package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded fire-and-forget executor for off-request work: {@code last_used_at}
 * touches and {@code USE_DENIED} audit writes. These are observability, not
 * correctness — so the queue is bounded and overflow is silently discarded
 * rather than blocking the request loop (§4.3, §12).
 */
@Component
public class ApiKeyBackgroundExecutor {

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1024),
            r -> {
                Thread t = new Thread(r, "apikey-bg");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());   // full queue → drop, don't block

    public void submit(Runnable task) {
        executor.execute(task);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
