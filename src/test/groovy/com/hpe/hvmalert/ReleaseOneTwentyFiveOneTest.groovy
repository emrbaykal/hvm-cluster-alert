package com.hpe.hvmalert

import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.TaskResult
import org.junit.Test

import static org.junit.Assert.*

/**
 * 1.25.1, from the verification run R1-R3 of 1.25.0 (VME lab, 2026-11-11; R1 reboot, R2 isolation, R3 all FC paths down).
 * Lines and mail rows are the real ones of that run.
 */
class ReleaseOneTwentyFiveOneTest {

    static final String C = 'hpe-vme-tr'

    static Finding f(String key, String sev, String title, String detail, String res) {
        new Finding(key: key, severity: sev, clusterId: 1L, cluster: C, resource: res, title: title, detail: detail,
                source: key.startsWith('hc:') ? 'host' : 'manager', confirmCycles: 1)
    }

    static AlertSettings settings(AlertSettings.ClusterRule rule) {
        AlertSettings s = new AlertSettings(intervalMinutes: 2)
        s.rules << rule
        s
    }

    static AlertSettings.ClusterRule rule() {
        new AlertSettings.ClusterRule(clusterId: 1L, name: C, recipients: ['a@x.com'], fingerprint: 'f1')
    }

    static final AlertCycle.Outcome O = new AlertCycle.Outcome(clusters: [(1L): C])

    /** A scheduler that has sent its "monitor started" mail with nothing open. */
    static AlertScheduler started(List<Map> sent, AlertSettings s) {
        AlertScheduler sch = AlertSettingsAndMailTest.scheduler(sent) { true }
        sch.notifyClusters(s, O, 0L)
        sch.notifyClusters(s, O, 0L)
        assertEquals(1, sent.size())
        sch
    }

    // F1: RESOLVED repeats the text that was mailed

    static final String PROVISIONED = 'Datastore EMC-DS04 is in provisioned state'
    static final String QUORUM_DOWN = 'Host reported as down by quorum check'
    static Finding status(String msg) { f('host:4:status', Finding.WARNING, 'Host status warning', msg, 'vmenode03') }
    static Finding peer(Long host, String res, int age, String last) {
        f("hc:${host}:peer:vmenode03-3".toString(), Finding.CRITICAL, "Peer vmenode03-3 not reached for ${age} s".toString(),
                "Last reached ${last} (agent quorum endpoint).".toString(), res)
    }

    @Test
    void aResolveRowShowsTheTextThatWasMailed() {
        List<Map> sent = []
        AlertSettings s = settings(rule())
        AlertScheduler sch = started(sent, s)
        // R3 08:52:16: NEW host status with the manager's first message
        sch.state.update([status(PROVISIONED)], 1000L)
        sch.notifyClusters(s, O, 1000L)
        assertEquals(2, sent.size())
        assertTrue(sent[1].html.contains(PROVISIONED))
        // R3 08:53:21: the message changed at the same severity (not mailed), the peer rows are new
        List<Finding> now = [status(QUORUM_DOWN), peer(1L, 'vmenode01', 149, '2026-11-11T08:50:46.687Z'),
                             peer(2L, 'vmenode02', 160, '2026-11-11T08:50:35.895Z')]
        sch.state.update(now, 2000L)
        sch.notifyClusters(s, O, 2000L)
        assertEquals(3, sent.size())
        assertTrue(sent[2].subject, sent[2].subject.endsWith(': 2 new'))
        assertFalse('a message change alone is no row', sent[2].html.contains(QUORUM_DOWN))
        assertEquals(PROVISIONED, sch.state.mail[1L].notified['host:4:status'].detail)
        // the peer ages grow, then everything clears
        sch.state.update([status(QUORUM_DOWN), peer(1L, 'vmenode01', 290, '2026-11-11T08:50:46.687Z'),
                          peer(2L, 'vmenode02', 301, '2026-11-11T08:50:35.895Z')], 3000L)
        sch.notifyClusters(s, O, 3000L)
        assertEquals('only the age grew: nothing to mail', 3, sent.size())
        sch.state.update([], 4000L)
        sch.state.update([], 5000L)
        sch.notifyClusters(s, O, 5000L)
        assertEquals(4, sent.size())
        assertTrue(sent[3].subject, sent[3].subject.endsWith(': 3 resolved'))
        String html = sent[3].html
        assertTrue(html.contains(PROVISIONED) && html.contains('not reached for 149 s') && html.contains('not reached for 160 s'))
        assertFalse(html.contains(QUORUM_DOWN) || html.contains('290 s') || html.contains('301 s'))
    }

