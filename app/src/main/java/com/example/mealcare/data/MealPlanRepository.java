package com.example.mealcare.data;

import com.example.mealcare.MealSlot;
import com.example.mealcare.data.model.MealPlan;
import com.example.mealcare.data.model.MealScheduleEntry;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 한 환자의 식사 계획을 patients/{patientUid}/mealPlans/{mealPlanId}에 등록합니다. */
public final class MealPlanRepository {
    private final String patientUid;
    private final CollectionReference mealPlans;
    private final Supplier<String> currentUid;

    /** @param patientUid 식사 계획을 소유할 환자의 UID */
    public MealPlanRepository(String patientUid) {
        this(FirebaseFirestore.getInstance(), patientUid);
    }

    MealPlanRepository(FirebaseFirestore db, String patientUid) {
        this(db, patientUid, CurrentUser::authenticatedUid);
    }

    MealPlanRepository(FirebaseFirestore db, String patientUid, Supplier<String> currentUid) {
        this.patientUid = PlanDocument.documentId(patientUid, "환자 UID");
        mealPlans = db.collection("patients").document(this.patientUid).collection("mealPlans");
        this.currentUid = currentUid;
    }

    /**
     * 환자 본인의 활성·일시정지 식사 계획을 서버에서 한 번 조회합니다. 해당 계획이 없으면 빈 목록입니다.
     * <pre>{@code
     * MealPlanRepository meals = new MealPlanRepository(patientUid);
     * meals.getPlans().addOnSuccessListener(plans -> {
     *     for (MealPlan plan : plans) Log.d("MealPlan", plan.getMealName());
     * });
     * }</pre>
     */
    public Task<List<MealPlan>> getPlans() {
        return PlanDocument.getPlans(mealPlans, null, currentUid, this::fromDocument);
    }

    /**
     * 환자 본인이 ID로 식사 계획의 서버 버전을 읽습니다. 없거나 중단된 계획은 NOT_FOUND로 실패합니다.
     * 조회한 객체에 환자 UID·식사 계획 ID·버전이 채워져 있어 변경한 뒤 바로 저장할 수 있습니다.
     * <pre>{@code
     * MealPlanRepository meals = new MealPlanRepository(patientUid);
     * Task<Void> saved = meals.getPlan(mealPlanId).onSuccessTask(plan -> {
     *     plan.getWeeklySchedule().get(0).setLocalTime("08:30");
     *     return meals.updatePlan(plan);
     * });
     * }</pre>
     */
    public Task<MealPlan> getPlan(String mealPlanId) {
        return PlanDocument.getPlan(mealPlans, mealPlanId, null, currentUid, this::fromDocument);
    }

    private MealPlan fromDocument(DocumentSnapshot doc) {
        MealPlan plan = doc.toObject(MealPlan.class);
        plan.setPatientUid(patientUid);
        plan.setMealPlanId(doc.getId());
        return plan;
    }

