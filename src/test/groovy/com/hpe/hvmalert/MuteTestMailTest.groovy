package com.hpe.hvmalert

import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import com.morpheusdata.views.ViewModel
import jakarta.servlet.http.HttpServletRequest
import org.junit.Test

import static org.junit.Assert.*

/** 1.23.0: test mail, mail status on the tab, planned-work mute, collapsible alert levels. */
class MuteTestMailTest {

    static final long H = 3600000L

    static Finding problem(String key, String title) {
        new Finding(key: key, severity: Finding.CRITICAL, clusterId: 1L, cluster: 'C', resource: 'h1', title: title, source: 'native-alarm')
    }

    static Finding event(String key, String title) {
        new Finding(key: key, severity: Finding.WARNING, clusterId: 1L, cluster: 'C', resource: 'h2', title: title, source: 'event', meta: [rule: 'hb-late'])
    }

    @Test
    void muteHoldsMailsAndEndsWithOneSummary() {
        List<Map> sent = []
        AlertScheduler sch = AlertSettingsAndMailTest.scheduler(sent) { true }
        AlertSettings s = new AlertSettings(intervalMinutes: 2)
        AlertSettings.ClusterRule rule = new AlertSettings.ClusterRule(clusterId: 1L, name: 'C', recipients: ['a@x.com'], fingerprint: 'f1')
        s.rules << rule
        AlertCycle.Outcome o = new AlertCycle.Outcome(clusters: [(1L): 'C'])
        sch.notifyClusters(s, o, 0L)
        sch.notifyClusters(s, o, 0L)
        assertEquals(1, sent.size())                                      // monitor started

        rule.muteUntil = 2 * H; rule.mutedBy = 'emre'
        sch.notifyClusters(s, o, 60000L)
        assertEquals(2, sent.size())
        assertTrue(sent[1].subject.contains('alerts muted until'))
        sch.state.update([problem('alarm:1', 'Quorum lost')], 120000L)
        sch.notifyClusters(s, new AlertCycle.Outcome(clusters: [(1L): 'C'], events: [event('event:3:a', 'Host heartbeat not updated')]), 120000L)
        sch.notifyClusters(s, o, 240000L)
        assertEquals('nothing while muted', 2, sent.size())

        rule.muteUntil = 4 * H                                            // extended: told once more
        sch.notifyClusters(s, o, 300000L)
        assertEquals(3, sent.size())
        assertTrue(sent[2].subject.contains('alerts muted until'))

        rule.muteUntil = null                                             // Unmute
        sch.notifyClusters(s, o, 360000L)
        assertEquals(4, sent.size())
        assertTrue(sent[3].subject, sent[3].subject.contains('CRITICAL C: mute ended, 1 open problem(s)'))
        assertTrue(sent[3].html.contains('Quorum lost') && sent[3].html.contains('Host heartbeat not updated'))
        assertNull(sch.state.mail[1L].mutedSince)

        sch.notifyClusters(s, o, 480000L)
        assertEquals('the summary counts as told: no NEW row afterwards', 4, sent.size())
        sch.state.update([], 600000L)
        sch.notifyClusters(s, o, 600000L)
        assertEquals(5, sent.size())
        assertTrue(sent[4].subject.contains('1 resolved'))
    }

    @Test
    void anExpiredMuteEndsByItselfAndAMuteBeforeTheStartedMailReplacesIt() {
        List<Map> sent = []
        AlertScheduler sch = AlertSettingsAndMailTest.scheduler(sent) { true }
        AlertSettings s = new AlertSettings(intervalMinutes: 2)
        AlertSettings.ClusterRule rule = new AlertSettings.ClusterRule(clusterId: 1L, name: 'C', recipients: ['a@x.com'], fingerprint: 'f1',
                muteUntil: H, mutedBy: 'emre')
        s.rules << rule
        AlertCycle.Outcome o = new AlertCycle.Outcome(clusters: [(1L): 'C'])
        sch.notifyClusters(s, o, 0L)
        assertEquals(1, sent.size())
        assertTrue(sent[0].subject.contains('alerts muted'))
        sch.notifyClusters(s, o, H + 1)                                   // time is up, the key is still there
        assertEquals(2, sent.size())
        assertTrue(sent[1].subject.contains('mute ended, 0 open problem(s)'))
        assertEquals('the summary replaces the started mail', 'f1', sch.state.mail[1L].announced)
        sch.notifyClusters(s, o, H + 120000L)
        assertEquals(2, sent.size())
    }

    @Test
    void muteStorage() {
        String j = TabSettings.muteJson(5000L, 'emre', 1000L)
        assertEquals([until: 5000L, by: 'emre', at: 1000L], TabSettings.parseMute(j))
        assertNull(TabSettings.parseMute('{"until":"soon"}'))
        assertNull(TabSettings.parseMute('not json'))
        assertNull(TabSettings.parseMute(null))
    }

