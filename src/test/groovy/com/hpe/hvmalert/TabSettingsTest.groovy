package com.hpe.hvmalert

import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import org.junit.Test

import static org.junit.Assert.*

class TabSettingsTest {

    static Map form(Map extra = [:]) {
        [clusterId: '7', op: 'save', alertEnabled: 'on', recipients: 'a@x.com; b@y.org a@x.com', relayNativeAlarms: 'on',
         agentWarnMinutes: '3', agentCriticalMinutes: '6', nodeUnreachableSeconds: '90', fencingWaitSeconds: '40'] + extra
    }

    static String stored(Map extra = [:], Map meta = [:]) {
        TabSettings.toJson(TabSettings.validate(form(extra)).values as Map, 'admin', 1000L, meta)
    }

    @Test
    void validFormIsTypedAndUnpostedBoxesAreOff() {
        Map r = TabSettings.validate(form(evil: '<script>', updatedBy: 'x', useForNew: 'on'))
        assertEquals([:], r.errors)
        Map v = r.values as Map
        assertEquals(true, v.alertEnabled)
        assertEquals(true, v.relayNativeAlarms)
        assertEquals('not posted = cleared', false, v.hostChecks)
        assertEquals(false, v.dailyStatus)
        assertEquals(['a@x.com', 'b@y.org'], v.recipients)
        assertEquals(3, v.agentWarnMinutes)
        assertFalse('unknown fields are dropped', ['evil', 'updatedBy', 'clusterId', 'useForNew'].any { v.containsKey(it) })
    }

    @Test
    void badInputIsRejected() {
        assertTrue(TabSettings.validate(form(recipients: 'a@x.com, nope')).errors.recipients.contains('nope'))
        assertTrue(TabSettings.validate(form(recipients: '')).errors.containsKey('recipients'))
        assertFalse('a cluster not watched needs no recipients', TabSettings.validate(form(recipients: '', alertEnabled: null)).errors.containsKey('recipients'))
        assertTrue(TabSettings.validate(form(agentWarnMinutes: '9', agentCriticalMinutes: '5')).errors.containsKey('agentCriticalMinutes'))
        assertTrue(TabSettings.validate(form(nodeUnreachableSeconds: '5')).errors.containsKey('nodeUnreachableSeconds'))
        assertTrue(TabSettings.validate(form(fencingWaitSeconds: '1e3')).errors.containsKey('fencingWaitSeconds'))
        assertTrue(TabSettings.validate(form(agentWarnMinutes: null)).errors.containsKey('agentWarnMinutes'))
        assertTrue(TabSettings.validate(form(recipients: (1..51).collect { "u$it@x.com" }.join(','))).errors.recipients.contains('50'))
    }

    @Test
    void storedJsonBecomesARule() {
        AlertSettings.ClusterRule r = TabSettings.ruleFromJson(7L, stored(), AlertSettings.OWN_SOURCE)
        assertEquals(AlertSettings.OWN_SOURCE, r.source)
        assertTrue(r.enabled)
        assertEquals(['a@x.com', 'b@y.org'], r.recipients)
        assertEquals(90, r.nodeUnreachableSeconds)
        assertFalse(r.hostChecks)
        String again = TabSettings.toJson(TabSettings.validate(form()).values as Map, 'other', 2000L)
        assertEquals('who and when do not change the fingerprint', r.fingerprint, TabSettings.ruleFromJson(7L, again, AlertSettings.OWN_SOURCE).fingerprint)
        assertNotEquals(r.fingerprint, TabSettings.ruleFromJson(7L, stored(fencingWaitSeconds: '41'), AlertSettings.OWN_SOURCE).fingerprint)
        assertNotEquals('own and template differ', r.fingerprint, TabSettings.ruleFromJson(7L, stored(), AlertSettings.TEMPLATE_SOURCE).fingerprint)
        assertNull(TabSettings.ruleFromJson(7L, 'not json', AlertSettings.OWN_SOURCE))
        assertNull('a foreign value at the key is ignored', TabSettings.ruleFromJson(7L, '{"a":1}', AlertSettings.OWN_SOURCE))
    }

    @Test
    void ownSettingsThenTemplateThenNotWatched() {
        String own = stored(recipients: 'own@x.com')
        String off = stored(alertEnabled: null)
        String tpl = stored([recipients: 'tpl@x.com'], [templateFrom: 3L, templateFromName: 'c3'])
        assertEquals(['own@x.com'], AlertSettings.ruleFor(7L, own, tpl).recipients)
        assertNull('own "watch" off wins over the template', AlertSettings.ruleFor(7L, off, tpl))
        AlertSettings.ClusterRule t = AlertSettings.ruleFor(7L, null, tpl)
        assertEquals(['tpl@x.com'], t.recipients)
        assertEquals(AlertSettings.TEMPLATE_SOURCE, t.source)
        assertNull('nothing saved, not watched', AlertSettings.ruleFor(7L, null, null))
    }