    /**
     * 식사 종류·표시 이름·요일별 시각·적용 기간·시간대·알림 설정을 한 문서에 저장합니다.
     * <pre>{@code
     * MealScheduleEntry weekdays = new MealScheduleEntry();
     * weekdays.setDaysOfWeek(Arrays.asList(1, 2, 3, 4, 5)); // 월요일 1 ~ 일요일 7
     * weekdays.setLocalTime("08:00");
     *
     * MealPlan mealPlan = new MealPlan();
     * mealPlan.setMealType("BREAKFAST"); // BREAKFAST, LUNCH, DINNER, SNACK, OTHER
     * mealPlan.setMealName("평일 아침");
     * mealPlan.setWeeklySchedule(Collections.singletonList(weekdays));
     * mealPlan.setTimeZone("Asia/Seoul");
     * mealPlan.setStartDate("2026-11-23"); // 종료일은 선택 사항
     * mealPlan.configureReminder(true, -10); // 식사 10분 전에 알림
     *
     * new MealPlanRepository(patientUid).createPlan(mealPlan)
     *         .addOnSuccessListener(id -> Log.d("MealPlan", "저장 완료: " + id))
     *         .addOnFailureListener(error -> Log.e("MealPlan", "저장 실패", error));
     * }</pre>
     * @param mealPlan 식사 계획 초안. ID가 없으면 첫 호출에서 발급합니다.
     * @return 온라인 저장이 확인되면 mealPlanId를 반환하는 Task. 같은 ID·내용으로 재시도하면
     *         최초 생성 시각을 보존합니다. 입력 오류, ID 충돌, 저장 오류는 실패 리스너로 전달합니다.
     */
    public Task<String> createPlan(MealPlan mealPlan) {
        if (mealPlan == null) return Tasks.forException(new IllegalArgumentException("식사 계획이 필요합니다."));
        final DocumentReference ref;
        final Map<String, Object> fields;
        try {
            // 동시에 같은 초안을 등록해도 같은 ID를 사용하고, 호출 시점의 일정을 복사합니다.
            synchronized (mealPlan) {
                if (mealPlan.getPatientUid() != null && !patientUid.equals(mealPlan.getPatientUid())) {
                    throw new IllegalArgumentException("계획의 환자와 저장 대상 환자가 다릅니다.");
                }
                fields = planFields(mealPlan, "active");
                fields.put("version", 1L);
                ref = mealPlan.getMealPlanId() == null ? mealPlans.document()
                        : mealPlans.document(PlanDocument.documentId(mealPlan.getMealPlanId(), "식사 계획 ID"));
                fields.put("patientUid", patientUid);
                fields.put("mealPlanId", ref.getId());
                mealPlan.setPatientUid(patientUid);
                mealPlan.setMealPlanId(ref.getId());
            }
        } catch (IllegalArgumentException error) {
            return Tasks.forException(error);
        }

        return ref.getFirestore().runTransaction(tx -> {
            DocumentSnapshot existing = tx.get(ref);
            if (existing.exists()) {
                PlanDocument.checkRetry(existing, fields, "같은 ID에 다른 식사 계획이 있습니다.");
            } else {
                tx.set(ref, PlanDocument.withServerTimestamps(fields));
            }
            return ref.getId();
        }).onSuccessTask(Runnable::run, id -> {
            synchronized (mealPlan) {
                mealPlan.setStatus("active");
                mealPlan.setVersion(1);
            }
            return Tasks.forResult(id);
        });
    }

    /**
     * 저장된 식사 계획의 이름·종류·요일별 시각·기간·알림·상태(active/paused)를 수정합니다.
     * <pre>{@code
     * mealPlan.getWeeklySchedule().get(0).setLocalTime("08:30");
     * mealPlan.configureReminder(true, -15);
     * Task<Void> saved = new MealPlanRepository(patientUid).updatePlan(mealPlan);
     * }</pre>
     * @param mealPlan 등록에 성공했거나 조회한 계획. ID·환자·버전은 그대로 두세요.
     * @return 서버 저장 후 완료되는 Task. 성공하면 mealPlan의 버전도 갱신됩니다.
     *         다른 수정과 충돌하면 최신 계획을 다시 읽어야 합니다.
     */
    public Task<Void> updatePlan(MealPlan mealPlan) {
        if (mealPlan == null) return Tasks.forException(new IllegalArgumentException("식사 계획이 필요합니다."));
        final DocumentReference ref;
        final Map<String, Object> fields;
        final int version;
        try {
            synchronized (mealPlan) {
                if (!patientUid.equals(mealPlan.getPatientUid())) {
                    throw new IllegalArgumentException("계획의 환자와 수정 대상 환자가 다릅니다.");
                }
                ref = mealPlans.document(PlanDocument.documentId(mealPlan.getMealPlanId(), "식사 계획 ID"));
                version = mealPlan.getVersion();
                fields = planFields(mealPlan, mealPlan.getStatus());
            }
        } catch (IllegalArgumentException error) {
            return Tasks.forException(error);
        }
        return ref.getFirestore().runTransaction(tx ->
                PlanDocument.update(tx, PlanDocument.readForUpdate(tx, ref), version, fields)
        ).onSuccessTask(Runnable::run, savedVersion -> {
            synchronized (mealPlan) {
                mealPlan.setVersion(Math.max(mealPlan.getVersion(), savedVersion));
            }
            return Tasks.forResult(null);
        });
    }

