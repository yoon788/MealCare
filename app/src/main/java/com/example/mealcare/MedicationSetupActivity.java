package com.example.mealcare;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.mealcare.data.model.DoseSlot;
import com.example.mealcare.data.model.Drug;
import com.example.mealcare.data.model.MealPlan;
import com.example.mealcare.data.model.MealScheduleEntry;
import com.example.mealcare.data.model.MedicationPlans;
import com.example.mealcare.databinding.Screen07MedicationSetupBinding;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MedicationSetupActivity extends AppCompatActivity {

    private static final String TIME_ZONE = "Asia/Seoul";

    // 복용 조건 (화면의 식전 / 식후 / 식사 무관)
    private static final String BEFORE_MEAL = "before";
    private static final String AFTER_MEAL = "after";
    private static final String FIXED_TIME = "fixed";

    // 기본 식사 계획: {mealPlanId, 이름, 예정 시각}
    private static final String[][] DEFAULT_MEALS = {
            {"breakfast", "아침", "08:00"},
            {"lunch", "점심", "12:00"},
            {"dinner", "저녁", "18:30"}
    };

    private Screen07MedicationSetupBinding b;
    private FirebaseFirestore db;
    private String conditionType = AFTER_MEAL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = Screen07MedicationSetupBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        db = FirebaseFirestore.getInstance();

        b.btnBack.setOnClickListener(v -> finish());
        b.btnSave.setOnClickListener(v -> saveMedication());
        b.chipBeforeMeal.setOnClickListener(v -> selectCondition(BEFORE_MEAL));
        b.chipAfterMeal.setOnClickListener(v -> selectCondition(AFTER_MEAL));
        b.chipNoMeal.setOnClickListener(v -> selectCondition(FIXED_TIME));
        b.etAlarmTime.setOnClickListener(v -> showTimePicker());

        b.tvSelectedMed.setText("등록할 약 · 이름을 입력하세요");
        b.etMedName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                b.tvSelectedMed.setText("등록할 약 · " + s.toString().trim());
            }
        });

        selectCondition(AFTER_MEAL);
    }

    // 로그인 전에는 테스트용 uid 사용
    private String getUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? user.getUid() : "test-patient";
    }

    private void selectCondition(String type) {
        conditionType = type;
        styleChip(b.chipBeforeMeal, BEFORE_MEAL.equals(type));
        styleChip(b.chipAfterMeal, AFTER_MEAL.equals(type));
        styleChip(b.chipNoMeal, FIXED_TIME.equals(type));

        // 식사 무관이면 끼니·N분 대신 알림 시각을 직접 고른다
        boolean fixed = FIXED_TIME.equals(type);
        b.cbBreakfast.setEnabled(!fixed);
        b.cbLunch.setEnabled(!fixed);
        b.cbDinner.setEnabled(!fixed);
        b.etOffsetMinutes.setEnabled(!fixed);
        b.etAlarmTime.setEnabled(fixed);
        b.etAlarmTime.setHint(fixed ? "눌러서 시각 선택" : "식사 시간 기준으로 자동 계산");
    }

    private void styleChip(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.mc_chip_mint : R.drawable.mc_chip_gray);
        chip.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.mc_primary : R.color.mc_muted));
    }

    private void showTimePicker() {
        new TimePickerDialog(this, (view, hour, minute) ->
                b.etAlarmTime.setText(String.format(Locale.KOREA, "%02d:%02d", hour, minute)),
                12, 0, true).show();
    }

    private void saveMedication() {
        String name = b.etMedName.getText().toString().trim();
        if (name.isEmpty()) {
            toast("약 이름을 입력하세요");
            return;
        }
        List<DoseSlot> slots = makeDoseSlots();
        if (slots == null) return;

        DocumentReference patient = db.collection("patients").document(getUid());

        // 식사 계획이 하나도 없으면 기본 아침·점심·저녁 계획을 같이 저장
        patient.collection("mealPlans").get()
                .addOnSuccessListener(mealSnap -> {
                    WriteBatch batch = db.batch();

                    if (mealSnap.isEmpty()) {
                        for (String[] meal : DEFAULT_MEALS) {
                            batch.set(patient.collection("mealPlans").document(meal[0]),
                                    makeMealPlan(meal[0], meal[1], meal[2]));
                        }
                    }

                    // API 연결 전이라 직접 입력한 이름으로 약품 문서를 만든다
                    DocumentReference drugRef = db.collection("drugs").document();
                    batch.set(drugRef, makeDrug(drugRef.getId(), name));

                    DocumentReference planRef = patient.collection("medicationPlans").document();
                    batch.set(planRef, makePlan(planRef.getId(), drugRef.getId(), slots));

                    // 세 가지를 한 번에 저장 (하나라도 실패하면 모두 저장 안 됨)
                    batch.commit()
                            .addOnSuccessListener(v -> {
                                toast("약이 등록되었습니다");
                                finish();
                            })
                            .addOnFailureListener(this::showError);
                })
                .addOnFailureListener(this::showError);
    }

    // 화면 입력값으로 회차 목록을 만든다. 입력이 잘못되면 null
    private List<DoseSlot> makeDoseSlots() {
        List<DoseSlot> slots = new ArrayList<>();

        if (FIXED_TIME.equals(conditionType)) {
            String time = b.etAlarmTime.getText().toString();
            if (time.isEmpty()) {
                toast("알림 시각을 선택하세요");
                return null;
            }
            DoseSlot s = newSlot("fixed");
            s.setTimingType("fixed");
            s.setLocalTime(time);
            slots.add(s);
            return slots;
        }

        int minutes = parseInt(b.etOffsetMinutes, -1);
        if (minutes < 0) {
            toast("복용 시간을 분 단위 숫자로 입력하세요");
            return null;
        }
        // 식전은 음수, 식후는 양수
        int offset = BEFORE_MEAL.equals(conditionType) ? -minutes : minutes;

        if (b.cbBreakfast.isChecked()) slots.add(mealSlot("breakfast", offset));
        if (b.cbLunch.isChecked()) slots.add(mealSlot("lunch", offset));
        if (b.cbDinner.isChecked()) slots.add(mealSlot("dinner", offset));

        if (slots.isEmpty()) {
            toast("대상 식사를 하나 이상 선택하세요");
            return null;
        }
        return slots;
    }

    private DoseSlot mealSlot(String mealPlanId, int offset) {
        DoseSlot s = newSlot(mealPlanId);
        s.setTimingType("mealRelative");
        s.setMealPlanId(mealPlanId);
        s.setOffsetMinutes(offset);
        return s;
    }

    private DoseSlot newSlot(String slotId) {
        DoseSlot s = new DoseSlot();
        s.setSlotId(slotId);
        s.setDoseAmount(1.0);   // 화면에 복용량 입력칸이 없어 1정으로 고정
        s.setDoseUnit("정");
        s.setDaysOfWeek(everyDay());
        return s;
    }

    private Drug makeDrug(String drugId, String name) {
        Drug d = new Drug();
        d.setDrugId(drugId);
        d.setProductName(name);
        d.setSource("manual");
        d.setCatalogStatus("active");
        d.setCreatedAt(Timestamp.now());
        d.setUpdatedAt(Timestamp.now());
        return d;
    }

    private MedicationPlans makePlan(String planId, String drugId, List<DoseSlot> slots) {
        Map<String, Object> reminder = new HashMap<>();
        reminder.put("enabled", b.swAlarm.isChecked());
        reminder.put("repeatIntervalMinutes", parseInt(b.etRepeatInterval, 0));
        reminder.put("maxRepeatCount", 0);

        MedicationPlans p = new MedicationPlans();
        p.setPlanId(planId);
        p.setPatientUid(getUid());
        p.setDrugId(drugId);
        p.setStartDate(today());
        p.setTimeZone(TIME_ZONE);
        p.setDoseSlots(slots);
        p.setReminderSettings(reminder);
        p.setStatus("active");
        p.setVersion(1);
        p.setCreatedAt(Timestamp.now());
        p.setUpdatedAt(Timestamp.now());
        return p;
    }

    private MealPlan makeMealPlan(String mealPlanId, String mealName, String time) {
        MealScheduleEntry entry = new MealScheduleEntry();
        entry.setDaysOfWeek(everyDay());
        entry.setLocalTime(time);

        Map<String, Object> reminder = new HashMap<>();
        reminder.put("enabled", false);
        reminder.put("offsetMinutes", 0);

        MealPlan m = new MealPlan();
        m.setMealPlanId(mealPlanId);
        m.setPatientUid(getUid());
        m.setMealType(mealPlanId);
        m.setMealName(mealName);
        m.setWeeklySchedule(Collections.singletonList(entry));
        m.setTimeZone(TIME_ZONE);
        m.setStartDate(today());
        m.setReminderSettings(reminder);
        m.setStatus("active");
        m.setVersion(1);
        m.setCreatedAt(Timestamp.now());
        m.setUpdatedAt(Timestamp.now());
        return m;
    }

    private List<Integer> everyDay() {
        return Arrays.asList(1, 2, 3, 4, 5, 6, 7);
    }

    private String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date());
    }

    private int parseInt(EditText et, int fallback) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void showError(Exception e) {
        Log.e("MedicationSetup", "저장 실패", e);
        toast("저장 실패: " + e.getMessage());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}