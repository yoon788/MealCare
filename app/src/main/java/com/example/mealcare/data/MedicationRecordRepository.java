package com.example.mealcare.data;

import com.example.mealcare.data.local.DailyMedicationDose;
import com.example.mealcare.data.model.DoseSlot;
import com.example.mealcare.data.model.MedicationPlans;
import com.example.mealcare.data.model.MedicationRecord;
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

/** 환자의 복약 회차별 결과를 patients/{patientUid}/medicationRecords에 기록합니다. */
public final class MedicationRecordRepository {
    private final DocumentReference patient;
    private final Supplier<String> currentUid;

    public MedicationRecordRepository(String patientUid) {
        this(FirebaseFirestore.getInstance(), patientUid, CurrentUser::authenticatedUid);
    }

    MedicationRecordRepository(FirebaseFirestore db, String patientUid, Supplier<String> currentUid) {
        patient = db.collection("patients").document(PlanDocument.documentId(patientUid, "환자 UID"));
        this.currentUid = currentUid;
    }

    /**
     * 환자의 복용 예정 날짜(yyyy-MM-dd)에 저장된 기록을 서버에서 읽습니다.
     * 복용이 자정을 넘었어도 회차가 배정된 날짜를 사용합니다.
     * 환자 본인과 활성 연결에서 복약 조회가 허용된 보호자가 호출할 수 있습니다.
     * <pre>{@code
     * MedicationRecordRepository records = new MedicationRecordRepository(patientUid);
     * Task<List<MedicationRecord>> result = records.getRecords("2026-11-23");
     * }</pre>
     * @return 저장된 기록이 없으면 빈 목록. 취소 기록은 voided 상태로 포함되며 복용 여부는 미확인입니다.
     */
    public Task<List<MedicationRecord>> getRecords(String scheduleDate) {
        return getRecords(scheduleDate, scheduleDate);
    }

    /**
     * 시작일과 종료일을 포함한 복약 예정 날짜(yyyy-MM-dd)로 저장된 기록을 조회합니다.
     * 취소 기록은 voided 상태로 포함되며 저장된 기록이 없으면 빈 목록입니다.
     * <pre>{@code
     * Task<List<MedicationRecord>> week = meds.getRecords("2026-11-23", "2026-11-29");
     * }</pre>
     */
    public Task<List<MedicationRecord>> getRecords(String startDate, String endDate) {
        return RecordDocument.getRecords(patient, "medicationRecords", "readMedications", startDate, endDate, currentUid, doc -> {
            MedicationRecord record = doc.toObject(MedicationRecord.class);
            record.setRecordId(doc.getId());
            record.setPatientUid(patient.getId());
            return record;
        });
    }

    /**
     * 기간 양 끝을 포함해 당시 계획의 예정·완료·미복용·미확인 건수를 반환합니다.
     * 알림이 꺼진 일정도 포함하며, 취소·미입력 회차는 미확인으로 집계합니다.
     * <pre>{@code
     * meds.getStatistics("2026-11-23", "2026-11-29").addOnSuccessListener(stats -> {
     *     long unconfirmed = stats.getUnconfirmedCount();
     * });
     * }</pre>
     */
    public Task<RecordStatistics> getStatistics(String startDate, String endDate) {
        return RecordDocument.readRange(patient, "medicationRecords", "readMedications", startDate, endDate, currentUid,
                records -> RecordStatisticsQuery.load(patient, true, startDate, endDate, records));
    }

    /**
     * 조회한 복약 기록의 결과를 정정합니다. 메모는 조회한 값을 사용하고 미복용이면 실제 복용 시각을 비웁니다.
     * <pre>{@code
     * // record는 meds.getRecords(날짜)에서 선택한 기록입니다.
     * Task<Integer> saved = meds.correctRecord(record, false);
     * }</pre>
     * @return 서버에 확정된 revision. 다음 변경에는 다시 조회한 기록을 사용하세요.
     */
    public Task<Integer> correctRecord(MedicationRecord record, boolean taken) {
        if (record == null) return Tasks.forException(new IllegalArgumentException("복약 기록이 필요합니다."));
        return correctRecord(record, taken, record.getTakenAt(), record.getNote());
    }

