package com.hpe.hvmalert

import org.junit.Test

import static org.junit.Assert.*

class AlertStateTest {

    static Finding f(String key, String sev = Finding.WARNING, int hold = 0, String source = 'host', int confirm = 2) {
        new Finding(key: key, severity: sev, clusterId: 1L, cluster: 'CLS', resource: 'h', title: key, source: source,
                holdSeconds: hold, confirmCycles: confirm)
    }

    @Test
    void opensAfterTwoCyclesAndResolvesAfterTwoMisses() {
        AlertState st = new AlertState()
        st.update([f('a')], 0L)
        assertTrue('one sighting is not enough', st.active.isEmpty())
        st.update([f('a')], 120000L)
        assertEquals(['a'], st.active.keySet() as List)
        assertEquals('since = first sighting', 0L, st.active.a.since.time)
        st.update([], 240000L)
        assertTrue('one miss keeps it open', st.active.containsKey('a'))
        st.update([f('a', Finding.CRITICAL)], 360000L)
        assertEquals('severity change applies at once', Finding.CRITICAL, st.active.a.severity)
        st.update([], 480000L)
        st.update([], 600000L)
        assertTrue(st.active.isEmpty())
    }

    @Test
    void transientReadNeverOpens() {
        AlertState st = new AlertState()
        st.update([f('blip')], 0L)
        st.update([], 120000L)
        st.update([f('blip')], 240000L)
        assertTrue(st.active.isEmpty())
    }

    @Test
    void nativeAlarmsPassStraightThrough() {
        AlertState st = new AlertState()
        st.update([f('alarm:1', Finding.CRITICAL, 0, 'native-alarm')], 0L)
        assertTrue(st.active.containsKey('alarm:1'))
        st.update([], 120000L)
        assertTrue(st.active.isEmpty())
    }

    @Test
    void holdTimeStillApplies() {
        AlertState st = new AlertState()
        st.update([f('dlm', Finding.CRITICAL, 300)], 0L)
        st.update([f('dlm', Finding.CRITICAL, 300)], 120000L)
        assertTrue('seen twice but inside the 300 s hold', st.active.isEmpty())
        st.update([f('dlm', Finding.CRITICAL, 300)], 300000L)
        assertTrue(st.active.containsKey('dlm'))
    }

    @Test
    void carriedPrefixKeepsProblemsOfUnreadHosts() {
        AlertState st = new AlertState()
        st.update([f('hc:1:quorum'), f('host:1:agent')], 0L)
        st.update([f('hc:1:quorum'), f('host:1:agent')], 1000L)
        st.update([], 2000L, ['hc:1:'] as Set)
        st.update([], 3000L, ['hc:1:'] as Set)
        assertTrue(st.active.containsKey('hc:1:quorum'))
        assertFalse(st.active.containsKey('host:1:agent'))
    }

    @Test
    void diffAgainstWhatWasNotified() {
        Map<String, Finding> told = [a: f('a'), b: f('b')]
        AlertState.Diff d = AlertState.diff(told, [f('a', Finding.CRITICAL), f('c')])
        assertEquals(['c'], d.opened*.key)
        assertEquals(['a'], d.changed*.key)
        assertEquals(['b'], d.resolved*.key)
        assertTrue(AlertState.diff(told, [f('a'), f('b')]).isEmpty())
    }

    @Test
    void firstEventCallReportsOnlyTheLookback() {
        AlertState st = new AlertState()
        List<Map> old = [[ts: '2026-11-04T18:00:00.000Z', severity: 'critical', title: 't', line: 'old'],
                         [ts: '2026-11-04T18:40:00.000Z', severity: 'critical', title: 't', line: 'failover']]
        List<Map> first = st.newEvents('hc:1:', old, '2026-11-04T18:46:36Z')
        assertEquals('events of the last 15 min survive a takeover', ['failover'], first*.line)
        assertTrue(first[0].preStart)
        List<Map> later = old + [[ts: '2026-11-04T18:50:00.000Z', severity: 'critical', title: 't', line: 'y']]
        List<Map> next = st.newEvents('hc:1:', later, '2026-11-04T18:51:00Z')
        assertEquals(['y'], next*.line)
        assertNull(next[0].preStart)
        assertEquals([], st.newEvents('hc:1:', later, '2026-11-04T18:52:00Z'))
    }

