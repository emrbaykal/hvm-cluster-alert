package com.hpe.hvmalert

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.MorpheusSynchronousOperationNotificationService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.OperationNotification
import org.junit.Assume
import org.junit.Test

import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

import static org.junit.Assert.*

/** 1.25.3: fixes from the code review (W1-W7, W10, W13, W14 of the review work list). */
class ReleaseOneTwentyFiveThreeTest {

    static final long CID = 5L
    static final long MIN = 60000L
    long now = System.currentTimeMillis()

    // ---- W1: hosts named hv, hv-2, hv-3; hv in maintenance and powered off

    static Map maintHv() {
        [id: 1L, name: 'hv', names: ['hv'] as Set, ips: ['10.0.0.1'] as Set, off: true, power: 'unknown', silentMin: 30L, full: true]
    }

    AlertCycle.Work work() {
        new AlertCycle.Work(cluster: new ComputeServerGroup(id: CID, name: 'C'), rule: new AlertSettings.ClusterRule(clusterId: CID),
                maintenanceHosts: [maintHv()], otherHostNames: ['hv-2', 'hv-3'] as Set, otherHostIps: ['10.0.0.2', '10.0.0.3'] as Set)
    }

    AlertCycle cycleWithHvInMaintenance() {
        AlertState st = new AlertState()
        st.maintenanceSeen(1L, CID, ['hv'] as Set, now - 10 * MIN)
        st.maintenanceSeen(1L, CID, ['hv'] as Set, now)
        new AlertCycle(state: st)
    }

    Finding peer(long reporter, String name, List addresses) {
        new Finding(key: "hc:${reporter}:peer:${name}", severity: Finding.CRITICAL, clusterId: CID, cluster: 'C', resource: "hv-${reporter}",
                title: "Peer ${name} not reached for 80 s", source: 'host', meta: [peer: name, addresses: addresses])
    }

    Finding ev(long hostId, String rule, String line, long t) {
        new Finding(key: "event:${hostId}:${t}", severity: Finding.WARNING, clusterId: CID, cluster: 'C', resource: "hv-${hostId}",
                title: rule, detail: line, source: 'event', since: new Date(t), meta: [rule: rule])
    }

    @Test
    void w1LossOfHv2WhileHvIsInMaintenanceIsMailedOnLayout13() {
        AlertCycle.Outcome o = new AlertCycle.Outcome(findings: [peer(3, 'hv-2-2', ['10.0.0.2'])],
                events: [ev(3, 'peer-unreachable', 'WARN  c.m.a.services.QuorumCheckService - Host hv-2-2 is unreachable, taking action', now - 60000),
                         ev(3, 'failover', 'WARN  c.m.agent.stats.MvmHeartbeatFailover - Host hv-2 is offline, moving vms', now - 20000)])
        cycleWithHvInMaintenance().finish(work(), [:], o)
        assertTrue(o.findings*.key.contains('hc:3:peer:hv-2-2'))
        assertEquals('both lines about hv-2 stay', ['peer-unreachable', 'failover'], o.events*.meta*.rule)
        Finding m = o.findings.find { it.key == 'maint:1:unreachable' }
        assertFalse(m.detail, m.detail.contains('reported unreachable in the agent log'))
        assertFalse(m.detail, m.detail.contains('not reached by'))
    }

    @Test
    void w1LossOfHv2WhileHvIsInMaintenanceIsMailedOnLayout20() {
        // no quorum checks (no GFS2): the failover line is the host-side signal, and with it the heartbeat warning stays
        AlertCycle.Outcome o = new AlertCycle.Outcome(findings: [],
                events: [ev(3, 'hb-late', 'WARN  c.m.agent.stats.MvmHeartbeatFailover - Hosts 0f1e2d have failed to update heartbeat in 160000 milliseconds', now - 30000),
                         ev(3, 'failover', 'WARN  c.m.agent.stats.MvmHeartbeatFailover - Host hv-2 is offline, moving vms', now - 20000)])
        cycleWithHvInMaintenance().finish(work(), [:], o)
        assertEquals(['hb-late', 'failover'], o.events*.meta*.rule)
    }

