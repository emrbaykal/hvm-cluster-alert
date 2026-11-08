package com.hpe.hvmalert

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.OperationNotification
import com.morpheusdata.model.TaskResult
import groovy.util.logging.Slf4j

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * One pass over the watched HVM clusters. Reads only; writes nothing to Morpheus or the hosts.
 *
 * Sources:
 *  1. Native HVM alarms (OperationNotification, category "mvm.alarm.<clusterId>", seen on VME 9.0.2).
 *  2. Manager-side state the native alarms do not cover: agent silence (HPE: 2 / 5 min), power state,
 *     cluster member status, datastore offline or in warning/failed state. Memory, CPU and datastore
 *     capacity are left to the native alarms (9.1: 15-minute averages, 80/90 % capacity).
 *  3. Host-side checks through the agent (HostCheck), all hosts of all clusters in one pool under one
 *     deadline, so a cycle stays well inside the leader lock TTL even when many agents hang.
 *
 * Hosts in maintenance (HPE: evacuated, may be powered off, stay quorum members, host_maintenance.rst)
 * are not checked, and what other hosts report about them (peer unreachable, nodes missing) is dropped.
 * Everything is grouped by cluster id; the name is only shown.
 */
@Slf4j
class AlertCycle {

    static final String CLUSTER_TYPE = 'mvm-cluster'
    static final String HOST_TYPE = 'mvmHost'
    static final String GFS2_DATASTORE_TYPE = 'libvirt-dir-gfs2'
    static final long HOST_CHECK_TIMEOUT_S = 60
    static final long CYCLE_DEADLINE_S = 75
    static final int HOST_CHECK_THREADS = 32      // threads only wait on agent replies; 16 hosts per cluster max
    static final Set<String> PEER_LOSS_RULES = ['peer-unreachable', 'fence-ack', 'failover'] as Set

    /** A host check that was cancelled at the cycle deadline before it started. */
    static class NotStarted extends RuntimeException {
        NotStarted() { super('not started before the cycle deadline') }
    }

    MorpheusContext morpheus
    AlertState state
    Closure<Boolean> cancelled = { false }     // true once the plugin is being unloaded
    Closure<String> cypherRead = { Account owner, String key -> null }   // TabSettings.read in the plugin
    Map<String, String> readCache = [:]        // last Cypher value per "owner:key", used when a read fails

    static class Outcome {
        Map<Long, String> clusters = [:]        // watched cluster id -> current name
        Map<Long, String> listed = [:]          // every HVM cluster id -> name, watched or not
        List<Finding> findings = []
        List<Finding> events = []
        Set<String> carry = [] as Set
        Set<Long> carryClusters = [] as Set
        Set<List> carryScoped = [] as Set       // [cluster id, key prefix] kept for one cluster only
    }

    /** What one cluster needs between the manager pass and the host results. */
    static class Work {
        ComputeServerGroup cluster
        AlertSettings.ClusterRule rule
        List<ComputeServer> runHosts = []
        List<Map> maintenanceHosts = []          // [id, names (short), off (power not on)]
        int expectedGfs2
        List<Map> apdCandidates = []             // consecutive heartbeat-failure runs found this cycle
    }

    /**
     * Gives every HVM cluster listed in this cycle its rule: its own tab settings, else the new-cluster template. The
     * cluster list and the settings are read every cycle, so a cluster created after the install is watched from its
     * first cycle when a template exists.
     */
    static List<ComputeServerGroup> watched(AlertSettings s, List<ComputeServerGroup> all, Closure<String> own, Closure<String> template) {
        all.findAll { ComputeServerGroup c ->
            AlertSettings.ClusterRule r = AlertSettings.ruleFor(c.id, own.call(c), template.call(c))
            if (r != null) s.rules << r
            r != null
        }
    }

