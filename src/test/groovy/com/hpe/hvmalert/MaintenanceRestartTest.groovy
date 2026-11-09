package com.hpe.hvmalert

import com.morpheusdata.model.ComputeServerGroup
import org.junit.Test

import static org.junit.Assert.*

/**
 * 1.21.0: a host restarted in maintenance gives one WARNING, not the survivors' peer-loss events. Lines from a field
 * restart of a host in maintenance (agent 4.0.1, layout 2.0, 2026-11-09).
 */
class MaintenanceRestartTest {

    static final long CID = 5L
    long now = System.currentTimeMillis()

    AlertCycle.Work work(Map maint) {
        new AlertCycle.Work(cluster: new ComputeServerGroup(id: CID, name: 'C'), rule: new AlertSettings.ClusterRule(clusterId: CID),
                maintenanceHosts: maint ? [maint] : [], otherHostNames: ['node1', 'node3'] as Set)
    }

    static Map maint2(boolean off = false) {
        [id: 2L, name: 'node2', names: ['node2'] as Set, ips: ['10.0.0.2'] as Set, off: off, power: off ? 'off' : 'on', silentMin: 3L, full: true]
    }

    Finding ev(long hostId, String rule, String line, long t) {
        new Finding(key: "event:${hostId}:${t}", severity: Finding.WARNING, clusterId: CID, cluster: 'C', resource: "node${hostId}",
                title: rule, detail: line, source: 'event', since: new Date(t), meta: [rule: rule])
    }

    Finding peer(long reporter, String name) {
        new Finding(key: "hc:${reporter}:peer:${name}", severity: Finding.CRITICAL, clusterId: CID, cluster: 'C', resource: "node${reporter}",
                title: "Peer ${name} not reached for 133 s", source: 'host', meta: [peer: name, addresses: []])
    }

    List<Finding> restartEvents() {
        [ev(1, 'peer-unreachable', 'WARN  c.m.a.services.QuorumCheckService - Host node2 is unreachable, taking action', now - 90000),
         ev(1, 'fence-ack', "WARN  c.m.a.services.QuorumCheckService - DLM did not enter 'wait fencing' state within 30s for node node2 (dlmNodeId=2). Attempting fence_ack anyway.", now - 60000),
         ev(3, 'hb-late', 'WARN  c.m.agent.stats.MvmHeartbeatFailover - Hosts 0f1e2d3c4b5a69788796a5b4c3d2e1f0 have failed to update heartbeat in 160000 milliseconds', now - 25000),
         ev(1, 'failover', 'WARN  c.m.agent.stats.MvmHeartbeatFailover - Host node2 is offline, moving vms', now - 10000),
         ev(1, 'hb-path', 'WARN  c.m.agent.stats.MvmHeartbeatFailover - Skipping unhealthy heartbeat datastore path /mnt/125a', now - 5000)]
    }

    AlertCycle cycleWithMaint(AlertState st = new AlertState()) {
        st.maintenanceSeen(2L, CID, ['node2'] as Set, now)
        new AlertCycle(state: st)
    }

    @Test
    void restartInMaintenanceGivesOneWarningAndNoEvents() {
        AlertCycle cycle = cycleWithMaint()
        AlertCycle.Outcome o = new AlertCycle.Outcome(findings: [peer(1, 'node2'), peer(3, 'node2')], events: restartEvents())
        cycle.finish(work(maint2()), [:], o)
        assertEquals(['maint:2:unreachable'], o.findings*.key)
        Finding f = o.findings[0]
        assertEquals(Finding.WARNING, f.severity)
        assertEquals('node2', f.resource)
        assertEquals(1, f.confirmCycles)
        assertTrue(f.detail, f.detail.contains('agent silent for 3 min') && f.detail.contains('not reached by node1, node3'))
        assertEquals([], o.events)
    }

    @Test
    void anotherHostLossKeepsTheFencingWarnings() {
        AlertCycle cycle = cycleWithMaint()
        List<Finding> events = restartEvents()
        AlertCycle.Outcome o = new AlertCycle.Outcome(findings: [peer(1, 'node2'), peer(1, 'node3')], events: events)
        cycle.finish(work(maint2()), [:], o)
        assertEquals(['hc:1:peer:node3', 'maint:2:unreachable'] as Set, o.findings*.key as Set)
        // lines naming node2 still go; the anonymous fencing-window lines stay because node3 is lost too
        assertEquals(['hb-late', 'hb-path'], o.events*.meta*.rule)
    }

    @Test
    void maintenanceHostUpAgainGivesNothing() {
        AlertCycle cycle = cycleWithMaint()
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        Map m = maint2()
        m.silentMin = 0L
        cycle.finish(work(m), [:], o)
        assertEquals([], o.findings)
    }

    @Test
    void powerOffAloneIsEnough() {
        AlertCycle cycle = cycleWithMaint()
        Map m = maint2(true)
        m.silentMin = 0L
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        cycle.finish(work(m), [:], o)
        assertEquals(['maint:2:unreachable'], o.findings*.key)
        assertTrue(o.findings[0].detail.startsWith('power state off'))
    }

