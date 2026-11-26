package com.example.mealcare;

import android.app.Application;

import com.example.mealcare.data.MealPlanSync;
import com.example.mealcare.data.MedicationPlanSync;
import com.example.mealcare.data.local.ReminderDao;
import com.example.mealcare.data.local.ReminderDatabase;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MealCareApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseFirestore firestore = FirebaseFirestore.getInstance();
        ReminderDao reminders = ReminderDatabase.getInstance(this).reminders();
        Executor worker = Executors.newSingleThreadExecutor();
        MedicationPlanSync medications = new MedicationPlanSync(firestore, reminders, worker);
        MealPlanSync meals = new MealPlanSync(firestore, reminders, worker);
        // 로그인 복원·계정 전환·로그아웃에 맞춰 두 컬렉션의 구독을 함께 시작하거나 해제합니다.
        FirebaseAuth.getInstance().addAuthStateListener(auth -> {
            FirebaseUser account = auth.getCurrentUser();
            String uid = account == null ? null : account.getUid();
            medications.watchPatient(uid);
            meals.watchPatient(uid);
        });
    }
}