    /** A Cypher value; on a read error the last value read, so a Cypher hiccup does not stop or switch the alerts. */
    String readSettings(ComputeServerGroup c, String key) {
        String cacheKey = "${c.owner?.id}:${key}"
        try {
            String v = c.owner == null ? null : cypherRead.call(c.owner, key)
            readCache[cacheKey] = v
            return v
        } catch (Throwable t) {
            log.warn("[hvm-cluster-alert] ${key} not readable, using the last value read: ${t.class.simpleName}: ${t.message}")
            return readCache[cacheKey]
        }
    }

    Outcome run(AlertSettings s, long nowMs) {
        Outcome o = new Outcome()
        List<ComputeServerGroup> all = morpheus.services.cluster.list(new DataQuery().withFilter('type.code', CLUSTER_TYPE))
        all.each { o.listed[it.id] = it.name }
        Map<Long, String> templates = [:]          // one template read per owner tenant per cycle
        List<ComputeServerGroup> clusters = watched(s, all, { ComputeServerGroup c -> readSettings(c, TabSettings.key(c.id)) },
                { ComputeServerGroup c ->
                    Long owner = c.owner?.id
                    if (!templates.containsKey(owner)) templates[owner] = readSettings(c, TabSettings.TEMPLATE_KEY)
                    templates[owner]
                })
        clusters.each { s.ruleForId(it.id).name = it.name; o.clusters[it.id] = it.name }

        List<Work> work = []
        clusters.each { ComputeServerGroup c ->
            if (cancelled.call()) return
            Work w = new Work(cluster: c, rule: s.ruleForId(c.id))
            guarded(c, o) { prepare(w, nowMs, o); work << w }
        }
        Map<Long, Integer> expected = [:]
        work.each { Work w -> w.runHosts.each { expected[it.id] = w.expectedGfs2 } }
        Map<Long, Object> results = runHostCommands(work.collectMany { it.runHosts }, expected)
        work.each { Work w ->
            if (cancelled.call()) return
            guarded(w.cluster, o) { finish(w, results, o) }
        }

        Map<Long, Set<Long>> maintenance = work.collectEntries { [(it.cluster.id): it.maintenanceHosts.collect { m -> m.id as Long } as Set] }
        List<ComputeServerGroup> relay = clusters.findAll { s.ruleForId(it.id).relayNativeAlarms }
        if (relay) {
            try {
                o.findings.addAll(nativeAlarms(relay, maintenance))
            } catch (Throwable t) {
                log.error("[hvm-cluster-alert] native alarms not readable: ${t.class.simpleName}: ${t.message}")
                o.carry << 'alarm:'
            }
        }
        return o
    }

    /** A failing cluster keeps its open problems (carried) and gets one "check failed" warning. */
    void guarded(ComputeServerGroup c, Outcome o, Closure body) {
        try {
            body.call()
        } catch (Throwable t) {
            log.error("[hvm-cluster-alert] cluster ${c.name}: ${t.class.simpleName}: ${t.message}", t)
            o.carryClusters << c.id
            o.findings << new Finding(key: "cluster:${c.id}:check", severity: Finding.WARNING, clusterId: c.id, cluster: c.name,
                    resource: c.name, title: 'Cluster check failed', detail: "${t.class.simpleName}: ${t.message}".toString().take(300), source: 'manager')
        }
    }