    @Test
    void sameMillisecondEventsAreToldApartByText() {
        AlertState st = new AlertState()
        st.newEvents('hc:1:', [], '2026-11-04T18:00:00Z')
        List<Map> a = [[ts: '2026-11-04T18:37:07.326Z', line: 'a']]
        assertEquals(['a'], st.newEvents('hc:1:', a, '2026-11-04T18:38:00Z')*.line)
        List<Map> ab = a + [[ts: '2026-11-04T18:37:07.326Z', line: 'b']]
        assertEquals(['b'], st.newEvents('hc:1:', ab, '2026-11-04T18:39:00Z')*.line)
        assertEquals([], st.newEvents('hc:1:', ab, '2026-11-04T18:40:00Z'))
    }

    @Test
    void sourceDebouncedFindingsOpenAtOnce() {
        AlertState st = new AlertState()
        st.update([f('hc:1:quorum', Finding.CRITICAL, 0, 'host', 1)], 0L)
        assertTrue(st.active.containsKey('hc:1:quorum'))
        st.update([], 120000L)
        assertTrue('resolve still needs two misses', st.active.containsKey('hc:1:quorum'))
    }

    @Test
    void failedClusterKeepsItsProblems() {
        AlertState st = new AlertState()
        st.update([f('hc:7:quorum'), f('ds:3:state')], 0L)
        st.update([f('hc:7:quorum'), f('ds:3:state')], 1000L)
        st.update([], 2000L, [] as Set, [1L] as Set)
        st.update([], 3000L, [] as Set, [1L] as Set)
        assertEquals(['hc:7:quorum', 'ds:3:state'] as Set, st.active.keySet())
    }

    @Test
    void heartbeatRunNeedsSixConsecutive() {
        AlertState st = new AlertState()
        long t0 = 1000000L
        // field HA test pattern: 5 lines, gaps 30/20/20/20 s
        assertNull(st.heartbeatRun(1L, [t0, t0 + 30000L, t0 + 50000L, t0 + 70000L, t0 + 90000L]))
        List<Long> run = st.heartbeatRun(1L, [t0 + 110000L])
        assertEquals([6L, t0, t0 + 110000L], run)
        st.markApdReported(1L, t0 + 110000L)
        assertNull('the same run is decided once', st.heartbeatRun(1L, [t0 + 130000L]))
        assertNull('a gap over 60 s breaks the run', st.heartbeatRun(2L, (0..5).collect { t0 + it * 61000L }))
    }

    @Test
    void unbrokenStreamIsOneIncident() {
        AlertState st = new AlertState()
        long t0 = 1000000L
        int reports = 0
        (0..179).collate(6).each { List<Integer> batch ->           // 60 min of failures every 20 s, read per 2 min
            List<Long> run = st.heartbeatRun(1L, batch.collect { t0 + it * 20000L })
            if (run) { reports++; st.markApdReported(1L, run[2]) }
        }
        assertEquals(1, reports)
        assertNull('still the same incident', st.heartbeatRun(1L, [t0 + 180 * 20000L]))
        assertNotNull('a new run after a gap is a new incident', st.heartbeatRun(1L, (0..5).collect { t0 + 4000000L + it * 20000L }))
    }

    @Test
    void resetClearsHostReadState() {
        AlertState st = new AlertState()
        st.logPos[5L] = ['1', 10L]
        st.heartbeatFailures[5L] = [1L]
        st.reset()
        assertTrue(st.logPos.isEmpty())
        assertTrue(st.heartbeatFailures.isEmpty())
    }
}
