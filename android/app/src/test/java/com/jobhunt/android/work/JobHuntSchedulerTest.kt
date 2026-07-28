package com.jobhunt.android.work

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class JobHuntSchedulerTest {

    @Test
    fun `waits until later today when the run hour has not passed`() {
        val now = LocalDateTime.of(2026, 6, 9, 5, 30)
        val delay = JobHuntScheduler.delayUntilNextRun(hour = 7, now = now)
        assertEquals(90, delay.toMinutes())
    }

    @Test
    fun `rolls over to tomorrow when the run hour has already passed`() {
        val now = LocalDateTime.of(2026, 6, 9, 9, 0)
        val delay = JobHuntScheduler.delayUntilNextRun(hour = 7, now = now)
        assertEquals(22 * 60, delay.toMinutes())
    }

    @Test
    fun `exactly on the hour schedules the next day, not a zero delay`() {
        val now = LocalDateTime.of(2026, 6, 9, 7, 0)
        val delay = JobHuntScheduler.delayUntilNextRun(hour = 7, now = now)
        assertEquals(24 * 60, delay.toMinutes())
    }

    @Test
    fun `an out-of-range hour is clamped instead of throwing`() {
        val now = LocalDateTime.of(2026, 6, 9, 12, 0)
        assertEquals(12 * 60, JobHuntScheduler.delayUntilNextRun(hour = 99, now = now).toMinutes())
        assertEquals(12 * 60, JobHuntScheduler.delayUntilNextRun(hour = -5, now = now).toMinutes())
    }
}
