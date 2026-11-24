package com.example.mealcare.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;

@Entity(tableName = "dose_slots", primaryKeys = {"patientUid", "planId", "slotId"},
        foreignKeys = @ForeignKey(entity = ReminderPlan.class,
                parentColumns = {"patientUid", "planId"}, childColumns = {"patientUid", "planId"},
                onDelete = ForeignKey.CASCADE))
public class ReminderDoseSlot {
    @NonNull public final String patientUid;
    @NonNull public final String planId;
    @NonNull public final String slotId;
    public double doseAmount;
    @NonNull public String doseUnit;
    public int weekdaysMask; // 월요일은 비트 0, 일요일은 비트 6
    @NonNull public String timingType;
    public String localTime;
    public String mealPlanId;
    public Integer offsetMinutes;

    public ReminderDoseSlot(@NonNull String patientUid, @NonNull String planId, @NonNull String slotId) {
        this.patientUid = patientUid;
        this.planId = planId;
        this.slotId = slotId;
    }
}
