package com.pulseticket.order.controller;

import com.pulseticket.common.context.UserContext;
import com.pulseticket.common.result.Result;
import com.pulseticket.order.entity.TicketOrder;
import com.pulseticket.order.service.OrderFacade;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderFacade orderFacade;

    public OrderController(OrderFacade orderFacade) {
        this.orderFacade = orderFacade;
    }

    public static class CreateOrderRequest {
        private Long sessionId;
        private List<Long> seatIds;
        private Boolean fastCloseDemo;
        private Boolean simulateSeataRollback;

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
    }

    @PostMapping("/create")
    public Result<TicketOrder> createOrder(@RequestBody CreateOrderRequest req) {
        Long userId = UserContext.getUserId();
        String username = UserContext.getUsername() != null ? UserContext.getUsername() : "user-" + userId;
        TicketOrder order = orderFacade.createOrderWithThreeLayerDefense(
                userId,
                username,
                req.getSessionId(),
                req.getSeatIds(),
                Boolean.TRUE.equals(req.getFastCloseDemo()),
                Boolean.TRUE.equals(req.getSimulateSeataRollback())
        );
        return Result.ok("锁座下单成功，请在倒计时结束前完成支付", order);
    }

    @PostMapping("/{orderNo}/pay")
    public Result<TicketOrder> payOrder(@PathVariable String orderNo) {
        Long userId = UserContext.getUserId();
        TicketOrder paid = orderFacade.payOrder(userId, orderNo);
        return Result.ok("支付成功！已通过 RabbitMQ Fanout 异步签发电子票并累加积分", paid);
    }

    @PostMapping("/{orderNo}/cancel")
    public Result<TicketOrder> cancelOrder(@PathVariable String orderNo) {
        Long userId = UserContext.getUserId();
        TicketOrder cancelled = orderFacade.cancelOrderManually(userId, orderNo);
        return Result.ok("订单已取消，锁定座位已立即释放", cancelled);
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
        return Result.ok(orderFacade.getArchitectureTelemetry());
    }
}
