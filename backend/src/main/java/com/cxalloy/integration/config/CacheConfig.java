package com.cxalloy.integration.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Spring Cache configuration — Caffeine L1 in-process cache.
 *
 * Cache regions and their TTLs:
 *
 *  Entity list caches (serve GET /api/checklists, /tasks etc)
 *   checklists-by-project   5 min   per projectId key
 *   tasks-by-project        5 min
 *   issues-by-project       5 min
 *   assets-by-project       5 min
 *   persons-by-project      5 min
 *   companies-by-project    5 min
 *   roles-by-project        5 min
 *   checklists-all         10 min   full table scan (rare)
 *   tasks-all              10 min
 *   issues-all             10 min
 *   assets-all             10 min
 *   persons-all            10 min
 *   companies-all          10 min
 *   roles-all              10 min
 *   entity-by-id           15 min   single-record lookups (very stable)
 *
 *  Project cache (tiny, very hot)
 *   projects-all            5 min   max 1 entry
 *   projects-by-id         15 min   max 200 entries
 *
 *  File storage (slow to compute — keep long)
 *   file-report            10 min
 *   file-duplicates        10 min
 *   file-largest           10 min
 *   file-heaviest          10 min
 *
 *  Sync stats (dashboard widget — short TTL for freshness)
 *   sync-stats              2 min
 *   sync-status             30 sec
 *
 * Hibernate L2 entity cache regions are configured separately via
 * application.properties (jcache / Caffeine provider).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager mgr = new SimpleCacheManager();
        mgr.setCaches(Arrays.asList(

            // ── Per-project entity lists (most-used; 5-min TTL, max 100 projects) ──
            build("checklists-by-project",  5,  TimeUnit.MINUTES, 100),
            build("tasks-by-project",       5,  TimeUnit.MINUTES, 100),
            build("issues-by-project",      5,  TimeUnit.MINUTES, 100),
            build("assets-by-project",      5,  TimeUnit.MINUTES, 100),
            build("equipment-by-project",   5,  TimeUnit.MINUTES, 100),
            build("persons-by-project",     5,  TimeUnit.MINUTES, 100),
            build("companies-by-project",   5,  TimeUnit.MINUTES, 100),
            build("roles-by-project",       5,  TimeUnit.MINUTES, 100),

            // ── Full-table list caches (admin use, rare) ──
            build("checklists-all",         10, TimeUnit.MINUTES, 5),
            build("tasks-all",              10, TimeUnit.MINUTES, 5),
            build("issues-all",             10, TimeUnit.MINUTES, 5),
            build("assets-all",             10, TimeUnit.MINUTES, 5),
            build("equipment-all",          10, TimeUnit.MINUTES, 5),
            build("persons-all",            10, TimeUnit.MINUTES, 5),
            build("companies-all",          10, TimeUnit.MINUTES, 5),
            build("roles-all",              10, TimeUnit.MINUTES, 5),

            // ── Single-record lookups (very stable; 15-min TTL) ──
            build("entity-by-id",           15, TimeUnit.MINUTES, 1000),

            // ── Project list (tiny, very hot) ──
            build("projects-all",           5,  TimeUnit.MINUTES, 5),
            build("projects-by-id",         15, TimeUnit.MINUTES, 500),
            build("projects-external",      15, TimeUnit.MINUTES, 500),

            // ── File storage analysis ──
            build("file-report",            10, TimeUnit.MINUTES, 50),
            build("file-duplicates",        10, TimeUnit.MINUTES, 50),
            build("file-largest",           10, TimeUnit.MINUTES, 50),
            build("file-heaviest",          10, TimeUnit.MINUTES, 50),

            // ── Sync stats / status (short TTL for dashboard freshness) ──
            build("sync-stats",             2,  TimeUnit.MINUTES, 5),
            build("sync-status",            30, TimeUnit.SECONDS, 5)
        ));
        return mgr;
    }

    private CaffeineCache build(String name, long duration, TimeUnit unit, long maxSize) {
        return new CaffeineCache(name,
            Caffeine.newBuilder()
                .expireAfterWrite(duration, unit)
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }
}

// NOTE: ShallowEtagHeaderFilter must be declared in a @Configuration class
// We add it here to keep all cache-related beans together.
