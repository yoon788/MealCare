package com.example.mealcare.data;

import com.example.mealcare.data.local.DailyMedicationDose;
import com.example.mealcare.data.model.MealRecord;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Transaction;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.google.firebase.firestore.FirebaseFirestoreException.Code.*;

/** 환자 본인의 식사 결과를 patients/{patientUid}/mealRecords에 기록합니다. */
public final class MealRecordRepository {
    private final DocumentReference patient;
    private final Supplier<String> currentUid;

    public MealRecordRepository(String patientUid) {
        this(FirebaseFirestore.getInstance(), patientUid, CurrentUser::authenticatedUid);
    }

    MealRecordRepository(FirebaseFirestore db, String patientUid, Supplier<String> currentUid) {
        patient = db.collection("patients").document(PlanDocument.documentId(patientUid, "환자 UID"));
        this.currentUid = currentUid;
    }

    /**
     * 환자의 식사 예정 날짜(yyyy-MM-dd)에 저장된 기록을 서버에서 읽습니다.
     * 환자 본인과 활성 연결에서 식사 조회가 허용된 보호자가 호출할 수 있습니다.
     * <pre>{@code
     * MealRecordRepository records = new MealRecordRepository(patientUid);
     * Task<List<MealRecord>> result = records.getRecords("2026-11-23");
     * }</pre>
     * @return 저장된 기록이 없으면 빈 목록. 취소 기록은 voided 상태로 포함되며 식사 여부는 미확인입니다.
     */
    public Task<List<MealRecord>> getRecords(String scheduleDate) {
        return getRecords(scheduleDate, scheduleDate);
    }

    /**
     * 시작일과 종료일을 포함한 식사 예정 날짜(yyyy-MM-dd)로 저장된 기록을 조회합니다.
     * 취소 기록은 voided 상태로 포함되며 저장된 기록이 없으면 빈 목록입니다.
     * <pre>{@code
     * Task<List<MealRecord>> week = meals.getRecords("2026-11-23", "2026-11-29");
     * }</pre>
     */
    public Task<List<MealRecord>> getRecords(String startDate, String endDate) {
        return RecordDocument.getRecords(patient, "mealRecords", "readMeals", startDate, endDate, currentUid, doc -> {
            MealRecord record = doc.toObject(MealRecord.class);
            record.setRecordId(doc.getId());
            record.setPatientUid(patient.getId());
            return record;
        });
    }

    /**
     * 기간 양 끝을 포함해 당시 계획의 예정·완료·결식·미확인 건수를 반환합니다.
     * 알림이 꺼진 일정도 포함하며, 취소·미입력 회차는 미확인으로 집계합니다.
     * <pre>{@code
     * meals.getStatistics("2026-11-23", "2026-11-29").addOnSuccessListener(stats -> {
     *     long unconfirmed = stats.getUnconfirmedCount();
     * });
     * }</pre>
     */
    public Task<RecordStatistics> getStatistics(String startDate, String endDate) {
        return RecordDocument.readRange(patient, "mealRecords", "readMeals", startDate, endDate, currentUid,
                records -> RecordStatisticsQuery.load(patient, false, startDate, endDate, records));
    }

    /**
     * 환자 본인이 조회한 식사 기록의 결과를 정정합니다. 메모는 조회한 값을 사용하고 결식이면 실제 완료 시각을 비웁니다.
     * <pre>{@code
     * // record는 meals.getRecords(날짜)에서 선택한 기록입니다.
     * Task<Integer> saved = meals.correctRecord(record, true);
     * }</pre>
     * @return 서버에 확정된 revision. 다음 변경에는 다시 조회한 기록을 사용하세요.
     */
    public Task<Integer> correctRecord(MealRecord record, boolean completed) {
        if (record == null) return Tasks.forException(new IllegalArgumentException("식사 기록이 필요합니다."));
        return correctRecord(record, completed, record.getCompletedAt(), record.getNote());
    }

    /**
     * 식사 여부·실제 완료 시각·메모를 함께 정정합니다. 실제 시각이나 메모를 비우려면 null을 전달하세요.
     * 취소된 기록도 정정할 수 있습니다.
     * <pre>{@code
     * Task<Integer> saved = meals.correctRecord(record, true, completedAt, "식사 완료 확인");
     * }</pre>
     * @return 확정된 revision. 조회 후 다른 변경이 저장됐다면 FAILED_PRECONDITION으로 실패합니다.
     */
    public Task<Integer> correctRecord(MealRecord record, boolean completed, Timestamp completedAt, String note) {
        return revise(record, completed ? "completed" : "skipped", completed ? completedAt : null, note);
    }

    /**
     * 잘못 입력한 식사 기록을 voided(미확인)로 바꾸고 실제 완료 시각을 비웁니다. 원래 내용은 수정 이력에 남습니다.
     * <pre>{@code
     * Task<Integer> saved = meals.cancelRecord(record);
     * }</pre>
     * @return 확정된 revision. 충돌하면 getRecords()로 최신 기록을 조회하세요.
     */
    public Task<Integer> cancelRecord(MealRecord record) {
        return revise(record, "voided", null, null);
    }

