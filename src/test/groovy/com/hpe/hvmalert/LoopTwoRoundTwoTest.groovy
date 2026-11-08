package com.hpe.hvmalert

import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import org.junit.Test

import static org.junit.Assert.*

/** Loop 2 round 2 fix list (M1, M2, S1-S5); field lines from the VME lab T3 test, 2026-11-08. */
class LoopTwoRoundTwoTest {

    static final String HB = '2026-11-08T08:40:28.100Z [x] [] WARN  c.m.agent.stats.MvmHeartbeatFailover - '

    @Test
    void m1AgentLinesGetTheirOwnRules() {
        List<String> lines = [
            HB + 'All heartbeat datastore paths have been unhealthy for host vmenode03 for 6 checks (budget 6). However, network heartbeat is still responsive. leaving VMs alone for now.',
            '2026-11-08T08:40:48.100Z [x] [] ERROR c.m.agent.stats.MvmHeartbeatFailover - All heartbeat datastore paths have been unhealthy for host vmenode03 for 7 checks (budget 6). Shutting down all VMs to protect data integrity.',
            '2026-11-08T08:40:38.100Z [x] [] WARN  c.m.a.services.QuorumCheckService - DLM lockspace membership lost (was previously joined) - confirmation 1/2 (reboot deferred until threshold reached)',
            '2026-11-08T08:41:38.100Z [x] [] ERROR c.m.a.services.QuorumCheckService - DLM lockspace membership lost for 2 consecutive cycles - rebooting to cleanly rejoin cluster',
            '2026-11-08T08:41:38.200Z [x] [] ERROR c.m.a.services.QuorumCheckService - Recovering self node via reboot in 5 seconds...']
        Map<String, String> byTime = HostCheck.parseEvents(lines).collectEntries { [(it.ts): it.rule] }     // events come sorted by time
        assertEquals([apd: 'apd-budget', shut: 'apd-shutdown', dlm: 'dlm-member-lost', reb: 'self-reboot', rec: 'self-reboot'],
                [apd: byTime['2026-11-08T08:40:28.100Z'], shut: byTime['2026-11-08T08:40:48.100Z'], dlm: byTime['2026-11-08T08:40:38.100Z'],
                 reb: byTime['2026-11-08T08:41:38.100Z'], rec: byTime['2026-11-08T08:41:38.200Z']])
        String crit = HostCheck.CRITICAL_GREP.toLowerCase(), warn = HostCheck.WARNING_GREP.toLowerCase()
        assertTrue((lines[3].toLowerCase() =~ crit).find())
        assertTrue('1/2 goes through the critical ring (1.16.0), the rule stays WARNING', (lines[2].toLowerCase() =~ crit).find())
    }

    @Test
    void m2AllPathsDownOpensOnTheFirstCycle() {
        List<Finding> found = []
        def add = { String k, String sev, String title, String detail, Map o = [:] ->
            found << new Finding(key: "hc:3:${k}", severity: sev, clusterId: 1L, title: title, detail: detail, source: 'host',
                    confirmCycles: (o.confirm ?: 2) as int)
        }
        HostCheck.parseMultipath(['360a |sdb|failed|faulty', '360a |sdc|failed|faulty', 'T|360a|2',
                                  '360b |sdd|failed|faulty', '360b |sde|active|ready', 'T|360b|2'], add)
        AlertState st = new AlertState()
        st.update(found, 0L)
        assertTrue('all paths down: first cycle', st.active.containsKey('hc:3:mpath:360a'))
        assertFalse('one of two paths: still two cycles', st.active.containsKey('hc:3:mpath:360b'))
    }

    @Test
    void s1CarriedHostKeepsThePendingCount() {
        Finding f = new Finding(key: 'hc:4:gfs2-mounts', severity: Finding.CRITICAL, clusterId: 1L, title: 't', source: 'host', confirmCycles: 2)
        AlertState st = new AlertState()
        st.update([f], 0L)
        st.update([], 120000L, ['hc:4:'] as Set)          // host 4 could not be read
        assertFalse(st.active.containsKey(f.key))
        st.update([f], 240000L)
        assertTrue('seen twice around a carried cycle', st.active.containsKey(f.key))
        AlertState plain = new AlertState()
        plain.update([f], 0L); plain.update([], 120000L); plain.update([f], 240000L)
        assertFalse('without a carry the count resets', plain.active.containsKey(f.key))
    }

    static AlertCycle.Work work(long hostId, String name, int count) {
        new AlertCycle.Work(cluster: new ComputeServerGroup(id: 5L, name: 'C'), rule: new AlertSettings.ClusterRule(clusterId: 5L),
                apdCandidates: [[host: new ComputeServer(id: hostId, name: name), count: count, first: 1000000L, last: 1100000L, stretch: false, preStart: false]])
    }