    @Test
    void aChangedSeverityIsMailedWithItsNewTextAndResolvesUnderIt() {
        // R2: "Host reported as down by quorum check" (warning) -> "Host has been fenced (nodedown)" (critical)
        List<Map> sent = []
        AlertSettings s = settings(rule())
        AlertScheduler sch = started(sent, s)
        sch.state.update([status(QUORUM_DOWN)], 1000L)
        sch.notifyClusters(s, O, 1000L)
        Finding fenced = f('host:4:status', Finding.CRITICAL, 'Host fenced (manager host status)', 'Host has been fenced (nodedown)', 'vmenode03')
        sch.state.update([fenced], 2000L)
        sch.notifyClusters(s, O, 2000L)
        assertEquals(3, sent.size())
        assertTrue(sent[2].subject, sent[2].subject.contains('CRITICAL') && sent[2].subject.endsWith(': 1 changed'))
        assertTrue(sent[2].html.contains('Host has been fenced (nodedown)'))
        sch.state.update([], 3000L)
        sch.state.update([], 4000L)
        sch.notifyClusters(s, O, 4000L)
        assertTrue(sent[3].html.contains('Host has been fenced (nodedown)') && !sent[3].html.contains(QUORUM_DOWN))
    }

    @Test
    void theDailyMailListsEveryRowSoItMovesEveryRowForward() {
        List<Map> sent = []
        AlertSettings s = settings(rule())
        AlertScheduler sch = started(sent, s)
        sch.state.update([status(PROVISIONED)], 1000L)
        sch.notifyClusters(s, O, 1000L)
        sch.state.update([status(QUORUM_DOWN)], 2000L)
        sch.notifyClusters(s, O, 2000L)
        assertEquals(2, sent.size())
        long day = 1000L + AlertScheduler.DAY_MS
        sch.state.update([status(QUORUM_DOWN)], day)
        sch.notifyClusters(s, O, day)
        assertEquals(3, sent.size())
        assertTrue(sent[2].subject.contains('daily status') && sent[2].html.contains(QUORUM_DOWN))
        assertEquals(QUORUM_DOWN, sch.state.mail[1L].notified['host:4:status'].detail)
        sch.state.update([], day + 1000L)
        sch.state.update([], day + 2000L)
        sch.notifyClusters(s, O, day + 2000L)
        assertTrue(sent[3].html.contains(QUORUM_DOWN) && !sent[3].html.contains(PROVISIONED))
    }

    @Test
    void theMuteEndedMailListsEveryRowSoItMovesEveryRowForward() {
        List<Map> sent = []
        AlertSettings.ClusterRule r = rule()
        AlertSettings s = settings(r)
        AlertScheduler sch = started(sent, s)
        sch.state.update([status(PROVISIONED)], 1000L)
        sch.notifyClusters(s, O, 1000L)
        r.muteUntil = 3600000L; r.mutedBy = 'emre'
        sch.notifyClusters(s, O, 2000L)
        sch.state.update([status(QUORUM_DOWN)], 3000L)
        r.muteUntil = null
        sch.notifyClusters(s, O, 3000L)
        assertEquals(4, sent.size())
        assertTrue(sent[3].subject.contains('mute ended') && sent[3].html.contains(QUORUM_DOWN))
        assertEquals(QUORUM_DOWN, sch.state.mail[1L].notified['host:4:status'].detail)
    }

    // F2: a later storage fold names what the host has open