    /**
     * 식사 계획을 중단하고 동기화 시 Room에서 요일별 식사 시각을 제거합니다.
     * 기존 복약 회차의 식사 참조는 보존되며, 해당 식사를 기준으로 한 복용 시각은 미정이 됩니다.
     * <pre>{@code
     * Task<Void> deleted = new MealPlanRepository(patientUid).deletePlan(mealPlan.getMealPlanId());
     * }</pre>
     * @return 서버 처리 후 완료되는 Task. 같은 ID를 다시 삭제해도 성공합니다.
     */
    public Task<Void> deletePlan(String mealPlanId) {
        return PlanDocument.delete(mealPlans, mealPlanId);
    }

    private static Map<String, Object> planFields(MealPlan plan, String status) {
        Map<String, Object> fields = PlanDocument.scheduleFields(plan.getStartDate(), plan.getEndDate(), plan.getTimeZone(), status);
        fields.put("mealType", mealType(plan.getMealType()));
        fields.put("mealName", PlanDocument.requiredText(plan.getMealName(), "식사 이름"));
        fields.put("weeklySchedule", weeklySchedule(plan.getWeeklySchedule()));
        fields.put("reminderSettings", reminderSettings(plan.getReminderSettings()));
        return fields;
    }

    private static String mealType(String value) {
        switch (PlanDocument.requiredText(value, "식사 종류").trim().toUpperCase(Locale.ROOT)) {
            case "아침": case MealSlot.BREAKFAST: return MealSlot.BREAKFAST;
            case "점심": case MealSlot.LUNCH: return MealSlot.LUNCH;
            case "저녁": case MealSlot.DINNER: return MealSlot.DINNER;
            case "간식": case "SNACK": return "SNACK";
            case "기타": case "OTHER": return "OTHER";
            default: throw new IllegalArgumentException("식사 종류는 아침·점심·저녁·간식·기타 중 하나여야 합니다.");
        }
    }

    /** 한 식사 계획에는 요일당 한 시각만 둡니다. 서로 다른 항목 사이의 중복 요일도 확인합니다. */
    private static List<Map<String, Object>> weeklySchedule(List<MealScheduleEntry> entries) {
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("요일별 식사 일정이 필요합니다.");
        Set<Integer> assigned = new HashSet<>();
        List<Map<String, Object>> schedule = new ArrayList<>();
        for (MealScheduleEntry entry : entries) {
            if (entry == null || entry.getDaysOfWeek() == null || entry.getDaysOfWeek().isEmpty()) {
                throw new IllegalArgumentException("각 식사 일정에 요일이 필요합니다.");
            }
            String time = PlanDocument.requiredText(entry.getLocalTime(), "식사 시각");
            if (!time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) {
                throw new IllegalArgumentException("식사 시각은 HH:mm 형식이어야 합니다.");
            }
            List<Long> days = new ArrayList<>();
            for (Integer day : entry.getDaysOfWeek()) {
                if (day == null || day < 1 || day > 7 || !assigned.add(day)) {
                    throw new IllegalArgumentException("요일은 1~7이며 한 식사 계획에서 중복될 수 없습니다.");
                }
                days.add(day.longValue()); // Firestore에서 정수를 읽을 때의 자료형과 일치시킵니다.
            }
            Map<String, Object> row = new HashMap<>();
            row.put("daysOfWeek", days);
            row.put("localTime", time);
            schedule.add(row);
        }
        return schedule;
    }

    private static Map<String, Object> reminderSettings(Map<String, Object> settings) {
        if (settings == null || !(settings.get("alarmEnabled") instanceof Boolean)) {
            throw new IllegalArgumentException("알림 활성화 여부가 필요합니다.");
        }
        Object offset = settings.getOrDefault("offsetMinutes", 0);
        if (!(offset instanceof Number)) throw new IllegalArgumentException("알림 시간차는 분 단위 정수여야 합니다.");
        Number number = (Number) offset;
        if (number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException("알림 시간차는 분 단위 정수여야 합니다.");
        }
        Map<String, Object> fields = new HashMap<>();
        fields.put("alarmEnabled", settings.get("alarmEnabled"));
        fields.put("offsetMinutes", (long) number.intValue());
        return fields;
    }
}