    @Test
    void w1MaintenanceHostWithDlmNodeId2IsStillRecognisedByItsAddress() {
        // hv has DLM node id 2, so its quorum name is "hv-2", the exact name of the healthy host hv-2
        AlertCycle.Outcome o = new AlertCycle.Outcome(findings: [peer(2, 'hv-2', ['10.0.0.1']), peer(3, 'hv-2', ['10.0.0.1'])],
                events: [ev(3, 'peer-unreachable', 'WARN  c.m.a.services.QuorumCheckService - Host hv-2 is unreachable, taking action', now - 60000)])
        cycleWithHvInMaintenance().finish(work(), [:], o)
        assertEquals(['maint:1:unreachable'], o.findings*.key)
        assertTrue(o.findings[0].detail, o.findings[0].detail.contains('not reached by hv-2, hv-3'))
        assertEquals('the quorum-name line about hv is dropped', [], o.events)
    }

    @Test
    void w1AnAddressOfAnotherHostWinsOverTheName() {
        List<Finding> fs = [peer(3, 'hv-2', ['10.0.0.2'])]
        AlertCycle.dropMaintenanceFindings(CID, [maintHv()], fs, ['10.0.0.2', '10.0.0.3'] as Set)
        assertEquals(['hc:3:peer:hv-2'], fs*.key)
        // an address the manager does not know: the name decides, as before
        fs = [peer(3, 'hv-1', ['10.0.99.1'])]
        AlertCycle.dropMaintenanceFindings(CID, [maintHv()], fs, ['10.0.0.2', '10.0.0.3'] as Set)
        assertEquals([], fs)
    }

    @Test
    void w1SuffixOnlyOnQuorumNameRules() {
        assertTrue(AlertCycle.namesHost('Host hv-1 is unreachable, taking action', ['hv']))
        assertFalse(AlertCycle.namesHost('Host hv-2 is offline, moving vms', ['hv'], false))
        assertTrue(AlertCycle.namesHost('Host hv is offline, moving vms', ['hv'], false))
        assertTrue(AlertCycle.eventNamesHost(ev(3, 'fence-ack', "DLM did not enter 'wait fencing' state within 30s for node hv-1 (dlmNodeId=1)", now), ['hv']))
        assertFalse(AlertCycle.eventNamesHost(ev(3, 'failover', 'Host hv-2 is offline, moving vms', now), ['hv']))
        assertFalse(AlertCycle.eventNamesHost(ev(3, 'hb-all', 'Failed to write heartbeat file for host hv-3, on all heartbeat targets.', now), ['hv']))
        // fence_ack gives the node id: only that suffix counts (bare quorum names exist: "vmenode01 (dlmNodeId=1)")
        assertTrue(AlertCycle.eventNamesHost(ev(3, 'fence-ack', 'DLM fence_wait: issuing fence_ack for unreachable node hv-2 (dlmNodeId=2)', now), ['hv']))
        assertFalse(AlertCycle.eventNamesHost(ev(3, 'fence-ack', 'DLM fence_wait: issuing fence_ack for unreachable node hv-2 (dlmNodeId=1)', now), ['hv']))
        assertTrue(AlertCycle.eventNamesHost(ev(3, 'fence-ack', 'DLM fence_wait: issuing fence_ack for unreachable node hv (dlmNodeId=1)', now), ['hv']))
    }

    // C1 (review round 2): host hv-2 has the bare quorum name "hv-2" (the agent left the suffix off, like vmenode01)

    /** @param nodeId hv-2's DLM node id: 1, or 2 (its bare name then ends in its own id) */
    List<Finding> bareHv2Loss(int nodeId = 1) {
        [ev(3, 'peer-unreachable', 'WARN  c.m.a.services.QuorumCheckService - Host hv-2 is unreachable, taking action', now - 60000),
         ev(3, 'fence-ack', "WARN  c.m.a.services.QuorumCheckService - DLM fence_wait: issuing fence_ack for unreachable node hv-2 (dlmNodeId=${nodeId})".toString(), now - 40000),
         ev(3, 'failover', 'WARN  c.m.agent.stats.MvmHeartbeatFailover - Host hv-2 is offline, moving vms', now - 20000)]
    }

