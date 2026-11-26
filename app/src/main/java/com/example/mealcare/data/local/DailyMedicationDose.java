package com.example.mealcare.data.local;

import android.icu.text.SimpleDateFormat;
import android.icu.util.Calendar;
import android.icu.util.GregorianCalendar;
import android.icu.util.TimeZone;

import androidx.annotation.Nullable;
import androidx.room.Embedded;

import java.text.ParseException;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/** Room에서 읽은 복약 회차 한 건과 그 회차의 예정 시각입니다. */
public final class DailyMedicationDose {
    public final ReminderDoseSlot slot;
    public final String drugName;
    public final String scheduleDate; // 반복 요일과 적용 기간을 확인한 날짜
    public final String timeZone;     // 결과를 표시할 복약 계획의 시간대
    @Nullable public final Long scheduledAtMillis;        // UTC epoch 밀리초
    @Nullable public final String scheduledLocalDateTime; // 복약 계획 시간대의 yyyy-MM-dd HH:mm

    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern TIME = Pattern.compile("(?:[01][0-9]|2[0-3]):[0-5][0-9]");

    /** 한 번의 JOIN으로 읽는 열입니다. 회차에는 planId, slotId, 복용량·단위가 들어 있습니다. */
    static class Row {
        @Embedded public ReminderDoseSlot slot;
        public String drugName;
        public String timeZone;
        @Nullable public String mealTime;
        @Nullable public String mealZone;
    }

    DailyMedicationDose(Row row, String date) {
        slot = row.slot;
        drugName = row.drugName;
        scheduleDate = date;
        timeZone = row.timeZone;
        boolean fixed = "fixed".equals(slot.timingType);
        String localTime = fixed ? slot.localTime : row.mealTime;
        String baseZone = fixed ? timeZone : row.mealZone;
        Integer offset = fixed ? Integer.valueOf(0) : slot.offsetMinutes;
        scheduledAtMillis = scheduledMillis(date, localTime, baseZone, offset, timeZone);
        scheduledLocalDateTime = scheduledAtMillis == null ? null
                : formatter("yyyy-MM-dd HH:mm", timeZone).format(new Date(scheduledAtMillis));
    }

    /** 날짜 자체의 요일을 구하므로 기기 시간대가 바뀌어도 월요일 1 ~ 일요일 7을 유지합니다. */
    public static int weekdayOf(String date) {
        if (date == null || !DATE.matcher(date).matches()) throw new IllegalArgumentException("날짜 형식은 yyyy-MM-dd입니다.");
        SimpleDateFormat format = formatter("yyyy-MM-dd", "UTC");
        try {
            Date parsed = format.parse(date);
            Calendar calendar = format.getCalendar();
            calendar.setTime(parsed);
            return (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1;
        } catch (ParseException error) {
            throw new IllegalArgumentException("유효하지 않은 날짜입니다: " + date, error);
        }
    }

    /** Room 일정과 Firestore 복약 기록에서 같은 시간대·서머타임 기준으로 복용 예정 시각을 구합니다. */
    @Nullable
    public static Long scheduledMillis(String date, @Nullable String time, @Nullable String zone,
                                @Nullable Integer offset, String displayZone) {
        if (time == null || !TIME.matcher(time).matches() || offset == null || zone == null || displayZone == null
                || TimeZone.getCanonicalID(zone) == null || TimeZone.getCanonicalID(displayZone) == null) return null;
        try {
            // 식사 현지 시각을 먼저 절대 시각으로 바꿔 자정이나 서머타임을 넘어도 실제 분 단위로 더합니다.
            Date base = formatter("yyyy-MM-dd HH:mm", zone).parse(date + " " + time);
            return base.getTime() + offset * 60_000L;
        } catch (ParseException missingLocalTime) {
            // 서머타임 전환으로 존재하지 않는 현지 시각은 미정으로 남깁니다.
            return null;
        }
    }

    private static SimpleDateFormat formatter(String pattern, String zone) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
        GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone(zone), Locale.ROOT);
        calendar.setLenient(false);
        calendar.setRepeatedWallTimeOption(Calendar.WALLTIME_FIRST); // 시계가 뒤로 가서 같은 시각이 두 번이면 첫 회차
        format.setCalendar(calendar);
        return format;
    }
}