    /** Manager-side checks and the list of hosts whose host checks should run. */
    void prepare(Work w, long nowMs, Outcome o) {
        ComputeServerGroup c = w.cluster
        List<ComputeServer> hosts = morpheus.services.computeServer.list(new DataQuery().withFilter('serverGroup.id', c.id))
                .findAll { it.computeServerType?.code == HOST_TYPE }
        List<Datastore> stores = null
        try {
            stores = morpheus.services.cloud.datastore.list(new DataQuery().withFilters(
                    new DataFilter('refType', 'ComputeServerGroup'), new DataFilter('refId', c.id)))
        } catch (Throwable t) {
            // keep checking the hosts; datastore problems stay as they were
            log.error("[hvm-cluster-alert] cluster ${c.name}: datastores not readable: ${t.class.simpleName}: ${t.message}")
            o.carryScoped << [c.id, 'ds:']
            o.findings << new Finding(key: "cluster:${c.id}:datastores", severity: Finding.WARNING, clusterId: c.id, cluster: c.name,
                    resource: c.name, title: 'Datastore state not readable', detail: "${t.class.simpleName}: ${t.message}".toString().take(300),
                    source: 'manager')
        }
        if (stores != null) {
            // an offline datastore has its own alert, and a datastore in maintenance is not expected mounted;
            // inactive ones count (a heartbeat datastore is often inactive for placement but mounted)
            w.expectedGfs2 = stores.count {
                it.datastoreType?.code == GFS2_DATASTORE_TYPE && it.online != false && it.status?.toLowerCase() != 'maintenance'
            } as int
            state.lastGfs2[c.id] = w.expectedGfs2
        } else {
            w.expectedGfs2 = state.lastGfs2[c.id] ?: 0
        }
        log.debug("[hvm-cluster-alert] cluster ${c.name}: ${hosts.size()} hosts, ${w.expectedGfs2} GFS2 datastores expected")
        hosts.each { ComputeServer h ->
            if (inMaintenance(h)) {
                w.maintenanceHosts << [id: h.id, names: [h.name, h.hostname].findAll { it }.collect { shortName(it as String) } as Set,
                                       ips: [h.externalIp, h.internalIp].findAll { it } as Set, off: h.powerState?.toString() != 'on']
                o.carry << "hc:${h.id}:".toString()
                o.carry << "host:${h.id}:".toString()
                return
            }
            boolean reachable = managerHostChecks(c, h, w.rule, nowMs, o)
            if (w.rule.hostChecks) {
                if (reachable) w.runHosts << h
                else o.carry << "hc:${h.id}:".toString()
            }
        }
        stores?.each { Datastore d -> datastoreChecks(c, d, o) }
    }

    void finish(Work w, Map<Long, Object> results, Outcome o) {
        w.runHosts.each { ComputeServer h -> hostResult(w, h, results[h.id], o) }
        if (w.maintenanceHosts) dropMaintenanceFindings(w.cluster.id, w.maintenanceHosts, o.findings)
        evaluateApd(w, o, System.currentTimeMillis())
    }

    /**
     * Other hosts still see a host in maintenance as a quorum member (host_maintenance.rst:119-121). What they
     * report about it is dropped: findings naming it, and node-count findings up to the number of
     * maintenance hosts that are themselves unreachable (powered off, or named down or unreached by a peer).
     */
    static void dropMaintenanceFindings(Long clusterId, List<Map> maintenanceHosts, List<Finding> findings) {
        List<Finding> mine = findings.findAll { it.clusterId == clusterId && it.key.startsWith('hc:') }
        List<String> named = (mine.collectMany { (it.meta.hosts ?: []) as List } +
                mine.findAll { it.meta.peer }.collect { it.meta.peer }).collect { it as String }
        Set<String> namedIps = mine.collectMany { (it.meta.addresses ?: []) as List }.collect { it.toString() } as Set
        def isMaint = { String name, List addresses ->
            maintenanceHosts.any { m -> (m.names as Set).any { sameHost(name, it as String) } || ((m.ips ?: []) as Set).any { ip -> (addresses ?: []).contains(ip) } }
        }
        int unreachable = maintenanceHosts.count { m ->
            m.off || (m.names as Set).any { mn -> named.any { sameHost(it, mn as String) } } || ((m.ips ?: []) as Set).any { namedIps.contains(it) }
        } as int
        findings.removeIf { Finding f ->
            if (f.clusterId != clusterId || !f.key.startsWith('hc:')) return false
            if (f.meta.peer) return isMaint(f.meta.peer as String, (f.meta.addresses ?: []) as List)
            if (f.meta.missing != null) return (f.meta.missing as int) <= unreachable
            if (f.meta.hosts) return (f.meta.hosts as List).every { isMaint(it as String, []) }
            false
        }
    }

    static final Set<String> STORAGE_EVENT_RULES = ['apd-budget', 'apd-shutdown', 'apd'] as Set

