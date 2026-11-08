package com.hpe.hvmalert

import org.junit.Assume
import org.junit.Test

import java.nio.file.Files

import static org.junit.Assert.*

class HostCheckTest {

    static String fixture(String name) {
        HostCheckTest.getResource("/fixtures/${name}").text
    }

    static HostCheck.Result parse(String name, Map extra = [:]) {
        HostCheck.parse(fixture(name), [cluster: 'CLS', hostId: 7L, hostName: 'host1',
                                        nodeUnreachableSeconds: 60, fencingWaitSeconds: 30] + extra)
    }

    @Test
    void healthyClusteredHostHasNoFindings() {
        HostCheck.Result r = parse('healthy_clustered.txt')
        assertTrue(r.complete)
        assertTrue(r.clustered)
        assertFalse(r.busy)
        assertEquals('2026-11-04T18:46:36Z', r.hostNow)
        assertEquals([], r.findings*.key)
        assertEquals('131075', r.logInode)
        assertEquals(52428800L, r.logSize)
        assertTrue(r.quorumLine.contains('quorumAchieved=true'))
        assertEquals(1, r.events.size())
        assertEquals('HA failover: host offline, VMs are being moved', r.events[0].title)
        assertTrue(r.events[0].line.startsWith('WARN'))
    }

    @Test
    void failureOutputGivesEveryCondition() {
        HostCheck.Result r = parse('failure_clustered.txt')
        Map<String, Finding> f = r.findings.collectEntries { [(it.key): it] }
        assertTrue(f.containsKey('hc:7:quorum'))
        assertFalse('Corosync quorate is not an alert on layout 1.3/2.0', f.containsKey('hc:7:corosync'))
        assertFalse('with a /quorum node list the map count is not reported (1.15.0)', f.containsKey('hc:7:reachable'))
        assertTrue(f.containsKey('hc:7:peer:host3'))
        assertFalse('host2 was reached 10 s ago', f.containsKey('hc:7:peer:host2'))
        assertEquals(30, f['hc:7:dlm-wait'].holdSeconds)
        assertTrue(f.containsKey('hc:7:gfs2:cls:vme_ds01'))
        assertFalse(f.containsKey('hc:7:gfs2:cls:vme_ds02'))
        assertFalse(f.containsKey('hc:7:quorum-unknown'))
        assertEquals(Finding.WARNING, f['hc:7:mpath:360002ac0000000000000000100000abc'].severity)
        assertEquals(Finding.CRITICAL, f['hc:7:mpath:360002ac0000000000000000200000abc'].severity)
        assertEquals('one finding per key', r.findings.size(), f.size())
        assertEquals('all-targets heartbeat failure is a warning (normal during fencing)', ['critical', 'warning', 'warning'], r.events*.severity)
    }

    @Test
    void hostWithoutGfs2SkipsClusterChecks() {
        HostCheck.Result r = parse('nfs_only.txt')
        assertTrue(r.complete)
        assertFalse(r.clustered)
        assertEquals([], r.findings*.key)
    }

    @Test
    void missingSudoAndCutOutputAreReported() {
        HostCheck.Result r = parse('no_sudo.txt')
        assertFalse(r.complete)
        assertEquals('none', r.privilege)
        assertNull(r.logInode)
        assertEquals(['hc:7:privilege', 'hc:7:script'] as Set, r.findings*.key as Set)
    }

    @Test
    void documentedQuorumObjectIsAccepted() {
        String out = "@@ID 0 2026-11-04T19:00:00Z\n@@PRIV root\n@@GFS2MOUNTS 1\n@@QUORUM_API\n" +
                '{"quorumAchieved":false,"waitFencing":true,"reachableNodes":2,"totalNodes":3}\n@@END\n'
        HostCheck.Result r = HostCheck.parse(out, [cluster: 'CLS', hostId: 1L, hostName: 'h'])
        assertEquals(['hc:1:dlm-wait', 'hc:1:quorum', 'hc:1:reachable', 'hc:1:quorum-unknown'] as Set, r.findings*.key as Set)
    }

