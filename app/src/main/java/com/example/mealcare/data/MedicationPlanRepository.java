package com.example.mealcare.data;

import com.example.mealcare.data.model.DoseSlot;
import com.example.mealcare.data.model.Drug;
import com.example.mealcare.data.model.MedicationPlans;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Transaction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.example.mealcare.data.PlanDocument.documentId;
import static com.example.mealcare.data.PlanDocument.requiredText;

/** 한 환자의 복약 계획을 patients/{patientUid}/medicationPlans에 저장합니다. */
public class MedicationPlanRepository {
    private final DocumentReference patient;
    private final Supplier<String> currentUid;

    /** @param patientUid 계획을 등록할 대상 환자의 UID */
    public MedicationPlanRepository(String patientUid) {
        this(FirebaseFirestore.getInstance(), patientUid, CurrentUser::authenticatedUid);
    }

    MedicationPlanRepository(FirebaseFirestore db, String patientUid, Supplier<String> currentUid) {
        patient = db.collection("patients").document(documentId(patientUid, "환자 UID"));
        this.currentUid = currentUid;
    }

    /**
     * 이 환자의 활성·일시정지 복약 계획을 서버에서 한 번 조회합니다. 해당 계획이 없으면 빈 목록입니다.
     * 보호자가 조회하면 서버의 활성 연결과 readMedications 권한을 확인합니다.
     * <pre>{@code
     * MedicationPlanRepository meds = new MedicationPlanRepository(patientUid);
     * meds.getPlans().addOnSuccessListener(plans -> {
     *     for (MedicationPlans plan : plans) Log.d("MedicationPlan", plan.getPlanId());
     * });
     * }</pre>
     */
    public Task<List<MedicationPlans>> getPlans() {
        return PlanDocument.getPlans(patient.collection("medicationPlans"), "readMedications", currentUid, this::fromDocument);
    }

    /**
     * ID로 복약 계획의 서버 버전을 읽습니다. 없거나 중단된 계획은 NOT_FOUND로 실패합니다.
     * 목록과 같은 조회 권한이 필요합니다.
     * 조회한 객체에 환자 UID·계획 ID·버전이 채워져 있어 변경한 뒤 바로 저장할 수 있습니다.
     * <pre>{@code
     * MedicationPlanRepository meds = new MedicationPlanRepository(patientUid);
     * Task<Void> saved = meds.getPlan(planId).onSuccessTask(plan -> {
     *     plan.configureReminder(true, 10, 2);
     *     return meds.updatePlan(plan);
     * });
     * }</pre>
     */
    public Task<MedicationPlans> getPlan(String planId) {
        return PlanDocument.getPlan(patient.collection("medicationPlans"), planId, "readMedications", currentUid, this::fromDocument);
    }

    private MedicationPlans fromDocument(DocumentSnapshot doc) {
        MedicationPlans plan = doc.toObject(MedicationPlans.class);
        // 이전 문서의 ID 필드가 누락되어도 수정은 조회한 환자의 실제 문서 경로를 사용합니다.
        plan.setPatientUid(patient.getId());
        plan.setPlanId(doc.getId());
        return plan;
    }

    /**
     * 검색에서 선택한 약의 품목코드·제품명과 사용자가 입력한 복용 일정을 한 문서로 등록합니다.
     * @param plan 기간·시간대·복용 회차·알림 설정을 채운 초안. 종료일은 선택 사항입니다.
     * @param selectedDrug DrugAPI 검색 결과에서 선택한 약. 상세 조회 없이 사용할 수 있습니다.
     * @return 서버 저장이 확인되면 planId, 입력 오류·권한 거부·네트워크 오류는 실패한 Task.
     *         온라인 트랜잭션으로 저장하며 생성·수정 시각은 서버가 기록합니다.
     *
     * 첫 호출에서 초안에 planId를 할당합니다. 재시도할 때 같은 초안이나 같은 planId를 사용하면
     * 동일한 등록은 기존 ID를 반환합니다. 이미 다른 내용으로 저장된 계획은 충돌로 처리합니다.
     * <pre>{@code
     * // 복용 회차와 시작일 등을 채운 초안에 알림을 설정하고 저장합니다.
     * plan.configureReminder(true, 5, 2);
     * new MedicationPlanRepository(patientUid).createPlan(plan, selectedDrug);
     * }</pre>
     */
    public Task<String> createPlan(MedicationPlans plan, Drug selectedDrug) {
        if (plan == null || selectedDrug == null) {
            return Tasks.forException(new IllegalArgumentException("복약 계획과 선택한 약이 필요합니다."));
        }

        final DocumentReference ref;
        final Map<String, Object> fields;
        final String sourceItemId;
        final String drugName;
        Map<String, String> mealsBySlot = new HashMap<>();
        try {
            // 같은 초안으로 동시에 등록해도 한 문서 ID만 발급합니다.
            synchronized (plan) {
                if (plan.getPatientUid() != null && !patient.getId().equals(plan.getPatientUid())) {
                    throw new IllegalArgumentException("계획의 환자와 저장 대상 환자가 다릅니다.");
                }
                sourceItemId = requiredText(selectedDrug.getSourceItemId(), "품목기준코드");
                drugName = requiredText(selectedDrug.getProductName(), "제품명");
                fields = planFields(plan, mealsBySlot, "active");
                fields.put("version", 1L);
                ref = plan.getPlanId() == null ? patient.collection("medicationPlans").document()
                        : patient.collection("medicationPlans").document(documentId(plan.getPlanId(), "계획 ID"));
                fields.put("patientUid", patient.getId());
                fields.put("planId", ref.getId());
                fields.put("sourceItemId", sourceItemId);
                fields.put("drugName", drugName);
                plan.setPlanId(ref.getId());
                plan.setPatientUid(patient.getId());
            }
        } catch (IllegalArgumentException error) {
            return Tasks.forException(error);
        }

        return patient.getFirestore().runTransaction(tx -> {
            DocumentSnapshot existing = tx.get(ref);
            if (existing.exists()) {
                PlanDocument.checkRetry(existing, fields, "같은 ID에 다른 복약 계획이 있습니다.");
            } else {
                checkMeals(tx, mealsBySlot.values());
                tx.set(ref, PlanDocument.withServerTimestamps(fields));
            }
            return ref.getId();
        }).onSuccessTask(Runnable::run, id -> {
            // 트랜잭션 본문은 여러 번 실행될 수 있으므로 약 연결은 서버 확인 후 초안에 반영합니다.
            synchronized (plan) {
                plan.setSourceItemId(sourceItemId);
                plan.setDrugName(drugName);
                plan.setStatus("active");
                plan.setVersion(1);
            }
            return Tasks.forResult(id);
        });
    }

