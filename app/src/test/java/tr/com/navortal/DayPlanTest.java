package tr.com.navortal;

import org.junit.Test;
import static org.junit.Assert.*;

public class DayPlanTest {
    static int at(int h, int m) { return h * 60 + m; }

    @Test public void remainingAtSeveralTimes() {
        assertEquals(1440, DayPlan.remaining(at(0, 0)));
        assertEquals(900, DayPlan.remaining(at(9, 0)));
        assertEquals(570, DayPlan.remaining(at(14, 30)));
        assertEquals(40, DayPlan.remaining(at(23, 20)));
        assertEquals(1, DayPlan.remaining(at(23, 59)));
    }

    @Test public void formatsRemainingTime() {
        assertEquals("24 sa", DayPlan.duration(DayPlan.remaining(at(0, 0))));
        assertEquals("15 sa", DayPlan.duration(DayPlan.remaining(at(9, 0))));
        assertEquals("9 sa 30 dk", DayPlan.duration(DayPlan.remaining(at(14, 30))));
        assertEquals("40 dk", DayPlan.duration(DayPlan.remaining(at(23, 20))));
        assertEquals("1 dk", DayPlan.duration(DayPlan.remaining(at(23, 59))));
    }

    @Test public void underOneHourAndExactHourFormats() {
        assertEquals("42 dk", DayPlan.duration(42));
        assertEquals("59 dk", DayPlan.duration(59));
        assertEquals("1 sa", DayPlan.duration(60));
        assertEquals("3 sa", DayPlan.duration(180));
        assertEquals("7 sa 42 dk", DayPlan.duration(462));
        assertEquals("0 dk", DayPlan.duration(0));
        assertFalse(DayPlan.showHours(59));
        assertTrue(DayPlan.showHours(60));
    }

    @Test public void dayFractionAndClock() {
        assertEquals(0f, DayPlan.dayFraction(0), 1e-6);
        assertEquals(0.5f, DayPlan.dayFraction(at(12, 0)), 1e-6);
        assertEquals(0.75f, DayPlan.dayFraction(at(18, 0)), 1e-6);
        assertEquals("00:00", DayPlan.clock(0));
        assertEquals("16:18", DayPlan.clock(at(16, 18)));
        assertEquals("23:59", DayPlan.clock(at(23, 59)));
    }

    @Test public void countdownRunsFromFullDayToRealRemaining() {
        assertEquals(1440, DayPlan.countdown(at(16, 18), 0f));
        assertEquals(DayPlan.remaining(at(16, 18)), DayPlan.countdown(at(16, 18), 1f));
        assertEquals(1440, DayPlan.countdown(0, 1f));
    }

    @Test public void planFitsInDayAtNineAm() {
        var s = DayPlan.summarize(DayPlan.remaining(at(9, 0)), DayPlan.SET_A, 3);
        assertEquals(70, s.planned);
        assertEquals(830, s.free);
        assertTrue(s.fits());
        assertEquals(0, s.overflow());
        assertEquals("1 sa 10 dk", DayPlan.duration(s.planned));
        assertEquals("13 sa 50 dk", DayPlan.duration(s.free));
        assertEquals(900, s.scale);
    }

    @Test public void exampleFromBriefAtFourEighteenPm() {
        // 16:18 -> 462 dk kalır; plan 1 sa 10 dk, boşta 6 sa 32 dk.
        var s = DayPlan.summarize(DayPlan.remaining(at(16, 18)), DayPlan.SET_A, 3);
        assertEquals("1 sa 10 dk", DayPlan.duration(s.planned));
        assertEquals("6 sa 32 dk", DayPlan.duration(s.free));
    }

    @Test public void planOverflowsAtTwentyThreeTwenty() {
        var s = DayPlan.summarize(DayPlan.remaining(at(23, 20)), DayPlan.SET_A, 3);
        assertEquals(70, s.planned);
        assertEquals(-30, s.free);
        assertFalse(s.fits());
        assertEquals(30, s.overflow());
        assertEquals("30 dk", DayPlan.duration(s.overflow()));
        assertEquals(DayPlan.MIN_SCALE, s.scale);
    }

    @Test public void partialPlans() {
        int remaining = DayPlan.remaining(at(23, 20)); // 40 dk
        var none = DayPlan.summarize(remaining, DayPlan.SET_A, 0);
        assertEquals(0, none.planned);
        assertEquals(40, none.free);
        assertTrue(none.fits());
        var one = DayPlan.summarize(remaining, DayPlan.SET_A, 1);
        assertEquals(20, one.planned);
        assertEquals(20, one.free);
        var two = DayPlan.summarize(remaining, DayPlan.SET_A, 2);
        assertEquals(65, two.planned);
        assertEquals(-25, two.free);
        assertFalse(two.fits());
        var three = DayPlan.summarize(remaining, DayPlan.SET_A, 3);
        assertEquals(70, three.planned);
        assertEquals(30, three.overflow());
    }

    @Test public void secondSetAndSegmentFractions() {
        assertEquals(70, DayPlan.sum(DayPlan.SET_B, 3));
        assertEquals(65, DayPlan.sum(DayPlan.SET_B, 2));
        assertEquals(70, DayPlan.sum(DayPlan.SET_B, 9));
        assertEquals(20f / 462f, DayPlan.segmentFraction(20, 462), 1e-6);
        assertEquals(1f, DayPlan.segmentFraction(100, 70), 1e-6);
    }
}