    @Test
    void quorumStateFromCacheOrUnknownWhenStale() {
        String fresh = '2026-11-04T18:55:00.000Z [t] [] INFO  x - handleAction results: {data={quorumAchieved=true, totalNodes=3, reachableNodes=3}}'
        String stale = '2026-11-04T18:40:00.000Z [t] [] INFO  x - handleAction results: {data={quorumAchieved=true, totalNodes=3, reachableNodes=3}}'
        String out = "@@ID 0 2026-11-04T19:00:00Z\n@@PRIV root\n@@GFS2MOUNTS 1\n@@QUORUM_API\n[]\n@@LOG 5 100\n@@LOGDATA\n@@END\n"
        Map ctx = [cluster: 'CLS', hostId: 1L, hostName: 'h']
        assertEquals([], HostCheck.parse(out, ctx + [lastQuorumLine: fresh]).findings*.key)
        assertEquals(['hc:1:quorum-unknown'], HostCheck.parse(out, ctx + [lastQuorumLine: stale]).findings*.key)
        assertEquals(['hc:1:quorum-unknown'], HostCheck.parse(out, ctx).findings*.key)
    }

    @Test
    void busyHostReadsNothing() {
        HostCheck.Result r = HostCheck.parse("@@BUSY\n@@END\n", [cluster: 'CLS', hostId: 3L, hostName: 'h'])
        assertTrue(r.busy)
        assertEquals(['hc:3:busy'], r.findings*.key)
    }

    @Test
    void scriptCarriesParametersAndTimeouts() {
        String s = HostCheck.script()
        ['@@ID', '@@PRIV', '@@GFS2MOUNTS', '@@QUORUM_API', '@@LOG', '@@LOGDATA', '@@DLM', '@@GFS2', '@@MPATH', '@@END', '@@BUSY'].each {
            assertTrue(it, s.contains(it))
        }
        assertFalse('no corosync check', s.contains('corosync-quorumtool'))
        assertFalse('every placeholder replaced', (s =~ /@[A-Z_]+@/).find())
        assertFalse('sudo is never probed', s.contains('sudo'))
        assertTrue(s.contains('L=/var/log/morpheus-node/morphd/current.log'))
        assertTrue(s.contains("TO='timeout -k 2 6'"))
        assertTrue(s.contains('flock -n 9'))
        assertTrue(s.startsWith("LI=''; LO=-1; E=0"))
        assertTrue(HostCheck.script('42', 1000L, 2).startsWith("LI='42'; LO=1000; E=2"))
        assertTrue('inode is digits only', HostCheck.script("1'; rm -rf /", 5L).startsWith("LI='1'; LO=5"))
    }

    @Test
    void scriptPassesShellSyntaxCheck() {
        ['/bin/sh', '/bin/bash', '/bin/dash', '/usr/bin/dash'].findAll { new File(it).canExecute() }.each { String sh ->
            Process p = [sh, '-n', '-c', HostCheck.script()].execute()
            p.waitFor()
            assertEquals("${sh}: ${p.err.text}", 0, p.exitValue())
        }
    }