    @Test
    void c1BareQuorumNameWithAKnownAddressIsTheOtherHost() {
        [1, 2].each { int id ->
            AlertCycle.Outcome o = new AlertCycle.Outcome(findings: [peer(3, 'hv-2', ['10.0.0.2'])], events: bareHv2Loss(id))
            cycleWithHvInMaintenance().finish(work(), [:], o)
            assertTrue(o.findings*.key.contains('hc:3:peer:hv-2'))
            assertEquals("node id ${id}".toString(), ['peer-unreachable', 'fence-ack', 'failover'], o.events*.meta*.rule)
            Finding m = o.findings.find { it.key == 'maint:1:unreachable' }
            assertFalse(m.detail, m.detail.contains('agent log') || m.detail.contains('not reached by'))
        }
    }

    @Test
    void c1BareQuorumNameWithoutAUsableAddressIsMailed() {
        [[['10.0.99.2'], 1], [[], 1], [['10.0.99.2'], 2], [[], 2]].each { List addr, int id ->
            AlertCycle.Outcome o = new AlertCycle.Outcome(findings: [peer(3, 'hv-2', addr)], events: bareHv2Loss(id))
            cycleWithHvInMaintenance().finish(work(), [:], o)
            assertTrue("address ${addr}: ambiguous, mailed".toString(), o.findings*.key.contains('hc:3:peer:hv-2'))
            assertEquals("address ${addr}, node id ${id}".toString(), ['peer-unreachable', 'fence-ack', 'failover'], o.events*.meta*.rule)
            Finding m = o.findings.find { it.key == 'maint:1:unreachable' }
            assertFalse(m.detail, m.detail.contains('agent log') || m.detail.contains('not reached by'))
        }
        // the same name with no other host called hv-2 is not ambiguous: it is hv with suffix 2, as before
        List<Finding> fs = [peer(3, 'hv-2', [])]
        AlertCycle.dropMaintenanceFindings(CID, [maintHv()], fs, ['10.0.0.3'] as Set, ['hv-3'] as Set)
        assertEquals([], fs)
    }

    @Test
    void c1MaintenanceHostsOwnSuffixedNameWithItsNodeIdIsStillRecognised() {
        // hv (in maintenance) has DLM node id 2: quorum name hv-2; its lines and its peer rows (by address) are dropped
        AlertCycle.Outcome o = new AlertCycle.Outcome(findings: [peer(3, 'hv-2', ['10.0.0.1'])],
                events: [ev(3, 'peer-unreachable', 'WARN  c.m.a.services.QuorumCheckService - Host hv-2 is unreachable, taking action', now - 60000),
                         ev(3, 'fence-ack', 'WARN  c.m.a.services.QuorumCheckService - DLM fence_wait: issuing fence_ack for unreachable node hv-2 (dlmNodeId=2)', now - 40000)])
        cycleWithHvInMaintenance().finish(work(), [:], o)
        assertEquals(['maint:1:unreachable'], o.findings*.key)
        assertEquals([], o.events)
    }

    // ---- W3 / W14: the agent log read across a rotation (rename) and a truncation

