package tr.com.navortal;

/** Hatırlatma türünün (eventType) kullanıcıya gösterilen Türkçe adı. Bilinmeyen tür genel "Hatırlatma" olur. */
final class PushEventLabel {
    private PushEventLabel() { }

    static String of(String eventType) {
        if (eventType == null) return "Hatırlatma";
        switch (eventType) {
            case "ScheduleStart": return "Plan başlıyor";
            case "ScheduleEnd": return "Plan bitiyor";
            case "TaskDeadline": return "Görev son tarihi";
            case "FocusTarget": return "Odak hedefi";
            case "BreakEnd": return "Mola bitti";
            case "ReturnToTask": return "Göreve dön";
            case "SpecialDay": return "Özel gün";
            default: return "Hatırlatma"; // Custom ve bilinmeyen türler
        }
    }

    /** Bildirimin küçük üst satırı: "Plan başlıyor · Tez bölüm 4" (üst görev yoksa yalnız tür). */
    static String subText(String eventType, String parentTitle) {
        var label = of(eventType);
        return parentTitle == null || parentTitle.isEmpty() ? label : label + " · " + parentTitle;
    }

    /** "Alt görev 2/5"; geçersiz veya yok ise boş. */
    static String stepSummary(int index, int count) {
        return count >= 1 && index >= 1 && index <= count ? "Alt görev " + index + "/" + count : "";
    }
}