    /** A cluster created after the install is watched on the next cycle when a template exists. */
    @Test
    void clusterCreatedLaterUsesTheTemplate() {
        String tpl = stored([recipients: 'noc@x.com'], [templateFrom: 1L])
        String ownA = stored(recipients: 'a@x.com')
        Account t1 = new Account(id: 1L)
        ComputeServerGroup a = new ComputeServerGroup(id: 1L, name: 'a', owner: t1)
        ComputeServerGroup b = new ComputeServerGroup(id: 2L, name: 'b-created-later', owner: t1)
        Map<String, String> cypher = [(TabSettings.key(1L)): ownA, (TabSettings.TEMPLATE_KEY): tpl]
        int templateReads = 0
        AlertCycle cycle = new AlertCycle(cypherRead: { Account o, String k -> if (k == TabSettings.TEMPLATE_KEY) templateReads++; cypher[k] })
        AlertSettings s = new AlertSettings()
        Map<Long, String> templates = [:]
        List<ComputeServerGroup> w = AlertCycle.watched(s, [a, b], { ComputeServerGroup c -> cycle.readSettings(c, TabSettings.key(c.id)) },
                { ComputeServerGroup c -> if (!templates.containsKey(c.owner.id)) templates[c.owner.id] = cycle.readSettings(c, TabSettings.TEMPLATE_KEY); templates[c.owner.id] })
        assertEquals([1L, 2L], w*.id)
        assertEquals(['a@x.com'], s.ruleForId(1L).recipients)
        assertEquals(['noc@x.com'], s.ruleForId(2L).recipients)
        assertEquals('template read once per tenant per cycle', 1, templateReads)
    }

    @Test
    void failedCypherReadKeepsTheLastValue() {
        int calls = 0
        AlertCycle c = new AlertCycle(cypherRead: { Account o, String k -> if (++calls > 1) throw new IllegalStateException('down'); 'v1' })
        ComputeServerGroup g = new ComputeServerGroup(id: 7L, owner: new Account(id: 1L))
        assertEquals('v1', c.readSettings(g, 'k'))
        assertEquals('v1', c.readSettings(g, 'k'))
        assertNull(new AlertCycle(cypherRead: { Account o, String k -> throw new IllegalStateException('down') }).readSettings(g, 'k'))
        assertNull('no owner, nothing read', new AlertCycle(cypherRead: { Account o, String k -> 'x' }).readSettings(new ComputeServerGroup(id: 8L), 'k'))
    }

    static Expando req(String method, Map<String, String> headers, Map<String, String[]> params = [:]) {
        new Expando(method: method, parameterMap: params, getHeader: { String n -> headers[n] })
    }

    @Test
    void onlySameOriginAjaxPostsGetThrough() {
        Map ok = ['X-Requested-With': 'XMLHttpRequest', Host: 'vme.lab:443', Origin: 'https://vme.lab:443']
        assertNull(HvmAlertController.refusal(req('POST', ok)))
        assertNull('no Origin header (older browsers)', HvmAlertController.refusal(req('POST', ok.findAll { it.key != 'Origin' })))
        assertNull('behind a load balancer', HvmAlertController.refusal(req('POST', ok + [Host: 'app1:443', 'X-Forwarded-Host': 'vme.lab:443'])))
        assertNotNull(HvmAlertController.refusal(null))
        assertNotNull(HvmAlertController.refusal(req('GET', ok)))
        assertNotNull('plain cross-site form post', HvmAlertController.refusal(req('POST', ok.findAll { it.key != 'X-Requested-With' })))
        assertNotNull(HvmAlertController.refusal(req('POST', ok + [Origin: 'https://evil.example'])))
        assertNotNull(HvmAlertController.refusal(req('POST', ok + [Origin: 'not a uri ::'])))
    }

    @Test
    void paramsFromModelAndServlet() {
        Map p = HvmAlertController.params([clusterId: ['7'] as String[], op: 'grails-value', action: 'settings', controller: 'plugin'],
                req('POST', [:], [op: ['save'] as String[], recipients: ['a@x.com'] as String[]]))
        assertEquals('7', p.clusterId)
        assertEquals('the posted value wins over the Grails map', 'save', p.op)
        assertEquals('a@x.com', p.recipients)
    }

    @Test
    void tabShowsSettingsAndEscapes() {
        // raw map: parse() would refuse this recipient (1.15.0), render must still escape it
        Map own = (Map) new groovy.json.JsonSlurper().parseText(TabSettings.toJson([version: 1, alertEnabled: true, recipients: ['"><img src=x onerror=alert(1)>@x.com'],
                agentWarnMinutes: 2, agentCriticalMinutes: 5, nodeUnreachableSeconds: 60, fencingWaitSeconds: 30], '<b>u</b>', 0L))
        Map f = [clusterId: 7L, clusterName: 'C<x>', url: HvmAlertController.URL, nonce: 'n0"nce', own: own,
                 template: [version: 1, templateFrom: 7L, templateFromName: 'C<x>', alertEnabled: true]]
        String html = SettingsPage.render(f)
        assertFalse(html.contains('<img'))
        assertFalse(html.contains('<b>u</b>'))
        assertFalse(html.contains('C<x>'))
        assertTrue(html.contains('nonce="n0&quot;nce"'))
        assertTrue(html.contains('name="alertEnabled" checked'))
        assertTrue('this cluster is the template source', html.contains('name="useForNew" checked'))
        assertTrue(html.contains('btn-default" data-action="reset"'))
        assertTrue('watched, no check yet', html.contains('No check result yet'))
        String fresh = SettingsPage.render([clusterId: 9L, clusterName: 'new', url: HvmAlertController.URL])
        assertTrue(fresh.contains('Not watched yet'))
        assertFalse(fresh.contains('btn-default" data-action="reset"'))
        String viaTemplate = SettingsPage.render([clusterId: 9L, clusterName: 'new', url: HvmAlertController.URL,
                template: [version: 1, templateFrom: 7L, templateFromName: 'p<r>od', alertEnabled: true, recipients: ['n@x.com']]])
        assertTrue(viaTemplate.contains('new-cluster template of cluster p&lt;r&gt;od'))
        assertTrue(viaTemplate.contains('now from cluster p&lt;r&gt;od'))
        assertFalse(viaTemplate.contains('name="useForNew" checked'))
        assertEquals('"\\u003c/script\\u003e\\""', SettingsPage.js('</script>"'))
    }
}
