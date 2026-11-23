package com.example.mealcare.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;

@Entity(tableName = "meal_times", primaryKeys = {"patientUid", "mealPlanId", "dayOfWeek"})
public class ReminderMealTime {
    @NonNull public final String patientUid;
    @NonNull public final String mealPlanId;
    public final int dayOfWeek;
    @NonNull public String localTime;
    @NonNull public String timeZone;
    @NonNull public String startDate;
    public String endDate;
    @NonNull public String status;

    public ReminderMealTime(@NonNull String patientUid, @NonNull String mealPlanId, int dayOfWeek) {
        this.patientUid = patientUid;
        this.mealPlanId = mealPlanId;
        this.dayOfWeek = dayOfWeek;
    }
}