    /**
     * 복용 여부·실제 복용 시각·메모를 함께 정정합니다. 환자 본인과 현재 대리 기록 권한이 있는 최초 작성자가 사용할 수 있습니다.
     * 실제 시각이나 메모를 비우려면 null을 전달하세요. 취소된 기록도 정정할 수 있습니다.
     * <pre>{@code
     * Task<Integer> saved = meds.correctRecord(record, true, takenAt, "복용 확인");
     * }</pre>
     * @return 확정된 revision. 조회 후 다른 변경이 저장됐다면 FAILED_PRECONDITION으로 실패합니다.
     */
    public Task<Integer> correctRecord(MedicationRecord record, boolean taken, Timestamp takenAt, String note) {
        return revise(record, taken ? "taken" : "notTaken", taken ? takenAt : null, note);
    }

    /**
     * 잘못 입력한 기록을 voided(미확인)로 바꾸고 실제 복용 시각을 비웁니다. 원래 내용은 수정 이력에 남습니다.
     * <pre>{@code
     * Task<Integer> saved = meds.cancelRecord(record);
     * }</pre>
     * @return 확정된 revision. 충돌하면 getRecords()로 최신 기록을 조회하세요.
     */
    public Task<Integer> cancelRecord(MedicationRecord record) {
        return revise(record, "voided", null, null);
    }

    private Task<Integer> revise(MedicationRecord record, String status, Timestamp takenAt, String note) {
        if (record == null) return Tasks.forException(new IllegalArgumentException("복약 기록이 필요합니다."));
        return RecordDocument.revise(patient.collection("medicationRecords"), record.getPatientUid(), record.getRecordId(),
                record.getRevision(), RecordDocument.resultChanges(status, "takenAt", takenAt, note), currentUid);
    }

    /**
     * 지정한 날짜에 배정된 복약 회차의 결과를 한 번 저장합니다. 환자 본인 또는 대리 기록 권한이
     * 있는 보호자가 호출할 수 있습니다. 예정 시각·약품·복용량·작성자는 자동으로 채웁니다.
     * <pre>{@code
     * MedicationRecordRepository records = new MedicationRecordRepository(patientUid);
     * Task<String> saved = records.saveRecord(planId, slotId, "2026-11-23", true);
     * }</pre>
     * @param planId 복약 계획 ID
     * @param slotId 계획 안의 복용 회차 ID
     * @param scheduleDate 환자 일정에서 회차가 배정된 날짜(yyyy-MM-dd). 자정을 넘어도 이 날짜를 사용합니다.
     * @param taken 복용을 확인했으면 true, 미복용을 확인했으면 false. 실제 복용 시각은 미입력으로 둡니다.
     * @return 온라인 저장 후 기록 ID. 같은 회차·결과로 재시도하면 같은 ID를 반환합니다.
     *         다른 결과가 이미 있으면 ALREADY_EXISTS, 예정 회차를 확인할 수 없으면 실패합니다.
     */
    public Task<String> saveRecord(String planId, String slotId, String scheduleDate, boolean taken) {
        String actor = currentUid.get();
        if (actor == null) return Tasks.forException(new FirebaseFirestoreException("로그인이 필요합니다.", UNAUTHENTICATED));
        final DocumentReference plan;
        final int weekday;
        try {
            plan = patient.collection("medicationPlans").document(PlanDocument.documentId(planId, "복약 계획 ID"));
            PlanDocument.requiredText(slotId, "복용 회차 ID");
            weekday = DailyMedicationDose.weekdayOf(scheduleDate);
        } catch (IllegalArgumentException error) {
            return Tasks.forException(error);
        }
        DocumentReference record = patient.collection("medicationRecords").document(RecordDocument.recordId(planId, slotId, scheduleDate));
        String status = taken ? "taken" : "notTaken";
        return RecordDocument.medicationWriterLink(patient, actor).onSuccessTask(Runnable::run,
                link -> save(record, plan, slotId, scheduleDate, weekday, status, actor, link));
    }

    private Task<String> save(DocumentReference record, DocumentReference plan, String slotId, String date,
                              int weekday, String status, String actor, DocumentReference link) {
        return patient.getFirestore().runTransaction(tx -> {
            if (!actor.equals(currentUid.get())) throw new FirebaseFirestoreException("로그인 계정이 변경되었습니다.", UNAUTHENTICATED);
            if (link != null) RecordDocument.requireMedicationGrant(tx, link, patient.getId(), actor);
            DocumentSnapshot existing = tx.get(record);
            if (existing.exists()) {
                if (status.equals(existing.get("status"))) return record.getId();
                throw new FirebaseFirestoreException("이 회차에 다른 복약 결과가 이미 있습니다.", ALREADY_EXISTS);
            }
            Map<String, Object> fields = scheduledDose(tx, plan, slotId, date, weekday);
            fields.put("recordId", record.getId());
            fields.put("patientUid", patient.getId());
            fields.put("planId", plan.getId());
            fields.put("slotId", slotId);
            fields.put("scheduleDate", date);
            fields.put("status", status);
            fields.put("createdByUid", actor);
            fields.put("updatedByUid", actor);
            fields.put("revision", 1L);
            tx.set(record, PlanDocument.withServerTimestamps(fields));
            return record.getId();
        });
    }