    /**
     * Runs the real script with stand-in commands (id reports root, GNU-style stat, findmnt, curl) on a
     * temporary agent log: first read, an incremental read from the reported offset, and the event caps.
     */
    @Test
    void scriptReadsTheAgentLogIncrementally() {
        Assume.assumeTrue(new File('/bin/sh').canExecute())
        File dir = Files.createTempDirectory('hvmalert').toFile()
        File bin = new File(dir, 'bin'); bin.mkdirs()
        def stub = { String name, String body ->
            File f = new File(bin, name); f.text = "#!/bin/sh\n${body}\n"; f.setExecutable(true)
        }
        stub('id', 'echo 0')
        stub('findmnt', 'echo /mnt/gfs2a')
        stub('curl', "echo '[]'")
        stub('stat', 'f="$3"; [ -f "$f" ] || exit 1; printf "%s %s\\n" "$(ls -i "$f" | awk \'{print $1}\')" "$(wc -c < "$f" | tr -d " ")"')
        stub('dlm_tool', 'echo "daemon now 1 fence_pid 0"')
        File logf = new File(dir, 'current.log')
        String q = '2026-11-04T18:59:00.000Z [t] [] INFO  x - handleAction results: {data={quorumAchieved=true, totalNodes=3, reachableNodes=3}}'
        StringBuilder sb = new StringBuilder()
        sb << q << '\n'
        60.times { sb << "2026-11-04T18:59:${String.format('%02d', it % 60)}.000Z [t] [] WARN  x - Hosts h2 have failed to update heartbeat in 61000 milliseconds\n" }
        sb << '2026-11-04T18:59:58.000Z [t] [] WARN  c.m.agent.stats.MvmHeartbeatFailover - Failed to write heartbeat file for host APD-web-01, on all heartbeat targets.\n'
        sb << '2026-11-04T18:59:59.500Z [t] [] WARN  x - Host h3 is offline, moving vms\n'
        logf.text = sb.toString()
        def run = { String script ->
            ProcessBuilder pb = new ProcessBuilder('/bin/sh', '-c', script).redirectErrorStream(true)
            pb.environment().put('PATH', bin.absolutePath + ':' + System.getenv('PATH'))
            Process proc = pb.start()
            String out = proc.inputStream.text
            proc.waitFor()
            out
        }
        try {
            HostCheck.Result r1 = HostCheck.parse(run(HostCheck.script(null, -1L, 0, logf.absolutePath)),
                    [cluster: 'CLS', hostId: 1L, hostName: 'h'])
            assertTrue(r1.complete)
            assertTrue(r1.clustered)
            assertEquals('root', r1.privilege)
            assertEquals(logf.length(), r1.logSize)
            assertTrue(r1.quorumLine.contains('quorumAchieved=true'))
            assertEquals('critical line kept despite 60 warnings', 1, r1.events.count { it.severity == Finding.CRITICAL })
            assertEquals(HostCheck.MAX_WARNING_EVENTS, r1.events.count { it.severity == Finding.WARNING })
            assertFalse('a host named APD-web-01 is not an APD event', r1.events.any { it.rule == 'apd' })

            logf << '2026-11-04T19:01:00.000Z [t] [] WARN  x - Host h2 is unreachable, taking action\n'
            HostCheck.Result r2 = HostCheck.parse(run(HostCheck.script(r1.logInode, r1.logSize, 0, logf.absolutePath)),
                    [cluster: 'CLS', hostId: 1L, hostName: 'h'])
            assertEquals('only the appended line is read', ['Peer unreachable, agent is taking action'], r2.events*.title)
            assertNull('no new quorum line', r2.quorumLine)
            assertEquals(logf.length(), r2.logSize)
        } finally {
            dir.deleteDir()
        }
    }

    @Test
    void agentMapKeysAfterTheNodeListAreRead() {
        String line = '2026-11-04T18:59:30.000Z [t] [] INFO  x - handleAction results: {data={quorumAchieved=true, fenced=false, ' +
                'totalNodes=3, reachableNodes=2, nodes=[{hostname=h1, lastReachedDate=1793818770000, waitFencing=false}, ' +
                '{hostname=h3, needFencing=false}], waitFencing=true, downHosts=[h3], designatedCoordinator=h1}}'
        String out = "@@ID 0 2026-11-04T19:00:00Z\n@@PRIV root\n@@GFS2MOUNTS 1\n@@QUORUM_API\n[]\n@@LOG 5 100\n@@LOGDATA\nQ ${line}\n@@END\n"
        HostCheck.Result r = HostCheck.parse(out, [cluster: 'CLS', clusterId: 9L, hostId: 1L, hostName: 'h1'])
        Map<String, Finding> f = r.findings.collectEntries { [(it.key): it] }
        assertEquals(['hc:1:dlm-wait', 'hc:1:reachable', 'hc:1:down-hosts'] as Set, f.keySet())
        assertEquals(['h3'], f['hc:1:down-hosts'].meta.hosts)
        assertEquals(1, f['hc:1:reachable'].meta.missing)
        assertEquals(1, f['hc:1:reachable'].confirmCycles)
        assertEquals(9L, f['hc:1:reachable'].clusterId)
    }

