package com.hpe.hvmalert

import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import org.junit.Test

import static org.junit.Assert.*

/**
 * 1.25.0, from the pre-production campaign (VME lab, 2026-11-10/11): D1 GFS2 blocked cluster-wide, D2 fast re-check while
 * fencing, D3 DLM wait aged from the agent's line, D5 VMs the APD shutdown powered off, D6 storage rows folded per host,
 * D7 distinct lines in aggregated rows, D8 a fenced host is critical. Host names replaced (node1..node3).
 */
class ReleaseOneTwentyFiveTest {

    static final String Q = '[pool-1-thread-1] [] '
    static final long T = HostCheck.parseTime('2026-11-10T13:56:52.000Z')

    /** node1's quorum line of T5 at 13:56:22: node3 left DLM (procdown), still reachable, needs fencing. */
    static String quorum(String time, boolean node3Reachable, boolean needFencing = true) {
        ("${time} ${Q}INFO  com.morpheus.agent.ActionHandler - handleAction results: {data={quorumAchieved=true, fenced=false, " +
                'corosyncActive=true, dlmActive=true, corosyncQuorate=true, totalNodes=3, reachableNodes=3, nodes=[' +
                "{hostname=node3-3, addresses=[10.0.0.3], me=false, lastReachedDate=1794318980689, reachable=${node3Reachable}, down=false, " +
                "dlm={nodeId=3, member=false, needFencing=${needFencing}, leftReason=procdown}}, " +
                '{hostname=node1-1, addresses=[10.0.0.1], me=true, lastReachedDate=1794318980689, reachable=true, down=false, ' +
                'dlm={nodeId=1, member=true, needFencing=false}}, ' +
                '{hostname=node2-2, addresses=[10.0.0.2], me=false, lastReachedDate=1794318980689, reachable=true, down=false, ' +
                'dlm={nodeId=2, member=true, needFencing=false}}]}}').toString()
    }

    static Finding ev(String host, String rule, long at, String detail = 'line', boolean pre = false) {
        new Finding(key: "event:${host}:${at}", severity: Finding.WARNING, clusterId: 1L, cluster: 'C', resource: host, title: rule,
                detail: detail, source: 'event', since: new Date(at), preStart: pre, meta: [rule: rule])
    }

    static AlertCycle.Work work(Map<String, List<String>> fenceReachable = [:], List<Map> maintenance = []) {
        new AlertCycle.Work(cluster: new ComputeServerGroup(id: 1L, name: 'C'), fenceReachable: fenceReachable, maintenanceHosts: maintenance)
    }

    // D1

    @Test
    void reachableNodesAwaitingAFenceAreReadFromTheQuorumLine() {
        assertEquals(['node3-3'], HostCheck.fenceWaitReachable(quorum('2026-11-10T13:56:22.171Z', true)))
        assertEquals('a lost node awaiting its fence is the normal case', [], HostCheck.fenceWaitReachable(quorum('2026-11-10T13:56:22.171Z', false)))
        assertEquals([], HostCheck.fenceWaitReachable(quorum('2026-11-10T13:54:11.038Z', true, false)))
        assertEquals([], HostCheck.fenceWaitReachable(null))
    }

