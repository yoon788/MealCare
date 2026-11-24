package com.example.mealcare.data;

import android.icu.text.SimpleDateFormat;
import android.icu.util.Calendar;
import android.icu.util.TimeZone;

import com.example.mealcare.data.local.DailyMedicationDose;
import com.example.mealcare.data.model.DoseSlot;
import com.example.mealcare.data.model.MedicationPlans;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;

/** 현재·과거 계획과 실제 기록을 같은 회차 키로 합칩니다. 날짜별로 Firestore를 다시 읽지 않습니다. */
final class RecordStatisticsQuery {
    private RecordStatisticsQuery() {}

    static Task<RecordStatistics> load(DocumentReference patient, boolean medication, String start, String end,
                                       QuerySnapshot records) {
        return patient.collection(medication ? "medicationPlans" : "mealPlans").get(Source.SERVER)
                .onSuccessTask(Runnable::run, roots -> {
                    Map<String, Task<List<DocumentSnapshot>>> reads = new HashMap<>();
                    for (DocumentSnapshot root : roots) reads.put(root.getId(), versions(root));
                    return gather(reads);
                }).onSuccessTask(ForkJoinPool.commonPool(), plans -> {
                    // 복약 통계에는 연결된 식사의 계획만 필요합니다. 식사 기록은 읽지 않습니다.
                    Set<String> linked = new HashSet<>();
                    if (medication) {
                        for (List<DocumentSnapshot> history : plans.values()) {
                            for (DocumentSnapshot doc : history) {
                                if (!inRange(doc, start, end)) continue;
                                for (DoseSlot slot : doc.toObject(MedicationPlans.class).getDoseSlots()) {
                                    if ("mealRelative".equals(slot.getTimingType())) linked.add(slot.getMealPlanId());
                                }
                            }
                        }
                    }
                    Map<String, Task<List<DocumentSnapshot>>> meals = new HashMap<>();
                    for (String id : linked) {
                        meals.put(id, patient.collection("mealPlans").document(id).get(Source.SERVER)
                                .onSuccessTask(Runnable::run, RecordStatisticsQuery::versions));
                    }
                    return gather(meals).onSuccessTask(ForkJoinPool.commonPool(), times ->
                            Tasks.forResult(count(medication, start, end, records, plans, times)));
                });
    }

    /** 현재 버전보다 이전인 연속 이력만 사용합니다. 조회 도중 생긴 더 최신 이력은 중복 적용하지 않습니다. */
    private static Task<List<DocumentSnapshot>> versions(DocumentSnapshot root) {
        if (!root.exists()) return Tasks.forResult(Collections.emptyList());
        int version = PlanDocument.versionOf(root);
        if (version == 0 || (version == 1 && RecordDocument.effectiveFrom(root) == null)) {
            return Tasks.forResult(Collections.singletonList(root));
        }
        return root.getReference().collection("versions").get(Source.SERVER).onSuccessTask(Runnable::run, history -> {
            Map<String, DocumentSnapshot> byId = new HashMap<>();
            for (DocumentSnapshot doc : history) byId.put(doc.getId(), doc);
            List<DocumentSnapshot> result = new ArrayList<>();
            result.add(root);
            for (int v = version - 1; v >= 0; v--) {
                DocumentSnapshot doc = byId.get(String.valueOf(v));
                if (v == 0 && doc == null) break; // 신규 계획은 1부터, 구형 계획은 0부터 시작합니다.
                if (doc == null || PlanDocument.versionOf(doc) != v || doc.getTimestamp("supersededAt") == null) {
                    throw new FirebaseFirestoreException("계획 변경 이력이 불완전합니다.",
                            FirebaseFirestoreException.Code.FAILED_PRECONDITION);
                }
                result.add(doc);
            }
            return Tasks.forResult(result);
        });
    }

    private static Task<Map<String, List<DocumentSnapshot>>> gather(Map<String, Task<List<DocumentSnapshot>>> reads) {
        return Tasks.whenAllComplete(new ArrayList<>(reads.values())).onSuccessTask(Runnable::run, ignored -> {
            Map<String, List<DocumentSnapshot>> result = new HashMap<>();
            for (Map.Entry<String, Task<List<DocumentSnapshot>>> entry : reads.entrySet()) {
                Task<List<DocumentSnapshot>> read = entry.getValue();
                if (!read.isSuccessful()) return Tasks.forException(read.getException());
                result.put(entry.getKey(), read.getResult());
            }
            return Tasks.forResult(result);
        });
    }