    @Test
    void peerTimesAsEpochOrMissing() {
        String api = '[{"hostname":"h2","lastReachedDate":1793818700000,"me":false,"serverTime":1793818800000},' +
                '{"hostname":"h3","me":false,"serverTime":1793818800000},{"hostname":"h4","lastReachedDate":"1793818795000","me":false,"serverTime":1793818800000}]'
        String out = "@@ID 0 2026-11-04T19:00:00Z\n@@PRIV root\n@@GFS2MOUNTS 1\n@@QUORUM_API\n${api}\n@@END\n"
        HostCheck.Result r = HostCheck.parse(out, [cluster: 'CLS', hostId: 1L, hostName: 'h1', lastQuorumLine:
                '2026-11-04T18:59:00.000Z [t] [] INFO x - {data={quorumAchieved=true}}'])
        Map<String, Finding> f = r.findings.collectEntries { [(it.key): it] }
        assertEquals(['hc:1:peer:h2', 'hc:1:peer:h3'] as Set, f.keySet())
        assertTrue(f['hc:1:peer:h2'].title.contains('100 s'))
        assertEquals('never reached waits the unreachable threshold', 60, f['hc:1:peer:h3'].holdSeconds)
    }

    @Test
    void expectedGfs2MountsAreChecked() {
        String out = "@@ID 0 2026-11-04T19:00:00Z\n@@PRIV root\n@@GFS2MOUNTS 0\n@@QUORUM_API\n[]\n@@END\n"
        HostCheck.Result r = HostCheck.parse(out, [cluster: 'CLS', hostId: 1L, hostName: 'h', expectedGfs2: 2,
                                                   lastQuorumLine: '2026-11-04T18:59:00.000Z x {data={quorumAchieved=true}}'])
        assertTrue('cluster checks run although nothing is mounted', r.clustered)
        Finding m = r.findings.find { it.key == 'hc:1:gfs2-mounts' }
        assertEquals(Finding.CRITICAL, m.severity)
        assertEquals('Expected 2 GFS2 datastore(s), 0 mounted', m.title)
    }

    @Test
    void eventRulesFollowTheFieldText() {
        List<Map> e = HostCheck.parseEvents([
                '2026-11-04T18:37:56.000Z [t] [] WARN  c.m.a.DlmService - DLM did not enter \'wait fencing\' state within 30s for node n3 (dlmNodeId=3). Attempting fence_ack anyway.',
                '2026-11-04T18:37:57.000Z [t] [] ERROR c.m.agent.stats.MvmHeartbeatFailover - Failed to write heartbeat file for host APD-web-01, on all heartbeat targets.',
                '2026-11-04T18:37:58.000Z [t] [] ERROR c.m.agent.stats.MvmHeartbeatFailover - isolation failure counter reached 6, All Paths Down',
                '2026-11-04T18:37:59.000Z [t] [] INFO  c.m.Other - vm APD-db started'])
        assertEquals(['fence-ack', 'hb-all', 'apd'], e*.rule)
        assertEquals([Finding.WARNING, Finding.WARNING, Finding.CRITICAL], e*.severity)
    }