    @Test
    void w3RotationByRenameReadsTheRestOfTheOldFileFirst() {
        Assume.assumeTrue(new File('/bin/sh').canExecute())
        File dir = Files.createTempDirectory('hvmalert').toFile()
        File bin = new File(dir, 'bin'); bin.mkdirs()
        def stub = { String name, String body -> File f = new File(bin, name); f.text = "#!/bin/sh\n${body}\n"; f.setExecutable(true) }
        stub('id', 'echo 0')
        stub('findmnt', 'true')
        stub('curl', "echo '[]'")
        stub('stat', 'f="$3"; [ -f "$f" ] || exit 1; printf "%s %s\\n" "$(ls -i "$f" | awk \'{print $1}\')" "$(wc -c < "$f" | tr -d " ")"')
        File tailArgs = new File(dir, 'tail.args')
        String realTail = ['/usr/bin/tail', '/bin/tail'].find { new File(it).canExecute() }
        Assume.assumeNotNull(realTail)
        stub('tail', "echo \"\$*\" >> '${tailArgs.absolutePath}'; exec ${realTail} \"\$@\"")
        def run = { String script ->
            ProcessBuilder pb = new ProcessBuilder('/bin/sh', '-c', script).redirectErrorStream(true)
            pb.environment().put('PATH', bin.absolutePath + ':' + System.getenv('PATH'))
            Process p = pb.start()
            String out = p.inputStream.text
            p.waitFor()
            out
        }
        def line = { String t, String host -> "2026-11-${t}.000Z [t] [] WARN  x - Host ${host} is offline, moving vms\n".toString() }
        def hosts = { HostCheck.Result r -> r.events*.line.collect { (it =~ /Host (\S+) is offline/)[0][1] } }
        File logf = new File(dir, 'current.log')
        File old = new File(dir, 'current.log.1')
        try {
            logf.text = line('04T23:57:00', 'h1')
            HostCheck.Result r1 = HostCheck.parse(run(HostCheck.script(null, -1L, 0, logf.absolutePath)), [cluster: 'C', hostId: 1L, hostName: 'h'])
            assertEquals(['h1'], hosts(r1))

            logf << line('04T23:58:30', 'h2')                     // written after the last read
            assertTrue(logf.renameTo(old))                     // rotation by rename at 00:00
            logf.text = line('05T00:00:05', 'h3')
            HostCheck.Result r2 = HostCheck.parse(run(HostCheck.script(r1.logInode, r1.logSize, 0, logf.absolutePath)), [cluster: 'C', hostId: 1L, hostName: 'h'])
            assertEquals('the rest of the old file, then the new one', ['h2', 'h3'], hosts(r2))
            assertNotEquals(r1.logInode, r2.logInode)

            // copy-and-truncate keeps the inode and shrinks the file: only the live file is read (current.log.1 is another inode)
            logf.text = line('05T00:01:00', 'h4')
            HostCheck.Result r3 = HostCheck.parse(run(HostCheck.script(r2.logInode, r2.logSize + 1000L, 0, logf.absolutePath)), [cluster: 'C', hostId: 1L, hostName: 'h'])
            assertEquals(['h4'], hosts(r3))

            // more than MAX_READ_BYTES unread in the rotated file: only its last 8 MB are read (tail seeks from the end), as in
            // the same-inode branch; the newest bytes win
            logf << line('05T00:01:30', 'h5')
            new RandomAccessFile(logf, 'rw').withCloseable { it.setLength(r3.logSize + HostCheck.MAX_READ_BYTES + 100000L) }
            logf << '\n' << line('05T23:59:00', 'h6')
            assertTrue(logf.renameTo(old))
            logf.text = line('06T00:00:05', 'h7')
            tailArgs.delete()
            HostCheck.Result r4 = HostCheck.parse(run(HostCheck.script(r3.logInode, r3.logSize, 0, logf.absolutePath)), [cluster: 'C', hostId: 1L, hostName: 'h'])
            assertEquals(['h6', 'h7'], hosts(r4))
            assertTrue(tailArgs.text, tailArgs.readLines().contains("-c ${HostCheck.MAX_READ_BYTES} ${old.absolutePath}".toString()))
        } finally {
            dir.deleteDir()
        }
    }

    // ---- W4: the "still reachable" dlm-wait variant wins over waitFencing=true

