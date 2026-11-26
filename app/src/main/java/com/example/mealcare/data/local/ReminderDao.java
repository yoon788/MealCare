package com.example.mealcare.data.local;

import androidx.annotation.Nullable;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Upsert;

import com.example.mealcare.data.model.DoseSlot;
import com.example.mealcare.data.model.MealPlan;
import com.example.mealcare.data.model.MealScheduleEntry;
import com.example.mealcare.data.model.MedicationPlans;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 기기에 저장된 알림용 계획과 회차를 조회합니다. 백그라운드 스레드에서 호출합니다.
 * 복약 계획과 회차를 함께 읽는 예시는 {@link #getEnabledPlansWithSlots(String)}에 있습니다.
 */
@Dao
public abstract class ReminderDao {
    @Upsert
    abstract void putPlan(ReminderPlan plan);

    @Insert
    abstract void insertSlot(ReminderDoseSlot slot);

    @Query("DELETE FROM dose_slots WHERE patientUid = :patientUid AND planId = :planId")
    abstract void clearSlots(String patientUid, String planId);

    /** 약 이름을 포함한 계획과 전체 회차를 한 트랜잭션에서 교체합니다. */
    public void saveMedicationPlan(MedicationPlans plan) {
        saveMedicationPlan(plan, null);
    }

    /** 등록 당시 제품명이 없는 이전 계획을 저장할 때만 별도의 이름을 받습니다. */
    @Transaction
    public void saveMedicationPlan(MedicationPlans plan, String drugName) {
        ReminderPlan row = plan.toReminderPlan(drugName);
        putPlan(row);
        clearSlots(row.patientUid, row.planId);
        for (DoseSlot slot : plan.getDoseSlots()) {
            insertSlot(slot.toReminderSlot(row.patientUid, row.planId));
        }
    }

    /**
     * @param patientUid 조회할 환자의 UID
     * @return 상태가 active이고 알림이 켜진 로컬 계획 목록. 일치하는 계획이 없으면 빈 목록입니다.
     *         특정 날짜의 복용 여부는 계획 기간과 {@link #getSlots(String, String)}의 반복 요일로 판단합니다.
     */
    @Query("SELECT * FROM medication_plans WHERE patientUid = :patientUid AND status = 'active' AND alarmEnabled = 1")
    public abstract List<ReminderPlan> getEnabledPlans(String patientUid);

    /**
     * 환자의 알림이 켜진 활성 계획과 그 계획의 모든 복용 회차를 Room에서 한 번에 읽습니다.
     * <pre>{@code
     * // 백그라운드 스레드에서 실행합니다.
     * ReminderDao dao = ReminderDatabase.getInstance(context).reminders();
     * dao.getEnabledPlansWithSlots(patientUid).forEach((plan, slots) -> {
     *     String drugName = plan.drugName;
     *     for (ReminderDoseSlot slot : slots) {
     *         // slot.doseAmount와 slot.doseUnit은 1회 복용량, slot.weekdaysMask는 반복 요일입니다.
     *     }
     * });
     * }</pre>
     * @param patientUid 조회할 환자의 UID
     * @return 계획을 키로, slotId 오름차순의 회차 목록을 값으로 갖는 맵.
     *         대상 계획이 없으면 빈 맵이며, 회차가 없는 계획도 빈 목록과 함께 반환합니다.
     */
    @Query("SELECT p.*, s.* FROM medication_plans AS p LEFT JOIN dose_slots AS s "
            + "ON s.patientUid = p.patientUid AND s.planId = p.planId "
            + "WHERE p.patientUid = :patientUid AND p.status = 'active' AND p.alarmEnabled = 1 "
            + "ORDER BY p.planId, s.slotId")
    public abstract Map<ReminderPlan, List<ReminderDoseSlot>> getEnabledPlansWithSlots(String patientUid);

    /**
     * 환자의 조회 날짜에 배정된 회차를 복용 예정 시각 순으로 읽습니다. 시작일과 종료일을 모두 포함합니다.
     * 식사 연동은 같은 날짜·요일의 활성 식사 시각에 offsetMinutes를 더합니다.
     * 식사 일정이 없거나 시각을 정할 수 없는 회차는 목록 끝에 오며 예정 시각은 null입니다.
     * <pre>{@code
     * // 백그라운드 스레드에서 실행합니다.
     * ReminderDao dao = ReminderDatabase.getInstance(context).reminders();
     * List<DailyMedicationDose> doses = dao.getDailyMedicationSchedule(patientUid, "2026-11-23");
     * for (DailyMedicationDose dose : doses) {
     *     String when = dose.scheduledLocalDateTime == null ? "시각 미정" : dose.scheduledLocalDateTime;
     *     String label = when + " " + dose.drugName + " " + dose.slot.doseAmount + dose.slot.doseUnit;
     * }
     * }</pre>
     * @param patientUid 조회할 환자의 UID
     * @param scheduleDate 회차가 배정된 날짜(yyyy-MM-dd). 자정을 넘는 회차도 이 날짜에 한 번만 반환합니다.
     * @return 알림이 켜진 활성 계획 중 적용 기간과 반복 요일이 일치하는 회차. 없으면 빈 목록입니다.
     * @throws IllegalArgumentException UID가 비었거나 날짜가 유효하지 않은 경우
     */
    public List<DailyMedicationDose> getDailyMedicationSchedule(String patientUid, String scheduleDate) {
        if (patientUid == null || patientUid.trim().isEmpty()) throw new IllegalArgumentException("환자 UID가 필요합니다.");
        int weekday = DailyMedicationDose.weekdayOf(scheduleDate);
        List<DailyMedicationDose> doses = new ArrayList<>();
        for (DailyMedicationDose.Row row : getDailyDoseRows(patientUid, scheduleDate, weekday, 1 << (weekday - 1))) {
            doses.add(new DailyMedicationDose(row, scheduleDate));
        }
        // 같은 시각(또는 미정)끼리는 SQL의 계획 ID·회차 ID 순서를 유지합니다.
        doses.sort(Comparator.comparing(dose -> dose.scheduledAtMillis, Comparator.nullsLast(Comparator.naturalOrder())));
        return doses;
    }

    // 식사 조건은 JOIN에 둬서 식사 시간이 아직 동기화되지 않은 복약 회차도 조회합니다.
    @Query("SELECT s.*, p.drugName, p.timeZone, m.localTime AS mealTime, m.timeZone AS mealZone "
            + "FROM medication_plans AS p JOIN dose_slots AS s ON s.patientUid = p.patientUid AND s.planId = p.planId "
            + "LEFT JOIN meal_times AS m ON s.timingType = 'mealRelative' AND m.patientUid = s.patientUid "
            + "AND m.mealPlanId = s.mealPlanId AND m.dayOfWeek = :weekday AND m.status = 'active' "
            + "AND m.startDate <= :date AND (m.endDate IS NULL OR m.endDate >= :date) "
            + "WHERE p.patientUid = :patientUid AND p.status = 'active' AND p.alarmEnabled = 1 "
            + "AND p.startDate <= :date AND (p.endDate IS NULL OR p.endDate >= :date) "
            + "AND (s.weekdaysMask & :dayMask) != 0 ORDER BY p.planId, s.slotId")
    abstract List<DailyMedicationDose.Row> getDailyDoseRows(String patientUid, String date, int weekday, int dayMask);

    /** 서버 전체 목록과 대조할 때 일시 중지되거나 알림이 꺼진 계획도 포함합니다. */
    @Query("SELECT planId FROM medication_plans WHERE patientUid = :patientUid")
    public abstract List<String> getMedicationPlanIds(String patientUid);

    /** 제품명이 없는 구형 문서를 받을 때 같은 환자·계획·약에 보관했던 표시명을 찾습니다. */
    @Nullable
    @Query("SELECT drugName FROM medication_plans WHERE patientUid = :patientUid AND planId = :planId AND drugId = :drugId")
    public abstract String getLegacyDrugName(String patientUid, String planId, String drugId);

    /**
     * @param patientUid 계획 소유자의 UID
     * @param planId 조회할 계획의 {@link ReminderPlan#planId}
     * @return 해당 계획의 복용 회차를 slotId 오름차순으로 반환합니다. 저장된 회차가 없으면 빈 목록입니다.
     *         고정 시각은 localTime, 식사 연동은 mealPlanId와 offsetMinutes를 읽습니다.
     */
    @Query("SELECT * FROM dose_slots WHERE patientUid = :patientUid AND planId = :planId ORDER BY slotId")
    public abstract List<ReminderDoseSlot> getSlots(String patientUid, String planId);

    @Query("DELETE FROM medication_plans WHERE patientUid = :patientUid AND planId = :planId")
    public abstract void deleteMedicationPlan(String patientUid, String planId);

    @Insert
    abstract void insertMealTime(ReminderMealTime time);

    /**
     * 환자의 식사 계획 중 지정한 날짜에 해당하는 활성 일정만 읽습니다. 알림을 꺼 둔 식사도 포함합니다.
     * 날짜(yyyy-MM-dd)와 반복 요일은 각 계획의 현지 날짜 기준이며, 시작일과 종료일을 모두 포함합니다.
     * 서로 다른 시간대의 식사도 실제 예정 시각순으로 반환합니다. 같은 시각이면 계획 ID순이고,
     * 서머타임 전환으로 현지 시각이 존재하지 않는 식사는 목록 끝에 둡니다. 일정이 없으면 빈 목록입니다.
     * <pre>{@code
     * // 백그라운드 스레드에서 조회합니다.
     * ReminderDao dao = ReminderDatabase.getInstance(context).reminders();
     * String date = "2026-11-23";
     * List<ReminderMealTime> meals = dao.getDailyMealSchedule(patientUid, date);
     * // 사용자가 목록에서 식사를 선택하고 완료를 확인했을 때 실행합니다.
     * ReminderMealTime selected = meals.get(selectedIndex);
     * new MealRecordRepository(patientUid).saveRecord(selected.mealPlanId, date, true);
     * }</pre>
     * @throws IllegalArgumentException UID가 비었거나 날짜가 유효하지 않은 경우
     */
    public List<ReminderMealTime> getDailyMealSchedule(String patientUid, String scheduleDate) {
        if (patientUid == null || patientUid.trim().isEmpty()) throw new IllegalArgumentException("환자 UID가 필요합니다.");
        int weekday = DailyMedicationDose.weekdayOf(scheduleDate);
        List<ReminderMealTime> meals = getDailyMealRows(patientUid, scheduleDate, weekday);
        // 같은 환자·요일에는 계획당 한 행입니다. 시간대 변환은 식사마다 한 번만 합니다.
        Map<String, Long> scheduledTimes = new HashMap<>();
        for (ReminderMealTime meal : meals) {
            scheduledTimes.put(meal.mealPlanId,
                    DailyMedicationDose.scheduledMillis(scheduleDate, meal.localTime, meal.timeZone, 0, meal.timeZone));
        }
        meals.sort(Comparator.comparing(meal -> scheduledTimes.get(meal.mealPlanId),
                Comparator.nullsLast(Comparator.naturalOrder())));
        return meals;
    }

    @Query("SELECT * FROM meal_times WHERE patientUid = :patientUid AND status = 'active' "
            + "AND dayOfWeek = :weekday AND startDate <= :date AND (endDate IS NULL OR endDate >= :date) "
            + "ORDER BY mealPlanId")
    abstract List<ReminderMealTime> getDailyMealRows(String patientUid, String date, int weekday);

    /** 재접속 시 서버와 대조할 식사 계획 ID를 가져옵니다. 여러 요일에 걸친 계획도 한 번만 반환합니다. */
    @Query("SELECT DISTINCT mealPlanId FROM meal_times WHERE patientUid = :patientUid")
    public abstract List<String> getMealPlanIds(String patientUid);

    @Query("DELETE FROM meal_times WHERE patientUid = :patientUid AND mealPlanId = :mealPlanId")
    public abstract void deleteMealPlan(String patientUid, String mealPlanId);

    /** 이전 요일을 지우고 새 주간 일정을 한 트랜잭션에 저장합니다. 실패하면 이전 일정이 보존됩니다. */
    @Transaction
    public void saveMealPlan(MealPlan meal) {
        deleteMealPlan(meal.getPatientUid(), meal.getMealPlanId());
        for (MealScheduleEntry entry : meal.getWeeklySchedule()) {
            for (int day : entry.getDaysOfWeek()) {
                if (day < 1 || day > 7) {
                    throw new IllegalArgumentException("요일은 1~7이어야 합니다.");
                }
                ReminderMealTime row = new ReminderMealTime(meal.getPatientUid(), meal.getMealPlanId(), day);
                row.localTime = entry.getLocalTime();
                row.timeZone = meal.getTimeZone();
                row.startDate = meal.getStartDate();
                row.endDate = meal.getEndDate();
                row.status = meal.getStatus();
                insertMealTime(row);
            }
        }
    }

    /**
     * 식사 연동 회차의 기준 시각을 조회합니다.
     * @param patientUid 식사 계획 소유자의 UID
     * @param mealPlanId 복용 회차의 mealPlanId
     * @param dayOfWeek 조회할 요일(월요일 1 ~ 일요일 7)
     * @return 해당 요일에 저장된 식사 일정. 일치하는 행이 없으면 null입니다.
     */
    @Query("SELECT * FROM meal_times WHERE patientUid = :patientUid AND mealPlanId = :mealPlanId AND dayOfWeek = :dayOfWeek")
    public abstract ReminderMealTime getMealTime(String patientUid, String mealPlanId, int dayOfWeek);
}