    @Test
    void tabShowsMailStatusMuteAndCollapsedLevels() {
        Map own = TabSettings.validate(LoopTwoFixesTest.form(level_multipath: 'critical')).values
        Map base = [clusterId: 1L, clusterName: 'c', url: '/u', own: own, now: 10 * H, open: [checkedAt: new Date(0L), findings: []]]
        String plain = SettingsPage.render(base + [mail: [lastOkMs: 9 * H]])
        assertTrue(plain.contains('sent 1970-01-01 09:00 UTC'))
        assertTrue(plain.contains('name="muteHours"') && plain.contains('data-action="mute"'))
        assertTrue(plain.contains('data-action="test"'))
        assertTrue(plain.contains('<details') && plain.contains('1 changed from Default'))
        assertTrue(plain.contains('data-action="reset-levels"'))
        assertTrue('changed row marked', plain.contains('class="hvmca-level" style="border-bottom:1px solid #ddd;background:#fff8e1;"'))

        String failed = SettingsPage.render(base + [mail: [lastOkMs: 8 * H, lastErrorMs: 9 * H, lastError: 'IOException: connect <timed> out',
                                                           nextAttemptMs: 9 * H + 240000L]])
        assertTrue(failed.contains('failed 1970-01-01 09:00 UTC: IOException: connect &lt;timed&gt; out; next try 1970-01-01 09:04 UTC'))
        assertTrue(SettingsPage.render(base).contains('Last mail:</dt> <dd>none yet from this monitor'))
        assertTrue(SettingsPage.render(base + [open: null]).contains('not known on this node'))

        String muted = SettingsPage.render(base + [mute: [until: 11 * H, by: 'emre', at: 10 * H]])
        assertTrue(muted.contains('Muted until 1970-01-01 11:00 UTC by emre.'))
        assertTrue(muted.contains('data-action="unmute"'))
        assertFalse(muted.contains('name="muteHours"'))
        String expired = SettingsPage.render(base + [mute: [until: 9 * H, by: 'emre', at: 8 * H]])
        assertFalse(expired.contains('data-action="unmute"'))
    }

    // controller: same request checks as a save, then the operation

    LoopTwoFixesTest harness = new LoopTwoFixesTest()
    List<Map> mails = []
    boolean smtpUp = true
    String partial = null

    Map post(Map<String, String> params) {
        harness.cypher.putIfAbsent(FormToken.KEY, 'test-key')
        params = params + [formToken: FormToken.issue('test-key', 1L, System.currentTimeMillis())]
        Map h = ['X-Requested-With': 'XMLHttpRequest', Host: 'vme.lab', Origin: 'https://vme.lab']
        Map<String, String[]> pm = params.findAll { it.value != null }.collectEntries { k, v -> [(k): [v] as String[]] }
        ViewModel<Map> m = new ViewModel<>()
        m.request = [getMethod: { 'POST' }, getHeader: { String n -> h[n] }, getParameterMap: { pm }] as HttpServletRequest
        m.user = new User(id: 5L, username: 'u', enabled: true, account: new Account(id: 1L), permissions: ['infrastructure-cluster': 'full'])
        m.object = [:]
        ApplianceMailer fake = [sendChecked: { List to, String subject, String html ->
            if (!smtpUp) throw new IOException('connect timed out')
            mails << [to: to, subject: subject]
            partial
        }] as ApplianceMailer
        def r = new HvmAlertController(morpheus: harness.ctx(), mailer: fake).settings(m)
        [status: r.status, data: r.data]
    }

    @Test
    void testMailGoesToTheFormRecipientsAndIsRateLimited() {
        harness.clusters[1L] = new ComputeServerGroup(id: 1L, name: 'c1', owner: new Account(id: 1L))
        HvmAlertController.lastTest.clear()
        HvmAlertController.userTests.clear()
        Map r = post([clusterId: '1', op: 'test', recipients: 'a@x.com, b@y.org'])
        assertTrue(r.data.msg, r.data.success)
        assertEquals([['a@x.com', 'b@y.org']], mails*.to)
        assertTrue(mails[0].subject.contains('c1: test mail'))
        assertEquals(429, post([clusterId: '1', op: 'test', recipients: 'a@x.com']).status)
        HvmAlertController.lastTest.clear()
        assertEquals(400, post([clusterId: '1', op: 'test', recipients: 'not-an-address']).status)
        assertEquals(400, post([clusterId: '1', op: 'test', recipients: '']).status)
        assertEquals(400, post([clusterId: '1', op: 'test', recipients: (1..11).collect { "u${it}@x.com" }.join(',')]).status)
        smtpUp = false
        Map f = post([clusterId: '1', op: 'test', recipients: 'a@x.com'])
        assertFalse(f.data.success)
        assertTrue(f.data.msg.contains('connect timed out'))
        smtpUp = true
        Map again = post([clusterId: '1', op: 'test', recipients: 'a@x.com'])
        assertTrue('a failed send does not hold the 30 s slot', again.data.success)
        HvmAlertController.lastTest.clear()
        partial = "delivered to [a@x.com]; refused: [typo@x.com]"
        Map half = post([clusterId: '1', op: 'test', recipients: 'a@x.com, typo@x.com'])
        assertFalse(half.data.success)
        assertTrue(half.data.msg.contains('refused: [typo@x.com]'))
        partial = null
        (1..10).each { HvmAlertController.lastTest.clear(); post([clusterId: '1', op: 'test', recipients: 'a@x.com']) }
        HvmAlertController.lastTest.clear()
        assertEquals('per-user hourly limit', 429, post([clusterId: '1', op: 'test', recipients: 'a@x.com']).status)
        assertNull('nothing stored', harness.cypher[TabSettings.key(1L)])
    }

