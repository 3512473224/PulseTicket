package com.pulseticket.common.page;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

public class PageDTO<T> implements Serializable {

    private long total;
    private long pageNo;
    private long pageSize;
    private List<T> records;

    public PageDTO() {}

    public PageDTO(long total, long pageNo, long pageSize, List<T> records) {
        this.total = total;
        this.pageNo = pageNo;
        this.pageSize = pageSize;
        this.records = records != null ? records : Collections.emptyList();
    }

    public static <T> PageDTO<T> of(long total, long pageNo, long pageSize, List<T> records) {
        return new PageDTO<>(total, pageNo, pageSize, records);
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getPageNo() {
        return pageNo;
    }

    public void setPageNo(long pageNo) {
        this.pageNo = pageNo;
    }

    public long getPageSize() {
        return pageSize;
    }

    public void setPageSize(long pageSize) {
        this.pageSize = pageSize;
    }

    public List<T> getRecords() {
        return records;
    }

    public void setRecords(List<T> records) {
        this.records = records;
    }
}