    /**
     * Possible APD, derived from a consecutive run of "on all heartbeat targets" failures on one host
     * (HPE: VMs destroyed at 6 consecutive failures, 9 during stretch arbitration). It matters where the agent's
     * own APD text is absent (agent 4.x: unseen). Suppressed only by evidence that explains THIS host's failures:
     * - a peer loss this host itself reported (events and peer/reachable/down-hosts findings keyed to this host):
     *   DLM holds GFS2 until the fence and survivors log these lines in a normal host loss (failure_scenarios.rst:
     *   20-29; seen in field HA tests). The survivors' reports of this host's own loss do not count:
     *   a storage-partitioned host reboots itself and is read late (storage-cut test);
     * - storage loss on another host of the cluster while this host has none (a peer's storage stall blocks the
     *   shared heartbeat datastore; seen in the storage-cut test).
     * A WARNING otherwise; CRITICAL when the same host also has all multipath paths down or a withdrawn GFS2.
     */
    void evaluateApd(Work w, Outcome o, long nowMs) {
        Long cid = w.cluster.id
        List<Finding> known = (o.findings + (state.active.values() as List)).findAll { it.clusterId == cid }
        w.apdCandidates.each { Map cand ->
            int limit = cand.stretch ? AlertState.APD_FAILURES_STRETCH : AlertState.APD_FAILURES
            if ((cand.count as int) < limit) return
            ComputeServer h = cand.host as ComputeServer
            long first = cand.first as long, last = cand.last as long
            state.markApdReported(h.id, last)
            String p = "hc:${h.id}:"
            Closure<Boolean> inWindow = { Long t -> t != null && t >= first - AlertState.PEER_LOSS_WINDOW_MS && t <= last + AlertState.PEER_LOSS_WINDOW_MS }
            boolean storageDown = known.any {
                (it.key.startsWith(p + 'mpath:') && it.severity == Finding.CRITICAL) || it.key.startsWith(p + 'gfs2:')
            }
            // peer loss reported by this host itself; a peer that has been down for hours explains nothing
            List<Long> ownLoss = o.events.findAll { it.clusterId == cid && it.key.startsWith("event:${h.id}:") && PEER_LOSS_RULES.contains(it.meta.rule) }
                    .collect { it.since?.time }
            o.findings.findAll { it.clusterId == cid && it.key ==~ /${java.util.regex.Pattern.quote(p)}(reachable|down-hosts|peer:.*)/ }
                    .each { ownLoss << (state.firstSeen[it.key] ?: nowMs) }
            boolean peerLoss = ownLoss.any(inWindow)
            if (peerLoss && !storageDown) {
                log.info("[hvm-cluster-alert] host ${h.name}: ${cand.count} heartbeat write failures during a peer loss; not an APD")
                return
            }
            // storage loss on another host of the cluster
            String other = null
            if (!storageDown) {
                Finding sf = known.find {
                    it.key ==~ /hc:\d+:(gfs2:|mpath:).*/ && !it.key.startsWith(p) && (it.key.contains(':gfs2:') || it.severity == Finding.CRITICAL) &&
                            inWindow(state.firstSeen[it.key] ?: nowMs)
                } ?: o.events.find {
                    it.clusterId == cid && !it.key.startsWith("event:${h.id}:") && STORAGE_EVENT_RULES.contains(it.meta.rule) && inWindow(it.since?.time)
                }
                other = sf?.resource
            }
            if (other != null) {
                log.info("[hvm-cluster-alert] host ${h.name}: ${cand.count} heartbeat write failures while ${other} lost its storage; not an APD of this host")
                return
            }
            o.events << new Finding(key: "event:${h.id}:apd:${last}", severity: storageDown ? Finding.CRITICAL : Finding.WARNING,
                    clusterId: cid, cluster: w.cluster.name, resource: h.name,
                    title: storageDown ? 'APD likely: heartbeat writes failing and storage paths down' : 'Possible APD',
                    detail: "${cand.count} consecutive heartbeat write failures on all heartbeat targets${peerLoss ? '' : ' with no peer loss reported by this host'}. ".toString() +
                            "HPE: the host destroys its non-pinned VMs at ${limit} consecutive failures. Derived from the agent log; unverified rule.",
                    source: 'event', since: new Date(last), preStart: cand.preStart == true, meta: [rule: 'apd-derived'])
        }
    }

