package com.example.mealcare;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.mealcare.data.model.DoseSlot;
import com.example.mealcare.data.model.MealPlan;
import com.example.mealcare.data.model.MealScheduleEntry;
import com.example.mealcare.data.model.MedicationPlans;
import com.example.mealcare.data.model.MedicationRecord;
import com.example.mealcare.databinding.ItemMedicationBinding;
import com.example.mealcare.databinding.Screen08MedicationManagementBinding;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MedicationManagementActivity extends AppCompatActivity {

    private static final String TIME_ZONE = "Asia/Seoul";

    // 오늘 먹을 약 한 회차 (복약 계획 + 회차 + 계산한 시각 + 기록)
    private static class DoseItem {
        MedicationPlans plan;
        DoseSlot slot;
        String drugName;
        String time;              // "HH:mm"
        MedicationRecord record;  // 아직 기록이 없으면 null
    }

    private Screen08MedicationManagementBinding b;
    private FirebaseFirestore db;
    private String today;

    private final Map<String, String> mealTimes = new HashMap<>();   // mealPlanId → 오늘 예정 시각
    private final Map<String, String> mealNames = new HashMap<>();   // mealPlanId → "아침" 등
    private final List<MedicationPlans> plans = new ArrayList<>();
    private final Map<String, String> drugNames = new HashMap<>();   // drugId → 약 이름
    private final List<DoseItem> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = Screen08MedicationManagementBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        db = FirebaseFirestore.getInstance();
        today = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date());

        b.btnBack.setOnClickListener(v -> finish());
        b.btnAddMed.setOnClickListener(v ->
                startActivity(new Intent(this, MedicationSetupActivity.class)));
        b.btnTakeAll.setOnClickListener(v -> confirmTakeAll());
    }

    // 약 등록 화면에서 돌아왔을 때도 목록이 새로 보이도록 onResume에서 불러옴
    @Override
    protected void onResume() {
        super.onResume();
        loadMealPlans();
    }

    private String getUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? user.getUid() : "test-patient";
    }

    private CollectionReference patientCol(String name) {
        return db.collection("patients").document(getUid()).collection(name);
    }

    // 불러오는 순서: 식사 계획 → 복약 계획 → 약 이름 → 오늘 복약 기록
    private void loadMealPlans() {
        patientCol("mealPlans").get()
                .addOnSuccessListener(snap -> {
                    mealTimes.clear();
                    mealNames.clear();
                    for (MealPlan m : snap.toObjects(MealPlan.class)) {
                        String time = timeForToday(m.getWeeklySchedule());
                        if (time != null) mealTimes.put(m.getMealPlanId(), time);
                        mealNames.put(m.getMealPlanId(), m.getMealName());
                    }
                    loadPlans();
                })
                .addOnFailureListener(this::showError);
    }

    private void loadPlans() {
        patientCol("medicationPlans").whereEqualTo("status", "active").get()
                .addOnSuccessListener(snap -> {
                    plans.clear();
                    plans.addAll(snap.toObjects(MedicationPlans.class));
                    loadDrugNames();
                })
                .addOnFailureListener(this::showError);
    }

    private void loadDrugNames() {
        List<Task<DocumentSnapshot>> tasks = new ArrayList<>();
        for (MedicationPlans p : plans) {
            tasks.add(db.collection("drugs").document(p.getDrugId()).get());
        }
        // 약 문서 여러 개를 동시에 불러오고, 전부 끝나면 다음 단계로
        Tasks.whenAllSuccess(tasks)
                .addOnSuccessListener(results -> {
                    drugNames.clear();
                    for (Object o : results) {
                        DocumentSnapshot doc = (DocumentSnapshot) o;
                        drugNames.put(doc.getId(), doc.getString("productName"));
                    }
                    loadRecords();
                })
                .addOnFailureListener(this::showError);
    }

    private void loadRecords() {
        patientCol("medicationRecords").whereEqualTo("scheduleDate", today).get()
                .addOnSuccessListener(snap -> {
                    Map<String, MedicationRecord> records = new HashMap<>();
                    for (MedicationRecord r : snap.toObjects(MedicationRecord.class)) {
                        records.put(r.getRecordId(), r);
                    }
                    buildItems(records);
                    showItems();
                })
                .addOnFailureListener(this::showError);
    }

    private void buildItems(Map<String, MedicationRecord> records) {
        items.clear();
        int dow = todayDayOfWeek();

        for (MedicationPlans p : plans) {
            if (p.getDoseSlots() == null) continue;
            for (DoseSlot s : p.getDoseSlots()) {
                if (s.getDaysOfWeek() != null && !s.getDaysOfWeek().contains(dow)) continue;

                String time = calcTime(s);
                if (time == null) continue;   // 식사 계획이 없으면 시각을 알 수 없어 제외

                DoseItem item = new DoseItem();
                item.plan = p;
                item.slot = s;
                item.drugName = drugNames.get(p.getDrugId());
                item.time = time;
                item.record = records.get(recordId(p, s));
                items.add(item);
            }
        }
        Collections.sort(items, (x, y) -> x.time.compareTo(y.time));
    }

    // 고정 시각이면 그대로, 식사 연동이면 식사 예정 시각 + offsetMinutes
    private String calcTime(DoseSlot s) {
        if ("fixed".equals(s.getTimingType())) {
            return s.getLocalTime();
        }
        String mealTime = mealTimes.get(s.getMealPlanId());
        if (mealTime == null) return null;
        int offset = s.getOffsetMinutes() != null ? s.getOffsetMinutes() : 0;
        return toTime(toMinutes(mealTime) + offset);
    }

    private void showItems() {
        b.medListContainer.removeAllViews();
        int done = 0, unchecked = 0, pending = 0;

        for (DoseItem item : items) {
            ItemMedicationBinding card =
                    ItemMedicationBinding.inflate(getLayoutInflater(), b.medListContainer, false);

            card.tvName.setText(item.drugName != null ? item.drugName : "이름 없는 약");
            card.tvCondition.setText(conditionText(item.slot));

            if (isTaken(item)) {
                done++;
                setChip(card, "완료", R.drawable.mc_chip_mint, R.color.mc_primary);
                card.tvTimeInfo.setText(item.time + "   복용 " + toTime(item.record.getTakenAt()));
                card.tvTake.setText("✓ 복용 완료 기록됨");
                card.tvTake.setTextColor(ContextCompat.getColor(this, R.color.mc_muted));
            } else {
                // 기록이 없거나 취소된 경우는 '미확인' (설계 문서 기준)
                if (toMinutes(item.time) <= nowMinutes()) {
                    unchecked++;
                    setChip(card, "미확인", R.drawable.mc_chip_amber, R.color.mc_amber);
                } else {
                    pending++;
                    setChip(card, "예정", R.drawable.mc_chip_gray, R.color.mc_muted);
                }
                String base = "fixed".equals(item.slot.getTimingType())
                        ? "시각 고정"
                        : "식사 " + mealTimes.get(item.slot.getMealPlanId());
                card.tvTimeInfo.setText(item.time + "   " + base);
                card.tvTake.setOnClickListener(v -> takeDose(item));
            }

            b.medListContainer.addView(card.getRoot());
        }

        b.tvDoneCount.setText(String.valueOf(done));
        b.tvUncheckedCount.setText(String.valueOf(unchecked));
        b.tvPendingCount.setText(String.valueOf(pending));
        b.tvMedCount.setText(plans.size() + "개");
        b.tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private String conditionText(DoseSlot s) {
        if ("fixed".equals(s.getTimingType())) {
            return "매일 " + s.getLocalTime() + " · 식사 무관";
        }
        int offset = s.getOffsetMinutes() != null ? s.getOffsetMinutes() : 0;
        String meal = mealNames.containsKey(s.getMealPlanId()) ? mealNames.get(s.getMealPlanId()) : "";
        String when = offset < 0 ? "식전" : "식후";
        return meal + " " + when + " " + Math.abs(offset) + "분";
    }

    private void setChip(ItemMedicationBinding card, String text, int bgRes, int colorRes) {
        card.tvStatus.setText(text);
        card.tvStatus.setBackgroundResource(bgRes);
        card.tvStatus.setTextColor(ContextCompat.getColor(this, colorRes));
    }

    private boolean isTaken(DoseItem item) {
        return item.record != null && "taken".equals(item.record.getStatus());
    }

    // 기록 id = planId_slotId_날짜 (같은 회차는 문서 하나로 유지)
    private String recordId(MedicationPlans p, DoseSlot s) {
        return p.getPlanId() + "_" + s.getSlotId() + "_" + today;
    }

    private void takeDose(DoseItem item) {
        String uid = getUid();
        Timestamp now = Timestamp.now();
        MedicationRecord r = item.record;

        if (r == null) {
            r = new MedicationRecord();
            r.setRecordId(recordId(item.plan, item.slot));
            r.setPatientUid(uid);
            r.setPlanId(item.plan.getPlanId());
            r.setSlotId(item.slot.getSlotId());
            r.setPlanVersion(item.plan.getVersion());
            r.setScheduleDate(today);
            r.setTimeZone(TIME_ZONE);
            r.setScheduledAt(toTimestamp(item.time));
            r.setDoseSnapshot(makeSnapshot(item));
            r.setCreatedByUid(uid);
            r.setCreatedAt(now);
            r.setRevision(1);
        } else {
            r.setRevision(r.getRevision() + 1);
        }
        r.setStatus("taken");
        r.setTakenAt(now);
        r.setUpdatedByUid(uid);
        r.setUpdatedAt(now);

        MedicationRecord saved = r;
        patientCol("medicationRecords").document(r.getRecordId()).set(r)
                .addOnSuccessListener(v -> {
                    item.record = saved;
                    showItems();
                })
                .addOnFailureListener(e -> {
                    showError(e);
                    loadMealPlans();
                });
    }

    // 기록 당시의 약 정보를 같이 저장 (나중에 약 정보가 바뀌어도 기록은 그대로)
    private Map<String, Object> makeSnapshot(DoseItem item) {
        Map<String, Object> snap = new HashMap<>();
        snap.put("drugId", item.plan.getDrugId());
        snap.put("productName", item.drugName);
        snap.put("doseAmount", item.slot.getDoseAmount());
        snap.put("doseUnit", item.slot.getDoseUnit());
        return snap;
    }

    private void confirmTakeAll() {
        List<DoseItem> due = new ArrayList<>();
        for (DoseItem item : items) {
            if (!isTaken(item) && toMinutes(item.time) <= nowMinutes()) due.add(item);
        }
        if (due.isEmpty()) {
            toast("지금 복용 완료로 기록할 약이 없습니다");
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("복용 완료 기록")
                .setMessage("복용 시간이 지난 약 " + due.size() + "개를 모두 복용 완료로 기록할까요?")
                .setPositiveButton("기록", (dialog, which) -> {
                    for (DoseItem item : due) takeDose(item);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ---------- 날짜·시간 계산 ----------

    // 월요일 1 ~ 일요일 7 (설계 문서 기준)
    private int todayDayOfWeek() {
        int dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);  // 일요일 1 ~ 토요일 7
        return (dow + 5) % 7 + 1;
    }

    private String timeForToday(List<MealScheduleEntry> schedule) {
        if (schedule == null) return null;
        int dow = todayDayOfWeek();
        for (MealScheduleEntry e : schedule) {
            if (e.getDaysOfWeek() != null && e.getDaysOfWeek().contains(dow)) return e.getLocalTime();
        }
        return null;
    }

    private Timestamp toTimestamp(String hhmm) {
        Calendar c = Calendar.getInstance();
        int m = toMinutes(hhmm);
        c.set(Calendar.HOUR_OF_DAY, m / 60);
        c.set(Calendar.MINUTE, m % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return new Timestamp(c.getTime());
    }

    private String toTime(Timestamp t) {
        if (t == null) return "";
        return new SimpleDateFormat("HH:mm", Locale.KOREA).format(t.toDate());
    }

    private String toTime(int minutes) {
        int m = ((minutes % 1440) + 1440) % 1440;
        return String.format(Locale.KOREA, "%02d:%02d", m / 60, m % 60);
    }

    private int toMinutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    private int nowMinutes() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private void showError(Exception e) {
        Log.e("MedicationManagement", "Firestore 오류", e);
        toast("불러오기 실패: " + e.getMessage());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}