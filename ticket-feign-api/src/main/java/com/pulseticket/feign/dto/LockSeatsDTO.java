package com.pulseticket.feign.dto;

import java.io.Serializable;
import java.util.List;

public class LockSeatsDTO implements Serializable {

    private String orderNo;
    private Long userId;
    private Long sessionId;
    private List<Long> seatIds;

    public LockSeatsDTO() {}

    public LockSeatsDTO(String orderNo, Long userId, Long sessionId, List<Long> seatIds) {
        this.orderNo = orderNo;
        this.userId = userId;
        this.sessionId = sessionId;
        this.seatIds = seatIds;
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
}