    static boolean inMaintenance(ComputeServer h) {
        String st = h.status?.toLowerCase()
        st in ['maintenance', 'maintenancing'] || h.serverGroupMemberStatus == ComputeServer.ServerGroupMemberStatus.maintenance
    }

    static String shortName(String n) {
        (n ?: '').toLowerCase().split(/\./)[0]
    }

    /** A quorum peer name matches a host name as-is or with the "-<dlmNodeId>" suffix the agent adds
     *  (vmenode02-2 for vmenode02, VME 9.0.2); "node-2" never matches "node-1". */
    static boolean sameHost(String peer, String host) {
        String p = shortName(peer), h = shortName(host)
        p && h && (p == h || (p.startsWith(h + '-') && p.substring(h.length() + 1).isInteger()))
    }

    /** @return true when the host is on and its agent reports, so host checks can run */
    boolean managerHostChecks(ComputeServerGroup c, ComputeServer h, AlertSettings.ClusterRule s, long nowMs, Outcome o) {
        boolean ok = true
        String power = h.powerState?.toString()
        if (power != 'on') {
            o.findings << new Finding(key: "host:${h.id}:power", severity: Finding.CRITICAL, clusterId: c.id, cluster: c.name, resource: h.name,
                    title: "Host power state ${power ?: 'unknown'}", detail: 'HPE: off or unknown requires investigation.', source: 'manager')
            ok = false
        }
        String st = h.status?.toLowerCase()
        if (st in ['warning', 'error', 'failed']) {
            // the manager's own view; it still works when the host's agent hangs (storage-partition test: "Error loading
            // hypervisor storage pools: Timed out" while the agent no longer answered commands, VME lab 2026-11-07)
            o.findings << new Finding(key: "host:${h.id}:status", severity: st == 'warning' ? Finding.WARNING : Finding.CRITICAL,
                    clusterId: c.id, cluster: c.name, resource: h.name, title: "Host status ${st}",
                    detail: (h.statusMessage ?: '').take(300), source: 'manager')
        }
        ComputeServer.ServerGroupMemberStatus member = h.serverGroupMemberStatus
        if (member in [ComputeServer.ServerGroupMemberStatus.offline, ComputeServer.ServerGroupMemberStatus.unclean,
                       ComputeServer.ServerGroupMemberStatus.fenced]) {
            o.findings << new Finding(key: "host:${h.id}:member", severity: Finding.CRITICAL, clusterId: c.id, cluster: c.name, resource: h.name,
                    title: "Cluster member status ${member}", detail: 'Manager view of this host in its cluster.', source: 'manager')
        }
        Date last = h.lastAgentUpdate
        if (last != null) {
            long minutes = (nowMs - last.time).intdiv(60000L)
            if (minutes >= s.agentWarnMinutes) {
                boolean crit = minutes >= s.agentCriticalMinutes
                // HPE's 5 min is the alert threshold and opens at once; the 2 min warning is a concern level
                // (monitoring.rst:116-118 vs 239-241) and needs two cycles, so one late check-in mails nothing
                o.findings << new Finding(key: "host:${h.id}:agent", severity: crit ? Finding.CRITICAL : Finding.WARNING,
                        clusterId: c.id, cluster: c.name, resource: h.name, title: "Agent silent for ${minutes} min",
                        detail: "Last agent update ${last.toInstant()}. HPE: > 2 min agent or network issue, > 5 min agent probably down.",
                        source: 'manager', confirmCycles: crit ? 1 : 2)
                if (crit) ok = false
            }
        }
        return ok
    }