    static final List<String> WWIDS = ['360000970000220002135533030313032', '360002ac0000000000000016b0000abc0', '360002ac0000000000000016c0000abc0',
                                       '360002ac0000000000000444a0000abc0', '360002ac000000000000045290000abc0', '360002ac0000000000000016d0000abc0',
                                       '360002ac00000000000000d6b0000abc0', '360002ac000000000000040660000abc0', '360002ac000000000000040670000abc0',
                                       '360002ac0000000000000121c0000abc0', '360002ac00000000000001be30000abc0',
                                       '360002ac0000000000000ffff0000abc0', '360002ac0000000000000fffe0000abc0']   // last 2: "(+2 more)", not shown
    static List<Finding> maps() {
        WWIDS.collect { f("hc:4:mpath:${it}".toString(), Finding.CRITICAL, "All 2 paths down on ${it}".toString(), 'Failed paths: ...', 'vmenode03') }
    }
    static Finding gfs2(String fs) {
        f("hc:4:gfs2:i13cru4sqieasg:${fs}".toString(), Finding.CRITICAL, "GFS2 filesystem withdrawn: i13cru4sqieasg:${fs}".toString(),
                '/sys/fs/gfs2/<fs>/withdraw is 1; the filesystem needs unmount/remount or a host reboot.', 'vmenode03')
    }
    static final List<String> LATER = ['ds01', 'ds02', 'ds03', 'ds05', 'ransom_test_ds_new']

    @Test
    void aLaterStorageFoldNamesWhatTheHostHasOpen() {
        List<Finding> first = maps() + [gfs2('emc_ds04')]
        List<Finding> later = LATER.collect { gfs2(it) }
        Map m1 = MailComposer.change(new AlertState.Diff(opened: first), 'vmemanager', first)
        assertEquals('Storage: 13 map(s) with all paths down, 1 GFS2 filesystem(s) withdrawn', (m1.rows[0][1] as Finding).title)
        Map m2 = MailComposer.change(new AlertState.Diff(opened: later), 'vmemanager', first + later)
        assertEquals('Storage: 5 GFS2 filesystem(s) withdrawn (now open on this host: 13 map(s) with all paths down, 6 GFS2 filesystem(s) withdrawn)',
                (m2.rows[0][1] as Finding).title)
        assertEquals('[HVM Cluster Alert] CRITICAL hpe-vme-tr: 1 new', m2.subject)
        Map partial = MailComposer.change(new AlertState.Diff(resolved: later), 'vmemanager', first)
        assertEquals('Storage: 5 GFS2 filesystem(s) withdrawn (still open: 13 map(s) with all paths down, 1 GFS2 filesystem(s) withdrawn)',
                (partial.rows[0][1] as Finding).title)
        Map all = MailComposer.change(new AlertState.Diff(resolved: first + later), 'vmemanager', [])
        assertEquals('Storage: 13 map(s) with all paths down, 6 GFS2 filesystem(s) withdrawn', (all.rows[0][1] as Finding).title)
        assertEquals('without the open problems: as before', 'Storage: 5 GFS2 filesystem(s) withdrawn',
                (MailComposer.change(new AlertState.Diff(opened: later), 'vmemanager').rows[0][1] as Finding).title)
    }

    @Test
    void theSchedulerPassesTheOpenProblemsToTheFold() {
        List<Map> sent = []
        AlertSettings s = settings(rule())
        AlertScheduler sch = started(sent, s)
        List<Finding> first = maps() + [gfs2('emc_ds04')]
        sch.state.update(first, 1000L)
        sch.notifyClusters(s, O, 1000L)
        sch.state.update(first + LATER.collect { gfs2(it) }, 2000L)
        sch.notifyClusters(s, O, 2000L)
        assertEquals(3, sent.size())
        assertTrue(sent[2].html, sent[2].html.contains('(now open on this host: 13 map(s) with all paths down, 6 GFS2 filesystem(s) withdrawn)'))
    }

    // F3: a quorum line from before the host's boot is ignored

