package com.hpe.hvmalert

import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.TaskResult
import org.junit.Test

import java.util.concurrent.TimeoutException

import static org.junit.Assert.*

/**
 * 1.24.0: fixes from the pre-production failure tests on the VME lab (VME 9.0.2, layout 1.3, agent 3.3.0, 2026-11-10/11).
 * Agent-log lines are the real ones with the host names replaced (node1, node2, node3).
 */
class PreProductionCampaignTest {

    static final String Q = '[pool-1-thread-1] [] '

    // T3 (node3 cut from node1/node2, manager link kept): node3's own lines before its self-fence
    static final List<String> T3_NODE3 = [
        "2026-11-11T06:19:46.500Z ${Q}WARN  c.m.a.services.QuorumCheckService - Quorum Not Achieved: 1 of 3 nodes reachable (need 2)",
        "2026-11-11T06:19:46.501Z ${Q}WARN  c.m.a.services.QuorumCheckService - Quorum loss confirmation 1/2 - deferring self-fence one cycle to let a boundary-stale peer age out and witness credit re-evaluate",
        "2026-11-11T06:20:46.500Z ${Q}INFO  c.m.a.services.QuorumCheckService - Fencing myself from cluster due to loss of quorum (2 consecutive cycles)",
        "2026-11-11T06:20:46.501Z ${Q}WARN  c.m.a.services.QuorumCheckService - Self-fencing via immediate reboot",
    ].collect { it.toString() }
    // T5 (corosync stopped on node3): node1's split-brain guard line
    static final String T5_REFUSED = "2026-11-10T13:57:00.708Z ${Q}WARN  c.m.a.services.QuorumCheckService - DLM fence_wait: NOT acking reachable node node3-3 (dlmNodeId=3) - no confirmed reboot since partition; node may still hold/write locks (split-brain guard)".toString()
    // lines that must stay quiet: every boot logs the first, the second follows the self-fence decision
    static final List<String> QUIET = [
        "2026-11-11T06:24:50.940Z ${Q}INFO  c.m.a.s.FilesystemMountService - DLM not quorate and agent quorum not achieved - not ready",
        "2026-11-11T06:20:54.996Z ${Q}INFO  c.m.a.services.QuorumCheckService - Rebooting",
        "2026-11-10T13:57:00.708Z ${Q}INFO  c.m.a.services.QuorumCheckService - Phase-2 reboot check for node3-3: boot_id pre-partition=77bb, now=77bb -> rebootedSincePartition=false",
    ].collect { it.toString() }

    static Finding event(String rule) {
        new Finding(key: 'event:3:2026-11-11T06:19:46.500Z', severity: Finding.CRITICAL, clusterId: 1L, source: 'event', meta: [rule: rule])
    }

    @Test
    void agent330QuorumAndFencePhrasesGetTheirRules() {
        List<Map> e = HostCheck.parseEvents(T3_NODE3 + [T5_REFUSED] + QUIET)
        assertEquals(['fence-refused', 'quorum-lost', 'quorum-lost', 'self-fence', 'self-fence'], e*.rule)
        assertTrue(e.every { it.severity == Finding.CRITICAL })
        assertTrue(e.find { it.rule == 'self-fence' }.title.contains('quorum lost'))
        // each maps to a row, so tab levels apply
        assertEquals('self-reboot', Checks.idFor(event('self-fence')))
        assertEquals('quorum-lost', Checks.idFor(event('quorum-lost')))
        assertEquals('fence-refused', Checks.idFor(event('fence-refused')))
        assertEquals(Checks.C, Checks.ALL.find { it.id == 'fence-refused' }.level)
    }

    /** The shell pre-filter keeps the new lines (critical ring) and drops the quiet ones. */
    @Test
    void newPhrasesPassTheShellPreFilter() {
        HostCheck.Result r = HostCheckTest.runScript((T3_NODE3 + [T5_REFUSED] + QUIET).join('\n') + '\n')
        assertEquals(['fence-refused', 'quorum-lost', 'quorum-lost', 'self-fence', 'self-fence'], r.events*.rule)
        String crit = HostCheck.CRITICAL_GREP.toLowerCase()
        (T3_NODE3 + [T5_REFUSED]).each { assertTrue(it, (it.toLowerCase() =~ crit).find()) }
        QUIET.each { assertFalse(it, (it.toLowerCase() =~ crit).find() || (it.toLowerCase() =~ HostCheck.WARNING_GREP).find()) }
    }

