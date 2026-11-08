package com.hpe.hvmalert

import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import org.junit.Test

import static org.junit.Assert.*

/** 1.18.0: a save needs the token the tab rendered for that cluster (group access through the cluster page). */
class FormTokenTest {

    @Test
    void tokenBoundToClusterKeyAndTime() {
        String t = FormToken.issue('k', 1L, 1000L)
        assertTrue(FormToken.valid('k', 1L, t, 2000L))
        assertFalse('other cluster', FormToken.valid('k', 2L, t, 2000L))
        assertFalse('other tenant key', FormToken.valid('k2', 1L, t, 2000L))
        assertFalse('expired', FormToken.valid('k', 1L, t, 1000L + FormToken.TTL_MS + 1))
        assertFalse('no key yet', FormToken.valid(null, 1L, t, 2000L))
        ['', 'x', '1.2.3', 'abc.def', "${Long.MAX_VALUE}.00"].each { assertFalse(it, FormToken.valid('k', 1L, it, 2000L)) }
        assertFalse(FormToken.valid('k', 1L, null, 2000L))
    }

    @Test
    void saveWithoutOrWithForeignTokenIsRefused() {
        LoopTwoFixesTest t = new LoopTwoFixesTest()
        t.clusters[1L] = new ComputeServerGroup(id: 1L, name: 'c1', owner: new Account(id: 1L))
        t.cypher[FormToken.KEY] = 'test-key'
        Map none = t.post(LoopTwoFixesTest.form(formToken: ''))
        assertEquals(403, none.status)
        assertNull('nothing written', t.cypher[TabSettings.key(1L)])
        Map other = t.post(LoopTwoFixesTest.form(formToken: FormToken.issue('test-key', 2L, System.currentTimeMillis())))
        assertEquals(403, other.status)
        assertEquals(200, t.post(LoopTwoFixesTest.form()).status)
        assertNotNull(t.cypher[TabSettings.key(1L)])
    }

    @Test
    void tabCarriesTheToken() {
        String html = SettingsPage.render([clusterId: 1L, clusterName: 'c', url: '/u', formToken: '9.ab'])
        assertTrue(html.contains('<input type="hidden" name="formToken" value="9.ab">'))
    }
}