    @Test
    void muteAndUnmuteWriteOnlyTheMuteKey() {
        harness.clusters[1L] = new ComputeServerGroup(id: 1L, name: 'c1', owner: new Account(id: 1L))
        assertEquals(400, post([clusterId: '1', op: 'mute', muteHours: '3']).status)
        long before = System.currentTimeMillis()
        Map r = post([clusterId: '1', op: 'mute', muteHours: '2'])
        assertTrue(r.data.msg, r.data.success)
        Map m = TabSettings.parseMute(harness.cypher[TabSettings.muteKey(1L)])
        assertEquals('u', m.by)
        assertTrue((m.until as long) >= before + 2 * H && (m.until as long) <= System.currentTimeMillis() + 2 * H)
        assertNull(harness.cypher[TabSettings.key(1L)])
        assertEquals('an active mute is not overwritten', 409, post([clusterId: '1', op: 'mute', muteHours: '24']).status)
        assertTrue(post([clusterId: '1', op: 'unmute']).data.success)
        assertNull(harness.cypher[TabSettings.muteKey(1L)])
    }

    /** The end mail compares with what the recipients heard before the mute; it starts from when the mute was set. */
    @Test
    void muteEndListsClearedProblemsAndTheMuteStart() {
        List<Map> sent = []
        AlertScheduler sch = AlertSettingsAndMailTest.scheduler(sent) { true }
        AlertSettings s = new AlertSettings(intervalMinutes: 2)
        AlertSettings.ClusterRule rule = new AlertSettings.ClusterRule(clusterId: 1L, name: 'C', recipients: ['a@x.com'], fingerprint: 'f1')
        s.rules << rule
        AlertCycle.Outcome o = new AlertCycle.Outcome(clusters: [(1L): 'C'])
        sch.state.update([problem('alarm:1', 'Old problem')], 0L)
        sch.notifyClusters(s, o, 0L)
        sch.notifyClusters(s, o, 0L)
        assertTrue(sent[0].html.contains('Old problem'))
        rule.muteUntil = 2 * H; rule.mutedBy = 'emre'; rule.mutedAt = 60000L
        sch.notifyClusters(s, o, 120000L)                                 // muted mail
        sch.state.update([problem('alarm:2', 'New problem')], 180000L)    // old cleared, new opened during the mute
        rule.muteUntil = null
        sch.notifyClusters(s, o, 240000L)
        Map end = sent.last()
        assertTrue(end.subject.contains('mute ended, 1 open problem(s)'))
        assertTrue(end.html.contains('muted from 1970-01-01 00:01 UTC'))
        assertTrue(end.html.contains('>NEW</td>') && end.html.contains('New problem'))
        assertTrue(end.html.contains('>RESOLVED</td>') && end.html.contains('Old problem'))
    }

    /** A cluster muted before its started mail and then no longer watched still gets the stopped mail. */
    @Test
    void stoppedMailAfterAMuteBeforeTheStartedMail() {
        List<Map> sent = []
        AlertScheduler sch = AlertSettingsAndMailTest.scheduler(sent) { true }
        AlertSettings s = new AlertSettings(intervalMinutes: 2)
        s.rules << new AlertSettings.ClusterRule(clusterId: 1L, name: 'C', recipients: ['a@x.com'], fingerprint: 'f1', muteUntil: H)
        sch.notifyClusters(s, new AlertCycle.Outcome(clusters: [(1L): 'C']), 0L)
        assertTrue(sent[0].subject.contains('alerts muted'))
        sch.notifyClusters(new AlertSettings(), new AlertCycle.Outcome(listed: [(1L): 'C']), 120000L)
        assertTrue(sent.last().subject.contains('monitor stopped'))
    }
}
