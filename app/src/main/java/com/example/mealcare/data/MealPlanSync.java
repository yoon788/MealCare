package com.example.mealcare.data;

import com.example.mealcare.data.local.ReminderDao;
import com.example.mealcare.data.model.MealPlan;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.List;
import java.util.concurrent.Executor;

/** patients/{uid}/mealPlans의 주간 일정을 Room의 meal_times에 요일별로 반영합니다. */
public final class MealPlanSync extends PlanCollectionSync {
    public MealPlanSync(FirebaseFirestore firestore, ReminderDao reminders, Executor worker) {
        super(firestore, reminders, worker, "mealPlans");
    }

    @Override
    List<String> localPlanIds(String uid) {
        return reminders.getMealPlanIds(uid);
    }

    @Override
    void savePlan(String uid, QueryDocumentSnapshot doc) {
        MealPlan meal = doc.toObject(MealPlan.class);
        // 구독한 환자와 문서 ID를 기준으로 해당 계획의 요일 행만 교체합니다.
        meal.setPatientUid(uid);
        meal.setMealPlanId(doc.getId());
        reminders.saveMealPlan(meal);
    }

    @Override
    void deletePlan(String uid, String planId) {
        reminders.deleteMealPlan(uid, planId);
    }
}
