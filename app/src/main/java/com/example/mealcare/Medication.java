package com.example.mealcare;

import com.google.firebase.firestore.DocumentId;
import java.util.List;
public class Medication {
    public static final String BEFORE_MEAL = "BEFORE_MEAL";
    public static final String AFTER_MEAL = "AFTER_MEAL";
    public static final String FIXED_TIME = "FIXED_TIME";   // 화면의 "식사 무관"

    @DocumentId
    public String id;
    public String name;
    public String conditionType;
    public List<String> mealSlots;      // MealSlot 값, 식사 무관이면 빈 목록
    public int offsetMinutes;           // 식전/식후 N분
    public String fixedTime;            // "HH:mm", 식사 무관일 때만 사용
    public boolean alarmEnabled;
    public int repeatIntervalMinutes;   // 미복용 시 다시 알리는 간격, 0이면 반복 없음
    public String repeatSchedule;       // 예: "매일"
    public boolean autoShiftAllowed;    // 식후 약만 true

    public Medication() {}              // Firestore가 문서를 객체로 바꿀 때 필요
}
}
