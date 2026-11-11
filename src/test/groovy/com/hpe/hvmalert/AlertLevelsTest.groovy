package com.hpe.hvmalert

import org.junit.Test

import static org.junit.Assert.*

/** 1.22.0: an alert level per check on the cluster tab; it changes the mails only. */
class AlertLevelsTest {

    static Finding f(String key, String sev = Finding.CRITICAL, Map meta = [:]) {
        new Finding(key: key, severity: sev, clusterId: 1L, cluster: 'C', resource: 'h1', title: key, source: key.startsWith('event:') ? 'event' : 'host', meta: meta)
    }

    @Test
    void everyFindingShapeHasItsRow() {
        Map<String, String> expect = [
            'alarm:12': 'hvm-alarm', 'ds:4:state': 'datastore', 'maint:3:unreachable': 'maint-unreachable',
            'cluster:1:check': 'plugin-health', 'cluster:1:datastores': 'plugin-health', 'cluster:1:gfs2-blocked': 'gfs2-blocked',
            'host:3:power': 'host-power', 'host:3:status': 'host-status', 'host:3:member': 'host-member', 'host:3:agent': 'agent-silent',
            'hc:3:quorum': 'quorum', 'hc:3:fenced': 'fenced', 'hc:3:dlm-wait': 'dlm-wait', 'hc:3:dlm-active': 'cluster-services',
            'hc:3:corosync-active': 'cluster-services', 'hc:3:peer:node-2': 'peer-reach', 'hc:3:reachable': 'peer-reach',
            'hc:3:down-hosts': 'peer-reach', 'hc:3:gfs2:lun1': 'gfs2-withdraw', 'hc:3:gfs2-mounts': 'gfs2-mounts',
            'hc:3:mpath:3600abc': 'multipath', 'hc:3:exec': 'plugin-health', 'hc:3:busy': 'plugin-health',
            'hc:3:privilege': 'plugin-health', 'hc:3:script': 'plugin-health', 'hc:3:quorum-unknown': 'plugin-health',
            'hc:3:quorum-api': 'plugin-health', 'hc:3:quorum-truncated': 'plugin-health']
        expect.each { k, id -> assertEquals(k, id, Checks.idFor(f(k))) }
        // every agent-log rule of HostCheck has a row, and so does the derived APD event
        HostCheck.EVENT_RULES.each { Map r ->
            assertNotNull(r.id as String, Checks.idFor(f("event:3:2026-11-10T10:00:00.000Z", Finding.WARNING, [rule: r.id])))
        }
        assertEquals('apd-derived', Checks.idFor(f('event:3:apd:123', Finding.WARNING, [rule: 'apd-derived'])))
        assertEquals('apd-agent', Checks.idFor(f('event:3:t', Finding.CRITICAL, [rule: 'apd-budget'])))
        assertNull(Checks.idFor(f('something:else')))
        assertNull('a new host check is never silenced by another row', Checks.idFor(f('hc:3:some-new-check')))
        assertEquals('every row is reachable', Checks.IDS,
                (expect.values() + HostCheck.EVENT_RULES.collect { Checks.EVENT_ROWS[it.id] ?: it.id } + ['apd-derived']) as Set)
    }