    /**
     * 저장된 계획에서 바꾼 기간·복용 회차·알림·상태(active/paused)를 한 번에 반영합니다.
     * 등록 당시의 약은 고정됩니다. 새 약을 복용하면 별도 계획을 등록하세요.
     * <pre>{@code
     * plan.getDoseSlots().get(0).setDoseAmount(1.0);
     * plan.configureReminder(true, 10, 2);
     * Task<Void> saved = new MedicationPlanRepository(patientUid).updatePlan(plan);
     * }</pre>
     * @param plan 등록에 성공했거나 조회한 계획. ID·환자·버전은 그대로 두세요.
     * @return 서버 저장 후 완료되는 Task. 성공하면 plan의 버전도 갱신됩니다.
     *         다른 수정과 충돌하면 최신 계획을 다시 읽어야 합니다.
     */
    public Task<Void> updatePlan(MedicationPlans plan) {
        if (plan == null) return Tasks.forException(new IllegalArgumentException("복약 계획이 필요합니다."));
        final DocumentReference ref;
        final Map<String, Object> fields;
        final String sourceItemId;
        final String drugId;
        final String drugName;
        final int version;
        final Map<String, String> mealsBySlot = new HashMap<>();
        try {
            synchronized (plan) {
                if (!patient.getId().equals(plan.getPatientUid())) {
                    throw new IllegalArgumentException("계획의 환자와 수정 대상 환자가 다릅니다.");
                }
                ref = patient.collection("medicationPlans").document(documentId(plan.getPlanId(), "계획 ID"));
                version = plan.getVersion();
                sourceItemId = plan.getSourceItemId();
                drugId = plan.getDrugId();
                drugName = plan.getDrugName();
                fields = planFields(plan, mealsBySlot, plan.getStatus());
            }
        } catch (IllegalArgumentException error) {
            return Tasks.forException(error);
        }
        return patient.getFirestore().runTransaction(tx -> {
            DocumentSnapshot existing = PlanDocument.readForUpdate(tx, ref);
            if ((sourceItemId != null && !sourceItemId.equals(existing.get("sourceItemId")))
                    || (drugId != null && !drugId.equals(existing.get("drugId")))
                    || (drugName != null && !drugName.equals(existing.get("drugName")))) {
                throw new FirebaseFirestoreException("등록 당시 약품은 변경할 수 없습니다.",
                        FirebaseFirestoreException.Code.FAILED_PRECONDITION);
            }
            // 기존 회차의 식사가 중단돼도 용량·알림은 수정할 수 있습니다. 새로 연결한 식사만 검사합니다.
            Map<String, String> newLinks = new HashMap<>(mealsBySlot);
            Object savedSlots = existing.get("doseSlots");
            if (savedSlots instanceof List) {
                for (Object value : (List<?>) savedSlots) {
                    if (!(value instanceof Map)) continue;
                    Map<?, ?> savedSlot = (Map<?, ?>) value;
                    if ("mealRelative".equals(savedSlot.get("timingType"))) {
                        newLinks.remove(savedSlot.get("slotId"), savedSlot.get("mealPlanId"));
                    }
                }
            }
            checkMeals(tx, newLinks.values());
            return PlanDocument.update(tx, existing, version, fields);
        }).onSuccessTask(Runnable::run, savedVersion -> {
            synchronized (plan) {
                plan.setVersion(Math.max(plan.getVersion(), savedVersion));
            }
            return Tasks.forResult(null);
        });
    }