    static final String Q = '[pool-2-thread-6] [] INFO  com.morpheus.agent.ActionHandler - handleAction results: '
    /** vmenode03 08:31:08, isolated: quorumAchieved=true with 1 of 3 reachable (r2_q53.txt:7; awk-stripped as the script prints it). */
    static final String MINORITY = "2026-11-11T08:31:08.902Z ${Q}{data={quorumAchieved=true, fenced=false, corosyncActive=true, dlmActive=true, " +
            'corosyncQuorate=true, totalNodes=3, reachableNodes=1, nodes=[{hostname=vmenode03-3, addresses=[10.0.0.3], me=true, ' +
            'lastReachedDate=1794385866193, reachable=true, down=false, dlm={nodeId=3, member=true, needFencing=false}}, {hostname=vmenode01, ' +
            'addresses=[10.0.0.1], me=false, lastReachedDate=1794385746202, reachable=false, down=false, dlm={nodeId=1, member=true, ' +
            'needFencing=false}}, {hostname=vmenode02-2, addresses=[10.0.0.2], me=false, lastReachedDate=1794385746202, reachable=false, ' +
            'down=false, dlm={nodeId=2, member=true, needFencing=false}}], designatedCoordinator=vmenode03-3, downHosts=[], sites=[], ' +
            'waitFencing=false, memberCount=3}, success=true}'
    /** vmenode03 08:38:11, the first line after its boot at 08:34:06 (r2_q53.txt:28). */
    static final String AFTER_BOOT = MINORITY.replace('2026-11-11T08:31:08.902Z', '2026-11-11T08:38:11.060Z')
            .replace('reachableNodes=1', 'reachableNodes=3').replace('reachable=false', 'reachable=true')

    static String out(String now, String up, String quorumLine) {
        ("@@ID 0 ${now}\n" + (up == null ? '' : "@@UP ${up}\n") + '@@PRIV root\n@@GFS2MOUNTS 6\n@@QUORUM_API\n[]\n@@LOG 5 100\n@@LOGDATA\n' +
                (quorumLine ? "Q ${quorumLine}\n" : '') + '@@END\n').toString()
    }

    static final Map CTX = [cluster: C, clusterId: 1L, hostId: 3L, hostName: 'vmenode03']

    @Test
    void aPreBootQuorumLineReadAfterTheBootIsIgnored() {
        // the latent case: the cycle before the reboot ran just before 08:31:08, the next one reads the line after the boot
        HostCheck.Result r = HostCheck.parse(out('2026-11-11T08:38:05Z', '239.52', MINORITY), CTX)
        assertNull(r.quorumLine)
        assertEquals('no Quorum lost, and no "quorum state unknown" right after the boot', [], r.findings*.key)
        HostCheck.Result old = HostCheck.parse(out('2026-11-11T08:38:05Z', null, MINORITY), CTX)
        assertTrue('without @@UP (older output) the line is used as before', old.findings*.key.contains('hc:3:quorum'))
        HostCheck.Result empty = HostCheck.parse(out('2026-11-11T08:38:05Z', '', MINORITY), CTX)
        assertTrue('an empty @@UP (no /proc/uptime) is tolerated', empty.findings*.key.contains('hc:3:quorum'))
    }

    @Test
    void aPreBootQuorumLineKeptFromAnEarlierCycleIsIgnored() {
        // R2: the host was checked again at 08:38:05; its stored line was the pre-reboot one
        HostCheck.Result r = HostCheck.parse(out('2026-11-11T08:38:05Z', '239.52', null), CTX + [lastQuorumLine: MINORITY])
        assertEquals([], r.findings*.key)
        HostCheck.Result later = HostCheck.parse(out('2026-11-11T08:40:20Z', '374.10', AFTER_BOOT), CTX + [lastQuorumLine: MINORITY])
        assertEquals(AFTER_BOOT, later.quorumLine)
        assertEquals('the post-boot line is read', [], later.findings*.key)
    }

    @Test
    void aPreBootStoppedCorosyncLineIsIgnored() {
        // T5 shape: corosync and DLM stopped at 13:56:22; the host rebooted itself; the line read after the boot
        String t5 = "2026-11-10T13:56:22.127Z ${Q}{data={quorumAchieved=true, fenced=false, corosyncActive=false, dlmActive=false, " +
                'corosyncQuorate=false, totalNodes=3, reachableNodes=3, nodes=[{hostname=node3-3, addresses=[10.0.0.3], me=true, ' +
                'lastReachedDate=1794318976309, reachable=true, down=false, dlm={nodeId=3}}]}}'
        assertEquals([], HostCheck.parse(out('2026-11-10T13:59:40Z', '60.00', t5), CTX).findings*.key)
        assertEquals(['hc:3:corosync-active', 'hc:3:dlm-active'] as Set,
                HostCheck.parse(out('2026-11-10T13:59:40Z', '600000.00', t5), CTX).findings*.key as Set)
    }

