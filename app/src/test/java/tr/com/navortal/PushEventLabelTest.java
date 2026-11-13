package tr.com.navortal;

import static org.junit.Assert.*;

import org.junit.Test;

public class PushEventLabelTest {
    @Test public void knownTypesHaveSpecificTurkishLabels() {
        assertEquals("Plan başlıyor", PushEventLabel.of("ScheduleStart"));
        assertEquals("Plan bitiyor", PushEventLabel.of("ScheduleEnd"));
        assertEquals("Görev son tarihi", PushEventLabel.of("TaskDeadline"));
        assertEquals("Odak hedefi", PushEventLabel.of("FocusTarget"));
        assertEquals("Mola bitti", PushEventLabel.of("BreakEnd"));
        assertEquals("Göreve dön", PushEventLabel.of("ReturnToTask"));
        assertEquals("Özel gün", PushEventLabel.of("SpecialDay"));
    }

    @Test public void customAndUnknownTypesAreGeneric() {
        assertEquals("Hatırlatma", PushEventLabel.of("Custom"));
        assertEquals("Hatırlatma", PushEventLabel.of("SomethingNew"));
        assertEquals("Hatırlatma", PushEventLabel.of(null));
    }

    @Test public void subTextJoinsLabelAndParentTask() {
        assertEquals("Plan başlıyor · Tez bölüm 4", PushEventLabel.subText("ScheduleStart", "Tez bölüm 4"));
        assertEquals("Plan başlıyor", PushEventLabel.subText("ScheduleStart", ""));
        assertEquals("Hatırlatma", PushEventLabel.subText("Custom", null));
    }

    @Test public void stepSummaryNeedsValidPosition() {
        assertEquals("Alt görev 2/5", PushEventLabel.stepSummary(2, 5));
        assertEquals("Alt görev 5/5", PushEventLabel.stepSummary(5, 5));
        assertEquals("", PushEventLabel.stepSummary(0, 5));
        assertEquals("", PushEventLabel.stepSummary(6, 5));
        assertEquals("", PushEventLabel.stepSummary(1, 0));
    }
}