    @Test
    void w4ReachableNodeVariantKeepsItsTitleAndAgentTime() {
        String q = '2026-11-10T13:56:22.171Z [pool-1-thread-1] [] INFO  com.morpheus.agent.ActionHandler - handleAction results: {data={quorumAchieved=true, ' +
                'fenced=false, corosyncActive=true, dlmActive=true, totalNodes=3, reachableNodes=3, nodes=[{hostname=node3-3, addresses=[10.0.0.3], me=false, ' +
                'reachable=true, down=false, dlm={nodeId=3, member=false, needFencing=true, leftReason=procdown}}, {hostname=node1-1, me=true, reachable=true, ' +
                'dlm={nodeId=1, member=true, needFencing=false}}], waitFencing=true, downHosts=[]}}'
        String out = "@@ID 0 2026-11-10T13:57:30Z\n@@UP 90000.5\n@@PRIV root\n@@GFS2MOUNTS 2\n@@QUORUM_API\n[]\n@@LOG 5 100\n@@LOGDATA\nQ ${q}\n@@DLM\n@@GFS2\n@@END\n"
        HostCheck.Result r = HostCheck.parse(out, [cluster: 'C', clusterId: 1L, hostId: 1L, hostName: 'node1', nodeUnreachableSeconds: 60,
                                                   fencingWaitSeconds: 30, expectedGfs2: 2])
        Finding f = r.findings.find { it.key == 'hc:1:dlm-wait' }
        assertEquals('DLM waits on fencing a node that is still reachable', f.title)
        assertEquals(HostCheck.parseTime('2026-11-10T13:56:22.171Z'), f.meta.observedMs)
        AlertState st = new AlertState()
        st.update([f], HostCheck.parseTime('2026-11-10T13:57:30Z'))
        assertTrue('held from the agent line, 68 s ago: open on the first read', st.active.containsKey('hc:1:dlm-wait'))
    }

    // ---- W5 / W13: a cluster whose check fails

    ComputeServerGroup cluster = new ComputeServerGroup(id: CID, name: 'C', owner: new Account(id: 1L))
    Closure hostList = { q -> [] }
    Closure datastoreList = { q -> [] }
    List<OperationNotification> alarms = []

    AlertCycle cycle(AlertState st) {
        def cl = [list: { q -> [cluster] }] as MorpheusSynchronousComputeServerGroupService
        def cs = [list: { q -> hostList.call(q) }] as MorpheusSynchronousComputeServerService
        def ds = [list: { q -> datastoreList.call(q) }] as MorpheusSynchronousDatastoreService
        def cloud = [getDatastore: { ds }] as MorpheusSynchronousCloudService
        def on = [list: { q -> alarms }] as MorpheusSynchronousOperationNotificationService
        def svc = [getCluster: { cl }, getComputeServer: { cs }, getCloud: { cloud }, getOperationNotification: { on }] as MorpheusServices
        String settings = TabSettings.toJson(TabSettings.validate(LoopTwoFixesTest.form(clusterId: CID.toString())).values as Map, 'u', now)
        new AlertCycle(morpheus: [getServices: { svc }] as MorpheusContext, state: st,
                cypherRead: { Account a, String k -> k == TabSettings.key(CID) ? settings : null })
    }

    static OperationNotification alarm(long id, long hostId) {
        new OperationNotification(id: id, category: "mvm.alarm.${CID}", refType: 'computeServer', refId: hostId, status: 'error',
                name: "Host Unreachable - host${hostId}", active: true)
    }

    @Test
    void w5HostListFailureKeepsTheAlarmOfAHostLastSeenInMaintenanceOut() {
        AlertState st = new AlertState()
        st.maintenanceSeen(1L, CID, ['hv'] as Set, now - 2 * MIN)
        st.update([new Finding(key: 'host:2:power', severity: Finding.CRITICAL, clusterId: CID, cluster: 'C', resource: 'hv-2',
                title: 'Host power state unknown', source: 'manager', confirmCycles: 1)], now - 2 * MIN)
        hostList = { q -> throw new RuntimeException('db down') }
        alarms = [alarm(7L, 1L), alarm(8L, 2L)]
        AlertSettings s = new AlertSettings()
        AlertCycle.Outcome o = cycle(st).run(s, now)
        assertTrue(o.findings*.key.contains('alarm:8'))
        assertFalse('host 1 was last seen in maintenance', o.findings*.key.contains('alarm:7'))
        Finding check = o.findings.find { it.key == "cluster:${CID}:check".toString() }
        assertEquals('RuntimeException: db down', check.detail)
        assertEquals([CID] as Set, o.carryClusters)
        st.update(o.findings, now, o.carry, o.carryClusters, o.carryScoped)
        assertTrue('carried while the cluster check fails', st.active.containsKey('host:2:power'))
        assertFalse('check failure needs two cycles', st.active.containsKey("cluster:${CID}:check".toString()))
    }

