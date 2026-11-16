package com.example.mealcare;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.mealcare.data.CurrentUser;
import com.example.mealcare.data.MedicationRepository;

import java.util.ArrayList;
import java.util.Locale;

public class MedicationSetupActivity extends AppCompatActivity {

    private EditText etMedName, etOffsetMinutes, etRepeatInterval, etAlarmTime, etRepeatSchedule;
    private TextView tvSelectedMed, chipBeforeMeal, chipAfterMeal, chipNoMeal;
    private CheckBox cbBreakfast, cbLunch, cbDinner;
    private Switch swAlarm;

    private String conditionType = Medication.AFTER_MEAL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_07_medication_setup);

        etMedName = findViewById(R.id.etMedName);
        etOffsetMinutes = findViewById(R.id.etOffsetMinutes);
        etRepeatInterval = findViewById(R.id.etRepeatInterval);
        etAlarmTime = findViewById(R.id.etAlarmTime);
        etRepeatSchedule = findViewById(R.id.etRepeatSchedule);
        tvSelectedMed = findViewById(R.id.tvSelectedMed);
        chipBeforeMeal = findViewById(R.id.chipBeforeMeal);
        chipAfterMeal = findViewById(R.id.chipAfterMeal);
        chipNoMeal = findViewById(R.id.chipNoMeal);
        cbBreakfast = findViewById(R.id.cbBreakfast);
        cbLunch = findViewById(R.id.cbLunch);
        cbDinner = findViewById(R.id.cbDinner);
        swAlarm = findViewById(R.id.swAlarm);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        chipBeforeMeal.setOnClickListener(v -> selectCondition(Medication.BEFORE_MEAL));
        chipAfterMeal.setOnClickListener(v -> selectCondition(Medication.AFTER_MEAL));
        chipNoMeal.setOnClickListener(v -> selectCondition(Medication.FIXED_TIME));
        etAlarmTime.setOnClickListener(v -> showTimePicker());

        // 입력한 약 이름을 아래 "등록할 약" 줄에 바로 반영
        tvSelectedMed.setText("등록할 약 · 이름을 입력하세요");
        etMedName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                tvSelectedMed.setText("등록할 약 · " + s.toString().trim());
            }
        });

        selectCondition(Medication.AFTER_MEAL);
    }

    private void selectCondition(String type) {
        conditionType = type;
        styleChip(chipBeforeMeal, Medication.BEFORE_MEAL.equals(type));
        styleChip(chipAfterMeal, Medication.AFTER_MEAL.equals(type));
        styleChip(chipNoMeal, Medication.FIXED_TIME.equals(type));

        // 식사 무관이면 끼니·N분 대신 알림 시각을 직접 고른다
        boolean fixed = Medication.FIXED_TIME.equals(type);
        cbBreakfast.setEnabled(!fixed);
        cbLunch.setEnabled(!fixed);
        cbDinner.setEnabled(!fixed);
        etOffsetMinutes.setEnabled(!fixed);
        etAlarmTime.setEnabled(fixed);
        etAlarmTime.setHint(fixed ? "눌러서 시각 선택" : "식사 시간 기준으로 자동 계산");
    }

    private void styleChip(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.mc_chip_mint : R.drawable.mc_chip_gray);
        chip.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.mc_primary : R.color.mc_muted));
    }

    private void showTimePicker() {
        new TimePickerDialog(this, (view, hour, minute) ->
                etAlarmTime.setText(String.format(Locale.KOREA, "%02d:%02d", hour, minute)),
                12, 0, true).show();
    }

    private void save() {
        String name = etMedName.getText().toString().trim();
        if (name.isEmpty()) {
            toast("약 이름을 입력하세요");
            return;
        }

        Medication m = new Medication();
        m.name = name;
        m.conditionType = conditionType;
        m.mealSlots = new ArrayList<>();

        if (Medication.FIXED_TIME.equals(conditionType)) {
            m.fixedTime = etAlarmTime.getText().toString();
            if (m.fixedTime.isEmpty()) {
                toast("알림 시각을 선택하세요");
                return;
            }
        } else {
            if (cbBreakfast.isChecked()) m.mealSlots.add(MealSlot.BREAKFAST);
            if (cbLunch.isChecked()) m.mealSlots.add(MealSlot.LUNCH);
            if (cbDinner.isChecked()) m.mealSlots.add(MealSlot.DINNER);
            if (m.mealSlots.isEmpty()) {
                toast("대상 식사를 하나 이상 선택하세요");
                return;
            }
            m.offsetMinutes = parseInt(etOffsetMinutes, -1);
            if (m.offsetMinutes < 0) {
                toast("복용 시간을 분 단위 숫자로 입력하세요");
                return;
            }
        }

        m.alarmEnabled = swAlarm.isChecked();
        m.repeatIntervalMinutes = parseInt(etRepeatInterval, 0);
        String schedule = etRepeatSchedule.getText().toString().trim();
        m.repeatSchedule = schedule.isEmpty() ? "매일" : schedule;
        // Auto Shift는 식후 약에만 적용 (식전·고정 시각 약은 제외)
        m.autoShiftAllowed = Medication.AFTER_MEAL.equals(conditionType);

        new MedicationRepository(CurrentUser.uid()).add(m)
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