package com.miunlock.sniper.core;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

public final class HttpSender {

    private final Session session;
    private final OkHttpClient client;
    private final ExecutorService warmup = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "preconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean preconnecting = new AtomicBoolean(false);

    public HttpSender(Session session) {
        this.session = session;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(3500, TimeUnit.MILLISECONDS)
                .readTimeout(4000, TimeUnit.MILLISECONDS)
                .writeTimeout(4000, TimeUnit.MILLISECONDS)
                .callTimeout(4500, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(true)
                .connectionPool(new ConnectionPool(4, 5, TimeUnit.MINUTES))
                .build();
    }

    public OkHttpClient client() {
        return client;
    }

    /** Прогревает DNS и TLS-сессию до цели: статус-запрос за пару сотен миллисекунд до отправки. */
    public void preconnect() {
        if (!preconnecting.compareAndSet(false, true)) {
            return;
        }
        warmup.execute(() -> {
            final long startedAt = System.nanoTime();
            try {
                InetAddress.getAllByName("sgp-api.buy.mi.com");
                final ApplyResult warm = CommunityApi.state(client, session);
                final long elapsedMs = (System.nanoTime() - startedAt) / 1000000L;
                Trace.i("HTTP", "канал прогрет за " + elapsedMs + " мс, соединение в пуле, статус: " + warm.describe());
            } catch (IOException error) {
                Trace.w("HTTP", "прогрев не удался: " + error.getMessage());
            } finally {
                preconnecting.set(false);
            }
        });
    }

    public ApplyResult apply() throws IOException {
        return CommunityApi.apply(client, session);
    }

    public ApplyResult state() throws IOException {
        return CommunityApi.state(client, session);
    }

    public ApplyResult logout() throws IOException {
        return CommunityApi.logout(client, session);
    }

    public void shutdown() {
        warmup.shutdownNow();
        try {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        } catch (Exception ignored) {
        }
    }
}
