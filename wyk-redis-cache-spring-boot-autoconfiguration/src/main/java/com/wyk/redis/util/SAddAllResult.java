package com.wyk.redis.util;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * sAddAllWithResume 执行结果，支持断点续传与重试。
 * 调用方可根据 processedCount 决定：重试时 skip(processedCount) 仅处理剩余部分。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SAddAllResult {

    /** 是否全部成功 */
    private boolean success;

    /** 已成功处理数量（可用于 skip，重试时从 values.subList(processedCount, size) 继续） */
    private int processedCount;

    /** 总数量 */
    private int totalCount;

    /** 失败时的异常信息 */
    private String errorMessage;

    public static SAddAllResult ok(int totalCount) {
        return new SAddAllResult(true, totalCount, totalCount, null);
    }

    public static SAddAllResult partial(int processedCount, int totalCount, String errorMessage) {
        return new SAddAllResult(false, processedCount, totalCount, errorMessage);
    }

    /** 剩余未处理数量，重试时只需处理这部分 */
    public int getRemainingCount() {
        return Math.max(0, totalCount - processedCount);
    }
}