    static String out(String now, String quorumLine, String api = '[]') {
        "@@ID 0 ${now}\n@@PRIV root\n@@GFS2MOUNTS 6\n@@QUORUM_API\n${api}\n@@LOG 5 100\n@@LOGDATA\nQ ${quorumLine}\n@@END\n".toString()
    }

    /** T5: corosync and dlm stopped; node3 was read once at 13:56:5x and rebooted itself at 13:57:41. */
    @Test
    void stoppedCorosyncAndDlmOpenOnTheFirstCheck() {
        String line = "2026-11-10T13:56:22.127Z ${Q}INFO  com.morpheus.agent.ActionHandler - handleAction results: {data={quorumAchieved=true, fenced=false, " +
                'corosyncActive=false, dlmActive=false, corosyncQuorate=false, totalNodes=3, reachableNodes=3, nodes=[{hostname=node3-3, addresses=[10.0.0.3], ' +
                'me=true, lastReachedDate=1794318976309, reachable=true, down=false, dlm={nodeId=3}}, {hostname=node1, addresses=[10.0.0.1], me=false, ' +
                'lastReachedDate=1794318976309, reachable=true, down=false, dlm={nodeId=1, member=false, needFencing=false}}]}}'
        HostCheck.Result r = HostCheck.parse(out('2026-11-10T13:56:52Z', line), [cluster: 'C', clusterId: 1L, hostId: 3L, hostName: 'node3'])
        Map<String, Finding> f = r.findings.collectEntries { [(it.key): it] }
        assertEquals(['hc:3:corosync-active', 'hc:3:dlm-active'] as Set, f.keySet())
        AlertState st = new AlertState()
        st.update(r.findings, 0L)
        assertEquals('both open on the first check', f.keySet(), st.active.keySet())
        assertEquals('cluster-services', Checks.idFor(f['hc:3:dlm-active']))
    }

    static String isolated(String reach, String total = '3', String extra = '') {
        "2026-11-11T06:20:33.319Z ${Q}INFO  com.morpheus.agent.ActionHandler - handleAction results: {data={quorumAchieved=true, fenced=false, corosyncActive=true, " +
                "dlmActive=true, corosyncQuorate=false, totalNodes=${total}, reachableNodes=${reach}, nodes=[{hostname=node3-3, addresses=[10.0.0.3], me=true, " +
                "reachable=true${extra}, down=false, dlm={nodeId=3, member=true, needFencing=false}}]}}"
    }

    static List<String> keys(String line) {
        List<String> k = []
        HostCheck.parseAgentQuorum(line, HostCheck.parseTime('2026-11-11T06:21:00Z'), 30, { String key, String sev, String t, String d, Map o = [:] -> k << key }, true)
        k
    }

    /** T3: the isolated node3 logged "Quorum Not Achieved: 1 of 3" but its quorum map said quorumAchieved=true. */
    @Test
    void minorityOfNodesIsQuorumLostDespiteQuorumAchieved() {
        String api = '[{"hostname":"node1","lastReachedDate":1794377886491,"me":false,"serverTime":1794377916491},' +
                '{"hostname":"node3-3","lastReachedDate":1794377916491,"me":true,"serverTime":1794377916491}]'
        HostCheck.Result r = HostCheck.parse(out('2026-11-11T06:20:40Z', isolated('1'), api), [cluster: 'C', clusterId: 1L, hostId: 3L, hostName: 'node3'])
        Finding q = r.findings.find { it.key == 'hc:3:quorum' }
        assertNotNull('reported even with a /quorum node list', q)
        assertEquals(1, q.confirmCycles)
        assertTrue(q.detail.contains('only 1 of 3 nodes reachable'))
        assertEquals('a survivor with 2 of 3 keeps quorum', [], keys(isolated('2')))
        assertEquals('4 nodes, 2 reachable: no majority', ['quorum'], keys(isolated('2', '4')))
        assertEquals('two-node cluster: not judged here', [], keys(isolated('1', '2')))
        assertEquals('witness: not judged here', [], keys(isolated('1', '3', ', witnessOnly=true')))
        assertEquals('stretch (sites): not judged here', [], keys(isolated('1', '3', ', site=siteA')))
        assertEquals('site=null is no stretch', ['quorum'], keys(isolated('1', '3', ', site=null')))
    }

