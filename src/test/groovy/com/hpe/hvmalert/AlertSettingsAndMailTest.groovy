package com.hpe.hvmalert

import org.junit.Test

import static org.junit.Assert.*

class AlertSettingsAndMailTest {

    @Test
    void changeMailSubjectAndEscaping() {
        AlertState.Diff d = new AlertState.Diff()
        d.opened << new Finding(key: 'k', severity: Finding.CRITICAL, cluster: 'CLS', resource: '<h1>', title: 'Quorum lost', detail: 'a&b')
        d.resolved << new Finding(key: 'r', severity: Finding.WARNING, cluster: 'CLS', resource: 'h2', title: 'Agent silent')
        Map m = MailComposer.change(d, 'node1')
        assertEquals('[HVM Cluster Alert] CRITICAL CLS: 1 new, 1 resolved', m.subject)
        assertTrue(m.html.contains('&lt;h1&gt;'))
        assertTrue(m.html.contains('a&amp;b'))
        assertTrue(m.html.contains('RESOLVED'))
    }

    @Test
    void startedAndDailyMails() {
        Map m = MailComposer.started([], [], 'node1', 'HVM 2.0 Cluster')
        assertEquals('[HVM Cluster Alert] HVM 2.0 Cluster: monitor started on node1, 0 open problem(s)', m.subject)
        assertTrue(m.html.contains('No open problems.'))
        Finding ev = new Finding(key: 'e', severity: Finding.CRITICAL, cluster: 'C', title: 'HA failover')
        assertTrue(MailComposer.started([], [ev], 'n', 'C').html.contains('HA failover'))
        assertEquals('[HVM Cluster Alert] C: daily status, 0 open problem(s)', MailComposer.daily([], 'n', 'C').subject)
    }

    static AlertScheduler scheduler(List<Map> sent, Closure<Boolean> smtpUp) {
        AlertScheduler sch = new AlertScheduler(node: 'n1')
        sch.mailer = [send: { List to, String subject, String html ->
            if (!smtpUp.call()) throw new IOException('connect timed out')
            sent << [to: to, subject: subject, html: html]
        }] as ApplianceMailer
        sch
    }

    /** Scheduler mail logic with a fake mailer: nothing is lost on failure, retries back off, daily status. */
    @Test
    void failedMailIsRetriedWithoutLosingChanges() {
        List<Map> sent = []
        boolean smtpUp = false
        AlertScheduler sch = scheduler(sent) { smtpUp }
        AlertSettings s = new AlertSettings(intervalMinutes: 2)
        AlertSettings.ClusterRule rule = new AlertSettings.ClusterRule(clusterId: 1L, name: 'C', recipients: ['a@x.com'],
                fingerprint: 'f1', dailyStatus: true)
        s.rules << rule
        AlertCycle.Outcome o = new AlertCycle.Outcome(clusters: [(1L): 'C'])
        Finding q = new Finding(key: 'alarm:9', severity: Finding.CRITICAL, clusterId: 1L, cluster: 'C', title: 'Quorum lost', source: 'native-alarm')

        sch.state.update([q], 0L)
        sch.notifyClusters(s, o, 0L)                         // first cycle of this cluster: summary waits
        assertNull(sch.state.mail[1L].lastErrorMs)
        sch.notifyClusters(s, o, 0L)                         // second cycle: started mail fails
        assertEquals([], sent)
        assertEquals(1, sch.state.mail[1L].failures)
        assertEquals(120000L, sch.state.mail[1L].nextAttemptMs)
        sch.notifyClusters(s, o, 60000L)                     // inside the backoff: no attempt
        sch.notifyClusters(s, o, 120000L)                    // fails again: backoff doubles
        assertEquals(120000L + 240000L, sch.state.mail[1L].nextAttemptMs)

        smtpUp = true
        Finding ev = new Finding(key: 'event:1:x', severity: Finding.CRITICAL, clusterId: 1L, cluster: 'C', title: 'HA failover', source: 'event')
        sch.notifyClusters(s, new AlertCycle.Outcome(clusters: [(1L): 'C'], events: [ev]), 360000L)
        assertEquals(1, sent.size())
        assertTrue(sent[0].subject.contains('monitor started'))
        assertTrue('the open problem and the event are in it', sent[0].html.contains('Quorum lost') && sent[0].html.contains('HA failover'))

        sch.state.update([], 400000L)                        // native alarm cleared
        smtpUp = false
        sch.notifyClusters(s, o, 400000L)
        smtpUp = true
        sch.notifyClusters(s, o, 400000L + 120000L)
        assertEquals(2, sent.size())
        assertTrue('resolve survives the failed send', sent[1].subject.contains('1 resolved'))

        sch.notifyClusters(s, o, 600000L)
        assertEquals('nothing changed, nothing sent', 2, sent.size())
        sch.notifyClusters(s, o, 520000L + AlertScheduler.DAY_MS)
        assertEquals(3, sent.size())
        assertTrue(sent[2].subject.contains('daily status'))

        rule.fingerprint = 'f2'                              // integration edited: summary after confirmation
        sch.notifyClusters(s, o, 530000L + AlertScheduler.DAY_MS)
        assertEquals(3, sent.size())
        sch.notifyClusters(s, o, 650000L + AlertScheduler.DAY_MS)
        assertTrue(sent[3].subject.contains('monitor started'))
    }

