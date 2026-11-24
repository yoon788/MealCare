package com.example.mealcare.data;

import com.example.mealcare.data.local.ReminderDao;
import com.example.mealcare.data.model.MedicationPlans;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.List;
import java.util.concurrent.Executor;

/** 한 환자의 복약 계획과 복용 회차를 Room에 반영합니다. */
public final class MedicationPlanSync extends PlanCollectionSync {
    public MedicationPlanSync(FirebaseFirestore firestore, ReminderDao reminders, Executor worker) {
        super(firestore, reminders, worker, "medicationPlans");
    }

    @Override
    List<String> localPlanIds(String uid) {
        return reminders.getMedicationPlanIds(uid);
    }

    @Override
    void savePlan(String uid, QueryDocumentSnapshot doc) {
        MedicationPlans plan = doc.toObject(MedicationPlans.class);
        // 문서 경로의 키를 사용해 다른 환자의 로컬 행에 덮어쓰는 일을 막습니다.
        plan.setPatientUid(uid);
        plan.setPlanId(doc.getId());
        String legacyName = null;
        if (plan.getSourceItemId() == null && plan.getDrugName() == null && plan.getDrugId() != null) {
            legacyName = reminders.getLegacyDrugName(uid, doc.getId(), plan.getDrugId());
            if (legacyName == null) legacyName = "약품명 미등록";
        }
        reminders.saveMedicationPlan(plan, legacyName);
    }

    @Override
    void deletePlan(String uid, String planId) {
        reminders.deleteMedicationPlan(uid, planId);
    }
}