    /**
     * 복약 계획을 중단하고 동기화 시 Room에서 해당 계획과 회차를 제거합니다.
     * 과거 기록이 참조하는 문서와 버전 이력은 보존합니다.
     * <pre>{@code
     * Task<Void> deleted = new MedicationPlanRepository(patientUid).deletePlan(plan.getPlanId());
     * }</pre>
     * @return 서버 처리 후 완료되는 Task. 같은 ID를 다시 삭제해도 성공합니다.
     */
    public Task<Void> deletePlan(String planId) {
        return PlanDocument.delete(patient.collection("medicationPlans"), planId);
    }

    private void checkMeals(Transaction tx, Collection<String> mealIds) throws FirebaseFirestoreException {
        for (String mealId : new HashSet<>(mealIds)) {
            DocumentSnapshot meal = tx.get(patient.collection("mealPlans").document(mealId));
            if (!meal.exists() || "stopped".equals(meal.get("status"))) {
                throw new FirebaseFirestoreException("연결할 수 있는 식사 계획이 없습니다: " + mealId,
                        FirebaseFirestoreException.Code.NOT_FOUND);
            }
        }
    }

    /** 비동기 저장에는 호출 시점의 값만 사용합니다. */
    private static Map<String, Object> planFields(MedicationPlans plan, Map<String, String> mealsBySlot, String status) {
        Map<String, Object> fields = PlanDocument.scheduleFields(plan.getStartDate(), plan.getEndDate(), plan.getTimeZone(), status);
        fields.put("doseSlots", doseSlots(plan.getDoseSlots(), mealsBySlot));
        fields.put("reminderSettings", reminderSettings(plan.getReminderSettings()));
        return fields;
    }

    private static List<Map<String, Object>> doseSlots(List<DoseSlot> slots, Map<String, String> mealsBySlot) {
        if (slots == null || slots.isEmpty()) throw new IllegalArgumentException("복용 회차가 필요합니다.");
        Set<String> slotIds = new HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (DoseSlot slot : slots) {
            if (slot == null || !slotIds.add(requiredText(slot.getSlotId(), "회차 ID"))) {
                throw new IllegalArgumentException("각 복용 회차에는 고유한 ID가 필요합니다.");
            }
            Double amount = slot.getDoseAmount();
            if (amount == null || !Double.isFinite(amount) || amount <= 0) {
                throw new IllegalArgumentException("1회 복용량은 양수여야 합니다.");
            }
            Map<String, Object> row = new HashMap<>();
            row.put("slotId", slot.getSlotId());
            row.put("doseAmount", amount);
            row.put("doseUnit", requiredText(slot.getDoseUnit(), "복용 단위"));
            if (slot.getDaysOfWeek() == null || slot.getDaysOfWeek().isEmpty()) {
                throw new IllegalArgumentException("반복 요일이 필요합니다.");
            }
            List<Long> days = new ArrayList<>();
            for (Integer day : slot.getDaysOfWeek()) {
                if (day == null || day < 1 || day > 7) throw new IllegalArgumentException("요일은 1~7이어야 합니다.");
                days.add(day.longValue());
            }
            row.put("daysOfWeek", days);
            row.put("timingType", slot.getTimingType());
            if ("fixed".equals(slot.getTimingType())) {
                String time = requiredText(slot.getLocalTime(), "복용 시각");
                if (!time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) {
                    throw new IllegalArgumentException("복용 시각은 HH:mm 형식이어야 합니다.");
                }
                row.put("localTime", time);
            } else if ("mealRelative".equals(slot.getTimingType())) {
                String mealId = documentId(slot.getMealPlanId(), "식사 계획 ID");
                if (slot.getOffsetMinutes() == null) throw new IllegalArgumentException("식사와의 시간차가 필요합니다.");
                mealsBySlot.put(slot.getSlotId(), mealId);
                row.put("mealPlanId", mealId);
                row.put("offsetMinutes", slot.getOffsetMinutes().longValue());
            } else {
                throw new IllegalArgumentException("복용 시각 방식은 fixed 또는 mealRelative여야 합니다.");
            }
            result.add(row);
        }
        return result;
    }

    private static Map<String, Object> reminderSettings(Map<String, Object> settings) {
        if (settings == null || !(settings.get("alarmEnabled") instanceof Boolean)) {
            throw new IllegalArgumentException("알림 활성화 여부가 필요합니다.");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("alarmEnabled", settings.get("alarmEnabled"));
        for (String key : new String[]{"repeatIntervalMinutes", "maxRepeatCount"}) {
            Object value = settings.getOrDefault(key, 0);
            if (!(value instanceof Number)) throw new IllegalArgumentException(key + " 값은 정수여야 합니다.");
            Number number = (Number) value;
            int count = number.intValue();
            if (count < 0 || number.doubleValue() != count) throw new IllegalArgumentException(key + " 값이 잘못되었습니다.");
            result.put(key, (long) count);
        }
        return result;
    }

}