    @Test
    void ownLinesFromTheMaintenancePeriodAreDroppedAfterTheExit() {
        AlertState st = new AlertState()
        st.maintenanceSeen(2L, CID, ['node2'] as Set, now - 10 * 60000L)      // left maintenance 10 min ago
        AlertCycle cycle = new AlertCycle(state: st)
        Finding during = ev(2, 'peer-unreachable', 'WARN - Host node1 is unreachable, taking action', now - 11 * 60000L)
        Finding after = ev(2, 'peer-unreachable', 'WARN - Host node1 is unreachable, taking action', now - 60000L)
        AlertCycle.Outcome o = new AlertCycle.Outcome(events: [during, after])
        cycle.finish(work(null), [:], o)
        assertEquals([after], o.events)
    }

    @Test
    void survivorHeartbeatRunDuringTheRestartIsNotAnApd() {
        AlertState st = new AlertState()
        AlertCycle cycle = cycleWithMaint(st)
        st.maintenanceDown(2L, now - 120000L)
        AlertCycle.Work w = work(maint2())
        w.apdCandidates << [host: new com.morpheusdata.model.ComputeServer(id: 1L, name: 'node1'), count: 6,
                            first: now - 150000L, last: now - 30000L, stretch: false, preStart: false]
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        cycle.finish(w, [:], o)
        assertFalse(o.events.any { it.meta.rule == 'apd-derived' })
    }

    @Test
    void namesHostMatchesWholeNamesOnly() {
        Set<String> n = ['node2'] as Set
        assertTrue(AlertCycle.namesHost('Host node2 is offline', n))
        assertTrue(AlertCycle.namesHost('Host node2.lab.local is offline', n))
        assertTrue(AlertCycle.namesHost('for node node2-2 (dlmNodeId=2)', n))
        assertTrue(AlertCycle.namesHost('Host NODE2 is offline', n))
        assertFalse(AlertCycle.namesHost('Host node21 is offline', n))
        assertFalse(AlertCycle.namesHost('Host mynode2 is offline', n))
        assertFalse(AlertCycle.namesHost('Host node2-a is offline', n))
    }

    // ---- round 1 (agents 2 and 3) ----

    @Test
    void longPowerOffInMaintenanceDoesNotHideALaterApdOrHeartbeatWarnings() {
        AlertState st = new AlertState()
        long start = now - 85 * 60000L
        // seen in maintenance and down every 2 min for 85 min
        for (long t = start; t <= now; t += 120000L) { st.maintenanceSeen(2L, CID, ['node2'] as Set, t); st.maintenanceDown(2L, t) }
        AlertCycle cycle = new AlertCycle(state: st)
        AlertCycle.Work w = work(maint2(true))
        w.apdCandidates << [host: new com.morpheusdata.model.ComputeServer(id: 1L, name: 'node1'), count: 7,
                            first: now - 150000L, last: now - 10000L, stretch: false, preStart: false]
        AlertCycle.Outcome o = new AlertCycle.Outcome(events: [ev(1, 'hb-path', 'WARN - Skipping unhealthy heartbeat datastore path /mnt/x', now - 20000)])
        cycle.finish(w, [:], o)
        assertTrue(o.events.any { it.meta.rule == 'apd-derived' })
        assertTrue(o.events.any { it.meta.rule == 'hb-path' })
    }

    @Test
    void linesFromBeforeTheMaintenanceAreKept() {
        AlertState st = new AlertState()
        st.maintenanceSeen(2L, CID, ['node2'] as Set, now - 60000L)          // entered a minute ago
        st.maintenanceSeen(2L, CID, ['node2'] as Set, now)
        AlertCycle cycle = new AlertCycle(state: st)
        Finding before = ev(1, 'failover', 'WARN - Host node2 is offline, moving vms', now - 12 * 60000L)
        Finding own = ev(2, 'apd-shutdown', 'All heartbeat datastore paths have been unhealthy ... Shutting down all VMs', now - 10 * 60000L)
        AlertCycle.Outcome o = new AlertCycle.Outcome(events: [before, own])
        Map m = maint2(); m.silentMin = 0L
        cycle.finish(work(m), [:], o)
        assertEquals([before, own], o.events)
    }