    private static RecordStatistics count(boolean medication, String start, String end, QuerySnapshot records,
                                          Map<String, List<DocumentSnapshot>> plans, Map<String, List<DocumentSnapshot>> meals)
            throws ParseException {
        Set<String> recorded = new HashSet<>();
        long completed = 0;
        long missed = 0;
        for (DocumentSnapshot doc : records) {
            String date = doc.getString("scheduleDate");
            String key = medication ? RecordDocument.recordId(doc.getString("planId"), doc.getString("slotId"), date)
                    : RecordDocument.recordId(doc.getString("mealPlanId"), date);
            if (!recorded.add(key)) throw new IllegalStateException("같은 예정 회차에 기록이 두 개 이상 있습니다.");
            String status = doc.getString("status");
            if ((medication ? "taken" : "completed").equals(status)) completed++;
            if ((medication ? "notTaken" : "skipped").equals(status)) missed++;
        }
        // 당시 일정에서 저장한 기록 자체도 예정 1건의 증거입니다. 이후 계획이 없어져도 결과를 보존합니다.
        long scheduled = recorded.size();
        SimpleDateFormat dates = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        dates.setTimeZone(TimeZone.getTimeZone("UTC"));
        dates.setLenient(false);
        for (Map.Entry<String, List<DocumentSnapshot>> plan : plans.entrySet()) {
            List<DocumentSnapshot> active = new ArrayList<>();
            Map<DocumentSnapshot, List<DoseSlot>> slots = new java.util.IdentityHashMap<>();
            String first = end;
            String last = start;
            for (DocumentSnapshot doc : plan.getValue()) {
                if (!inRange(doc, start, end)) continue;
                active.add(doc);
                if (medication) slots.put(doc, doc.toObject(MedicationPlans.class).getDoseSlots());
                if (doc.getString("startDate").compareTo(first) < 0) first = doc.getString("startDate");
                String until = doc.getString("endDate");
                if (until == null || until.compareTo(end) > 0) until = end;
                if (until.compareTo(last) > 0) last = until;
            }
            if (active.isEmpty()) continue;
            Calendar day = Calendar.getInstance(dates.getTimeZone(), Locale.ROOT);
            day.setTime(dates.parse(first.compareTo(start) < 0 ? start : first));
            long lastMillis = dates.parse(last).getTime();
            // 하루 안에서 같은 slotId가 여러 버전에 등장해도 1회로 세고, 날짜가 바뀌면 집합을 재사용합니다.
            Set<String> dueSlots = new HashSet<>();
            for (; day.getTimeInMillis() <= lastMillis; day.add(Calendar.DATE, 1)) {
                String date = dates.format(day.getTime());
                int weekday = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1;
                dueSlots.clear();
                for (DocumentSnapshot doc : active) {
                    if (!RecordDocument.scheduledOn(doc, date)) continue;
                    if (medication) {
                        for (DoseSlot slot : slots.get(doc)) {
                            if (slot.getDaysOfWeek().contains(weekday) && doseIsScheduled(doc, slot, date, weekday, meals)) {
                                dueSlots.add(slot.getSlotId());
                            }
                        }
                    } else if (RecordDocument.mealTime(doc, date, weekday, doc.getString("timeZone")) != null) {
                        if (!recorded.contains(RecordDocument.recordId(plan.getKey(), date))) scheduled++;
                        break; // 한 식사 계획은 하루 한 건입니다.
                    }
                }
                for (String slotId : dueSlots) {
                    if (!recorded.contains(RecordDocument.recordId(plan.getKey(), slotId, date))) scheduled++;
                }
            }
        }
        return new RecordStatistics(scheduled, completed, missed);
    }

    private static boolean inRange(DocumentSnapshot doc, String start, String end) {
        String first = doc.getString("startDate");
        String last = doc.getString("endDate");
        return "active".equals(doc.get("status")) && first != null && first.compareTo(end) <= 0
                && (last == null || (last.compareTo(first) >= 0 && last.compareTo(start) >= 0));
    }

    private static boolean doseIsScheduled(DocumentSnapshot doc, DoseSlot slot, String date, int weekday,
                                          Map<String, List<DocumentSnapshot>> meals) {
        String zone = doc.getString("timeZone");
        if ("fixed".equals(slot.getTimingType())) {
            Long due = DailyMedicationDose.scheduledMillis(date, slot.getLocalTime(), zone, 0, zone);
            return due != null && RecordDocument.effectiveAt(doc, due);
        }
        boolean hasMealTime = false;
        for (DocumentSnapshot meal : meals.get(slot.getMealPlanId())) {
            Long base = RecordDocument.mealTime(meal, date, weekday, zone);
            if (base == null) continue;
            hasMealTime = true;
            if (RecordDocument.effectiveAt(doc, base + slot.getOffsetMinutes() * 60_000L)) return true;
        }
        // 식사 시각이 미정이어도 그 날짜에 유효한 복약 회차는 Room 일정처럼 미확인으로 남깁니다.
        if (hasMealTime) return false;
        SimpleDateFormat localDate = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        localDate.setTimeZone(TimeZone.getTimeZone(zone));
        Timestamp from = RecordDocument.effectiveFrom(doc);
        Timestamp until = doc.getTimestamp("supersededAt");
        if (from != null && localDate.format(from.toDate()).compareTo(date) > 0) return false;
        if (until == null) return true;
        long lastMillis = until.getSeconds() * 1000 + (until.getNanoseconds() + 999999L) / 1000000 - 1;
        return localDate.format(new Date(lastMillis)).compareTo(date) >= 0;
    }
}