    @Test
    void w5HostThatLeftMaintenanceIsNotFiltered() {
        AlertState st = new AlertState()
        st.maintenanceSeen(1L, CID, ['hv'] as Set, now - 4 * MIN)
        st.maintenanceLeft(1L)
        hostList = { q -> throw new RuntimeException('db down') }
        alarms = [alarm(7L, 1L)]
        AlertCycle.Outcome o = cycle(st).run(new AlertSettings(), now)
        assertTrue(o.findings*.key.contains('alarm:7'))
    }

    @Test
    void w13UnreadableDatastoresKeepTheirRowsAndTheLastGfs2Count() {
        AlertState st = new AlertState()
        st.lastGfs2[CID] = 2
        st.update([new Finding(key: 'ds:9:state', severity: Finding.CRITICAL, clusterId: CID, cluster: 'C', resource: 'DS9',
                title: 'Datastore offline', source: 'manager', confirmCycles: 1)], now - 2 * MIN)
        datastoreList = { q -> throw new IllegalStateException('timeout') }
        AlertCycle.Outcome o = cycle(st).run(new AlertSettings(), now)
        assertEquals([[CID, 'ds:']] as Set, o.carryScoped)
        assertTrue(o.findings*.key.contains("cluster:${CID}:datastores".toString()))
        assertEquals(2, st.lastGfs2[CID])
        assertTrue(o.carryClusters.isEmpty())
        st.update(o.findings, now, o.carry, o.carryClusters, o.carryScoped)
        assertTrue('kept, not resolved', st.active.containsKey('ds:9:state'))
    }

    // ---- W6: the agent call error is named

    @Test
    void w6OnlyATimeoutReadsAsNoAnswer() {
        String timeout = "no answer from the agent within ${AlertCycle.HOST_CHECK_TIMEOUT_S} s"
        assertEquals(timeout, AlertCycle.failureText(new ExecutionException(new RuntimeException(new TimeoutException()))))
        assertEquals(timeout, AlertCycle.failureText(new TimeoutException()))
        assertEquals('IllegalStateException: agent not connected',
                AlertCycle.failureText(new ExecutionException(new IllegalStateException('agent not connected'))))
        assertEquals('IOException: closed', AlertCycle.failureText(new ExecutionException(new RuntimeException(new IOException('closed')))))

        AlertCycle c = new AlertCycle(state: new AlertState())
        AlertCycle.Work w = new AlertCycle.Work(cluster: new ComputeServerGroup(id: CID, name: 'C'), rule: new AlertSettings.ClusterRule(clusterId: CID))
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        c.hostResult(w, new ComputeServer(id: 3L, name: 'hv-3'), new ExecutionException(new IllegalStateException('agent not connected')), o)
        assertEquals('IllegalStateException: agent not connected', o.findings.find { it.key == 'hc:3:exec' }.detail)
    }

    // ---- W7: a "wait 30 seconds" answer costs no test-mail quota

    @Test
    void w7WaitAnswersDoNotUseTheHourlyQuota() {
        MuteTestMailTest t = new MuteTestMailTest()
        t.harness.clusters[1L] = new ComputeServerGroup(id: 1L, name: 'c1', owner: new Account(id: 1L))
        HvmAlertController.lastTest.clear()
        HvmAlertController.userTests.clear()
        assertTrue(t.post([clusterId: '1', op: 'test', recipients: 'a@x.com']).data.success)
        // a later millisecond: the slot check compares times, and a request in the very same millisecond as the sent one passes
        // it (1.25.2 behaviour, not part of this fix)
        12.times { Thread.sleep(2); assertEquals(429, t.post([clusterId: '1', op: 'test', recipients: 'a@x.com']).status) }
        assertEquals('only the sent mail counts', 1, HvmAlertController.userTests.values().sum { it.size() })
        HvmAlertController.lastTest.clear()
        assertTrue(t.post([clusterId: '1', op: 'test', recipients: 'a@x.com']).data.success)

        // quota used up: refused, and the cluster slot is left free
        8.times { HvmAlertController.lastTest.clear(); t.post([clusterId: '1', op: 'test', recipients: 'a@x.com']) }
        HvmAlertController.lastTest.clear()
        Map r = t.post([clusterId: '1', op: 'test', recipients: 'a@x.com'])
        assertEquals(429, r.status)
        assertTrue(r.data.msg, r.data.msg.contains('per user per hour'))
        assertFalse(HvmAlertController.lastTest.containsKey(1L))
        assertEquals(10, t.mails.size())
    }

