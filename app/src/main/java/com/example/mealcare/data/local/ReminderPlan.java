package com.example.mealcare.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;

import java.util.Objects;

@Entity(tableName = "medication_plans", primaryKeys = {"patientUid", "planId"})
public class ReminderPlan {
    @NonNull public final String patientUid;
    @NonNull public final String planId;
    public String sourceItemId; // 식약처 품목기준코드. 이전 버전의 행에서는 null입니다.
    public String drugId; // 이전 버전의 앱 내부 약품 ID
    @NonNull public String drugName;
    @NonNull public String startDate;
    public String endDate;
    @NonNull public String timeZone;
    @NonNull public String status;
    public int version;
    public boolean alarmEnabled;
    public int repeatIntervalMinutes;
    public int maxRepeatCount;

    public ReminderPlan(@NonNull String patientUid, @NonNull String planId) {
        this.patientUid = patientUid;
        this.planId = planId;
    }

    /** 여러 회차에 반복되는 계획 행을 Room의 복합 기본키로 묶습니다. */
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ReminderPlan)) return false;
        ReminderPlan plan = (ReminderPlan) other;
        return patientUid.equals(plan.patientUid) && planId.equals(plan.planId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(patientUid, planId);
    }
}