    /** Runs the script with stand-in commands on a temporary agent log; returns the parsed result. */
    static HostCheck.Result runScript(String logText, Map ctx = [:]) {
        File dir = Files.createTempDirectory('hvmalert').toFile()
        try {
            File bin = new File(dir, 'bin'); bin.mkdirs()
            def stub = { String name, String body -> File f = new File(bin, name); f.text = "#!/bin/sh\n${body}\n"; f.setExecutable(true) }
            stub('id', 'echo 0')
            stub('findmnt', 'echo /mnt/gfs2a')
            stub('curl', "echo '[]'")
            stub('stat', 'f="$3"; [ -f "$f" ] || exit 1; printf "%s %s\\n" "$(ls -i "$f" | awk \'{print $1}\')" "$(wc -c < "$f" | tr -d " ")"')
            stub('dlm_tool', 'echo "daemon now 1 fence_pid 0"')
            File logf = new File(dir, 'current.log')
            logf.text = logText
            ProcessBuilder pb = new ProcessBuilder('/bin/sh', '-c', HostCheck.script(null, -1L, 0, logf.absolutePath)).redirectErrorStream(true)
            pb.environment().put('PATH', bin.absolutePath + ':' + System.getenv('PATH'))
            Process proc = pb.start()
            String out = proc.inputStream.text
            proc.waitFor()
            return HostCheck.parse(out, [cluster: 'CLS', clusterId: 5L, hostId: 1L, hostName: 'h'] + ctx)
        } finally {
            dir.deleteDir()
        }
    }

    @Test
    void everyRuleSamplePassesTheShellInAnyCase() {
        List<String> samples = [
                'WARN  c.m.a.services.QuorumCheckService - Host h3 IS UNREACHABLE, taking action',
                'WARN  c.m.agent.stats.MvmHeartbeatFailover - Host h3 is offline, moving vms',
                'ERROR c.m.agent.stats.MvmHeartbeatFailover - ISOLATION FAILURE counter reached 6',
                'ERROR c.m.Agent - quorum LOST',
                'ERROR c.m.agent.stats.MvmHeartbeatFailover - Failed to write heartbeat file for host h1, on all heartbeat targets.',
                'WARN  c.m.a.DlmService - Attempting fence_ack anyway.',
                'WARN  c.m.agent.stats.MvmHeartbeatFailover - Hosts h2 have failed to update heartbeat in 61000 milliseconds',
                'WARN  c.m.agent.stats.MvmHeartbeatFailover - Skipping unhealthy heartbeat datastore path /x']
        String text = samples.withIndex().collect { l, i -> "2026-11-04T18:37:${String.format('%02d', i)}.000Z [t] [] ${l}\n" }.join('')
        HostCheck.Result r = runScript(text)
        assertEquals(['peer-unreachable', 'failover', 'apd', 'quorum-lost', 'hb-all', 'fence-ack', 'hb-late', 'hb-path'] as Set,
                r.events*.rule as Set)
    }

    @Test
    void heartbeatTimesSurviveAWarningFlood() {
        StringBuilder sb = new StringBuilder()
        6.times { sb << "2026-11-04T18:37:${String.format('%02d', it * 9)}.000Z [t] [] ERROR c.m.agent.stats.MvmHeartbeatFailover - Failed to write heartbeat file for host h1, on all heartbeat targets.\n" }
        40.times { sb << "2026-11-04T18:38:${String.format('%02d', it)}.000Z [t] [] WARN  x - Hosts h2 have failed to update heartbeat in 61000 milliseconds\n" }
        HostCheck.Result r = runScript(sb.toString())
        assertEquals(6, r.heartbeatFailureTimes.size())
        assertEquals(0, r.events.count { it.rule == 'hb-all' })
    }

