package com.pulseticket.show.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulseticket.common.exception.BizException;
import com.pulseticket.feign.dto.LockSeatsDTO;
import com.pulseticket.feign.dto.OrderClosedEventDTO;
import com.pulseticket.feign.dto.OrderPaidEventDTO;
import com.pulseticket.feign.dto.SeatLockResultVO;
import com.pulseticket.show.entity.SeatInventory;
import com.pulseticket.show.entity.ShowInfo;
import com.pulseticket.show.entity.ShowSession;
import com.pulseticket.show.mapper.SeatInventoryMapper;
import com.pulseticket.show.mapper.ShowInfoMapper;
import com.pulseticket.show.mapper.ShowSessionMapper;
import io.seata.core.context.RootContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
public class ShowCacheAndSeatService {

    private static final Logger log = LoggerFactory.getLogger(ShowCacheAndSeatService.class);

    public static final String CACHE_SHOW_DETAIL_PREFIX = "ticket:cache:show:";
    public static final String CACHE_SEATMAP_PREFIX = "ticket:cache:seatmap:";
    public static final String LOCK_CACHE_REBUILD_PREFIX = "lock:cache:show:rebuild:";
    public static final String NULL_SENTINEL = "__NULL__";

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ShowInfoMapper showInfoMapper;
    private final ShowSessionMapper showSessionMapper;
    private final SeatInventoryMapper seatInventoryMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    public ShowCacheAndSeatService(
            ShowInfoMapper showInfoMapper,
            ShowSessionMapper showSessionMapper,
            SeatInventoryMapper seatInventoryMapper,
            StringRedisTemplate redisTemplate,
            RedissonClient redissonClient
    ) {
        this.showInfoMapper = showInfoMapper;
        this.showSessionMapper = showSessionMapper;
        this.seatInventoryMapper = seatInventoryMapper;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public List<Map<String, Object>> listShows(String category) {
        LambdaQueryWrapper<ShowInfo> qw = new LambdaQueryWrapper<>();
        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category)) {
            qw.eq(ShowInfo::getCategory, category.trim().toUpperCase());
        }
        qw.orderByDesc(ShowInfo::getHotScore);
        List<ShowInfo> shows = showInfoMapper.selectList(qw);
        List<ShowSession> allSessions = showSessionMapper.selectList(
                new LambdaQueryWrapper<ShowSession>().orderByAsc(ShowSession::getStartTime)
        );

        Map<Long, List<ShowSession>> sessionGroup = new HashMap<>();
        for (ShowSession s : allSessions) {
            sessionGroup.computeIfAbsent(s.getShowId(), k -> new ArrayList<>()).add(s);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (ShowInfo show : shows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("show", show);
            item.put("sessions", sessionGroup.getOrDefault(show.getId(), Collections.emptyList()));
            result.add(item);
        }
        return result;
    }

    /**
     * 热点演出详情查询 — 完整实现 Redis 缓存三座大山防护：
     * 1. 防缓存穿透 (Penetration)：不存在的 ID 缓存 "__NULL__" 空对象 (短 TTL 60s)
     * 2. 防缓存击穿 (Breakdown)：热点 Key 失效时采用 Redisson RLock 互斥锁 + 双重检查锁重建
     * 3. 防缓存雪崩 (Avalanche)：基础 TTL 1800s + 随机抖动值 (60~360s)
     */
    public Map<String, Object> getShowDetailWithCacheProtection(Long showId) {
        String cacheKey = CACHE_SHOW_DETAIL_PREFIX + showId;
        String cachedJson = redisTemplate.opsForValue().get(cacheKey);

        if (cachedJson != null) {
            if (NULL_SENTINEL.equals(cachedJson)) {
                Map<String, Object> guardResp = new LinkedHashMap<>();
                guardResp.put("cacheStatus", "REDIS_NULL_GUARD");
                guardResp.put("message", "触发 Redis 空值哨兵防穿透拦截 (__NULL__, TTL<=60s)，未查询 MySQL");
                guardResp.put("show", null);
                return guardResp;
            }
            try {
                Map<String, Object> data = objectMapper.readValue(cachedJson, new TypeReference<>() {});
                data.put("cacheStatus", "REDIS_HIT");
                data.put("ttlSeconds", redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS));
                return data;
            } catch (Exception ignored) {
            }
        }

