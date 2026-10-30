package com.pulseticket.order.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseticket.common.context.UserContext;
import com.pulseticket.common.exception.BizException;
import com.pulseticket.common.result.Result;
import com.pulseticket.order.entity.TicketOrder;
import com.pulseticket.order.mapper.TicketOrderMapper;
import com.pulseticket.order.service.OrderFacade;
import org.springframework.web.bind.annotation.*;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderFacade orderFacade;
    private final TicketOrderMapper ticketOrderMapper;
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public OrderController(OrderFacade orderFacade, TicketOrderMapper ticketOrderMapper) {
        this.orderFacade = orderFacade;
        this.ticketOrderMapper = ticketOrderMapper;
    }

    public static class CreateOrderRequest {
        private Long sessionId;
        private List<Long> seatIds;
        private Boolean fastCloseDemo;
        private Boolean simulateSeataRollback;
        private Integer stressSlot;

        public Long getSessionId() {
            return sessionId;
        }

        public void setSessionId(Long sessionId) {
            this.sessionId = sessionId;
        }

        public List<Long> getSeatIds() {
            return seatIds;
        }

        public void setSeatIds(List<Long> seatIds) {
            this.seatIds = seatIds;
        }

        public Boolean getFastCloseDemo() {
            return fastCloseDemo;
        }

        public void setFastCloseDemo(Boolean fastCloseDemo) {
            this.fastCloseDemo = fastCloseDemo;
        }

        public Boolean getSimulateSeataRollback() {
            return simulateSeataRollback;
        }

        public void setSimulateSeataRollback(Boolean simulateSeataRollback) {
            this.simulateSeataRollback = simulateSeataRollback;
        }

        public Integer getStressSlot() {
            return stressSlot;
        }

        public void setStressSlot(Integer stressSlot) {
            this.stressSlot = stressSlot;
        }
    }

    /**
     * 公开电子票闸机扫码核验接口（微信扫一扫 / 手机相机直接打开 H5 核验页调用）
     */
    @GetMapping("/public/verify/{ticketCode}")
    public Result<Map<String, Object>> verifyTicketPublic(@PathVariable String ticketCode) {
        String code = ticketCode != null ? ticketCode.trim().toUpperCase() : "";
        if (code.isEmpty()) {
            throw new BizException(400, "核验码不能为空");
        }
        TicketOrder order = ticketOrderMapper.selectOne(
                new LambdaQueryWrapper<TicketOrder>().eq(TicketOrder::getTicketCode, code)
        );
        if (order == null) {
            throw new BizException(404, "未找到该电子票核验码，请确认票面真伪");
        }

        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("valid", "PAID".equals(order.getStatus()));
        vo.put("status", order.getStatus());
        vo.put("ticketCode", order.getTicketCode());
        vo.put("orderNo", order.getOrderNo());
        vo.put("showId", order.getShowId());
        vo.put("showTitle", order.getShowTitle());
        vo.put("venueName", order.getVenueName());
        vo.put("sessionTitle", order.getSessionTitle());
        vo.put("seatLabelsJson", order.getSeatLabelsJson());
        vo.put("seatCount", order.getSeatCount());
        vo.put("totalAmount", order.getTotalAmount());
        vo.put("paidAt", order.getPaidAt() != null ? order.getPaidAt().format(DT_FMT) : null);
        vo.put("verifiedAt", LocalDateTime.now().format(DT_FMT));
        return Result.ok("电子票核验成功", vo);
    }

    /**
     * 本地开发辅助：返回宿主机局域网 IPv4 地址，使 localhost 下生成的二维码也可被同 Wi-Fi 手机微信直接扫码打开
     */
    @GetMapping("/public/lan-ip")
    public Result<Map<String, String>> getLanIp() {
        String ip = detectLanIpv4();
        return Result.ok(Map.of("lanIp", ip));
    }

    @PostMapping("/create")
    public Result<TicketOrder> createOrder(@RequestBody CreateOrderRequest req) {
        Long userId = UserContext.getUserId();
        String username = UserContext.getUsername() != null ? UserContext.getUsername() : "user-" + userId;

        boolean isAdmin = UserContext.isAdmin();
        // 普通购票用户强制使用标准商业模式（15分钟锁座、禁止故障注入）
        boolean fastClose = isAdmin && Boolean.TRUE.equals(req.getFastCloseDemo());
        boolean simulateRollback = isAdmin && Boolean.TRUE.equals(req.getSimulateSeataRollback());

        // 架构师并发压测虚拟买家槽位（模拟 20 个不同买家并发争抢同一座位）
        if (isAdmin && req.getStressSlot() != null && req.getStressSlot() > 0 && req.getStressSlot() <= 100) {
            userId = 9000L + req.getStressSlot();
            username = "sim_buyer_" + req.getStressSlot();
        }

        TicketOrder order = orderFacade.createOrderWithThreeLayerDefense(
                userId,
                username,
                req.getSessionId(),
                req.getSeatIds(),
                fastClose,
                simulateRollback
        );
        return Result.ok("锁座下单成功，请在倒计时结束前完成支付", order);
    }

    @PostMapping("/{orderNo}/pay")
    public Result<TicketOrder> payOrder(@PathVariable String orderNo) {
        Long userId = UserContext.getUserId();
        TicketOrder paid = orderFacade.payOrder(userId, orderNo);
        return Result.ok("支付成功！电子票已签发", paid);
    }

    @PostMapping("/{orderNo}/cancel")
    public Result<TicketOrder> cancelOrder(@PathVariable String orderNo) {
        Long userId = UserContext.getUserId();
        TicketOrder cancelled = orderFacade.cancelOrderManually(userId, orderNo);
        return Result.ok("订单已取消，锁定座位已释放", cancelled);
    }

    @GetMapping("/my")
    public Result<List<TicketOrder>> listMyOrders() {
        Long userId = UserContext.getUserId();
        return Result.ok(orderFacade.listUserOrders(userId));
    }

    @GetMapping("/detail/{orderNo}")
    public Result<TicketOrder> getOrderDetail(@PathVariable String orderNo) {
        Long userId = UserContext.getUserId();
        return Result.ok(orderFacade.getOrderByNo(userId, orderNo));
    }

    @GetMapping("/telemetry")
    public Result<Map<String, Object>> getTelemetry() {
        if (!UserContext.isAdmin()) {
            throw new BizException(403, "仅限架构运维专员查看底层事务与消息审计日志");
        }
        return Result.ok(orderFacade.getArchitectureTelemetry());
    }

    private String detectLanIpv4() {
        String fallback = "127.0.0.1";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = ni.getDisplayName().toLowerCase();
                if (name.contains("docker") || name.contains("wsl") || name.contains("vethernet") || name.contains("vmware") || name.contains("virtualbox")) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        String host = addr.getHostAddress();
                        if (host.startsWith("192.168.") || host.startsWith("10.")) {
                            return host;
                        }
                        fallback = host;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