    ComputeServerGroup c = new ComputeServerGroup(id: 5L, name: 'C')
    AlertSettings.ClusterRule rule = new AlertSettings.ClusterRule(clusterId: 5L)

    /** T3: a timeout, a skipped cycle (power unknown), then one empty answer after the reboot mailed "could not run". */
    @Test
    void checkFailuresAroundASkippedCycleAreNotConsecutive() {
        AlertState st = new AlertState()
        AlertCycle cycle = new AlertCycle(state: st)
        ComputeServer h = new ComputeServer(id: 3L, name: 'node3', powerState: ComputeServer.PowerState.on, lastAgentUpdate: new Date(0L))
        def run = { Object result, long now ->
            AlertCycle.Outcome o = new AlertCycle.Outcome()
            cycle.hostResult(new AlertCycle.Work(cluster: c, rule: rule), h, result, o)
            st.update(o.findings, now, o.carry)
        }
        run(new TimeoutException(), 0L)
        st.update([], 125000L, ['hc:3:'] as Set)                     // host not asked (power unknown)
        run(new TaskResult(success: true), 260000L)                    // answered, no output
        assertFalse(st.active.containsKey('hc:3:exec'))
        run(new TaskResult(success: true), 380000L)                    // the next consecutive failure confirms it
        assertTrue(st.active.containsKey('hc:3:exec'))
        // other host findings still keep their count across a carried cycle (1.15.0)
        Finding mounts = new Finding(key: 'hc:3:gfs2-mounts', severity: Finding.WARNING, clusterId: 5L, title: 't', source: 'host')
        AlertState s2 = new AlertState()
        s2.update([mounts], 0L); s2.update([], 120000L, ['hc:3:'] as Set); s2.update([mounts], 240000L)
        assertTrue(s2.active.containsKey(mounts.key))
    }

    @Test
    void heartbeatTitleNoLongerCallsTheFailuresExpected() {
        String title = HostCheck.EVENT_RULES.find { it.id == 'hb-all' }.title
        assertFalse(title.contains('expected'))
        assertTrue(title.contains('shut down the non-pinned VMs'))
    }

    /** T1: power unknown while the host ran and its peers reached it; the detail says the manager may have lost the agent. */
    @Test
    void unknownPowerDetailNamesTheAgentCase() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        cycle.managerHostChecks(c, new ComputeServer(id: 3L, name: 'node3', powerState: ComputeServer.PowerState.unknown), rule, 0L, o)
        assertTrue(o.findings[0].detail.contains('no agent updates'))
        AlertCycle.Outcome off = new AlertCycle.Outcome()
        cycle.managerHostChecks(c, new ComputeServer(id: 3L, name: 'node3', powerState: ComputeServer.PowerState.off), rule, 0L, off)
        assertEquals('HPE: off or unknown requires investigation.', off.findings[0].detail)
    }

    /** T3 mail 5 said "15 events" over 4 rows. */
    @Test
    void subjectCountsEventRows() {
        List<Finding> ev = (0..4).collect { i ->
            new Finding(key: "event:3:t${i}", severity: Finding.WARNING, clusterId: 1L, cluster: 'C', resource: 'node3', title: 'hb',
                    source: 'event', since: new Date(1000L * i), meta: [rule: 'hb-all'])
        } + [new Finding(key: 'event:3:x', severity: Finding.CRITICAL, clusterId: 1L, cluster: 'C', resource: 'node3', title: 'shut',
                source: 'event', since: new Date(9000L), meta: [rule: 'apd-shutdown'])]
        Map m = MailComposer.change(new AlertState.Diff(events: ev), 'mgr')
        assertTrue(m.subject as String, (m.subject as String).endsWith('C: 2 events'))
        assertEquals(2, (m.rows as List).size())
    }
}