    @Test
    void quorumUnknownWaitsTenMinutesAfterTheBoot() {
        assertEquals([], HostCheck.parse(out('2026-11-11T08:38:05Z', '239.52', null), CTX).findings*.key)
        assertEquals(['hc:3:quorum-unknown'], HostCheck.parse(out('2026-11-11T08:45:05Z', '659.52', null), CTX).findings*.key)
        assertEquals(['hc:3:quorum-unknown'], HostCheck.parse(out('2026-11-11T08:45:05Z', null, null), CTX).findings*.key)
    }

    @Test
    void thePreBootLineIsNotStoredAsTheHostsQuorumLine() {
        AlertState st = new AlertState()
        AlertCycle cycle = new AlertCycle(state: st)
        AlertCycle.Work w = new AlertCycle.Work(cluster: new ComputeServerGroup(id: 1L, name: C), rule: new AlertSettings.ClusterRule(clusterId: 1L))
        ComputeServer h = new ComputeServer(id: 3L, name: 'vmenode03')
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        cycle.hostResult(w, h, new TaskResult(success: true, output: out('2026-11-11T08:38:05Z', '239.52', MINORITY)), o)
        assertNull(st.quorumLine[3L])
        assertFalse(w.freshQuorum.contains('vmenode03'))
        assertFalse(o.findings.any { it.key == 'hc:3:quorum' })
    }

    @Test
    void theScriptPrintsTheUptime() {
        assertTrue(HostCheck.script().contains('echo "@@UP $(cut -d\' \' -f1 /proc/uptime 2>/dev/null)"'))
        assertEquals(['239.52'], HostCheck.sections(out('2026-11-11T08:38:05Z', '239.52', null))['UP'])
    }

    @Test
    void aKeptMinorityLineOfTheSameBootKeepsQuorumLostOpen() {
        // the agent writes a quorum line about every 2 min, the fast re-check runs every 30 s: a kept line of the same
        // boot must keep the row open, or it resolves and reopens while the host is still in the minority
        List<String> keys = []
        Closure add = { String k, String sev, String t, String d, Map o = [:] -> keys << k }
        long now = HostCheck.parseTime('2026-11-11T08:33:00Z')
        HostCheck.parseAgentQuorum(MINORITY, now, 30, add, true, false)
        assertEquals(['quorum'], keys)
        HostCheck.parseAgentQuorum(MINORITY, now, 30, add, true, true)
        assertEquals(['quorum', 'quorum'], keys)
    }

    // F4: the fence_ack row names every node

    /** vmenode03's seven fence_ack lines of 08:31:16-08:31:19 (r2_q53.txt:8-14). */
    static final List<String> FENCE_ACK = [
        "2026-11-11T08:31:16.213Z [scheduled-executor-thread-2] [] INFO  c.m.a.services.QuorumCheckService - Fast-path fence_ack (20s cycle): DLM in 'wait fencing', single-host failure - issuing fence_ack",
        "2026-11-11T08:31:16.213Z [pool-5-thread-7] [] WARN  c.m.a.services.QuorumCheckService - DLM is in 'wait fencing' state - checking for nodes needing fence_ack",
        '2026-11-11T08:31:16.213Z [pool-5-thread-7] [] WARN  c.m.a.services.QuorumCheckService - DLM fence_wait: issuing fence_ack for unreachable node vmenode01 (dlmNodeId=1)',
        '2026-11-11T08:31:16.221Z [pool-5-thread-7] [] INFO  c.m.a.services.QuorumCheckService - Issued fence_ack for vmenode01 (dlmNodeId=1)',
        '2026-11-11T08:31:16.221Z [pool-5-thread-7] [] WARN  c.m.a.services.QuorumCheckService - DLM fence_wait: issuing fence_ack for unreachable node vmenode02-2 (dlmNodeId=2)',
        '2026-11-11T08:31:16.229Z [pool-5-thread-7] [] INFO  c.m.a.services.QuorumCheckService - Issued fence_ack for vmenode02-2 (dlmNodeId=2)',
        "2026-11-11T08:31:19.238Z [pool-5-thread-7] [] INFO  c.m.a.services.QuorumCheckService - DLM cleared 'wait fencing' after batch fence_ack (1 check(s))",
    ]

