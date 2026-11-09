package com.hpe.hvmalert

import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.TaskResult
import org.junit.Test

import java.util.concurrent.TimeoutException

import static org.junit.Assert.*

/** 1.20.0: a host whose check hung and whose agent stays silent is not asked again (field, host powered off, 2026-11-09). */
class SilentHostSkipTest {

    static final long NOW = 10_000_000L
    ComputeServerGroup c = new ComputeServerGroup(id: 5L, name: 'C')
    AlertSettings.ClusterRule rule = new AlertSettings.ClusterRule(clusterId: 5L)

    ComputeServer host(long silentMs) {
        new ComputeServer(id: 3L, name: 'h3', powerState: ComputeServer.PowerState.on, lastAgentUpdate: new Date(NOW - silentMs))
    }

    @Test
    void hungCheckAndSilentAgentSkipsTheHost() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        AlertCycle.Work w = new AlertCycle.Work(cluster: c, rule: rule)
        cycle.hostResult(w, host(90_000L), new TimeoutException(), new AlertCycle.Outcome())
        assertTrue(cycle.state.hostNoAnswer.contains(3L))
        // agent silent 3 min (warning level): skipped now, still asked before the warning level
        assertFalse(cycle.managerHostChecks(c, host(180_000L), rule, NOW, new AlertCycle.Outcome()))
        assertTrue(cycle.managerHostChecks(c, host(60_000L), rule, NOW, new AlertCycle.Outcome()))
    }

    @Test
    void silentAgentWithoutAHungCheckIsStillAsked() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        assertTrue(cycle.managerHostChecks(c, host(180_000L), rule, NOW, new AlertCycle.Outcome()))
    }

    @Test
    void anAnswerOrACycleDeadlineClearsOrKeepsTheMark() {
        AlertCycle cycle = new AlertCycle(state: new AlertState())
        AlertCycle.Work w = new AlertCycle.Work(cluster: c, rule: rule)
        cycle.hostResult(w, host(0L), new AlertCycle.NotStarted(), new AlertCycle.Outcome())
        assertFalse('not started is not a hung agent', cycle.state.hostNoAnswer.contains(3L))
        cycle.hostResult(w, host(0L), new TimeoutException(), new AlertCycle.Outcome())
        cycle.hostResult(w, host(0L), new TaskResult(success: false, error: 'x'), new AlertCycle.Outcome())
        assertFalse('the agent answered', cycle.state.hostNoAnswer.contains(3L))
    }
}
