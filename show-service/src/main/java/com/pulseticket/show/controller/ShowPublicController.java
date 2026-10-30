package com.pulseticket.show.controller;

import com.pulseticket.common.result.Result;
import com.pulseticket.show.service.ShowCacheAndSeatService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/show")
public class ShowPublicController {

    private final ShowCacheAndSeatService showService;

    public ShowPublicController(ShowCacheAndSeatService showService) {
        this.showService = showService;
    }

    @GetMapping("/public/list")
    public Result<List<Map<String, Object>>> listShows(@RequestParam(required = false) String category) {
        return Result.ok(showService.listShows(category));
    }

    @GetMapping("/public/detail/{id}")
    public Result<Map<String, Object>> getShowDetail(@PathVariable Long id) {
        return Result.ok(showService.getShowDetailWithCacheProtection(id));
    }

    @GetMapping("/public/sessions/{sessionId}/seats")
    public Result<Map<String, Object>> getSessionSeats(@PathVariable Long sessionId) {
        return Result.ok(showService.getSessionSeatMap(sessionId));
    }

    @PostMapping("/cache/evict/{showId}")
    public Result<String> evictCache(@PathVariable Long showId) {
        showService.evictShowCaches(showId, null);
        return Result.ok("已清除 Redis 演出详情缓存 key=ticket:cache:show:" + showId);
    }
}