    static List<Finding> events(List<String> lines, String host) {
        HostCheck.parseEvents(lines).collect { Map e ->
            new Finding(key: "event:3:${e.ts}", severity: e.severity, clusterId: 1L, cluster: C, resource: host, title: e.title, detail: e.line,
                    source: 'event', since: new Date(HostCheck.parseTime(e.ts as String)), meta: [rule: e.rule])
        }
    }

    @Test
    void theFenceAckRowNamesEveryNode() {
        List<Finding> agg = MailComposer.aggregate(events(FENCE_ACK, 'vmenode03'))
        assertEquals(1, agg.size())
        assertTrue(agg[0].title, agg[0].title.contains('(x7, 08:31:16-08:31:19 UTC)'))
        assertTrue(agg[0].detail, agg[0].detail.startsWith('Nodes: vmenode01, vmenode02-2. '))
        assertTrue(agg[0].detail.endsWith('(+4 more distinct lines)'))
        // R1: one line is mailed as it is
        String one = "2026-11-11T08:15:36.000Z [p] [] WARN  c.m.a.services.QuorumCheckService - DLM did not enter 'wait fencing' state within 30s for node vmenode03-3 (dlmNodeId=3). Attempting fence_ack anyway."
        Finding single = MailComposer.aggregate(events([one], 'vmenode01'))[0]
        assertTrue(single.detail, single.detail.startsWith('WARN') && single.detail.contains('DLM did not enter'))
        // rows whose lines name no DLM node stay as they were (R3 APD budget lines)
        List<Finding> apd = MailComposer.aggregate(events([
                '2026-11-11T08:49:37.000Z [p] [] WARN  c.m.agent.stats.MvmHeartbeatFailover - All heartbeat datastore paths have been unhealthy for host vmenode03 for 6 checks (budget 6). However, network heartbeat is still responsive. leaving VMs alone for now.',
                '2026-11-11T08:49:57.000Z [p] [] WARN  c.m.agent.stats.MvmHeartbeatFailover - All heartbeat datastore paths have been unhealthy for host vmenode03 for 7 checks (budget 6). However, network heartbeat is still responsive. leaving VMs alone for now.'], 'vmenode03'))
        assertTrue(apd[0].detail, apd[0].detail.startsWith('WARN') && !apd[0].detail.contains('Nodes:'))
    }

    // F5: the minority row says what happens next

    @Test
    void theMinorityQuorumRowStatesTheCountAndTheSelfFence() {
        List<Map> got = []
        Closure add = { String k, String sev, String t, String d, Map o = [:] -> got << [key: k, title: t, detail: d] }
        HostCheck.parseAgentQuorum(MINORITY, HostCheck.parseTime('2026-11-11T08:31:15Z'), 30, add, true, true)
        assertEquals('Quorum lost', got[0].title)
        String d = got[0].detail
        assertTrue(d, d.contains('only 1 of 3 nodes reachable (a majority needs 2)') && d.contains('minority') && d.contains('immediate reboot'))
        assertFalse(d.contains('although'))
    }

    // F6: the derived APD row does not claim the VMs are destroyed

    @Test
    void theDerivedApdRowDoesNotClaimTheVmsAreDestroyed() {
        AlertState st = new AlertState()
        AlertCycle cycle = new AlertCycle(state: st)
        ComputeServer h = new ComputeServer(id: 4L, name: 'vmenode03')
        long first = HostCheck.parseTime('2026-11-11T08:48:27Z'), last = HostCheck.parseTime('2026-11-11T08:50:17Z')
        AlertCycle.Work w = new AlertCycle.Work(cluster: new ComputeServerGroup(id: 1L, name: C), rule: new AlertSettings.ClusterRule(clusterId: 1L),
                apdCandidates: [[host: h, count: 8, first: first, last: last, stretch: false]])
        AlertCycle.Outcome o = new AlertCycle.Outcome(findings: maps())
        cycle.evaluateApd(w, o, last + 10000L)
        Finding apd = o.events.find { it.meta.rule == 'apd-derived' }
        assertEquals(Finding.CRITICAL, apd.severity)
        assertEquals('APD likely: heartbeat writes failing and storage paths down', apd.title)
        assertTrue(apd.detail, apd.detail.startsWith('8 consecutive heartbeat write failures on all heartbeat targets with no peer loss reported by this host.'))
        assertTrue(apd.detail.contains('network heartbeat also failed'))
        assertFalse(apd.detail.contains('destroys'))
    }