    /** Every key HostCheck can add is mapped; a new add() key fails here until it gets a row. */
    @Test
    void everyHostCheckKeyInTheSourceHasARow() {
        String src = new File('src/main/groovy/com/hpe/hvmalert/HostCheck.groovy').text
        List<String> keys = (src =~ /add\(["']([a-z0-9-]+)/).collect { it[1] } as List<String>
        assertTrue(keys.size() >= 15)
        keys.unique().each { String k -> assertNotNull(k, Checks.idFor(f("hc:3:${k}"))) }
    }

    @Test
    void pluginHealthCannotBeTurnedOff() {
        assertEquals([:], Checks.clean(['plugin-health': 'off']))
        assertEquals(['plugin-health': 'critical'], Checks.clean(['plugin-health': 'critical']))
        assertEquals([:], TabSettings.validate(LoopTwoFixesTest.form('level_plugin-health': 'off')).values.levels)
        String html = SettingsPage.levelTable([:])
        String row = html.substring(html.indexOf('name="level_plugin-health"'))
        assertFalse(row.substring(0, row.indexOf('</select>')).contains('value="off"'))
    }

    @Test
    void applyForcesSilencesOrKeeps() {
        Map<String, String> lv = ['multipath': 'critical', 'hb-path': 'off', 'host-power': 'warning']
        Finding some = f('hc:3:mpath:x', Finding.WARNING)
        Finding forced = Checks.apply(lv, some)
        assertEquals(Finding.CRITICAL, forced.severity)
        assertEquals('the original keeps its severity for the rules', Finding.WARNING, some.severity)
        assertNull(Checks.apply(lv, f('event:3:t', Finding.WARNING, [rule: 'hb-path'])))
        assertEquals(Finding.WARNING, Checks.apply(lv, f('host:3:power')).severity)
        Finding quorum = f('hc:3:quorum')
        assertSame('default: unchanged object', quorum, Checks.apply(lv, quorum))
        assertSame(quorum, Checks.apply(null, quorum))
        assertEquals(['Unhealthy heartbeat datastore path skipped'], Checks.offTitles(lv))
    }

    @Test
    void formLevelsAreValidatedAndStoredWithoutDefaults() {
        Map v = TabSettings.validate(LoopTwoFixesTest.form(level_multipath: 'Critical', 'level_hb-path': 'off', level_quorum: 'default'))
        assertFalse(v.errors.toString(), v.errors.containsKey('levels'))
        assertEquals([multipath: 'critical', 'hb-path': 'off'], v.values.levels)
        assertTrue(TabSettings.validate(LoopTwoFixesTest.form(level_quorum: 'info')).errors.containsKey('levels'))
        assertEquals('a form without the table (older page) stores no levels', [:], TabSettings.validate(LoopTwoFixesTest.form()).values.levels)
    }

    @Test
    void storedLevelsReachTheRuleAndTheFingerprint() {
        String plain = TabSettings.toJson(TabSettings.validate(LoopTwoFixesTest.form()).values, 'u', 0L)
        String withLv = TabSettings.toJson(TabSettings.validate(LoopTwoFixesTest.form('level_fence-ack': 'off')).values, 'u', 0L)
        AlertSettings.ClusterRule a = TabSettings.ruleFromJson(1L, plain, AlertSettings.OWN_SOURCE)
        AlertSettings.ClusterRule b = TabSettings.ruleFromJson(1L, withLv, AlertSettings.OWN_SOURCE)
        assertEquals([:], a.levels)
        assertEquals(['fence-ack': 'off'], b.levels)
        assertNotEquals('a level change announces the new settings', a.fingerprint, b.fingerprint)
        // settings saved before 1.22.0 (no levels key) keep their fingerprint, so an upgrade sends no "monitor started"
        String old = plain.replace('"levels":{},', '').replace(',"levels":{}', '')
        assertFalse(old.contains('levels'))
        assertEquals(a.fingerprint, TabSettings.ruleFromJson(1L, old, AlertSettings.OWN_SOURCE).fingerprint)
        // a hand-edited unknown row or level is ignored
        String odd = withLv.replace('"fence-ack":"off"', '"fence-ack":"off","nope":"off","quorum":"loud"')
        assertEquals(['fence-ack': 'off'], TabSettings.ruleFromJson(1L, odd, AlertSettings.OWN_SOURCE).levels)
    }

    @Test
    void mailsUseTheLevelsAndNameTheChecksThatAreOff() {
        List<Map> sent = []
        AlertScheduler sch = AlertSettingsAndMailTest.scheduler(sent) { true }
        AlertSettings s = new AlertSettings(intervalMinutes: 2)
        s.rules << new AlertSettings.ClusterRule(clusterId: 1L, name: 'C', recipients: ['a@x.com'], fingerprint: 'f1',
                levels: ['multipath': 'critical', 'fence-ack': 'off', 'gfs2-mounts': 'off'])
        AlertCycle.Outcome o = new AlertCycle.Outcome(clusters: [(1L): 'C'])
        Finding mp = new Finding(key: 'hc:3:mpath:x', severity: Finding.WARNING, clusterId: 1L, cluster: 'C', resource: 'h1',
                title: '1 of 4 paths failed on x', source: 'native-alarm')
        Finding mounts = new Finding(key: 'hc:3:gfs2-mounts', severity: Finding.WARNING, clusterId: 1L, cluster: 'C', resource: 'h1',
                title: 'Expected 2 GFS2 datastore(s), 1 mounted', source: 'native-alarm')
        sch.state.update([mp, mounts], 0L)
        sch.notifyClusters(s, o, 0L)
        sch.notifyClusters(s, o, 0L)
        assertEquals(1, sent.size())
        assertTrue(sent[0].html.contains('1 of 4 paths failed'))
        assertTrue('multipath row mailed at its set level', sent[0].html.contains('<td style="padding:6px 8px">critical</td>'))
        assertFalse(sent[0].html.contains('<td style="padding:6px 8px">warning</td>'))
        assertFalse('an Off problem is not mailed', sent[0].html.contains('GFS2 datastore(s)'))
        assertTrue(sent[0].html.contains('2 check(s) set to Off'))
        assertEquals(Finding.WARNING, sch.state.active['hc:3:mpath:x'].severity)

        Finding ack = new Finding(key: 'event:3:t1', severity: Finding.WARNING, clusterId: 1L, cluster: 'C', resource: 'h2',
                title: 'DLM fence_ack attempted for a lost node', source: 'event', meta: [rule: 'fence-ack'])
        Finding fo = new Finding(key: 'event:3:t2', severity: Finding.CRITICAL, clusterId: 1L, cluster: 'C', resource: 'h2',
                title: 'HA failover', source: 'event', meta: [rule: 'failover'])
        sch.notifyClusters(s, new AlertCycle.Outcome(clusters: [(1L): 'C'], events: [ack, fo]), 120000L)
        assertEquals(2, sent.size())
        assertTrue(sent[1].subject.contains('CRITICAL') && sent[1].subject.contains('1 event'))
        assertFalse(sent[1].html.contains('fence_ack'))

        // the raw severity moving from warning to critical is no change when the level fixes it at critical
        sch.state.update([mp.withSeverity(Finding.CRITICAL), mounts], 240000L)
        sch.notifyClusters(s, o, 240000L)
        assertEquals(2, sent.size())
    }

    @Test
    void theTabListsEveryCheckAndMarksOffProblems() {
        Map own = TabSettings.validate(LoopTwoFixesTest.form('level_hb-all': 'off', level_quorum: 'warning')).values
        Finding q = new Finding(key: 'hc:3:quorum', severity: Finding.CRITICAL, clusterId: 1L, cluster: 'C', resource: 'h1', title: 'Quorum lost', since: new Date(0L))
        Finding mp = new Finding(key: 'hc:3:mpath:x', severity: Finding.WARNING, clusterId: 1L, cluster: 'C', resource: 'h1', title: 'MP', since: new Date(0L))
        own.levels['multipath'] = 'off'
        String html = SettingsPage.render([clusterId: 1L, clusterName: 'c', url: '/u', own: own, open: [checkedAt: new Date(0L), findings: [q, mp]]])
        Checks.ALL.each { assertTrue(it.id as String, html.contains("name=\"level_${it.id}\"")) }
        assertTrue(html.contains('<option value="off" selected>Off</option>'))
        assertTrue(html.contains('2 check(s) set to Off'))
        assertTrue(html.contains('(Off: not mailed)'))
        assertTrue('quorum shown with its set level', html.contains('<td style="padding:6px 8px;text-align:left;vertical-align:top">warning</td>'))
    }

    /** A level changed while events wait (mail failing) applies to them: Off drops them, a forced level is used. */
    @Test
    void pendingEventsFollowTheLevelInForceWhenMailed() {
        List<Map> sent = []
        boolean up = true
        AlertScheduler sch = AlertSettingsAndMailTest.scheduler(sent) { up }
        AlertSettings s = new AlertSettings(intervalMinutes: 2)
        AlertSettings.ClusterRule rule = new AlertSettings.ClusterRule(clusterId: 1L, name: 'C', recipients: ['a@x.com'], fingerprint: 'f1')
        s.rules << rule
        AlertCycle.Outcome o = new AlertCycle.Outcome(clusters: [(1L): 'C'])
        sch.notifyClusters(s, o, 0L)
        sch.notifyClusters(s, o, 0L)
        assertEquals(1, sent.size())                                    // monitor started
        up = false
        Finding late = new Finding(key: 'event:3:t1', severity: Finding.WARNING, clusterId: 1L, cluster: 'C', resource: 'h2',
                title: 'Host heartbeat not updated', source: 'event', meta: [rule: 'hb-late'])
        Finding path = new Finding(key: 'event:3:t2', severity: Finding.WARNING, clusterId: 1L, cluster: 'C', resource: 'h2',
                title: 'Unhealthy heartbeat datastore path skipped', source: 'event', meta: [rule: 'hb-path'])
        sch.notifyClusters(s, new AlertCycle.Outcome(clusters: [(1L): 'C'], events: [late, path]), 120000L)
        assertEquals(2, sch.state.mail[1L].pendingEvents.size())
        rule.levels = ['hb-late': 'off', 'hb-path': 'critical']
        rule.fingerprint = 'f2'
        up = true
        sch.notifyClusters(s, o, 600000L)
        sch.notifyClusters(s, o, 600000L)
        assertEquals(2, sent.size())
        assertTrue('only the off note names it', sent[1].html.contains('set to Off on the HVM Alert tab are not mailed: Host heartbeat not updated'))
        assertEquals(1, sent[1].html.count('EVENT</td>'))
        assertTrue(sent[1].html.contains('Unhealthy heartbeat datastore path skipped</td>'))
        assertTrue(sent[1].html.contains('<td style="padding:6px 8px">critical</td>'))
        assertEquals(Finding.WARNING, path.severity)
    }
}