    static Finding ev(long hostId, String rule, long t) {
        new Finding(key: "event:${hostId}:${t}", severity: Finding.CRITICAL, clusterId: 5L, cluster: 'C', resource: "h${hostId}",
                source: 'event', since: new Date(t), meta: [rule: rule])
    }

    static List<Finding> apd(AlertCycle.Outcome o) { o.events.findAll { it.meta.rule == 'apd-derived' } }

    @Test
    void s2ApdSuppressedOnlyByEvidenceForThisHost() {
        // (a) T3 replay: survivors report the loss of host 3 itself; host 3's run is still reported
        AlertCycle.Outcome a = new AlertCycle.Outcome(events: [ev(1, 'peer-unreachable', 1050000L), ev(1, 'fence-ack', 1060000L)])
        new AlertCycle(state: new AlertState()).evaluateApd(work(3, 'vmenode03', 9), a, 1200000L)
        assertEquals(Finding.WARNING, apd(a)[0]?.severity)
        // (b) a survivor's own peer finding suppresses its run
        AlertCycle.Outcome b = new AlertCycle.Outcome(findings: [new Finding(key: 'hc:1:peer:vmenode03-3', severity: Finding.CRITICAL, clusterId: 5L, source: 'host')])
        new AlertCycle(state: new AlertState()).evaluateApd(work(1, 'vmenode01', 6), b, 1050000L)
        assertEquals([], apd(b))
        // (c) B3: a healthy survivor while the peer lost its storage
        AlertCycle.Outcome c = new AlertCycle.Outcome(findings: [
                new Finding(key: 'hc:3:gfs2:cls:ds01', severity: Finding.CRITICAL, clusterId: 5L, resource: 'vmenode03', source: 'host'),
                new Finding(key: 'hc:3:mpath:360a', severity: Finding.CRITICAL, clusterId: 5L, resource: 'vmenode03', source: 'host')])
        new AlertCycle(state: new AlertState()).evaluateApd(work(1, 'vmenode01', 6), c, 1050000L)
        assertEquals([], apd(c))
        // (d) fabric-wide: the candidate has its own paths down
        AlertCycle.Outcome d = new AlertCycle.Outcome(findings: [
                new Finding(key: 'hc:1:mpath:360a', severity: Finding.CRITICAL, clusterId: 5L, resource: 'vmenode01', source: 'host'),
                new Finding(key: 'hc:3:mpath:360a', severity: Finding.CRITICAL, clusterId: 5L, resource: 'vmenode03', source: 'host')])
        new AlertCycle(state: new AlertState()).evaluateApd(work(1, 'vmenode01', 6), d, 1050000L)
        assertEquals(Finding.CRITICAL, apd(d)[0]?.severity)
    }

    static final String QLINE = '2026-11-04T18:59:30.000Z [t] [] INFO  x - handleAction results: {data={quorumAchieved=true, fenced=false, ' +
            'totalNodes=3, reachableNodes=2, nodes=[{hostname=h1}], waitFencing=false, downHosts=[h3]}}'

    static Set<String> keys(String quorumApi) {
        String out = "@@ID 0 2026-11-04T19:00:00Z\n@@PRIV root\n@@GFS2MOUNTS 1\n@@QUORUM_API\n${quorumApi}\n@@LOG 5 100\n@@LOGDATA\nQ ${QLINE}\n@@END\n"
        HostCheck.parse(out, [cluster: 'CLS', clusterId: 9L, hostId: 1L, hostName: 'h1']).findings*.key as Set
    }

    @Test
    void s3MapCountsDroppedWithANodeList() {
        String api = '[{"hostname":"h1","me":true},{"hostname":"h3","lastReachedDate":"2026-11-04T18:55:00Z"}]'
        assertEquals(['hc:1:peer:h3'] as Set, keys(api))
        assertEquals('empty node list: map counts as before', ['hc:1:reachable', 'hc:1:down-hosts'] as Set, keys('[]'))
    }

    @Test
    void s4AndS5StoredValues() {
        String html = SettingsPage.render([clusterId: 1L, clusterName: 'c', url: '/u',
                own: [version: 1, alertEnabled: true, recipients: ['a@x.com'], agentWarnMinutes: 2, agentCriticalMinutes: 5,
                      nodeUnreachableSeconds: 10, fencingWaitSeconds: 30]])
        assertTrue(html.contains('name="nodeUnreachableSeconds" min="60" max="3600" required value="60"'))
        assertNull(TabSettings.parse('{"version":1,"recipients":["not an address"]}'))
        assertNotNull(TabSettings.parse('{"version":1,"recipients":["ops@x.com"]}'))
    }
}