    @Test
    void longQuorumLineKeepsItsTail() {
        String nodes = (1..20).collect { "{hostname=host${it}, keyHash=${'a' * 64}, dlmNodeId=${it}, lastReachedDate=1793818770000, me=false, bootId=${'b' * 36}, addresses=[10.0.0.${it}], needFencing=false}" }.join(', ')
        String q = "2099-01-01T00:00:00.000Z [t] [] INFO  com.morpheus.agent.ActionHandler - handleAction results: {data={quorumAchieved=true, fenced=false, totalNodes=20, reachableNodes=19, nodes=[${nodes}], waitFencing=true, downHosts=[host7]}, success=true}"
        assertTrue("line longer than the old 4000 cap: ${q.length()}".toString(), q.length() > 4500)
        HostCheck.Result r = runScript(q + '\n', [hostNow: null])
        assertTrue(r.quorumLine.endsWith('success=true}'))
        Map ctxNow = [cluster: 'CLS', hostId: 1L, hostName: 'h']
        List<String> keys = []
        HostCheck.parseAgentQuorum(r.quorumLine, HostCheck.parseTime('2099-01-01T00:01:00Z'), 30, { String k, String sev, String t, String d, Map o = [:] -> keys << k })
        assertEquals(['dlm-wait', 'reachable', 'down-hosts'] as Set, keys as Set)
        List<String> cut = []
        HostCheck.parseAgentQuorum(q.take(3000), HostCheck.parseTime('2099-01-01T00:01:00Z'), 30, { String k, String sev, String t, String d, Map o = [:] -> cut << k })
        assertTrue('a cut line is reported, not parsed silently', cut.contains('quorum-truncated'))
    }

    @Test
    void hostNamedApdStaysAWarning() {
        String text = '2026-11-04T18:37:00.000Z [t] [] ERROR c.m.agent.stats.MvmHeartbeatFailover - Failed to write heartbeat file for host hv-apd, on all heartbeat targets.\n' +
                '2026-11-04T18:37:20.000Z [t] [] WARN  c.m.agent.stats.MvmHeartbeatFailover - Hosts hv-apd have failed to update heartbeat in 61000 milliseconds\n' +
                '2026-11-04T18:37:40.000Z [t] [] WARN  c.m.agent.stats.MvmHeartbeatFailover - Host isolation-01 heartbeat ok\n'
        HostCheck.Result r = runScript(text)
        assertEquals(['hb-all', 'hb-late'], r.events*.rule)
        assertEquals(1, r.heartbeatFailureTimes.size())
        assertTrue(HostCheck.parseEvents(['2026-11-04T18:37:00.000Z [t] [] ERROR c.m.agent.stats.MvmHeartbeatFailover - APD protection mode activated'])*.rule == ['apd'])
    }

    /** VME lab 2026-11-07 (agent 3.3.0): the agent writes the script output into current.log. */
    @Test
    void ownOutputEchoedInTheAgentLogIsIgnored() {
        String echo = '2026-11-04T18:40:00.000Z [p] [] INFO  com.morpheus.agent.ActionHandler - handleAction results: {success=true, data={output=@@ID 0 2026-11-04T18:40:00Z\n' +
                '@@LOGDATA\n' +
                'Q 2026-11-04T18:39:00.000Z [t] [] INFO  x - handleAction results: {data={quorumAchieved=false, totalNodes=3, reachableNodes=1}}\n' +
                'E 2026-11-04T18:38:00.000Z [t] [] WARN  x - Host h3 is offline, moving vms\n' +
                '@@END\n'
        String real = '2026-11-04T18:39:30.000Z [t] [] INFO  x - handleAction results: {data={quorumAchieved=true, keyHash=abc, totalNodes=3, reachableNodes=3}}\n'
        HostCheck.Result r = runScript(real + echo)
        assertTrue('the real quorum line wins', r.quorumLine.startsWith('2026-11-04T18:39:30.000Z'))
        assertFalse('bulky fields dropped', r.quorumLine.contains('keyHash'))
        assertEquals('echoed events are not events', [], r.events)
    }

    /** VME lab storage-partition test 2026-11-07 (agent 3.3.0): the agent's real APD line. */
    @Test
    void agentApdBudgetLineIsCritical() {
        String line = '2026-11-07T19:38:16.504Z [pool-4-thread-4] [] WARN  c.m.agent.stats.MvmHeartbeatFailover - All heartbeat datastore paths have been unhealthy for host vmenode03 for 6 checks (budget 6). However, network heartbeat is still responsive. leaving VMs alone for now.'
        List<Map> e = HostCheck.parseEvents([line])
        assertEquals(['apd-budget'], e*.rule)
        assertEquals(Finding.CRITICAL, e[0].severity)
        assertEquals(['apd-budget'], runScript(line + '\n').events*.rule)
    }
}
