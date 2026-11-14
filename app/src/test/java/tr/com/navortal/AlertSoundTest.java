package tr.com.navortal;

import static org.junit.Assert.*;

import org.junit.Test;

public class AlertSoundTest {
    @Test public void knownSoundsAreAccepted() {
        for (var id : AlertSound.IDS) assertEquals(id, AlertSound.valid(id));
        assertEquals("bell", AlertSound.valid(" bell "));
    }

    @Test public void unknownOrMissingSoundFallsBackToEmpty() {
        assertEquals("", AlertSound.valid(null));
        assertEquals("", AlertSound.valid(""));
        assertEquals("", AlertSound.valid("custom"));
        assertEquals("", AlertSound.valid("../bell"));
        assertEquals("", AlertSound.valid("BELL"));
    }

    @Test public void channelAndResourceNamesAreStable() {
        assertEquals("reminders-glass-v3", AlertSound.channelId("glass"));
        assertEquals("alert_glass", AlertSound.rawName("glass"));
        assertEquals("Çan", AlertSound.label("bell"));
    }

    @Test public void phoneSoundUnlessCustomIsEnabledWithAValidSound() {
        assertEquals(PushNotifications.CHANNEL, AlertSound.channelFor(false, "bell"));
        assertEquals(PushNotifications.CHANNEL, AlertSound.channelFor(true, ""));
        assertEquals(PushNotifications.CHANNEL, AlertSound.channelFor(true, "custom"));
        assertEquals("reminders-bell-v3", AlertSound.channelFor(true, "bell"));
    }

    @Test public void eventTypesMapToCategories() {
        assertEquals("pomodoro", AlertSound.category("FocusTarget"));
        assertEquals("pomodoro", AlertSound.category("BreakEnd"));
        assertEquals("taskEnd", AlertSound.category("ScheduleEnd"));
        for (var type : new String[] {"Custom", "ReturnToTask", "TaskDeadline", "SpecialDay", "ScheduleStart", "SomethingNew", "", null})
            assertEquals(String.valueOf(type), "reminder", AlertSound.category(type));
    }

    @Test public void allThreeValidSoundsAreRequired() throws Exception {
        var ok = AlertSound.parseSounds(new org.json.JSONObject("{\"pomodoro\":\"bell\",\"reminder\":\"marimba\",\"taskEnd\":\"soft\"}"));
        assertArrayEquals(new String[] {"bell", "marimba", "soft"}, ok);
        assertNull(AlertSound.parseSounds(null));
        assertNull(AlertSound.parseSounds(new org.json.JSONObject("{\"pomodoro\":\"bell\",\"reminder\":\"marimba\"}")));
        assertNull(AlertSound.parseSounds(new org.json.JSONObject("{\"pomodoro\":\"bell\",\"reminder\":\"x\",\"taskEnd\":\"soft\"}")));
        assertNull(AlertSound.parseSounds(new org.json.JSONObject("{\"pomodoro\":1,\"reminder\":\"bell\",\"taskEnd\":\"soft\"}")));
    }
}
