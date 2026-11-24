package com.example.mealcare.data.local;

import android.content.Context;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.mealcare.data.model.DoseSlot;
import com.example.mealcare.data.model.MealPlan;
import com.example.mealcare.data.model.MealScheduleEntry;
import com.example.mealcare.data.model.MedicationPlans;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReminderDatabaseTest {
    @Test
    public void enabledPlansWithSlotsGroupsByPatientAndPlan() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ReminderDatabase db = Room.inMemoryDatabaseBuilder(context, ReminderDatabase.class).build();
        try {
            ReminderDao dao = db.reminders();
            ReminderPlan daily = insertPlan(dao, "A", "daily", "active", true);
            ReminderPlan empty = insertPlan(dao, "A", "empty", "active", true);
            ReminderPlan weekly = insertPlan(dao, "A", "weekly", "active", true);
            insertPlan(dao, "A", "muted", "active", false);
            insertPlan(dao, "A", "paused", "paused", true);
            insertPlan(dao, "B", "daily", "active", true);
            insertSlot(dao, "A", "daily", "pm");
            insertSlot(dao, "A", "daily", "am");
            insertSlot(dao, "A", "weekly", "only");
            insertSlot(dao, "B", "daily", "other");

            Map<ReminderPlan, List<ReminderDoseSlot>> schedules = dao.getEnabledPlansWithSlots("A");
            assertEquals(3, schedules.size());
            List<ReminderDoseSlot> slots = schedules.get(daily);
            assertEquals(2, slots.size());
            assertEquals("am", slots.get(0).slotId);
            assertEquals("pm", slots.get(1).slotId);
            assertEquals(1, schedules.get(weekly).size());
            assertEquals("only", schedules.get(weekly).get(0).slotId);
            assertTrue(schedules.get(empty).isEmpty());
            assertTrue(dao.getEnabledPlansWithSlots("unknown").isEmpty());
        } finally {
            db.close();
        }
    }

    private static ReminderPlan insertPlan(ReminderDao dao, String patientUid, String planId,
                                           String status, boolean alarmEnabled) {
        ReminderPlan plan = new ReminderPlan(patientUid, planId);
        plan.drugName = "테스트 약";
        plan.startDate = "2026-01-01";
        plan.timeZone = "Asia/Seoul";
        plan.status = status;
        plan.alarmEnabled = alarmEnabled;
        dao.putPlan(plan);
        return plan;
    }

    private static ReminderDoseSlot fixedSlot(String patientUid, String planId, String slotId) {
        ReminderDoseSlot slot = new ReminderDoseSlot(patientUid, planId, slotId);
        slot.doseAmount = 1.0;
        slot.doseUnit = "정";
        slot.weekdaysMask = 1;
        slot.timingType = "fixed";
        slot.localTime = "08:00";
        return slot;
    }

    private static void insertSlot(ReminderDao dao, String patientUid, String planId, String slotId) {
        dao.insertSlot(fixedSlot(patientUid, planId, slotId));
    }

    private static void relativeSlot(ReminderDao dao, String id, String mealId, int offset, int dayMask) {
        ReminderDoseSlot slot = fixedSlot("A", "plan", id);
        slot.timingType = "mealRelative";
        slot.localTime = null;
        slot.mealPlanId = mealId;
        slot.offsetMinutes = offset;
        slot.weekdaysMask = dayMask;
        dao.insertSlot(slot);
    }

    private static ReminderMealTime mealTime(String uid, String mealId, int day, String time, String zone) {
        ReminderMealTime meal = new ReminderMealTime(uid, mealId, day);
        meal.localTime = time;
        meal.timeZone = zone;
        meal.status = "active";
        meal.startDate = "2026-01-01";
        return meal;
    }

    @Test public void dailyMealScheduleFiltersByPatientDateAndOrdersAcrossTimeZones() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ReminderDatabase db = Room.inMemoryDatabaseBuilder(context, ReminderDatabase.class).build();
        try {
            ReminderDao dao = db.reminders();
            String sunday = "2026-03-08";
            ReminderMealTime lunch = mealTime("A", "lunch", 7, "12:00", "Asia/Seoul");
            lunch.startDate = sunday;
            lunch.endDate = sunday;
            dao.insertMealTime(lunch);
            dao.insertMealTime(mealTime("A", "breakfast", 7, "03:00", "UTC")); // 서울 12:00와 같은 시각
            dao.insertMealTime(mealTime("A", "early", 7, "08:00", "Asia/Seoul"));
            dao.insertMealTime(mealTime("A", "dst-gap", 7, "02:30", "America/New_York"));
            dao.insertMealTime(mealTime("B", "other", 7, "08:30", "UTC"));
            dao.insertMealTime(mealTime("A", "monday", 1, "09:30", "UTC"));
            ReminderMealTime paused = mealTime("A", "paused", 7, "07:00", "UTC");
            paused.status = "paused";
            dao.insertMealTime(paused);
            ReminderMealTime future = mealTime("A", "future", 7, "07:00", "UTC");
            future.startDate = "2026-03-09";
            dao.insertMealTime(future);
            ReminderMealTime expired = mealTime("A", "expired", 7, "07:00", "UTC");
            expired.endDate = "2026-03-07";
            dao.insertMealTime(expired);

            List<ReminderMealTime> meals = dao.getDailyMealSchedule("A", sunday);
            assertEquals(4, meals.size());
            assertEquals("early", meals.get(0).mealPlanId);
            assertEquals("breakfast", meals.get(1).mealPlanId);
            assertEquals("lunch", meals.get(2).mealPlanId);
            assertEquals("12:00", meals.get(2).localTime);
            assertEquals("dst-gap", meals.get(3).mealPlanId);
            assertTrue(dao.getDailyMealSchedule("A", "2026-03-10").isEmpty());
            assertThrows(IllegalArgumentException.class, () -> dao.getDailyMealSchedule(" ", sunday));
            assertThrows(IllegalArgumentException.class, () -> dao.getDailyMealSchedule("A", "2026-02-30"));
        } finally {
            db.close();
        }
    }

    @Test public void dailyScheduleFiltersPlansAndCombinesFixedAndRelativeDoses() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ReminderDatabase db = Room.inMemoryDatabaseBuilder(context, ReminderDatabase.class).build();
        try {
            ReminderDao dao = db.reminders();
            String monday = "2026-11-23";
            ReminderPlan plan = insertPlan(dao, "A", "plan", "active", true);
            plan.startDate = monday;
            plan.endDate = monday;
            dao.putPlan(plan);
            insertSlot(dao, "A", "plan", "fixed");
            ReminderDoseSlot tuesday = fixedSlot("A", "plan", "tue");
            tuesday.weekdaysMask = 2;
            dao.insertSlot(tuesday);
            insertPlan(dao, "B", "plan", "active", true);
            insertSlot(dao, "B", "plan", "other");
            insertPlan(dao, "A", "off", "active", false);
            insertSlot(dao, "A", "off", "off");
            insertPlan(dao, "A", "paused", "paused", true);
            insertSlot(dao, "A", "paused", "paused");

            ReminderMealTime breakfast = mealTime("A", "breakfast", 1, "08:00", "Asia/Seoul");
            breakfast.startDate = monday;
            breakfast.endDate = monday;
            dao.insertMealTime(breakfast);
            dao.insertMealTime(mealTime("A", "early", 1, "00:10", "Asia/Seoul"));
            dao.insertMealTime(mealTime("A", "late", 1, "23:50", "Asia/Seoul"));
            // 다른 환자, 다른 요일, 기간 만료, 중단된 식사는 기준 시각으로 쓸 수 없습니다.
            dao.insertMealTime(mealTime("B", "missing", 1, "09:00", "Asia/Seoul"));
            dao.insertMealTime(mealTime("A", "missing", 2, "09:00", "Asia/Seoul"));
            ReminderMealTime expired = mealTime("A", "expired", 1, "10:00", "Asia/Seoul");
            expired.endDate = "2026-11-22";
            dao.insertMealTime(expired);
            ReminderMealTime stopped = mealTime("A", "stopped", 1, "08:00", "Asia/Seoul");
            stopped.status = "stopped";
            dao.insertMealTime(stopped);

            relativeSlot(dao, "after", "breakfast", 30, 1);
            relativeSlot(dao, "before", "breakfast", -30, 1);
            relativeSlot(dao, "previous", "early", -30, 1);
            relativeSlot(dao, "next", "late", 30, 1);
            relativeSlot(dao, "missing", "missing", 30, 1);
            relativeSlot(dao, "expired", "expired", 30, 1);
            relativeSlot(dao, "stopped", "stopped", 30, 1);
            List<DailyMedicationDose> doses = dao.getDailyMedicationSchedule("A", monday);
            assertEquals(8, doses.size());
            String[] ids = {"previous", "before", "fixed", "after", "next", "expired", "missing", "stopped"};
            String[] times = {"2026-11-22 23:40", "2026-11-23 07:30", "2026-11-23 08:00",
                    "2026-11-23 08:30", "2026-11-24 00:20", null, null, null};
            for (int i = 0; i < ids.length; i++) {
                DailyMedicationDose dose = doses.get(i);
                assertEquals(ids[i], dose.slot.slotId);
                assertEquals(monday, dose.scheduleDate);
                assertEquals(times[i], dose.scheduledLocalDateTime);
                assertEquals(times[i] == null, dose.scheduledAtMillis == null);
            }
            assertTrue(dao.getDailyMedicationSchedule("A", "2026-11-16").isEmpty());
            assertTrue(dao.getDailyMedicationSchedule("A", "2026-11-30").isEmpty());
            assertTrue(dao.getDailyMedicationSchedule("unknown", monday).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> dao.getDailyMedicationSchedule("A", "2026-02-30"));
        } finally {
            db.close();
        }
    }

    @Test public void dailyScheduleUsesMealZoneAndElapsedMinutesAcrossDst() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ReminderDatabase db = Room.inMemoryDatabaseBuilder(context, ReminderDatabase.class).build();
        try {
            ReminderDao dao = db.reminders();
            insertPlan(dao, "A", "plan", "active", true); // 결과 표시 시간대: Asia/Seoul
            dao.insertMealTime(mealTime("A", "ny", 7, "01:45", "America/New_York"));
            dao.insertMealTime(mealTime("A", "gap", 7, "02:15", "America/New_York"));
            relativeSlot(dao, "after", "ny", 30, 64);
            relativeSlot(dao, "gap", "gap", 0, 64);
            List<DailyMedicationDose> spring = dao.getDailyMedicationSchedule("A", "2026-03-08");
            assertEquals(2, spring.size());
            assertEquals("2026-03-08 16:15", spring.get(0).scheduledLocalDateTime); // 뉴욕 03:15
            assertNull(spring.get(1).scheduledAtMillis); // 뉴욕 02:15는 이날 존재하지 않음
            List<DailyMedicationDose> autumn = dao.getDailyMedicationSchedule("A", "2026-11-01");
            assertEquals("2026-11-01 15:15", autumn.get(0).scheduledLocalDateTime); // 첫 01:45 + 30분
        } finally {
            db.close();
        }
    }

    @Test
    public void migratesLegacyPlanAndSavesNewPlanWithoutInternalDrugId() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String file = "test-reminders-migration-" + UUID.randomUUID();
        try {
            // 실제 1번 스키마로 만든 DB에 기존 알림과 복용 회차를 넣은 뒤 앱과 같은 경로로 업그레이드합니다.
            try (SQLiteDatabase oldDb = context.openOrCreateDatabase(file, Context.MODE_PRIVATE, null);
                 Scanner scanner = new Scanner(InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                         .open(ReminderDatabase.class.getName() + "/1.json"), "UTF-8")) {
                JSONObject schema = new JSONObject(scanner.useDelimiter("\\A").next()).getJSONObject("database");
                JSONArray entities = schema.getJSONArray("entities");
                for (int i = 0; i < entities.length(); i++) {
                    JSONObject table = entities.getJSONObject(i);
                    oldDb.execSQL(table.getString("createSql").replace("${TABLE_NAME}", table.getString("tableName")));
                }
                JSONArray setup = schema.getJSONArray("setupQueries");
                for (int i = 0; i < setup.length(); i++) oldDb.execSQL(setup.getString(i));
                oldDb.execSQL("INSERT INTO medication_plans (patientUid, planId, drugId, drugName, startDate, "
                        + "timeZone, status, version, alarmEnabled, repeatIntervalMinutes, maxRepeatCount) "
                        + "VALUES ('A','old','001234567','기존 이름','2026-01-01','Asia/Seoul','active',1,1,0,0)");
                oldDb.execSQL("INSERT INTO dose_slots (patientUid, planId, slotId, doseAmount, doseUnit, "
                        + "weekdaysMask, timingType, localTime) VALUES ('A','old','morning',1,'정',1,'fixed','08:00')");
                oldDb.setVersion(1);
            }
            ReminderDatabase db = Room.databaseBuilder(context, ReminderDatabase.class, file).build();
            try {
                ReminderDao dao = db.reminders();
                ReminderPlan old = dao.getEnabledPlans("A").get(0);
                assertEquals("001234567", old.drugId);
                assertNull(old.sourceItemId);
                assertEquals("기존 이름", old.drugName);
                assertEquals("08:00", dao.getSlots("A", "old").get(0).localTime);

                MedicationPlans plan = new MedicationPlans();
                plan.setPatientUid("B");
                plan.setPlanId("new");
                plan.setSourceItemId("001234567");
                plan.setDrugName("등록 당시 제품명");
                plan.setStartDate("2026-01-01");
                plan.setTimeZone("Asia/Seoul");
                plan.setStatus("active");
                plan.setDoseSlots(Collections.emptyList());
                plan.setReminderSettings(Collections.singletonMap("alarmEnabled", true));
                dao.saveMedicationPlan(plan);
                ReminderPlan saved = dao.getEnabledPlans("B").get(0);
                assertEquals("001234567", saved.sourceItemId);
                assertNull(saved.drugId);
                assertEquals("등록 당시 제품명", saved.drugName);
                assertEquals(saved.drugName, plan.toReminderPlan("외부에서 받은 이름").drugName);
            } finally {
                db.close();
            }
        } finally {
            context.deleteDatabase(file);
        }
    }

    @Test
    public void schedulesSurviveReopenAndUpdatesStayWithinPatient() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String file = "test-reminders-" + UUID.randomUUID();
        ReminderDatabase db = Room.databaseBuilder(context, ReminderDatabase.class, file).build();
        try {
            DoseSlot breakfast = new DoseSlot();
            breakfast.setSlotId("breakfast");
            breakfast.setTimingType("mealRelative");
            breakfast.setMealPlanId("meal");
            breakfast.setOffsetMinutes(-30);
            breakfast.setDaysOfWeek(Arrays.asList(1, 7));
            breakfast.setDoseAmount(0.5);
            breakfast.setDoseUnit("정");

            DoseSlot evening = new DoseSlot();
            evening.setSlotId("evening");
            evening.setTimingType("fixed");
            evening.setLocalTime("20:00");
            evening.setDaysOfWeek(Collections.singletonList(1));
            evening.setDoseAmount(1.0);
            evening.setDoseUnit("정");

            MedicationPlans plan = new MedicationPlans();
            plan.setPlanId("plan");
            plan.setDrugId("drug");
            plan.setStartDate("2026-01-01");
            plan.setTimeZone("Asia/Seoul");
            plan.setStatus("active");
            Map<String, Object> settings = new HashMap<>();
            settings.put("alarmEnabled", true);
            settings.put("repeatIntervalMinutes", 5L);
            settings.put("maxRepeatCount", 2L);
            plan.setReminderSettings(settings);
            plan.setDoseSlots(Arrays.asList(breakfast, evening));
            plan.setPatientUid("A");
            db.reminders().saveMedicationPlan(plan, "약 A");
            plan.setPatientUid("B");
            db.reminders().saveMedicationPlan(plan, "약 B");

            MealScheduleEntry monday = new MealScheduleEntry();
            monday.setDaysOfWeek(Collections.singletonList(1));
            monday.setLocalTime("08:00");
            MealPlan meal = new MealPlan();
            meal.setPatientUid("A");
            meal.setMealPlanId("meal");
            meal.setTimeZone("Asia/Seoul");
            meal.setStartDate("2026-01-01");
            meal.setStatus("active");
            meal.setWeeklySchedule(Collections.singletonList(monday));
            db.reminders().saveMealPlan(meal);

            db.close();
            db = Room.databaseBuilder(context, ReminderDatabase.class, file).build();
            ReminderDao dao = db.reminders();
            ReminderPlan cached = dao.getEnabledPlans("A").get(0);
            assertEquals("약 A", cached.drugName);
            assertEquals(5, cached.repeatIntervalMinutes);
            assertEquals(2, cached.maxRepeatCount);
            ReminderDoseSlot slot = dao.getSlots("A", "plan").get(0);
            assertEquals(65, slot.weekdaysMask);
            assertEquals(Integer.valueOf(-30), slot.offsetMinutes);
            assertEquals(0.5, slot.doseAmount, 0.0);
            assertEquals("08:00", dao.getMealTime("A", slot.mealPlanId, 1).localTime);
            assertNull(dao.getMealTime("B", slot.mealPlanId, 1));

            plan.setPatientUid("A");
            plan.setDoseSlots(Arrays.asList(evening, evening));
            try {
                dao.saveMedicationPlan(plan, "실패할 변경");
                fail("Duplicate slot IDs must roll back the entire change");
            } catch (SQLiteConstraintException expected) {
                assertEquals("약 A", dao.getEnabledPlans("A").get(0).drugName);
                assertEquals(2, dao.getSlots("A", "plan").size());
            }
            plan.setDoseSlots(Collections.singletonList(evening));
            dao.saveMedicationPlan(plan, "약 A");
            assertEquals("evening", dao.getSlots("A", "plan").get(0).slotId);
            dao.deleteMedicationPlan("A", "plan");
            assertTrue(dao.getSlots("A", "plan").isEmpty());
            assertEquals(2, dao.getSlots("B", "plan").size());
        } finally {
            db.close();
            context.deleteDatabase(file);
        }
    }
}
