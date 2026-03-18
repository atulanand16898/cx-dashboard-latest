package com.cxalloy.integration.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.client5.http.config.RequestConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class RestTemplateConfig {

    /**
     * Pooled RestTemplate — reuses TCP connections across all threads.
     *
     * maxTotal=100        : up to 100 open connections in the pool
     * defaultMaxPerRoute=30: up to 30 concurrent connections to the same host
     * connectTimeout=5s   : fail fast if CxAlloy is unreachable
     * responseTimeout=30s : long enough for slow endpoints, short enough to not block forever
     */
    @Bean
    public RestTemplate restTemplate() {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(100);
        cm.setDefaultMaxPerRoute(30);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(10))
                .setResponseTimeout(Timeout.ofSeconds(60))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .build();

        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        return new RestTemplate(factory);
    }

    /**
     * Shared thread pool for parallel project syncing.
     *
     * REDUCED to 5 threads to prevent:
     *   - CxAlloy API rate limiting (429s)
     *   - HTTP connection pool exhaustion
     *   - DB Hikari pool starvation
     *   - Avalanche of simultaneous timeouts
     *
     * With 5 threads: 525 projects process in batches of 5.
     * Each project calls 7 endpoints = 35 concurrent HTTP calls max.
     * This is well within safe limits for CxAlloy and our DB pool.
     *
     * Use named threads for easier debugging in logs.
     */
    @Bean(name = "syncExecutor")
    public ExecutorService syncExecutor() {
        return Executors.newFixedThreadPool(5, r -> {
            Thread t = new Thread(r);
            t.setName("sync-worker-" + t.getId());
            t.setDaemon(true);
            return t;
        });
    }
}
