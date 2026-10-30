package com.pulseticket.feign.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 支付成功后向 RabbitMQ Fanout 广播的事件载体
 * 1) show-service 监听将座位从 LOCKED -> SOLD
 * 2) user-service 监听增加用户积分并发送电子票签发站内信 (靠 UNIQUE(order_no, biz_type) 保证消费幂等)
 */
public class OrderPaidEventDTO implements Serializable {

    private String messageId;
    private String orderNo;
    private Long userId;
    private String username;
    private Long showId;
    private String showTitle;
    private String venueName;
    private Long sessionId;
    private String sessionTitle;
    private List<Long> seatIds;
    private List<String> seatLabels;
    private BigDecimal totalAmount;
    private String ticketCode;

    public OrderPaidEventDTO() {}

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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Long getShowId() {
        return showId;
    }

    public void setShowId(Long showId) {
        this.showId = showId;
    }

    public String getShowTitle() {
        return showTitle;
    }

    public void setShowTitle(String showTitle) {
        this.showTitle = showTitle;
    }

    public String getVenueName() {
        return venueName;
    }

    public void setVenueName(String venueName) {
        this.venueName = venueName;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionTitle() {
        return sessionTitle;
    }

    public void setSessionTitle(String sessionTitle) {
        this.sessionTitle = sessionTitle;
    }

    public List<Long> getSeatIds() {
        return seatIds;
    }

    public void setSeatIds(List<Long> seatIds) {
        this.seatIds = seatIds;
    }

    public List<String> getSeatLabels() {
        return seatLabels;
    }

    public void setSeatLabels(List<String> seatLabels) {
        this.seatLabels = seatLabels;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getTicketCode() {
        return ticketCode;
    }

    public void setTicketCode(String ticketCode) {
        this.ticketCode = ticketCode;
    }
}