    /** Two clusters with the same name stay apart: rules, recipients and mail state are keyed by cluster id. */
    @Test
    void sameNameClustersStayApart() {
        List<Map> sent = []
        AlertScheduler sch = scheduler(sent) { true }
        AlertSettings s = new AlertSettings()
        s.rules << new AlertSettings.ClusterRule(clusterId: 1L, name: 'prod', recipients: ['a@x.com'], fingerprint: 'a')
        s.rules << new AlertSettings.ClusterRule(clusterId: 2L, name: 'prod', recipients: ['b@y.com'], fingerprint: 'b')
        AlertCycle.Outcome o = new AlertCycle.Outcome(clusters: [(1L): 'prod', (2L): 'prod'])
        sch.state.update([new Finding(key: 'alarm:1', severity: Finding.CRITICAL, clusterId: 1L, cluster: 'prod', title: 'A-only', source: 'native-alarm'),
                          new Finding(key: 'alarm:2', severity: Finding.CRITICAL, clusterId: 2L, cluster: 'prod', title: 'B-only', source: 'native-alarm')], 0L)
        sch.notifyClusters(s, o, 0L)
        sch.notifyClusters(s, o, 120000L)
        assertEquals(2, sent.size())
        Map toA = sent.find { it.to == ['a@x.com'] }, toB = sent.find { it.to == ['b@y.com'] }
        assertTrue(toA.html.contains('A-only') && !toA.html.contains('B-only'))
        assertTrue(toB.html.contains('B-only') && !toB.html.contains('A-only'))
    }

    /** Hosts in maintenance: what other hosts report about them is dropped, but not a real missing host. */
    @Test
    void maintenanceHostFindingsAreDropped() {
        def hf = { String key, Map meta -> new Finding(key: key, severity: Finding.CRITICAL, clusterId: 5L, cluster: 'C', source: 'host', meta: meta) }
        List<Finding> fs = [hf('hc:1:peer:hvm3.lab.local', [peer: 'hvm3.lab.local']), hf('hc:1:peer:hvm2', [peer: 'hvm2']),
                            hf('hc:1:reachable', [missing: 1]), hf('hc:1:down-hosts', [hosts: ['hvm3']]), hf('hc:2:reachable', [missing: 2])]
        AlertCycle.dropMaintenanceFindings(5L, [[id: 3L, names: ['hvm3'] as Set, off: true]], fs)
        assertEquals(['hc:1:peer:hvm2', 'hc:2:reachable'], fs*.key)

        List<Finding> up = [hf('hc:1:reachable', [missing: 1])]
        AlertCycle.dropMaintenanceFindings(5L, [[id: 3L, names: ['hvm3'] as Set, off: false]], up)
        assertEquals('maintenance host is up, so the missing node is another host', ['hc:1:reachable'], up*.key)
    }

    static com.morpheusdata.model.ComputeServer host(long id, String name) {
        new com.morpheusdata.model.ComputeServer(id: id, name: name)
    }

    static AlertCycle.Work work(Map cand) {
        new AlertCycle.Work(cluster: new com.morpheusdata.model.ComputeServerGroup(id: 5L, name: 'C'),
                rule: new AlertSettings.ClusterRule(clusterId: 5L), apdCandidates: [cand])
    }