    private Task<Integer> revise(MealRecord record, String status, Timestamp completedAt, String note) {
        if (record == null) return Tasks.forException(new IllegalArgumentException("식사 기록이 필요합니다."));
        return RecordDocument.revise(patient.collection("mealRecords"), record.getPatientUid(), record.getRecordId(),
                record.getRevision(), RecordDocument.resultChanges(status, "completedAt", completedAt, note), currentUid);
    }

    /**
     * 환자가 지정한 날짜의 식사 완료 또는 결식을 확인해 저장합니다.
     * 예정 시각, 식사 종류·이름은 당시 계획에서 가져옵니다. 실제 식사 완료 시각은 미입력으로 둡니다.
     * <pre>{@code
     * MealRecordRepository records = new MealRecordRepository(patientUid);
     * Task<String> saved = records.saveRecord(mealPlanId, "2026-11-23", true);
     * }</pre>
     * @param mealPlanId 식사 계획 ID
     * @param scheduleDate 식사 계획의 시간대를 기준으로 식사가 배정된 날짜(yyyy-MM-dd)
     * @param completed 식사를 마쳤으면 true, 결식을 확인했으면 false
     * @return 온라인 저장 후 기록 ID. 같은 식사·날짜·결과로 재시도하면 기존 ID를 반환합니다.
     *         반대 결과가 이미 있으면 ALREADY_EXISTS, 해당 날짜에 예정된 식사가 없으면 실패합니다.
     */
    public Task<String> saveRecord(String mealPlanId, String scheduleDate, boolean completed) {
        String actor = currentUid.get();
        if (actor == null) return Tasks.forException(new FirebaseFirestoreException("로그인이 필요합니다.", UNAUTHENTICATED));
        if (!patient.getId().equals(actor)) {
            return Tasks.forException(new FirebaseFirestoreException("식사 기록은 환자 본인만 작성할 수 있습니다.", PERMISSION_DENIED));
        }
        final DocumentReference plan;
        final int weekday;
        try {
            plan = patient.collection("mealPlans").document(PlanDocument.documentId(mealPlanId, "식사 계획 ID"));
            weekday = DailyMedicationDose.weekdayOf(scheduleDate);
        } catch (IllegalArgumentException error) {
            return Tasks.forException(error);
        }
        DocumentReference record = patient.collection("mealRecords").document(RecordDocument.recordId(mealPlanId, scheduleDate));
        String status = completed ? "completed" : "skipped";
        return patient.getFirestore().runTransaction(tx -> {
            if (!actor.equals(currentUid.get())) throw new FirebaseFirestoreException("로그인 계정이 변경되었습니다.", UNAUTHENTICATED);
            // 결과를 먼저 읽어 계획 변경 후의 재시도에서도 최초 기록과 당시 계획 정보를 보존합니다.
            DocumentSnapshot existing = tx.get(record);
            if (existing.exists()) {
                if (status.equals(existing.get("status"))) return record.getId();
                throw new FirebaseFirestoreException("이 식사에 다른 결과가 이미 있습니다.", ALREADY_EXISTS);
            }
            Map<String, Object> fields = scheduledMeal(tx, plan, scheduleDate, weekday);
            fields.put("recordId", record.getId());
            fields.put("patientUid", patient.getId());
            fields.put("mealPlanId", plan.getId());
            fields.put("scheduleDate", scheduleDate);
            fields.put("status", status);
            fields.put("createdByUid", actor);
            fields.put("updatedByUid", actor);
            fields.put("revision", 1L);
            tx.set(record, PlanDocument.withServerTimestamps(fields));
            return record.getId();
        });
    }

    private static Map<String, Object> scheduledMeal(Transaction tx, DocumentReference ref, String date, int weekday)
            throws FirebaseFirestoreException {
        DocumentSnapshot doc = tx.get(ref);
        if (!doc.exists()) throw new FirebaseFirestoreException("식사 계획을 찾을 수 없습니다.", NOT_FOUND);
        for (; doc != null; doc = RecordDocument.previousVersion(tx, ref, doc)) {
            String zone = doc.getString("timeZone");
            Long time = RecordDocument.mealTime(doc, date, weekday, zone);
            if (time == null) continue;
            Map<String, Object> meal = new HashMap<>();
            meal.put("mealType", PlanDocument.requiredText(doc.getString("mealType"), "식사 종류"));
            meal.put("mealName", PlanDocument.requiredText(doc.getString("mealName"), "식사 이름"));
            Map<String, Object> fields = new HashMap<>();
            fields.put("scheduledAt", new Timestamp(new Date(time)));
            fields.put("timeZone", zone);
            fields.put("planVersion", (long) PlanDocument.versionOf(doc));
            fields.put("mealSnapshot", meal);
            return fields;
        }
        throw new FirebaseFirestoreException("이 날짜에 예정된 식사가 없습니다.", FAILED_PRECONDITION);
    }
}
