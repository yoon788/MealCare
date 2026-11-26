package com.example.mealcare.data;

/** 한 환자의 기간별 복약 또는 식사 건수. 예정 = 완료 + 미복용(결식) + 미확인입니다. */
public final class RecordStatistics {
    private final long scheduled;
    private final long completed;
    private final long missed;

    RecordStatistics(long scheduled, long completed, long missed) {
        this.scheduled = scheduled;
        this.completed = completed;
        this.missed = missed;
    }

    public long getScheduledCount() { return scheduled; }
    public long getCompletedCount() { return completed; }
    /** 명시적으로 확인한 미복용 또는 결식 건수입니다. */
    public long getMissedCount() { return missed; }
    public long getUnconfirmedCount() { return scheduled - completed - missed; }
}