    // ---- W2: retry capped at 10 minutes, and a working relay ends the other clusters' wait

    static Finding crit(long cid, String key, String title) {
        new Finding(key: key, severity: Finding.CRITICAL, clusterId: cid, cluster: "C${cid}", resource: 'h1', title: title, source: 'manager')
    }

    static AlertState.ClusterMail announced(String fp) {
        AlertState.ClusterMail cm = new AlertState.ClusterMail()
        cm.announced = fp; cm.fingerprintSeen = fp; cm.cycles = 5; cm.lastOkMs = 0L
        cm
    }

    @Test
    void w2RetryWaitIsCappedAtTenMinutes() {
        List<Map> sent = []
        long t = 0
        AlertScheduler sch = AlertSettingsAndMailTest.scheduler(sent) { t >= 69 * MIN }      // relay down for 69 min
        AlertSettings s = new AlertSettings(intervalMinutes: 2)
        s.rules << new AlertSettings.ClusterRule(clusterId: 1L, name: 'C1', recipients: ['a@x.com'], fingerprint: 'f1')
        AlertState.ClusterMail cm = announced('f1')
        sch.state.mail[1L] = cm
        AlertCycle.Outcome o = new AlertCycle.Outcome(clusters: [(1L): 'C1'])
        List<Long> waits = []
        Long sentAt = null
        for (long m = 2; m <= 90 && sentAt == null; m += 2) {
            t = m * MIN
            List<Finding> f = [crit(1L, 'alarm:9', 'Datastore warning')]
            if (m >= 70) f << crit(1L, 'host:2:power', 'Host power state unknown')         // a host fails at 70 min
            sch.state.update(f, t)
            int failures = cm.failures
            sch.notifyClusters(s, o, t)
            if (cm.failures > failures) waits << (cm.nextAttemptMs - t).intdiv(MIN)
            if (sent.any { it.html.contains('Host power state unknown') }) sentAt = m
        }
        // failures at 4, 6, 10, 18, 28, 38, 48, 58 and 68 min (an hour cap waited from 66 to 126 min)
        assertEquals([2L, 4L, 8L, 10L, 10L, 10L, 10L, 10L, 10L], waits)
        assertEquals('the host lost at 70 min is mailed at 78 min', 78L, sentAt)
    }

    @Test
    void w2ASuccessfulMailEndsTheWaitOfTheOtherClusters() {
        List<Map> sent = []
        AlertScheduler sch = AlertSettingsAndMailTest.scheduler(sent) { true }
        AlertSettings s = new AlertSettings(intervalMinutes: 2)
        [1L, 2L, 3L].each { s.rules << new AlertSettings.ClusterRule(clusterId: it, name: "C${it}", recipients: ['a@x.com'], fingerprint: 'f') }
        [1L, 2L, 3L].each { sch.state.mail[it] = announced('f') }
        [1L, 3L].each { sch.state.mail[it].failures = 5; sch.state.mail[it].nextAttemptMs = 100 * MIN }
        List<Finding> open = [crit(1L, 'alarm:1', 'A1'), crit(2L, 'alarm:2', 'A2'), crit(3L, 'alarm:3', 'A3')]
        sch.state.update(open, 48 * MIN)
        sch.state.update(open, 50 * MIN)                                     // confirmed on the 2nd cycle
        Map<Long, String> order = new LinkedHashMap<>(); order[1L] = 'C1'; order[2L] = 'C2'; order[3L] = 'C3'
        sch.notifyClusters(s, new AlertCycle.Outcome(clusters: order), 50 * MIN)
        assertEquals('C2 mails; C3, after it in the loop, tries at once', ['C2', 'C3'], sent.collect { (it.subject =~ /CRITICAL (C\d)/)[0][1] })
        assertEquals('C1, before it, tries in the next cycle', 0L, sch.state.mail[1L].nextAttemptMs)
        sch.notifyClusters(s, new AlertCycle.Outcome(clusters: order), 52 * MIN)
        assertEquals(3, sent.size())
    }
}