    /**
     * Runs the host script on every host in parallel, as root through the agent (sudo=true, the way the
     * agent runs its own commands). All hosts share one deadline.
     * @return host id -> TaskResult or the Throwable it failed with
     */
    Map<Long, Object> runHostCommands(List<ComputeServer> hosts, Map<Long, Integer> expectedGfs2) {
        if (!hosts) return [:]
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(HOST_CHECK_THREADS, hosts.size()), { Runnable r ->
            Thread t = new Thread(r, 'hvm-cluster-alert-host')
            t.daemon = true
            t
        } as ThreadFactory)
        Map<Long, Object> out = [:]
        Set<Long> started = Collections.synchronizedSet(new HashSet<Long>())
        try {
            Map<Long, Future> futures = hosts.collectEntries { ComputeServer h ->
                List pos = state.logPos[h.id]
                String script = HostCheck.script(pos ? pos[0] as String : null, pos ? pos[1] as Long : -1L, expectedGfs2[h.id] ?: 0)
                [(h.id): pool.submit({
                    started << h.id
                    morpheus.executeCommandOnServer(h, script, false, null, null, null, null, null, false, true)
                            .timeout(HOST_CHECK_TIMEOUT_S, TimeUnit.SECONDS).blockingGet()
                } as Callable)]
            }
            long deadline = System.currentTimeMillis() + CYCLE_DEADLINE_S * 1000L
            boolean interrupted = false
            futures.each { Long id, Future f ->
                if (interrupted) {
                    f.cancel(true)
                    out[id] = new InterruptedException('plugin stopping')
                    return
                }
                try {
                    out[id] = f.get(Math.max(1L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                } catch (InterruptedException e) {
                    interrupted = true
                    f.cancel(true)
                    out[id] = e
                } catch (Throwable t) {
                    f.cancel(true)
                    out[id] = started.contains(id) ? t : new NotStarted()
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    void hostResult(Work w, ComputeServer h, Object result, Outcome o) {
        ComputeServerGroup c = w.cluster
        AlertSettings.ClusterRule s = w.rule
        int expectedGfs2 = w.expectedGfs2
        String prefix = "hc:${h.id}:"
        TaskResult tr = result instanceof TaskResult ? (TaskResult) result : null
        if (result instanceof Throwable) {
            Throwable t = (Throwable) result
            log.warn("[hvm-cluster-alert] host ${h.name}: command failed: ${t.class.simpleName}: ${t.message}")
        }
        String out = tr?.output ?: (tr?.data instanceof String ? tr.data : null)
        if (tr == null || !out) {
            String why = tr ? (tr.error ?: tr.msg ?: 'no output from the agent') :
                    (result instanceof NotStarted ? ((Throwable) result).message :
                     result instanceof Throwable ? "no answer from the agent within ${HOST_CHECK_TIMEOUT_S} s" : 'no output from the agent')
            o.findings << new Finding(key: "${prefix}exec", severity: Finding.WARNING, clusterId: c.id, cluster: c.name, resource: h.name,
                    title: 'Host checks could not run', detail: why.toString().take(300), source: 'host')
            o.carry << prefix
            return
        }
        HostCheck.Result r = HostCheck.parse(out, [cluster: c.name, clusterId: c.id, hostId: h.id, hostName: h.name,
                                                   nodeUnreachableSeconds: s.nodeUnreachableSeconds,
                                                   fencingWaitSeconds: s.fencingWaitSeconds,
                                                   expectedGfs2: expectedGfs2,
                                                   lastQuorumLine: state.quorumLine[h.id]])
        log.debug("[hvm-cluster-alert] host ${h.name}: busy=${r.busy} priv=${r.privilege} clustered=${r.clustered} complete=${r.complete} findings=${r.findings.size()} events=${r.events.size()}")
        if (r.busy) {
            log.warn("[hvm-cluster-alert] host ${h.name}: the previous check is still running; skipped")
            o.findings.addAll(r.findings)
            o.carry << prefix
            return
        }
        boolean firstRead = !state.logPos.containsKey(h.id)
        if (firstRead) log.info("[hvm-cluster-alert] host ${h.name}: host checks run as ${r.privilege}")
        if (r.complete && r.logInode != null) state.logPos[h.id] = [r.logInode, r.logSize]
        if (r.quorumLine) state.quorumLine[h.id] = r.quorumLine
        o.findings.addAll(r.findings)
        if (!r.complete) o.carry << prefix
        state.newEvents(prefix, r.events, r.hostNow).each { Map e ->
            o.events << new Finding(key: "event:${h.id}:${e.ts}", severity: e.severity, clusterId: c.id, cluster: c.name, resource: h.name,
                    title: e.title, detail: e.line, source: 'event', since: new Date(HostCheck.parseTime(e.ts as String)),
                    preStart: e.preStart == true, meta: [rule: e.rule])
        }
        long hostNow = HostCheck.parseTime(r.hostNow) ?: System.currentTimeMillis()
        List<Long> failures = r.heartbeatFailureTimes.collect { HostCheck.parseTime(it) }.findAll { it != null && it >= hostNow - AlertState.EVENT_LOOKBACK_MS }
        List<Long> run = state.heartbeatRun(h.id, failures)
        if (run) w.apdCandidates << [host: h, count: run[0], first: run[1], last: run[2], stretch: r.stretch, preStart: firstRead]
    }

    void datastoreChecks(ComputeServerGroup c, Datastore d, Outcome o) {
        String status = d.status?.toLowerCase()
        Map base = [key: "ds:${d.id}:state".toString(), clusterId: c.id, cluster: c.name, resource: d.name, source: 'manager']
        if (d.online == false) {
            o.findings << new Finding(base + [severity: Finding.CRITICAL, title: 'Datastore offline',
                    detail: "status ${d.status}${d.statusMessage ? ': ' + d.statusMessage : ''}".toString()])
        } else if (status in ['failed', 'error', 'offline']) {
            o.findings << new Finding(base + [severity: Finding.CRITICAL, title: "Datastore ${status}".toString(), detail: d.statusMessage ?: ''])
        } else if (status == 'warning') {
            o.findings << new Finding(base + [severity: Finding.WARNING, title: 'Datastore warning', detail: d.statusMessage ?: ''])
        }
    }

    /** @param maintenance cluster id -> ids of its hosts in maintenance (their alarms are expected) */
    List<Finding> nativeAlarms(List<ComputeServerGroup> clusters, Map<Long, Set<Long>> maintenance) {
        Map<String, ComputeServerGroup> byId = clusters.collectEntries { [(it.id.toString()): it] }
        List<OperationNotification> alarms = morpheus.services.operationNotification.list(new DataQuery().withFilter('active', true))
        alarms.findResults { OperationNotification a ->
            String cat = a.category ?: ''
            if (!cat.startsWith('mvm.alarm.')) return null
            // Acknowledged means seen, not resolved (operations/alarms.rst:34): an alarm already open stays open until it
            // clears; one acknowledged before this monitor saw it is not relayed (VME cluster ALARMS counts unacknowledged).
            if (a.acknowledged == true && !state?.active?.containsKey("alarm:${a.id}".toString())) return null
            ComputeServerGroup c = byId[cat.substring('mvm.alarm.'.length())]
            if (c == null) return null
            if (a.refType?.equalsIgnoreCase('computeServer') && a.refId != null && maintenance[c.id]?.contains(a.refId)) return null
            String st = a.status?.toLowerCase()
            new Finding(key: "alarm:${a.id}", severity: (st in ['error', 'critical', 'failed']) ? Finding.CRITICAL : Finding.WARNING,
                    clusterId: c.id, cluster: c.name, resource: a.resourceName ?: "${a.refType}:${a.refId}",
                    title: a.name ?: 'HVM alarm', detail: a.statusMessage ?: '', source: 'native-alarm',
                    since: a.startDate ?: new Date(), meta: [acknowledged: a.acknowledged == true])
        }
    }
}