    @Test
    void gfs2BlockedWhenAReachableNodeAwaitsAFenceAndOtherHostsFailTheirHeartbeat() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        AlertCycle.Outcome o = new AlertCycle.Outcome(events: [ev('node1', 'hb-all', T - 2000), ev('node2', 'hb-all', T - 1000)])
        cycle.gfs2Blocked(work([node1: ['node3-3'], node2: ['node3-3']]), o, T)
        Finding f = o.findings.find { it.key == 'cluster:1:gfs2-blocked' }
        assertNotNull(f)
        assertEquals(Finding.CRITICAL, f.severity)
        assertEquals(1, f.confirmCycles)
        assertTrue(f.detail, f.detail.contains('node3') && f.detail.contains('node1, node2') && f.detail.contains('non-pinned VMs'))
        assertEquals('gfs2-blocked', Checks.idFor(f))
    }

    @Test
    void gfs2BlockedStaysQuietInANormalHostLossOrForTheNodeItself() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        AlertCycle.Outcome none = new AlertCycle.Outcome(events: [ev('node1', 'hb-all', T)])
        cycle.gfs2Blocked(work([:]), none, T)                                  // lost node not reachable: no fence-wait node
        assertFalse(none.findings.any { it.key.endsWith('gfs2-blocked') })
        AlertCycle.Outcome own = new AlertCycle.Outcome(events: [ev('node3', 'hb-all', T)])
        cycle.gfs2Blocked(work([node1: ['node3-3']]), own, T)                  // only the node itself fails its heartbeat
        assertFalse(own.findings.any { it.key.endsWith('gfs2-blocked') })
        AlertCycle.Outcome maint = new AlertCycle.Outcome(events: [ev('node1', 'hb-all', T)])
        cycle.gfs2Blocked(work([node1: ['node3-3']], [[id: 4L, names: ['node3'] as Set]]), maint, T)
        assertFalse('a host in maintenance left its lockspaces', maint.findings.any { it.key.endsWith('gfs2-blocked') })
        AlertCycle.Outcome old = new AlertCycle.Outcome(events: [ev('node1', 'hb-all', T - 6 * 60000L)])
        cycle.gfs2Blocked(work([node1: ['node3-3']]), old, T)
        assertFalse(old.findings.any { it.key.endsWith('gfs2-blocked') })
    }

    @Test
    void aRefusedFenceAloneIsEnough() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        AlertCycle.Outcome o = new AlertCycle.Outcome(events: [ev('node1', 'fence-refused', T,
                'WARN  c.m.a.services.QuorumCheckService - DLM fence_wait: NOT acking reachable node node3-3 (dlmNodeId=3) - no confirmed reboot since partition')])
        cycle.gfs2Blocked(work(), o, T)
        assertTrue(o.findings.find { it.key == 'cluster:1:gfs2-blocked' }.detail.contains('node3'))
    }

    // D2

    @Test
    void fencingRiskDrivesTheFastRecheck() {
        assertTrue(AlertCycle.fencingRisk(new AlertCycle.Outcome(findings: [new Finding(key: 'cluster:1:gfs2-blocked')]), T))
        assertTrue(AlertCycle.fencingRisk(new AlertCycle.Outcome(findings: [new Finding(key: 'hc:3:dlm-active')]), T))
        assertTrue(AlertCycle.fencingRisk(new AlertCycle.Outcome(events: [ev('node1', 'hb-all', T - 60000L)]), T))
        assertFalse(AlertCycle.fencingRisk(new AlertCycle.Outcome(events: [ev('node1', 'hb-all', T - 4 * 60000L)]), T))
        assertFalse('re-read after a reload', AlertCycle.fencingRisk(new AlertCycle.Outcome(events: [ev('node1', 'hb-all', T, 'l', true)]), T))
        assertFalse(AlertCycle.fencingRisk(new AlertCycle.Outcome(findings: [new Finding(key: 'hc:3:mpath:x')], events: [ev('n', 'hb-path', T)]), T))
        assertEquals(6, AlertScheduler.MAX_FAST_CYCLES)
    }

    // D3

    @Test
    void aReachableNodeAwaitingItsFenceIsTimedFromTheAgentsLine() {
        List<Finding> found = []
        def add = { String k, String sev, String title, String detail, Map o = [:] ->
            found << new Finding(key: "hc:1:${k}", severity: sev, clusterId: 1L, title: title, detail: detail, source: 'host',
                    holdSeconds: (o.hold ?: 0) as int, confirmCycles: (o.confirm ?: 2) as int, meta: (o.meta ?: [:]) as Map)
        }
        HostCheck.parseAgentQuorum(quorum('2026-11-10T13:56:22.171Z', true), T, 30, add, true, true)
        Finding f = found.find { it.key == 'hc:1:dlm-wait' }
        assertEquals(HostCheck.parseTime('2026-11-10T13:56:22.171Z'), f.meta.observedMs)
        assertTrue(f.title.contains('still reachable'))
        AlertState st = new AlertState()
        st.update([f], T + 1000L)
        assertTrue('30 s after the line: opens on the first read', st.active.containsKey('hc:1:dlm-wait'))

        found.clear()
        HostCheck.parseAgentQuorum(quorum('2026-11-10T13:56:22.171Z', false), T, 30, add, true, true)
        Finding lost = found.find { it.key == 'hc:1:dlm-wait' }
        assertNull('a lost node keeps the old timing', lost.meta.observedMs)
        AlertState st2 = new AlertState()
        st2.update([lost], T)
        assertFalse(st2.active.containsKey('hc:1:dlm-wait'))
        found.clear()
        HostCheck.parseAgentQuorum(quorum('2026-11-10T13:56:22.171Z', true), T, 30, add, true, false)
        assertNull('an older line read again keeps the old timing', found.find { it.key == 'hc:1:dlm-wait' }.meta.observedMs)
    }

    // D5

    static final List<String> APD = [
        "2026-11-10T13:58:40.884Z ${Q}ERROR c.m.agent.stats.MvmHeartbeatFailover - All heartbeat datastore paths have been unhealthy for host node1 for 6 checks (budget 6). Shutting down all VMs to protect data integrity.",
        "2026-11-10T13:58:40.992Z ${Q}INFO  c.m.agent.stats.MvmHeartbeatFailover - Successfully powered off VM 81e638ad-50fe-4290-bd87-b3f6f6d1c0ed",
        "2026-11-10T13:58:40.997Z ${Q}INFO  c.m.agent.stats.MvmHeartbeatFailover - Successfully powered off VM 0baf21ce-6033-4212-b704-8b45d6c75ca6",
        "2026-11-10T13:58:41.000Z ${Q}INFO  c.m.agent.stats.MvmHeartbeatFailover - Successfully powered off VM 7d84c0c4-da07-4326-94bb-12ebdbbb3454",
        "2026-11-11T06:20:39.908Z ${Q}ERROR c.m.agent.stats.MvmHeartbeatFailover - Error destroying VM c5af53b8-8b38-4359-831f-5f3e1b173b9b - Exit Code: 123",
        "2026-11-10T13:58:41.000Z ${Q}INFO  c.m.a.SomeOtherService - Successfully powered off VM 11111111-0000-0000-0000-000000000000",
    ].collect { it.toString() }

    @Test
    void theApdShutdownCountsItsPoweredOffVmsAndAFailedOne() {
        HostCheck.Result r = HostCheckTest.runScript(APD.join('\n') + '\n')
        assertEquals('only the APD class counts', 3, r.vmOffTimes.size())
        assertEquals(['apd-shutdown', 'apd-vm-error'], r.events*.rule)
        assertEquals('apd-agent', Checks.idFor(ev('node1', 'apd-vm-error', T)))

        AlertCycle cycle = new AlertCycle(state: new AlertState())
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        ComputeServer h = new ComputeServer(id: 1L, name: 'node1')
        long hostNow = HostCheck.parseTime('2026-11-10T13:59:00.000Z')
        cycle.vmOffEvent(new ComputeServerGroup(id: 1L, name: 'C'), h, r, hostNow, false, o)
        assertEquals(1, o.events.size())
        assertTrue(o.events[0].title, o.events[0].title.contains('powered off 3 VM(s)'))
        assertEquals(Finding.CRITICAL, o.events[0].severity)
        assertEquals('apd-agent', Checks.idFor(o.events[0]))
        cycle.vmOffEvent(new ComputeServerGroup(id: 1L, name: 'C'), h, r, hostNow, false, o)
        assertEquals('the same lines are counted once', 1, o.events.size())
    }

    // D6

    static Finding item(String key, String sev, String title, String res = 'node3') {
        new Finding(key: key, severity: sev, clusterId: 1L, cluster: 'C', resource: res, title: title, source: 'host', since: new Date(T))
    }

    @Test
    void storageRowsFoldIntoOneRowPerHostAndDatastoresPerCluster() {
        AlertState.Diff d = new AlertState.Diff()
        (1..13).each { d.opened << item("hc:4:mpath:wwid${it}", Finding.CRITICAL, "All 2 paths down on wwid${it}") }
        (1..6).each { d.opened << item("hc:4:gfs2:fs${it}", Finding.CRITICAL, "GFS2 filesystem withdrawn: fs${it}") }
        (1..5).each { d.opened << item("ds:${it}:state", Finding.CRITICAL, 'Datastore offline', "ds0${it}") }
        d.opened << item('hc:2:mpath:other', Finding.WARNING, '1 of 2 paths failed on other', 'node1')
        Map m = MailComposer.change(d, 'mgr')
        List<Finding> rows = m.rows.collect { it[1] as Finding }
        assertEquals(3, rows.size())
        assertEquals('Storage: 13 map(s) with all paths down, 6 GFS2 filesystem(s) withdrawn', rows[0].title)
        assertTrue(rows[0].detail.contains('(+7 more)'))
        assertEquals('5 datastores: Datastore offline', rows[1].title)
        assertEquals('fewer than 3 stay as they are', '1 of 2 paths failed on other', rows[2].title)
        assertEquals('[HVM Cluster Alert] CRITICAL C: 3 new', m.subject)
    }

    // D7

    @Test
    void aggregatedRowsKeepTheDistinctLines() {
        List<Finding> agg = MailComposer.aggregate([
                ev('node1', 'fence-refused', T, 'NOT acking reachable node node3-3 (dlmNodeId=3)'),
                ev('node1', 'fence-refused', T + 1, 'NOT acking reachable node node2-2 (dlmNodeId=2)'),
                ev('node1', 'fence-refused', T + 2, 'NOT acking reachable node node3-3 (dlmNodeId=3)')])
        assertEquals(1, agg.size())
        assertTrue(agg[0].detail, agg[0].detail.contains('node3-3') && agg[0].detail.contains('node2-2'))
    }

    // D8

    @Test
    void aHostTheManagerReportsFencedIsCritical() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        ComputeServer h = new ComputeServer(id: 4L, name: 'node3', powerState: ComputeServer.PowerState.on, status: 'warning',
                statusMessage: 'Host has been fenced (nodedown)', lastAgentUpdate: new Date())
        cycle.managerHostChecks(new ComputeServerGroup(id: 1L, name: 'C'), h, new AlertSettings.ClusterRule(clusterId: 1L), System.currentTimeMillis(), o)
        assertEquals(Finding.CRITICAL, o.findings.find { it.key == 'host:4:status' }.severity)
        AlertCycle.Outcome w = new AlertCycle.Outcome()
        h.statusMessage = 'Error loading hypervisor storage pools: Timed out'
        cycle.managerHostChecks(new ComputeServerGroup(id: 1L, name: 'C'), h, new AlertSettings.ClusterRule(clusterId: 1L), System.currentTimeMillis(), w)
        assertEquals(Finding.WARNING, w.findings.find { it.key == 'host:4:status' }.severity)
    }

    // review fixes

    @Test
    void theRowHoldsBetweenChecksAndEndsOnAFreshClearLine() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        AlertCycle.Outcome o1 = new AlertCycle.Outcome(events: [ev('node1', 'hb-all', T)])
        cycle.gfs2Blocked(work([node1: ['node3-3']]), o1, T)
        assertTrue(o1.findings.any { it.key == 'cluster:1:gfs2-blocked' })
        AlertCycle.Outcome o2 = new AlertCycle.Outcome()                       // 30 s later, no new line: stays open
        cycle.gfs2Blocked(work(), o2, T + 30000L)
        assertTrue(o2.findings.any { it.key == 'cluster:1:gfs2-blocked' })
        AlertCycle.Outcome o3 = new AlertCycle.Outcome()                       // a fresh quorum line without such a node
        AlertCycle.Work clear = work()
        clear.freshQuorum << 'node1'
        cycle.gfs2Blocked(clear, o3, T + 60000L)
        assertFalse(o3.findings.any { it.key == 'cluster:1:gfs2-blocked' })
        AlertCycle.Outcome o4 = new AlertCycle.Outcome(events: [ev('node1', 'hb-all', T + 70000L)])
        cycle.gfs2Blocked(work([node1: ['node3-3']]), o4, T + 70000L)
        AlertCycle.Outcome o5 = new AlertCycle.Outcome()
        cycle.gfs2Blocked(work(), o5, T + 70000L + AlertCycle.BLOCKED_HOLD_MS + 1)
        assertFalse('the hold time ends it', o5.findings.any { it.key == 'cluster:1:gfs2-blocked' })
    }

    @Test
    void reReadLinesAndAnOrdinaryHostLossDoNotRaiseIt() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        AlertCycle.Outcome reload = new AlertCycle.Outcome(events: [ev('node1', 'fence-refused', T,
                'NOT acking reachable node node3-3 (dlmNodeId=3)', true)])
        cycle.gfs2Blocked(work(), reload, T)
        assertFalse(reload.findings.any { it.key.endsWith('gfs2-blocked') })
        AlertCycle.Outcome loss = new AlertCycle.Outcome(events: [ev('node1', 'hb-all', T)],
                findings: [new Finding(key: 'hc:1:peer:node3-3', clusterId: 1L, severity: Finding.CRITICAL)])
        cycle.gfs2Blocked(work([node1: ['node3-3']]), loss, T)
        assertFalse('the lost node is fenced normally', loss.findings.any { it.key.endsWith('gfs2-blocked') })
        AlertCycle.Outcome loss2 = new AlertCycle.Outcome(events: [ev('node1', 'hb-all', T),
                ev('node1', 'peer-unreachable', T, 'WARN c.m.a.services.QuorumCheckService - Host node3-3 is unreachable, taking action')])
        cycle.gfs2Blocked(work([node1: ['node3-3']]), loss2, T)
        assertFalse(loss2.findings.any { it.key.endsWith('gfs2-blocked') })
    }

    @Test
    void mutualRefusalsNameTheNodeRefusedByBoth() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        String n = 'NOT acking reachable node '
        AlertCycle.Outcome o = new AlertCycle.Outcome(events: [
                ev('node1', 'fence-refused', T, n + 'node3-3 (dlmNodeId=3)'), ev('node1', 'fence-refused', T + 1, n + 'node2-2 (dlmNodeId=2)'),
                ev('node2', 'fence-refused', T + 2, n + 'node3-3 (dlmNodeId=3)'), ev('node2', 'fence-refused', T + 3, n + 'node1-1 (dlmNodeId=1)')])
        cycle.gfs2Blocked(work(), o, T)
        String d = o.findings.find { it.key == 'cluster:1:gfs2-blocked' }.detail
        assertTrue(d, d.startsWith('Node(s) node3-3 left DLM'))
    }

    @Test
    void aRemoteDestroyByASurvivorIsNotAnApdError() {
        String remote = "2026-11-11T06:20:54.883Z ${Q}ERROR c.m.agent.stats.MvmHeartbeatFailover - Error destroying VM c5af53b8-8b38-4359-831f-5f3e1b173b9b on remote host 10.0.0.3 - Exit Code: 255 ssh: connect to host 10.0.0.3 port 22: No route to host".toString()
        assertEquals([], HostCheck.parseEvents([remote])*.rule)
        assertFalse((remote.toLowerCase() =~ HostCheck.CRITICAL_GREP.toLowerCase()).find())
        assertEquals(['apd-vm-error'], HostCheck.parseEvents([APD[4]])*.rule)
    }

    @Test
    void anOldOpenFindingNoLongerDrivesFastChecksAndUnfencedIsNotFenced() {
        AlertCycle.Outcome o = new AlertCycle.Outcome(findings: [new Finding(key: 'hc:3:corosync-active')])
        assertFalse(AlertCycle.fencingRisk(o, T, ['hc:3:corosync-active': T - 11 * 60000L]))
        assertTrue(AlertCycle.fencingRisk(o, T, ['hc:3:corosync-active': T - 60000L]))
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        AlertCycle.Outcome w = new AlertCycle.Outcome()
        ComputeServer h = new ComputeServer(id: 4L, name: 'node3', powerState: ComputeServer.PowerState.on, status: 'warning',
                statusMessage: 'Host unfenced, rejoining', lastAgentUpdate: new Date())
        cycle.managerHostChecks(new ComputeServerGroup(id: 1L, name: 'C'), h, new AlertSettings.ClusterRule(clusterId: 1L), System.currentTimeMillis(), w)
        assertEquals(Finding.WARNING, w.findings.find { it.key == 'host:4:status' }.severity)
    }
}