        // 缓存未命中：使用 Redisson 互斥锁防止热点 Key 并发击穿 MySQL
        RLock rebuildLock = redissonClient.getLock(LOCK_CACHE_REBUILD_PREFIX + showId);
        rebuildLock.lock();
        try {
            // Double Check 双重检查
            String secondCheck = redisTemplate.opsForValue().get(cacheKey);
            if (secondCheck != null) {
                if (NULL_SENTINEL.equals(secondCheck)) {
                    return Map.of("cacheStatus", "REDIS_NULL_GUARD", "show", Collections.emptyMap());
                }
                Map<String, Object> data = objectMapper.readValue(secondCheck, new TypeReference<>() {});
                data.put("cacheStatus", "REDIS_HIT_AFTER_WAIT");
                return data;
            }

            ShowInfo show = showInfoMapper.selectById(showId);
            if (show == null) {
                // 写入空值哨兵防穿透 (60s 短过期)
                redisTemplate.opsForValue().set(cacheKey, NULL_SENTINEL, Duration.ofSeconds(60));
                Map<String, Object> missResp = new LinkedHashMap<>();
                missResp.put("cacheStatus", "PENETRATION_PROTECTED");
                missResp.put("message", "MySQL 中不存在该演出，已写入 Redis __NULL__ 哨兵 (TTL=60s)");
                missResp.put("show", null);
                return missResp;
            }

            List<ShowSession> sessions = showSessionMapper.selectList(
                    new LambdaQueryWrapper<ShowSession>()
                            .eq(ShowSession::getShowId, showId)
                            .orderByAsc(ShowSession::getStartTime)
            );

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("show", show);
            payload.put("sessions", sessions);

            // 防缓存雪崩：基础 1800s + 随机抖动 60~360s
            long jitterTtl = 1800L + ThreadLocalRandom.current().nextInt(60, 361);
            redisTemplate.opsForValue().set(
                    cacheKey,
                    objectMapper.writeValueAsString(payload),
                    Duration.ofSeconds(jitterTtl)
            );

            payload.put("cacheStatus", "MYSQL_MUTEX_REBUILT");
            payload.put("ttlSeconds", jitterTtl);
            return payload;
        } catch (Exception e) {
            throw new BizException(500, "加载演出详情失败: " + e.getMessage());
        } finally {
            if (rebuildLock.isHeldByCurrentThread()) {
                rebuildLock.unlock();
            }
        }
    }

    /**
     * 查询场次实时座位图
     */
    public Map<String, Object> getSessionSeatMap(Long sessionId) {
        ShowSession session = showSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException(404, "演出场次不存在: " + sessionId);
        }
        ShowInfo show = showInfoMapper.selectById(session.getShowId());
        List<SeatInventory> seats = seatInventoryMapper.selectList(
                new LambdaQueryWrapper<SeatInventory>()
                        .eq(SeatInventory::getSessionId, sessionId)
                        .orderByAsc(SeatInventory::getRowNo)
                        .orderByAsc(SeatInventory::getColNo)
        );

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("show", show);
        res.put("session", session);
        res.put("seats", seats);
        return res;
    }

    /**
     * Seata AT 分支事务：锁定所选座位
     * 面试核心考点：
     * 1. 通过 WHERE status = 'AVAILABLE' 实现数据库乐观条件更新兜底；
     * 2. 受影響行数 updated != seatIds.size() 立即抛异常触发全局回滚；
     * 3. 采用 Cache-Aside (先更新数据库，再删除缓存)。
     */
    @Transactional(rollbackFor = Exception.class)
    public SeatLockResultVO lockSeatsInAtTransaction(LockSeatsDTO dto) {
        String xid = RootContext.getXID();
        log.info("[Seata RM show-service] 收到锁座请求 orderNo={}, sessionId={}, seatIds={}, XID={}",
                dto.getOrderNo(), dto.getSessionId(), dto.getSeatIds(), xid);

        if (dto.getSeatIds() == null || dto.getSeatIds().isEmpty()) {
            throw new BizException("请至少选择 1 个座位");
        }

        ShowSession session = showSessionMapper.selectById(dto.getSessionId());
        if (session == null) {
            throw new BizException(404, "场次不存在");
        }
        ShowInfo show = showInfoMapper.selectById(session.getShowId());

        int updated = seatInventoryMapper.lockAvailableSeats(
                dto.getSessionId(),
                dto.getOrderNo(),
                dto.getUserId(),
                dto.getSeatIds()
        );
        if (updated != dto.getSeatIds().size()) {
            throw new BizException(409, "手慢了一步！所选座位刚被其他观众抢占，请重新选座");
        }

        showSessionMapper.syncSessionSeatCounts(dto.getSessionId());

        List<SeatInventory> lockedSeats = seatInventoryMapper.selectBatchIds(dto.getSeatIds());
        BigDecimal totalAmount = BigDecimal.ZERO;
        List<String> labels = new ArrayList<>();
        for (SeatInventory seat : lockedSeats) {
            totalAmount = totalAmount.add(seat.getPrice());
            labels.add(seat.getZoneName() + " " + seat.getSeatLabel());
        }

        evictShowCaches(session.getShowId(), dto.getSessionId());
        registerPostTxCacheEviction(session.getShowId(), dto.getSessionId());

        SeatLockResultVO vo = new SeatLockResultVO();
        vo.setOrderNo(dto.getOrderNo());
        vo.setShowId(show.getId());
        vo.setShowTitle(show.getTitle());
        vo.setVenueName(show.getVenueName());
        vo.setSessionId(session.getId());
        vo.setSessionTitle(session.getSessionTitle());
        vo.setStartTime(session.getStartTime().format(DT_FMT));
        vo.setSeatIds(dto.getSeatIds());
        vo.setSeatLabels(labels);
        vo.setTotalAmount(totalAmount);
        return vo;
    }

    /**
     * 超时未支付关单或取消时幂等释放座位 (LOCKED -> AVAILABLE)
     */
    @Transactional(rollbackFor = Exception.class)
    public int releaseSeatsIdempotent(OrderClosedEventDTO dto) {
        int released = seatInventoryMapper.releaseLockedSeatsByOrder(dto.getSessionId(), dto.getOrderNo());
        if (released > 0) {
            ShowSession session = showSessionMapper.selectById(dto.getSessionId());
            showSessionMapper.syncSessionSeatCounts(dto.getSessionId());
            if (session != null) {
                evictShowCaches(session.getShowId(), dto.getSessionId());
            }
            log.info("[座位库存已释放] orderNo={}, releasedSeats={}, reason={}",
                    dto.getOrderNo(), released, dto.getCloseReason());
        } else {
            log.info("[座位释放幂等跳过] orderNo={} 座位已释放或未处于锁定态", dto.getOrderNo());
        }
        return released;
    }

    /**
     * 支付成功后幂等确认座位售出 (LOCKED -> SOLD)
     */
    @Transactional(rollbackFor = Exception.class)
    public int confirmSeatsSoldIdempotent(OrderPaidEventDTO dto) {
        int sold = seatInventoryMapper.confirmLockedSeatsSold(dto.getSessionId(), dto.getOrderNo());
        if (sold > 0) {
            showSessionMapper.syncSessionSeatCounts(dto.getSessionId());
            evictShowCaches(dto.getShowId(), dto.getSessionId());
            log.info("[座位状态流转 LOCKED->SOLD] orderNo={}, soldSeats={}", dto.getOrderNo(), sold);
        } else {
            log.info("[座位出票幂等跳过] orderNo={} 座位已处于 SOLD 状态", dto.getOrderNo());
        }
        return sold;
    }

    public void evictShowCaches(Long showId, Long sessionId) {
        if (showId != null) {
            redisTemplate.delete(CACHE_SHOW_DETAIL_PREFIX + showId);
        }
        if (sessionId != null) {
            redisTemplate.delete(CACHE_SEATMAP_PREFIX + sessionId);
        }
    }

    private void registerPostTxCacheEviction(Long showId, Long sessionId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    evictShowCaches(showId, sessionId);
                }
            });
        }
    }
}
