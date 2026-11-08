package com.hpe.hvmalert

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import org.junit.Test

import static org.junit.Assert.*

/** Loop 2 round 3 fix list (M1-M3, S1). */
class LoopTwoRoundThreeTest {

    List<List> released = []

    MorpheusContext lockCtx(List<Object> checks, Object acquire) {
        List<Object> q = new ArrayList(checks)
        [checkLock: { String n, Map o -> def v = q ? q.remove(0) : null; if (v instanceof Throwable) throw v; v == null ? Maybe.empty() : Maybe.just(v) },
         releaseLock: { String n, Map o -> released << [o.lock]; Single.just(false) },
         acquireLock: { String n, Map o -> if (acquire instanceof Throwable) throw acquire; acquire == null ? Single.error(new NullPointerException('item is null')) : Single.just(acquire) }] as MorpheusContext
    }

    @Test
    void m1RenewalThatFailsTakesTheTruthFromCheckLock() {
        AlertScheduler a = new AlertScheduler(morpheus: lockCtx(['A', 'X'], new NullPointerException('item is null')), lockId: 'A')
        assertEquals(AlertScheduler.Leadership.LEADER, a.ensureLeader(360000L))
        assertEquals('X', a.lockId)
        AlertScheduler b = new AlertScheduler(morpheus: lockCtx(['A', null], null), lockId: 'A')
        assertEquals(AlertScheduler.Leadership.FOLLOWER, b.ensureLeader(360000L))
        assertNull(b.lockId)
        released.clear()
        AlertScheduler c = new AlertScheduler(morpheus: lockCtx([new IllegalStateException('store down')], 'B'), lockId: 'A')
        assertEquals(AlertScheduler.Leadership.UNKNOWN, c.ensureLeader(360000L))
        assertEquals('A', c.lockId)
        assertEquals('nothing released when the store is unreachable', [], released)
    }

    @Test
    void m1StopReleasesStoredAndHeldIds() {
        AlertScheduler d = new AlertScheduler(morpheus: lockCtx(['X'], 'B'), lockId: 'A')
        d.releaseLeadership()
        assertEquals([['A'], ['X']], released)
        assertNull(d.lockId)
    }

    @Test
    void m2RfcAtextLocalPartsAreAccepted() {
        ['ops#1@x.com', 'a&b@x.com', 'team/noc@x.com', 'x=y@x.com', 'ops!@x.com'].each {
            assertFalse(it, TabSettings.validate(LoopTwoFixesTest.form(recipients: it)).errors.containsKey('recipients'))
            assertNotNull(it, TabSettings.parse("{\"version\":1,\"recipients\":[\"${it}\"]}"))
        }
        ['a@b.c.', 'a@b..c', '.a@b.c', 'a.@b.c', 'a:b@c.d', 'a<b@c.d', '"a@b.c', 'a@-b.c', 'a@b'].each {
            assertTrue(it, TabSettings.validate(LoopTwoFixesTest.form(recipients: it)).errors.containsKey('recipients'))
        }
    }

    @Test
    void m3StorageLossInAnotherClusterDoesNotSuppress() {
        AlertCycle.Outcome o = new AlertCycle.Outcome(findings: [
                new Finding(key: 'hc:9:gfs2:cls:ds01', severity: Finding.CRITICAL, clusterId: 77L, resource: 'other-cluster-host', source: 'host')])
        new AlertCycle(state: new AlertState()).evaluateApd(LoopTwoRoundTwoTest.work(1, 'vmenode01', 6), o, 1050000L)
        assertEquals(1, LoopTwoRoundTwoTest.apd(o).size())
    }

    @Test
    void s1CarriedPendingExpiresAfterTenMinutes() {
        Finding f = new Finding(key: 'hc:4:gfs2-mounts', severity: Finding.CRITICAL, clusterId: 1L, title: 't', source: 'host', confirmCycles: 2)
        AlertState st = new AlertState()
        st.update([f], 0L)
        (1..720).each { st.update([], it * 120000L, ['hc:4:'] as Set) }     // 24 h in maintenance
        st.update([f], 721 * 120000L)
        assertFalse('one late sighting after a day does not open', st.active.containsKey(f.key))
    }
}