    // F7: the datastore title names the state

    @Test
    void aDatastoreMarkedNotOnlineIsTitledWithItsState() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        ComputeServerGroup c = new ComputeServerGroup(id: 1L, name: C)
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        cycle.datastoreChecks(c, new Datastore(id: 4L, name: 'EMC-DS04', online: false, status: 'warning', statusMessage: 'Check host(s) vmenode03 status'), o)
        Finding d = o.findings[0]
        assertEquals('Datastore not online: warning', d.title)
        assertEquals('level unchanged', Finding.CRITICAL, d.severity)
        assertEquals('status warning: Check host(s) vmenode03 status', d.detail)
        AlertCycle.Outcome off = new AlertCycle.Outcome()
        cycle.datastoreChecks(c, new Datastore(id: 5L, name: 'DS05', online: false, status: 'offline'), off)
        cycle.datastoreChecks(c, new Datastore(id: 6L, name: 'DS06', online: false, status: null), off)
        assertEquals(['Datastore offline', 'Datastore offline'], off.findings*.title)
    }

    // F8: the datastore fold keeps the manager's reason

    @Test
    void theDatastoreFoldKeepsTheReason() {
        List<Finding> ds = ['DS01', 'DS02', 'DS03', 'DS05', 'EMC-DS04', 'Ransom-Test-DS-New'].withIndex().collect { String n, int i ->
            f("ds:${i + 1}:state".toString(), Finding.WARNING, 'Datastore warning', 'Check host(s) vmenode03 status', n)
        }
        Finding row = MailComposer.change(new AlertState.Diff(opened: ds), 'vmemanager').rows[0][1] as Finding
        assertEquals('6 datastores: Datastore warning', row.title)
        assertEquals('DS01 (Datastore warning); DS02 (Datastore warning); DS03 (Datastore warning); DS05 (Datastore warning); ' +
                'EMC-DS04 (Datastore warning); Ransom-Test-DS-New (Datastore warning); reason: Check host(s) vmenode03 status', row.detail)
        List<Finding> mixed = (1..5).collect { f("ds:${it}:state".toString(), Finding.WARNING, 'Datastore warning', "reason ${it % 4}".toString(), "DS0${it}".toString()) }
        String d = (MailComposer.change(new AlertState.Diff(opened: mixed), 'm').rows[0][1] as Finding).detail
        assertTrue(d, d.endsWith('; reasons: reason 1 | reason 2 | reason 3 (+1 more)'))
    }

    // F9: a fenced host's row says fenced

    @Test
    void aFencedHostStatusRowIsTitledFenced() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        ComputeServerGroup c = new ComputeServerGroup(id: 1L, name: C)
        ComputeServer h = new ComputeServer(id: 4L, name: 'vmenode03', powerState: ComputeServer.PowerState.on, status: 'warning',
                statusMessage: 'Host has been fenced (nodedown)', lastAgentUpdate: new Date())
        AlertCycle.Outcome o = new AlertCycle.Outcome()
        cycle.managerHostChecks(c, h, new AlertSettings.ClusterRule(clusterId: 1L), System.currentTimeMillis(), o)
        Finding st = o.findings.find { it.key == 'host:4:status' }
        assertEquals(Finding.CRITICAL, st.severity)
        assertEquals('Host fenced (manager host status)', st.title)
        assertFalse(st.title.contains('warning'))
        h.statusMessage = QUORUM_DOWN
        AlertCycle.Outcome w = new AlertCycle.Outcome()
        cycle.managerHostChecks(c, h, new AlertSettings.ClusterRule(clusterId: 1L), System.currentTimeMillis(), w)
        assertEquals('Host status warning', w.findings.find { it.key == 'host:4:status' }.title)
    }
}
