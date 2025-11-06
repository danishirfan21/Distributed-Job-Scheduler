package com.distributed.jobscheduler.common.enums;

public enum Priority {
    LOW(1),
    NORMAL(5),
    HIGH(10),
    CRITICAL(20);

    private final int value;

    Priority(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
