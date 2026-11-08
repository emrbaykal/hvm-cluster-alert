package com.hpe.hvmalert

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.cypher.CypherAccess
import com.morpheusdata.core.synchronous.MorpheusSynchronousOperationNotificationService
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService
import com.morpheusdata.core.synchronous.cypher.MorpheusSynchronousCypherService
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.OperationNotification
import com.morpheusdata.model.User
import com.morpheusdata.views.ViewModel
import jakarta.servlet.http.HttpServletRequest
import org.junit.Test

import static org.junit.Assert.*

/** Loop 2 round 1 fix list (M1-M3, S1-S4); fakes after agent 2's harness. */
class LoopTwoFixesTest {

    Map<String, String> cypher = [:]
    Map<Long, ComputeServerGroup> clusters = [:]
    List<OperationNotification> alarms = []

    MorpheusContext ctx() {
        def cy = [read: { CypherAccess a, String k -> cypher[k] },
                  write: { CypherAccess a, String k, String v, Long l -> cypher[k] = v; v },
                  delete: { CypherAccess a, String k -> cypher.remove(k) != null }] as MorpheusSynchronousCypherService
        def cl = [get: { Long id -> clusters[id] }] as MorpheusSynchronousComputeServerGroupService
        def on = [list: { q -> alarms }] as MorpheusSynchronousOperationNotificationService
        def svc = [getCypher: { cy }, getCluster: { cl }, getOperationNotification: { on }] as MorpheusServices
        [getServices: { svc }] as MorpheusContext
    }

    Map post(Map<String, String> params) {
        cypher.putIfAbsent(FormToken.KEY, 'test-key')
        if (!params.containsKey('formToken')) params = params + [formToken: FormToken.issue('test-key', params.clusterId?.toLong(), System.currentTimeMillis())]
        Map h = ['X-Requested-With': 'XMLHttpRequest', Host: 'vme.lab', Origin: 'https://vme.lab']
        Map<String, String[]> pm = params.findAll { it.value != null }.collectEntries { k, v -> [(k): [v] as String[]] }
        ViewModel<Map> m = new ViewModel<>()
        m.request = [getMethod: { 'POST' }, getHeader: { String n -> h[n] }, getParameterMap: { pm }] as HttpServletRequest
        m.user = new User(id: 5L, username: 'u', enabled: true, account: new Account(id: 1L), permissions: ['infrastructure-cluster': 'full'])
        m.object = [:]
        def r = new HvmAlertController(morpheus: ctx()).settings(m)
        [status: r.status, data: r.data]
    }

    static Map form(Map extra = [:]) {
        [clusterId: '1', op: 'save', alertEnabled: 'on', recipients: 'ops@x.com', relayNativeAlarms: 'on', hostChecks: 'on', dailyStatus: 'on',
         agentWarnMinutes: '2', agentCriticalMinutes: '5', nodeUnreachableSeconds: '60', fencingWaitSeconds: '30'] + extra
    }

    @Test
    void m1TemplateThatDoesNotWatchIsRefusedBeforeAnyWrite() {
        clusters[1L] = new ComputeServerGroup(id: 1L, name: 'c1', owner: new Account(id: 1L))
        assertEquals(200, post(form(useForNew: 'on')).status)
        String own = cypher[TabSettings.key(1L)], tpl = cypher[TabSettings.TEMPLATE_KEY]
        assertNotNull(tpl)
        Map r = post(form(useForNew: 'on', alertEnabled: null))
        assertEquals(400, r.status)
        assertTrue(r.data.errors.containsKey('useForNew'))
        assertEquals('nothing written', own, cypher[TabSettings.key(1L)])
        assertEquals(tpl, cypher[TabSettings.TEMPLATE_KEY])
        Map removed = post(form())
        assertTrue(removed.data.msg.contains('no longer watched'))
        assertNull(cypher[TabSettings.TEMPLATE_KEY])
        assertTrue('pausing without the template stays possible', TabSettings.validate(form(alertEnabled: null, recipients: '')).errors.isEmpty())
        assertTrue(SettingsPage.script('f', '1', '/u', null, true).contains('removes the new-cluster template'))
    }

    @Test
    void m2OnlyAddressesTheMailerCanParse() {
        ['a@b.c.', 'a@b..c', '.a@b.c', 'a.@b.c', 'a:b@c.d', 'a<b@c.d', '"a@b.c', 'a@-b.c', 'a@b'].each {
            assertTrue(it, TabSettings.validate(form(recipients: it)).errors.containsKey('recipients'))
        }
        ['first.last+tag@sub.example.co.uk', "o'brien@x.ie", 'noc_team@corp-mail.com'].each {
            assertFalse(it, TabSettings.validate(form(recipients: it)).errors.containsKey('recipients'))
        }
    }

