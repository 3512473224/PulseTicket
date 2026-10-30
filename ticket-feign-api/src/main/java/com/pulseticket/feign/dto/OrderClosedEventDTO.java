package com.pulseticket.feign.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 订单超时未支付自动关单 (或用户主动取消) 后通知 show-service 释放座位的事件载体
 */
public class OrderClosedEventDTO implements Serializable {

    private String messageId;
    private String orderNo;
    private Long userId;
    private Long sessionId;
    private List<Long> seatIds;
    private String closeReason;

    public OrderClosedEventDTO() {}

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

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

    public String getCloseReason() {
        return closeReason;
    }

    public void setCloseReason(String closeReason) {
        this.closeReason = closeReason;
    }
}
