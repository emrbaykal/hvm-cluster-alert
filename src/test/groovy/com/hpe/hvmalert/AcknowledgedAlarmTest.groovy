package com.hpe.hvmalert

import org.junit.Test

import static org.junit.Assert.*

/** 1.25.2: an HVM alarm acknowledged in VME leaves the open list and the daily mail; a mailed one resolves once, marked. */
class AcknowledgedAlarmTest {

    static Finding alarm(String key, String title) {
        new Finding(key: key, severity: Finding.WARNING, clusterId: 1L, cluster: 'C', resource: 'vm1', title: title, source: 'native-alarm')
    }

    @Test
    void anAcknowledgedAlarmResolvesOnceMarkedAndThenStaysQuiet() {
        List<Map> sent = []
        AlertScheduler sch = AlertSettingsAndMailTest.scheduler(sent) { true }
        AlertSettings s = new AlertSettings(intervalMinutes: 2)
        s.rules << new AlertSettings.ClusterRule(clusterId: 1L, name: 'C', recipients: ['a@x.com'], fingerprint: 'f1', dailyStatus: true)
        AlertCycle.Outcome o = new AlertCycle.Outcome(clusters: [(1L): 'C'])
        sch.notifyClusters(s, o, 0L)
        sch.notifyClusters(s, o, 0L)
        assertEquals(1, sent.size())                                          // monitor started

        sch.state.update([alarm('alarm:7', 'VM Guest Agent Not Responding - vm1'), alarm('alarm:8', 'VM Guest Agent Not Responding - vm2')], 120000L)
        sch.notifyClusters(s, o, 120000L)
        assertEquals(2, sent.size())
        assertTrue(sent[1].subject, sent[1].subject.contains('2 new'))

        // alarm 7 acknowledged in VME: the alarm read no longer returns it and names it acknowledged
        sch.state.acknowledged = ['alarm:7'] as Set
        sch.state.update([alarm('alarm:8', 'VM Guest Agent Not Responding - vm2')], 240000L)
        sch.notifyClusters(s, o, 240000L)
        assertEquals(3, sent.size())
        assertTrue(sent[2].subject, sent[2].subject.contains('1 resolved'))
        assertTrue(sent[2].html.contains('VM Guest Agent Not Responding - vm1 (acknowledged in VME)'))
        assertFalse('the open alarm is not marked', sent[2].html.contains('vm2 (acknowledged'))

        sch.notifyClusters(s, o, 360000L)
        assertEquals('told once', 3, sent.size())
        sch.notifyClusters(s, o, 240000L + AlertScheduler.DAY_MS)
        assertEquals(4, sent.size())
        assertFalse('not in the daily mail', sent[3].html.contains('Responding - vm1'))
        assertTrue(sent[3].html.contains('Responding - vm2'))
    }

    @Test
    void aClearedAlarmWithoutAcknowledgeResolvesUnmarked() {
        AlertState.Diff d = AlertState.diff(['alarm:7': alarm('alarm:7', 'Host unreachable')], [], ['alarm:9'] as Set)
        assertEquals(['Host unreachable'], d.resolved*.title)
        AlertState.Diff n = AlertState.diff(['alarm:7': alarm('alarm:7', 'Host unreachable')], [])
        assertEquals(['Host unreachable'], n.resolved*.title)
    }
}