    /** 뒤늦게 입력해도 예정 시각에 적용되었던 복약·식사 계획을 사용합니다. 알림을 꺼 둔 회차도 기록할 수 있습니다. */
    private Map<String, Object> scheduledDose(Transaction tx, DocumentReference ref, String slotId,
                                               String date, int weekday) throws FirebaseFirestoreException {
        DocumentSnapshot doc = tx.get(ref);
        if (!doc.exists()) throw new FirebaseFirestoreException("복약 계획을 찾을 수 없습니다.", NOT_FOUND);
        for (; doc != null; doc = RecordDocument.previousVersion(tx, ref, doc)) {
            if (!RecordDocument.scheduledOn(doc, date)) continue;
            MedicationPlans plan = doc.toObject(MedicationPlans.class);
            if (plan.getDoseSlots() == null) continue;
            for (DoseSlot slot : plan.getDoseSlots()) {
                if (!slotId.equals(slot.getSlotId()) || slot.getDaysOfWeek() == null || !slot.getDaysOfWeek().contains(weekday)) continue;
                Long due = null;
                if ("fixed".equals(slot.getTimingType())) {
                    due = DailyMedicationDose.scheduledMillis(date, slot.getLocalTime(), plan.getTimeZone(), 0, plan.getTimeZone());
                } else if ("mealRelative".equals(slot.getTimingType()) && slot.getOffsetMinutes() != null) {
                    due = mealRelativeTime(tx, slot, date, weekday, doc);
                }
                if (due == null || !RecordDocument.effectiveAt(doc, due)) continue;
                Map<String, Object> fields = new HashMap<>();
                fields.put("scheduledAt", new Timestamp(new Date(due)));
                fields.put("planVersion", (long) PlanDocument.versionOf(doc));
                fields.put("timeZone", plan.getTimeZone());
                fields.put("doseSnapshot", doseSnapshot(plan, slot));
                return fields;
            }
        }
        throw new FirebaseFirestoreException("이 날짜에 예정 시각을 확인할 수 있는 복약 회차가 없습니다.", FAILED_PRECONDITION);
    }

    private Long mealRelativeTime(Transaction tx, DoseSlot slot, String date, int weekday, DocumentSnapshot medication)
            throws FirebaseFirestoreException {
        DocumentReference meal = patient.collection("mealPlans").document(PlanDocument.documentId(slot.getMealPlanId(), "식사 계획 ID"));
        for (DocumentSnapshot doc = tx.get(meal); doc != null && doc.exists(); doc = RecordDocument.previousVersion(tx, meal, doc)) {
            Long base = RecordDocument.mealTime(doc, date, weekday, medication.getString("timeZone"));
            if (base == null) continue;
            long due = base + slot.getOffsetMinutes() * 60_000L;
            if (RecordDocument.effectiveAt(medication, due)) return due;
        }
        return null;
    }

    private static Map<String, Object> doseSnapshot(MedicationPlans plan, DoseSlot slot) {
        Map<String, Object> dose = new HashMap<>();
        if (plan.getDrugId() != null) dose.put("drugId", plan.getDrugId());
        if (plan.getSourceItemId() != null) dose.put("sourceItemId", plan.getSourceItemId());
        if (dose.isEmpty()) throw new IllegalArgumentException("계획의 약품 식별자가 필요합니다.");
        String name = plan.getDrugName();
        dose.put("drugName", name == null && plan.getDrugId() != null ? "약품명 미등록" : PlanDocument.requiredText(name, "약품명"));
        Double amount = slot.getDoseAmount();
        if (amount == null || !Double.isFinite(amount) || amount <= 0) throw new IllegalArgumentException("유효한 복용량이 필요합니다.");
        dose.put("doseAmount", amount);
        dose.put("doseUnit", PlanDocument.requiredText(slot.getDoseUnit(), "복용 단위"));
        return dose;
    }

}
