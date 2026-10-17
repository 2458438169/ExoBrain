package com.kama.jchatmind.execution;

/** 单个事件的结束状态：事件只在真实结束时落库（没有“开始中”的悬空事件）。 */
public enum RunEventStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED
}
