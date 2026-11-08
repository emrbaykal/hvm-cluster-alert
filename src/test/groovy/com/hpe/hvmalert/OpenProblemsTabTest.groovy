package com.hpe.hvmalert

import org.junit.Test

import static org.junit.Assert.*

/** 1.17.0: the tab lists the open problems of the last check below the settings, in the appliance's own classes. */
class OpenProblemsTabTest {

    static Map own() {
        [version: 1, alertEnabled: true, recipients: ['ops@x.com'], agentWarnMinutes: 2, agentCriticalMinutes: 5,
         nodeUnreachableSeconds: 60, fencingWaitSeconds: 30, updatedBy: 'u', updatedAt: 0L]
    }

    @Test
    void openProblemsSortedAndAcknowledgedMarked() {
        List<Finding> open = Collections.unmodifiableList([
                new Finding(key: 'alarm:1', severity: Finding.WARNING, resource: 'ds01', title: 'Datastore warning', source: 'native-alarm',
                        since: new Date(1000L), meta: [acknowledged: true]),
                new Finding(key: 'hc:3:gfs2', severity: Finding.CRITICAL, resource: 'vmenode03', title: 'GFS2 <withdrawn>', source: 'host',
                        since: new Date(2000L))])
        String html = SettingsPage.render([clusterId: 1L, clusterName: 'c', url: '/u', own: own(),
                                           open: [checkedAt: new Date(60000L), findings: open]])
        assertTrue(html.contains('<h5>Open problems</h5>'))
        assertTrue(html.contains('2 open. Last check: 1970-01-01 00:01 UTC.'))
        assertTrue('critical first', html.indexOf('vmenode03') < html.indexOf('ds01'))
        assertTrue(html.contains('Datastore warning <span class="text-muted">(acknowledged)</span>'))
        assertTrue(html.contains('GFS2 &lt;withdrawn&gt;'))
        assertTrue('mail look: green header', html.contains('background:#01a982;color:#fff'))
        assertTrue('bold red state for a critical', html.contains('color:#c62828;font-weight:bold">OPEN</td>'))
        assertTrue('orange for a warning', html.contains('color:#ef6c00;font-weight:bold">OPEN</td>'))
        assertFalse('appliance font sizes, no inline ones', html.contains('font-size'))
        assertTrue(html.contains('class="form-horizontal"') && html.contains('class="info-list"'))
    }

    @Test
    void emptyAndNotWatched() {
        String none = SettingsPage.render([clusterId: 1L, clusterName: 'c', url: '/u', own: own(), open: [checkedAt: new Date(0L), findings: []]])
        assertTrue(none.contains('No open problems. Last check: 1970-01-01 00:00 UTC.'))
        String off = SettingsPage.render([clusterId: 1L, clusterName: 'c', url: '/u', own: own() + [alertEnabled: false],
                                          open: [checkedAt: new Date(0L), findings: []]])
        assertFalse('not watched: no problem list', off.contains('Open problems'))
    }

    @Test
    void snapshotOnlyOnTheLeaderAndOnlyForWatchedClusters() {
        AlertScheduler s = new AlertScheduler(node: 'mgr')
        s.state.active['hc:3:x'] = new Finding(key: 'hc:3:x', severity: Finding.CRITICAL, clusterId: 1L, source: 'host')
        s.state.active['hc:9:y'] = new Finding(key: 'hc:9:y', severity: Finding.WARNING, clusterId: 2L, source: 'host')
        s.publishOpen([1L] as Set, new Date(5L))
        assertNull('not the leader', s.openFor(1L))
        s.lockId = 'lock'
        assertEquals(['hc:3:x'], s.openFor(1L).findings*.key)
        assertEquals(new Date(5L), s.openFor(1L).checkedAt)
        assertNull('cluster 2 is not watched', s.openFor(2L))
        s.stopped = true
        assertNull(s.openFor(1L))
    }
}
