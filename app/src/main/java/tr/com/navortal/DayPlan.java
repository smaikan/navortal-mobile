package tr.com.navortal;

import java.util.Locale;

/** Karşılama ekranının saf zaman/plan hesabı; Android'e bağlı değildir. */
final class DayPlan {
    static final int DAY_MINUTES = 1440;
    /** Çubuk, kalan süre bundan kısaysa bile en az bu kadar dakikaya göre ölçeklenir. */
    static final int MIN_SCALE = 70;
    static final int[] SET_A = {20, 45, 5};
    static final int[] SET_B = {40, 25, 5};

    private DayPlan() { }

    private static int clampMinute(int minuteOfDay) { return Math.max(0, Math.min(DAY_MINUTES - 1, minuteOfDay)); }

    /** Gece yarısına kalan dakika (00:00 için 1440). */
    static int remaining(int minuteOfDay) { return DAY_MINUTES - clampMinute(minuteOfDay); }

    static float dayFraction(int minuteOfDay) { return clampMinute(minuteOfDay) / (float) DAY_MINUTES; }

    /** Açılış animasyonunda gösterilen kalan dakika: progress 0'da 1440, 1'de gerçek değer. */
    static int countdown(int minuteOfDay, float progress) {
        return Math.round(DAY_MINUTES - clampMinute(minuteOfDay) * Math.max(0f, Math.min(1f, progress)));
    }

    static boolean showHours(int minutes) { return minutes >= 60; }

    /** "7 sa 42 dk", "42 dk", "3 sa". */
    static String duration(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        if (h == 0) return m + " dk";
        return m == 0 ? h + " sa" : h + " sa " + m + " dk";
    }

    static String clock(int minuteOfDay) { return String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60); }

    static int sum(int[] minutes, int count) {
        int total = 0;
        for (int i = 0; i < Math.min(count, minutes.length); i++) total += minutes[i];
        return total;
    }

    /** Görevlerin ilk {@code shown} tanesinin planı; free negatifse gün yetmiyor. */
    static final class Summary {
        final int planned, free, scale;
        Summary(int planned, int free, int scale) { this.planned = planned; this.free = free; this.scale = scale; }
        boolean fits() { return free >= 0; }
        int overflow() { return Math.max(0, -free); }
    }

    static Summary summarize(int remaining, int[] minutes, int shown) {
        int planned = sum(minutes, shown);
        return new Summary(planned, remaining - planned, Math.max(remaining, MIN_SCALE));
    }

    static float segmentFraction(int taskMinutes, int scale) { return Math.min(1f, taskMinutes / (float) scale); }
}
