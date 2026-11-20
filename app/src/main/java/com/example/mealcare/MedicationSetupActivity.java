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

import com.example.mealcare.databinding.Screen07MedicationSetupBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.Locale;

public class MedicationSetupActivity extends AppCompatActivity {

    private Screen07MedicationSetupBinding b;
    private FirebaseFirestore db;
    private String conditionType = Medication.AFTER_MEAL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = Screen07MedicationSetupBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        db = FirebaseFirestore.getInstance();

        b.btnBack.setOnClickListener(v -> finish());
        b.btnSave.setOnClickListener(v -> saveMedication());
        b.chipBeforeMeal.setOnClickListener(v -> selectCondition(Medication.BEFORE_MEAL));
        b.chipAfterMeal.setOnClickListener(v -> selectCondition(Medication.AFTER_MEAL));
        b.chipNoMeal.setOnClickListener(v -> selectCondition(Medication.FIXED_TIME));
        b.etAlarmTime.setOnClickListener(v -> showTimePicker());

        b.tvSelectedMed.setText("등록할 약 · 이름을 입력하세요");
        b.etMedName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                b.tvSelectedMed.setText("등록할 약 · " + s.toString().trim());
            }
        });

        selectCondition(Medication.AFTER_MEAL);
    }

    // 로그인 전에는 테스트용 uid 사용
    private String getUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? user.getUid() : "test-patient";
    }

    private void selectCondition(String type) {
        conditionType = type;
        styleChip(b.chipBeforeMeal, Medication.BEFORE_MEAL.equals(type));
        styleChip(b.chipAfterMeal, Medication.AFTER_MEAL.equals(type));
        styleChip(b.chipNoMeal, Medication.FIXED_TIME.equals(type));

        // 식사 무관이면 끼니·N분 대신 알림 시각을 직접 고른다
        boolean fixed = Medication.FIXED_TIME.equals(type);
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

        Medication m = new Medication();
        m.name = name;
        m.conditionType = conditionType;
        m.mealSlots = new ArrayList<>();

        if (Medication.FIXED_TIME.equals(conditionType)) {
            m.fixedTime = b.etAlarmTime.getText().toString();
            if (m.fixedTime.isEmpty()) {
                toast("알림 시각을 선택하세요");
                return;
            }
        } else {
            if (b.cbBreakfast.isChecked()) m.mealSlots.add(MealSlot.BREAKFAST);
            if (b.cbLunch.isChecked()) m.mealSlots.add(MealSlot.LUNCH);
            if (b.cbDinner.isChecked()) m.mealSlots.add(MealSlot.DINNER);
            if (m.mealSlots.isEmpty()) {
                toast("대상 식사를 하나 이상 선택하세요");
                return;
            }
            m.offsetMinutes = parseInt(b.etOffsetMinutes, -1);
            if (m.offsetMinutes < 0) {
                toast("복용 시간을 분 단위 숫자로 입력하세요");
                return;
            }
        }

        m.alarmEnabled = b.swAlarm.isChecked();
        m.repeatIntervalMinutes = parseInt(b.etRepeatInterval, 0);
        String schedule = b.etRepeatSchedule.getText().toString().trim();
        m.repeatSchedule = schedule.isEmpty() ? "매일" : schedule;
        // Auto Shift는 식후 약에만 적용
        m.autoShiftAllowed = Medication.AFTER_MEAL.equals(conditionType);

        // users/{uid}/medications 에 저장
        db.collection("users").document(getUid())
                .collection("medications")
                .add(m)
                .addOnSuccessListener(ref -> {
                    toast("약이 등록되었습니다");
                    finish();
                })
                .addOnFailureListener(e -> {
                    Log.e("MedicationSetup", "저장 실패", e);
                    toast("저장 실패: " + e.getMessage());
                });
    }

    private int parseInt(EditText et, int fallback) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}