    @Test
    void apdIsSuppressedDuringPeerLoss() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        Map cand = [host: host(1L, 'h1'), count: 6L, first: 1000000L, last: 1100000L, stretch: false, preStart: false]
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        // the survivor h1 itself reports the lost peer (1.15.0: only this host's own reports count)
        o.events << new Finding(key: 'event:1:x', severity: Finding.CRITICAL, clusterId: 5L, cluster: 'C', source: 'event',
                since: new Date(1020000L), meta: [rule: 'peer-unreachable'])
        cycle.evaluateApd(work(cand), o, 1200000L)
        assertEquals('no APD during a host loss', [], o.events.findAll { it.meta.rule == 'apd-derived' })
    }

    @Test
    void apdWarningWithoutPeerLossCriticalWithPathsDown() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        Map cand = [host: host(1L, 'h1'), count: 6L, first: 1000000L, last: 1100000L, stretch: false, preStart: true]
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        cycle.evaluateApd(work(cand), o, 1200000L)
        Finding apd = o.events.find { it.meta.rule == 'apd-derived' }
        assertEquals(Finding.WARNING, apd.severity)
        assertEquals(1100000L, apd.since.time)
        assertTrue(apd.preStart)

        AlertCycle c2 = new AlertCycle(state: new AlertState())
        AlertCycle.Outcome o2 = new AlertCycle.Outcome()
        o2.findings << new Finding(key: 'hc:1:mpath:360002ac', severity: Finding.CRITICAL, clusterId: 5L, cluster: 'C', source: 'host')
        c2.evaluateApd(work(cand), o2, 1200000L)
        assertEquals(Finding.CRITICAL, o2.events.find { it.meta.rule == 'apd-derived' }.severity)

        AlertCycle c3 = new AlertCycle(state: new AlertState())
        AlertCycle.Outcome o3 = new AlertCycle.Outcome()
        c3.evaluateApd(work(cand + [stretch: true]), o3, 1200000L)
        assertEquals('stretch cluster: 9 needed', [], o3.events)
    }

    @Test
    void oldPeerLossDoesNotHideALaterApd() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        cycle.state.firstSeen['hc:2:peer:h9'] = 1000L                     // h9 has been down for days
        Map cand = [host: host(1L, 'h1'), count: 6L, first: 900000000L, last: 900100000L, stretch: false, preStart: false]
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        o.findings << new Finding(key: 'hc:2:peer:h9', severity: Finding.CRITICAL, clusterId: 5L, cluster: 'C', source: 'host', meta: [peer: 'h9'])
        cycle.evaluateApd(work(cand), o, 900200000L)
        assertEquals(Finding.WARNING, o.events.find { it.meta.rule == 'apd-derived' }?.severity)
    }

    @Test
    void storagePathsDownAreNeverSuppressed() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        Map cand = [host: host(1L, 'h1'), count: 6L, first: 1000000L, last: 1100000L, stretch: false, preStart: false]
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        o.events << new Finding(key: 'event:2:x', severity: Finding.CRITICAL, clusterId: 5L, cluster: 'C', source: 'event',
                since: new Date(1020000L), meta: [rule: 'peer-unreachable'])
        o.findings << new Finding(key: 'hc:1:mpath:360002ac', severity: Finding.CRITICAL, clusterId: 5L, cluster: 'C', source: 'host')
        cycle.evaluateApd(work(cand), o, 1200000L)
        assertEquals(Finding.CRITICAL, o.events.find { it.meta.rule == 'apd-derived' }?.severity)
    }

    /** VME lab 2026-11-07: /quorum names peers "vmenode02-2"; match by suffix or address, never node-1 for node-2. */
    @Test
    void maintenancePeerMatchedBySuffixOrAddress() {
        assertTrue(AlertCycle.sameHost('vmenode02-2', 'vmenode02'))
        assertTrue(AlertCycle.sameHost('VMENODE01.lab.local', 'vmenode01'))
        assertFalse(AlertCycle.sameHost('node-2', 'node-1'))
        def hf = { String key, Map meta -> new Finding(key: key, severity: Finding.CRITICAL, clusterId: 5L, cluster: 'C', source: 'host', meta: meta) }
        List<Finding> fs = [hf('hc:1:peer:vmenode03-3', [peer: 'vmenode03-3', addresses: ['10.0.0.23']]),
                            hf('hc:1:peer:x', [peer: 'renamed', addresses: ['10.0.0.23']]),
                            hf('hc:1:peer:node-2', [peer: 'node-2', addresses: ['10.0.0.2']])]
        AlertCycle.dropMaintenanceFindings(5L, [[id: 4L, names: ['vmenode03', 'node-1'] as Set, ips: ['10.0.0.23'] as Set, off: false]], fs)
        assertEquals(['hc:1:peer:node-2'], fs*.key)
    }

    /** The agent logs command output; the script output must not be read back as log lines. */
    @Test
    void multipathSummaryLines() {
        List<String> keys = [], titles = []
        HostCheck.parseMultipath(['3600a|sdc|failed|faulty', 'T|3600a|2', '3600b|sdd|failed|faulty', '3600b|sde|failed|faulty', 'T|3600b|2'],
                { String k, String sev, String t, String d, Map o = [:] -> keys << k; titles << t })
        assertEquals(['1 of 2 paths failed on 3600a', 'All 2 paths down on 3600b'], titles)
    }

    @Test
    void repeatedEventsAreOneRow() {
        def ev = { String host, String rule, long t -> new Finding(key: "event:${host}:${t}", severity: Finding.WARNING, cluster: 'C', resource: host,
                title: rule, source: 'event', since: new Date(t), meta: [rule: rule]) }
        List<Finding> evs = (0..5).collect { ev('h1', 'hb-all', 1000000L + it * 20000L) } + [ev('h1', 'peer-unreachable', 1010000L), ev('h2', 'hb-all', 1000000L)]
        List<Finding> rows = MailComposer.aggregate(evs)
        assertEquals(3, rows.size())
        assertTrue(rows.find { it.resource == 'h1' && it.meta.rule == 'hb-all' }.title.contains('(x6, 00:16:40-00:18:20 UTC)'))
    }
}