    AlertCycle cycle(AlertState st) {
        new AlertCycle(morpheus: ctx(), state: st)
    }

    @Test
    void m3AcknowledgedOpenAlarmStaysOpenUntilItClears() {
        ComputeServerGroup c = new ComputeServerGroup(id: 1L, name: 'c1')
        OperationNotification a = new OperationNotification(id: 9L, category: 'mvm.alarm.1', name: 'Host unreachable', status: 'error', active: true)
        alarms = [a]
        AlertState st = new AlertState()
        st.update(cycle(st).nativeAlarms([c], [:]), 0L)
        assertTrue(st.active.containsKey('alarm:9'))
        a.acknowledged = true
        st.update(cycle(st).nativeAlarms([c], [:]), 120000L)
        assertTrue('acknowledged = seen, not resolved', st.active.containsKey('alarm:9'))
        alarms = []
        st.update(cycle(st).nativeAlarms([c], [:]), 240000L)
        assertFalse(st.active.containsKey('alarm:9'))
        alarms = [new OperationNotification(id: 10L, category: 'mvm.alarm.1', name: 'old', status: 'warning', active: true, acknowledged: true)]
        assertEquals('acknowledged before the monitor saw it', [], cycle(new AlertState()).nativeAlarms([c], [:]))
    }

    static class RecordingMailer extends ApplianceMailer {
        List<List> sent = []
        @Override void send(List<String> to, String subject, String html) { sent << [to, subject] }
    }

    @Test
    void s1StoppedMailOnceToTheLastRecipients() {
        RecordingMailer m = new RecordingMailer()
        AlertScheduler s = new AlertScheduler(mailer: m, node: 'mgr')
        s.state.mail[1L] = new AlertState.ClusterMail(announced: 'f', recipients: ['ops@x.com'])
        s.state.mail[2L] = new AlertState.ClusterMail(announced: 'f', recipients: ['ops@x.com'])     // deleted cluster
        s.state.mail[3L] = new AlertState.ClusterMail(recipients: ['ops@x.com'])                     // never announced
        s.notifyClusters(new AlertSettings(), new AlertCycle.Outcome(listed: [1L: 'c1', 3L: 'c3']), 0L)
        assertEquals([[['ops@x.com'], '[HVM Cluster Alert] c1: monitor stopped']], m.sent)
        assertTrue(s.state.mail.isEmpty())
    }

    static class FakeSendFailedException extends RuntimeException {
        List validSentAddresses, invalidAddresses = ['bad@x.com'], validUnsentAddresses = []
    }
    static class FakeMailSendException extends RuntimeException {
        Map failedMessages
    }

    @Test
    void s2PartialDeliveryCountsAsSent() {
        def partial = new FakeMailSendException(failedMessages: [m: new FakeSendFailedException(validSentAddresses: ['ops@x.com'])])
        assertTrue(ApplianceMailer.partialDelivery(partial).contains('ops@x.com'))
        assertNull(ApplianceMailer.partialDelivery(new FakeMailSendException(failedMessages: [m: new FakeSendFailedException(validSentAddresses: [])])))
        assertNull(ApplianceMailer.partialDelivery(new IllegalStateException('no SMTP')))
    }

    @Test
    void s3WrongShapeIsIgnored() {
        assertNull(TabSettings.parse('{"version":1,"recipients":"ops@x.com"}'))
        assertNull(TabSettings.parse('{"version":1,"recipients":{"a":1}}'))
        assertNotNull(TabSettings.parse('{"version":1,"recipients":["ops@x.com"]}'))
        SettingsPage.render([clusterId: 1L, clusterName: 'c', url: '/u', own: TabSettings.parse('{"version":1,"recipients":{"a":1}}')])
    }

    @Test
    void s4MinimumsFollowHpeCadence() {
        assertTrue(TabSettings.validate(form(agentWarnMinutes: '1')).errors.containsKey('agentWarnMinutes'))
        assertTrue(TabSettings.validate(form(nodeUnreachableSeconds: '59')).errors.containsKey('nodeUnreachableSeconds'))
        assertTrue(TabSettings.validate(form(fencingWaitSeconds: '29')).errors.containsKey('fencingWaitSeconds'))
        assertEquals(60, TabSettings.ruleFromJson(1L, '{"version":1,"alertEnabled":true,"recipients":["a@x.com"],"nodeUnreachableSeconds":10}',
                AlertSettings.OWN_SOURCE).nodeUnreachableSeconds)
    }
}