    @Test
    void hostStillEvacuatingKeepsItsEventsAndGetsNoWarning() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())       // maintenanceSeen is only called for full maintenance
        Map m = maint2(true); m.full = false
        AlertCycle.Outcome o = new AlertCycle.Outcome(findings: [peer(1, 'node2')], events: restartEvents())
        cycle.finish(work(m), [:], o)
        assertFalse(o.findings.any { it.key.startsWith('maint:') })
        assertEquals(5, o.events.size())
    }

    @Test
    void onlyPeerLossAndHeartbeatLinesNamingTheHostAreDropped() {
        AlertCycle cycle = cycleWithMaint()
        Finding quorum = ev(1, 'quorum-lost', 'ERROR - quorum lost, peers [node2]', now - 30000)
        AlertCycle.Outcome o = new AlertCycle.Outcome(events: [quorum])
        cycle.finish(work(maint2()), [:], o)
        assertEquals([quorum], o.events)
    }

    @Test
    void agentSilenceAloneNeedsTwoCycles() {
        AlertCycle cycle = cycleWithMaint()
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        cycle.finish(work(maint2()), [:], o)
        assertEquals(2, o.findings.find { it.key == 'maint:2:unreachable' }.confirmCycles)
        Map m = maint2(); m.silentMin = 5L
        AlertCycle.Outcome o2 = new AlertCycle.Outcome()
        cycleWithMaint().finish(work(m), [:], o2)
        assertEquals(1, o2.findings[0].confirmCycles)
    }

    @Test
    void countOnlyReachableFindingIsDroppedWhileTheMaintenanceHostIsDown() {
        AlertCycle cycle = cycleWithMaint()
        Finding reach = new Finding(key: 'hc:1:reachable', severity: Finding.CRITICAL, clusterId: CID, resource: 'node1', source: 'host',
                meta: [missing: 1])
        AlertCycle.Outcome o = new AlertCycle.Outcome(findings: [reach, peer(3, 'node2')])
        cycle.finish(work(maint2()), [:], o)
        assertEquals(['maint:2:unreachable'], o.findings*.key)
    }

    @Test
    void fenceAckForAnotherHostIsKept() {
        AlertState st = new AlertState()
        AlertCycle cycle = cycleWithMaint(st)
        st.maintenanceDown(2L, now - 60000L)
        Finding other = ev(1, 'fence-ack', "WARN - DLM did not enter 'wait fencing' state within 30s for node node3-3 (dlmNodeId=3). Attempting fence_ack anyway.", now - 20000)
        AlertCycle.Outcome o = new AlertCycle.Outcome(events: [other])
        cycle.finish(work(maint2()), [:], o)
        assertEquals([other], o.events)
    }

    @Test
    void rejoinQuorumFalseWithAllNodesReachableNeedsTwoFreshLines() {
        String ts = java.time.Instant.ofEpochMilli(now).toString()
        String line = "${ts} [x] INFO c.m.a.services.QuorumCheckService - handleAction results: [quorumAchieved=false, totalNodes=3, reachableNodes=3, fenced=false]"
        List<Map> got = []
        Closure add = { String key, String sev, String title, String detail, Map o = [:] -> got << [key: key, confirm: o.confirm] }
        HostCheck.parseAgentQuorum(line, now, 30, add, true, true)
        assertEquals([[key: 'quorum', confirm: 2]], got)
        got.clear()
        HostCheck.parseAgentQuorum(line, now, 30, add, true, false)      // same line kept from an earlier cycle
        assertEquals([], got)
        got.clear()
        HostCheck.parseAgentQuorum(line.replace('reachableNodes=3', 'reachableNodes=1'), now, 30, add, true, false)
        assertEquals([[key: 'quorum', confirm: 1]], got)
    }

    // ---- round 2 (agent 2) ----

    @Test
    void survivorsOwnHeartbeatLinesDuringAnUncleanRestartAreDropped() {
        AlertState st = new AlertState()
        AlertCycle cycle = cycleWithMaint(st)
        st.maintenanceDown(2L, now - 60000L)
        List<Finding> hb = (1..5).collect { i ->
            ev(1, 'hb-all', 'ERROR c.m.agent.stats.MvmHeartbeatFailover - Failed to write heartbeat file for host node1, on all heartbeat targets.', now - i * 20000L)
        }
        AlertCycle.Outcome o = new AlertCycle.Outcome(findings: [peer(1, 'node2')], events: hb)
        cycle.finish(work(maint2(true)), [:], o)
        assertEquals([], o.events)
    }

    @Test
    void reEntryStartsANewPeriodButSkippedCyclesDoNot() {
        AlertState st = new AlertState()
        st.maintenanceSeen(2L, CID, ['node2'] as Set, now - 20 * 60000L)
        st.maintenanceSeen(2L, CID, ['node2'] as Set, now - 13 * 60000L)      // 7 min without a cycle: same period
        assertEquals(now - 20 * 60000L, st.maintenance[2L].enteredMs)
        st.maintenanceLeft(2L)                                                 // seen out of maintenance
        st.maintenanceSeen(2L, CID, ['node2'] as Set, now)                    // back in maintenance
        assertEquals(now, st.maintenance[2L].enteredMs)
        st.maintenanceSeen(2L, CID, ['node2'] as Set, now + 125000L)          // next cycle: same period
        assertEquals(now, st.maintenance[2L].enteredMs)
    }

    @Test
    void silenceOnlyStartsNoDownEpisode() {
        AlertState st = new AlertState()
        AlertCycle cycle = cycleWithMaint(st)
        cycle.finish(work(maint2()), [:], new AlertCycle.Outcome())
        assertNull(st.maintenance[2L].firstDownMs)
    }
}
