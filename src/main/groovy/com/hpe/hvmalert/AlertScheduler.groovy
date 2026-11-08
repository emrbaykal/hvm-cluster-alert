package com.hpe.hvmalert

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import groovy.util.logging.Slf4j

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Background loop. Every app node runs it; a distributed lock (MorpheusContext.acquireLock) makes
 * one node the leader, and only the leader lists the clusters, checks and mails, so an HA
 * appliance sends one mail. Every node wakes every 30 s but tries the lock only once per check interval
 * (2 minutes; the per-cluster settings are read from Cypher in the cycle). The leader renews the lock every cycle; when it dies the lock
 * expires (3 intervals, at least 5 min) and another node takes over with a "monitor started" summary.
 */
@Slf4j
class AlertScheduler {

    static final String LOCK = 'hvm-cluster-alert.leader'
    static final long TICK_SECONDS = 30
    static final long DAY_MS = 24L * 3600L * 1000L
    static final long MAX_MAIL_BACKOFF_MS = 60L * 60000L

    enum Leadership { LEADER, FOLLOWER, UNKNOWN }

    Plugin plugin
    MorpheusContext morpheus
    ScheduledExecutorService exec
    AlertState state = new AlertState()
    ApplianceMailer mailer = new ApplianceMailer()
    volatile String lockId
    long nextRunMs = 0
    volatile Date lastCycle
    volatile Map openSnapshot = [:]   // [checkedAt: Date, open: cluster id -> open problems], read by the cluster tab
    Map<String, String> readCache = [:]             // last Cypher values (leader), used when a read fails
    boolean nothingWatchedLogged = false
    String node = hostName()
    volatile boolean stopped = false

    void start() {
        exec = Executors.newSingleThreadScheduledExecutor({ Runnable r ->
            Thread t = new Thread(r, 'hvm-cluster-alert')
            t.daemon = true
            t
        } as ThreadFactory)
        exec.scheduleWithFixedDelay({ safeTick() } as Runnable, 60, TICK_SECONDS, TimeUnit.SECONDS)
        log.info("[hvm-cluster-alert] scheduler started on ${node}; appliance SMTP: ${mailer.describe()}")
    }

    void stop() {
        stopped = true
        try { exec?.shutdownNow() } catch (Throwable ignored) { }
        try { exec?.awaitTermination(20, TimeUnit.SECONDS) } catch (Throwable ignored) { }
        releaseLeadership()
        log.info("[hvm-cluster-alert] scheduler stopped on ${node}")
    }

    void safeTick() {
        try {
            tick()
        } catch (Throwable t) {
            log.error("[hvm-cluster-alert] cycle failed on ${node}: ${t.class.simpleName}: ${t.message}", t)
        }
    }

    void tick() {
        if (stopped) return
        if (!registered()) {
            // Morpheus ran initialize() but did not register this instance (a failed upload): it never calls onDestroy,
            // so this scheduler would run on next to the loaded plugin. Stop after two ticks to rule out a slow load.
            if (++unregisteredTicks >= 2) {
                log.warn("[hvm-cluster-alert] this plugin instance on ${node} is not loaded in Morpheus (failed upload); its scheduler stops")
                stopped = true
                releaseLeadership()
                exec?.shutdown()
            }
            return
        }
        unregisteredTicks = 0
        long now = System.currentTimeMillis()
        if (now < nextRunMs) return
        AlertSettings s = new AlertSettings()       // no plugin settings: rules come from Cypher in the cycle
        nextRunMs = now + s.intervalMinutes * 60000L

        boolean wasLeader = lockId != null
        if (stopped) return                              // never renew the lock while being unloaded
        Leadership l = ensureLeader(Math.max(3L * s.intervalMinutes, 5L) * 60000L)
        if (l == Leadership.UNKNOWN) return              // lock store unreachable: keep the state, try next interval
        if (l == Leadership.FOLLOWER) {
            if (wasLeader) {
                log.info("[hvm-cluster-alert] ${node} lost leadership")
                state.reset()
                openSnapshot = [:]
            }
            return
        }
        if (!wasLeader) {
            log.info("[hvm-cluster-alert] ${node} is the leader")
            state.reset()
            readCache.clear()
        }
        AlertCycle cycle = new AlertCycle(morpheus: morpheus, state: state, cancelled: { stopped }, readCache: readCache,
                cypherRead: { com.morpheusdata.model.Account owner, String key -> TabSettings.read(morpheus, owner, key) })
        AlertCycle.Outcome o = cycle.run(s, now)
        if (stopped) return
        lastCycle = new Date(now)
        state.update(o.findings, now, o.carry, o.carryClusters, o.carryScoped)
        log.info("[hvm-cluster-alert] cycle on ${node}: clusters=${o.clusters.values()} open=${state.active.size()} events=${o.events.size()}")
        if (!o.clusters && !nothingWatchedLogged) {
            log.warn("[hvm-cluster-alert] nothing is watched: save settings in a cluster's HVM Alert tab (tick \"Use these settings " +
                    "for new HVM clusters\" to cover every cluster without its own settings)")
        }
        nothingWatchedLogged = !o.clusters
        if (!stillLeader()) {
            // a slow cycle outlived the lock and another node took over: it mails, this node must not
            log.warn("[hvm-cluster-alert] ${node} lost the lock during the cycle; results discarded")
            lockId = null
            state.reset()
            openSnapshot = [:]
            return
        }
        publishOpen(o.clusters.keySet(), lastCycle)
        notifyClusters(s, o, System.currentTimeMillis())
    }

    /** The open problems of each watched cluster after this cycle, as one immutable map (web threads read it). */
    void publishOpen(Set<Long> watched, Date checkedAt) {
        Map<Long, List<Finding>> by = state.active.values().groupBy { it.clusterId }
        Map<Long, List<Finding>> snap = [:]
        watched.each { Long id -> snap[id] = Collections.unmodifiableList(new ArrayList<Finding>(by[id] ?: [])) }
        openSnapshot = Collections.unmodifiableMap([checkedAt: checkedAt, open: Collections.unmodifiableMap(snap)])
    }

    /** For the cluster tab: [checkedAt, findings] of the last check of this cluster on this node, or null. */
    Map openFor(Long clusterId) {
        if (stopped || lockId == null || clusterId == null) return null
        Map snap = openSnapshot
        List<Finding> l = (snap.open as Map)?.get(clusterId) as List<Finding>
        l == null ? null : [checkedAt: snap.checkedAt, findings: l]
    }

    /**
     * True unless the lock store says another node holds the lock (an error counts as still leader).
     * Field note (Enterprise 9.1.0.1, 2026-11-07): checkLock returns only a lock this node acquired; on a follower it
     * returned null while the leader held the lock. So this catches the loss of this node's own lock, not a takeover;
     * the real guard is the cycle deadline (75 s) far below the lock TTL (at least 5 min).
     */
    boolean stillLeader() {
        try {
            String current = morpheus.checkLock(LOCK, [:]).blockingGet()
            return current == null || current == lockId
        } catch (Throwable ignored) {
            return true
        }
    }

    /**
     * One mail per cluster to its own recipients. What the recipients were told moves forward only on a
     * successful send; a failure keeps the changes and events for the next attempt, which waits one
     * interval, doubling per failure up to an hour.
     */
    void notifyClusters(AlertSettings s, AlertCycle.Outcome o, long now) {
        Map<Long, List<Finding>> openBy = state.active.values().groupBy { it.clusterId }
        Map<Long, List<Finding>> eventsBy = o.events.groupBy { it.clusterId }
        tellStopped(o)
        state.mail.keySet().retainAll(o.clusters.keySet())
        o.clusters.each { Long id, String name ->
            if (stopped) return
            AlertSettings.ClusterRule rule = s.ruleForId(id)
            if (rule == null) return
            AlertState.ClusterMail cm = state.mail.computeIfAbsent(id) { new AlertState.ClusterMail() }
            if (cm.fingerprintSeen != rule.fingerprint) {
                cm.fingerprintSeen = rule.fingerprint
                cm.cycles = 0
            }
            cm.cycles++
            cm.pendingEvents.addAll(eventsBy[id] ?: [])
            if (cm.pendingEvents.size() > AlertState.MAX_PENDING_EVENTS) {
                cm.pendingEvents = cm.pendingEvents.drop(cm.pendingEvents.size() - AlertState.MAX_PENDING_EVENTS)
            }
            if (now < cm.nextAttemptMs) return
            List<Finding> open = openBy[id] ?: []
            Map m
            if (cm.announced != rule.fingerprint) {
                // a new or changed rule: summarize once problems could be confirmed (CONFIRM_CYCLES cycles)
                if (cm.cycles < AlertState.CONFIRM_CYCLES) return
                m = MailComposer.started(open, cm.pendingEvents, node, name)
            } else {
                AlertState.Diff d = AlertState.diff(cm.notified, open)
                d.events = cm.pendingEvents
                if (!d.isEmpty()) {
                    m = MailComposer.change(d, node)
                } else if (rule.dailyStatus && cm.lastOkMs != null && now - cm.lastOkMs >= DAY_MS) {
                    m = MailComposer.daily(open, node, name)
                } else {
                    return
                }
            }
            String error = deliver(rule.recipients, m)
            logRows(name, m, error)
            if (error == null) {
                cm.announced = rule.fingerprint
                cm.notified = open.collectEntries { [(it.key): it] }
                cm.pendingEvents = []
                cm.lastOkMs = now
                cm.recipients = rule.recipients
                cm.failures = 0
                cm.nextAttemptMs = 0
            } else {
                cm.failures++
                cm.lastErrorMs = now
                cm.lastError = error
                long backoff = Math.min(MAX_MAIL_BACKOFF_MS, s.intervalMinutes * 60000L * (1L << Math.min(cm.failures - 1, 6)))
                cm.nextAttemptMs = now + backoff
            }
        }
    }

    /**
     * One "monitor stopped" mail, one attempt, to the last recipients of a cluster that was announced and is still listed
     * but no longer watched. A deleted cluster or a takeover (empty mail state) sends nothing; a Cypher read error cannot
     * cause it, because readSettings falls back to the last value read.
     */
    void tellStopped(AlertCycle.Outcome o) {
        state.mail.findAll { Long id, AlertState.ClusterMail cm ->
            !o.clusters.containsKey(id) && cm.announced != null && o.listed.containsKey(id) && cm.recipients
        }.each { Long id, AlertState.ClusterMail cm ->
            if (stopped) return
            String error = deliver(cm.recipients, MailComposer.stopped(node, o.listed[id]))
            log.warn("[hvm-cluster-alert] ${o.listed[id]}: no longer watched; monitor stopped mail ${error == null ? 'sent' : 'NOT sent: ' + error}")
        }
    }

    static final int MAX_LOGGED_ROWS = 40

    /** What a mail said, one log line per row, so the alert history is in the log even when SMTP fails. */
    void logRows(String cluster, Map m, String error) {
        List<List> rows = (m.rows ?: []) as List<List>
        rows.take(MAX_LOGGED_ROWS).each { List row ->
            Finding f = row[1] as Finding
            log.info("[hvm-cluster-alert] ${cluster} ${error == null ? 'mailed' : 'NOT mailed'}: ${row[0]} ${f.severity} ${f.resource}: " +
                    "${f.title}${f.detail ? ' - ' + f.detail.take(200) : ''}")
        }
        if (rows.size() > MAX_LOGGED_ROWS) log.info("[hvm-cluster-alert] ${cluster}: ${rows.size() - MAX_LOGGED_ROWS} more row(s) not logged")
    }

    /** @return null when sent, else the error */
    String deliver(List<String> recipients, Map m) {
        if (!recipients) {
            log.warn("[hvm-cluster-alert] no recipients for this cluster; not sent: ${m.subject}")
            return 'no recipients'
        }
        if (stopped) return 'plugin stopping'
        try {
            mailer.send(recipients, m.subject as String, m.html as String)
            log.info("[hvm-cluster-alert] mail sent to ${recipients}: ${m.subject}")
            return null
        } catch (Throwable t) {
            log.error("[hvm-cluster-alert] mail failed (${m.subject}): ${t.class.simpleName}: ${t.message}")
            return "${t.class.simpleName}: ${t.message}".toString().take(300)
        }
    }

    /**
     * The Plugin API has no lock renewal, so the leader releases and re-acquires; other nodes try only
     * once per interval with a 200 ms wait, so the gap is rarely hit, and if it is, the other node simply
     * becomes the leader. An error from the lock store keeps the lock id and the state (UNKNOWN) instead
     * of dropping leadership: the held lock would block every node until its TTL.
     */
    Leadership ensureLeader(long ttlMs) {
        try {
            if (lockId) {
                String current = morpheus.checkLock(LOCK, [:]).blockingGet()
                if (current != null && current != lockId) {
                    lockId = null
                    return Leadership.FOLLOWER
                }
                if (current == lockId) morpheus.releaseLock(LOCK, [lock: lockId]).blockingGet()
                // our lock is released now: a failed or null re-acquire must not keep the stale id (VME lab 2026-11-08: an
                // upload during the renewal left a lock no instance released, 9 min without checks)
                String renewed = null
                Throwable why = null
                try { renewed = morpheus.acquireLock(LOCK, [ttl: ttlMs, timeout: 2000L]).blockingGet() } catch (Throwable t) { why = t }
                if (renewed == null) {
                    lockId = heldLock()
                    log.info("[hvm-cluster-alert] renewal not acquired on ${node}${why ? ' (' + why.class.simpleName + ': ' + why.message + ')' : ''}; " +
                            "lock held by this node: ${lockId ?: 'none'}")
                } else {
                    lockId = renewed
                }
                return lockId != null ? Leadership.LEADER : Leadership.FOLLOWER
            }
            lockId = morpheus.acquireLock(LOCK, [ttl: ttlMs, timeout: 200L]).blockingGet()
            return lockId != null ? Leadership.LEADER : Leadership.FOLLOWER
        } catch (Throwable t) {
            if (lockId) {
                log.warn("[hvm-cluster-alert] lock store error on ${node}; cycle skipped, leadership kept: ${t.class.simpleName}: ${t.message}")
                return Leadership.UNKNOWN
            }
            log.debug("[hvm-cluster-alert] lock not acquired on ${node}: ${t.class.simpleName}: ${t.message}")
            return Leadership.FOLLOWER
        }
    }

    int unregisteredTicks = 0

    /** True while Morpheus's plugin manager lists this plugin instance (or when that cannot be told). */
    boolean registered() {
        try {
            def pm = plugin?.pluginManager
            if (pm == null) return true
            return pm.plugins.any { it.is(plugin) }
        } catch (Throwable ignored) {
            return true
        }
    }

    /** The lock this node holds (checkLock is node-local, field 2026-11-07), or null. */
    String heldLock() {
        try { morpheus.checkLock(LOCK, [:]).blockingGet() } catch (Throwable ignored) { null }
    }

    /** Releases the stored id and whatever this node holds, so an interrupted renewal cannot orphan a lock. */
    void releaseLeadership() {
        String held = heldLock()
        List<String> ids = [lockId, held].findAll { it } .unique()
        if (!ids) return
        ids.each { String id ->
            try {
                def ok = morpheus.releaseLock(LOCK, [lock: id]).blockingGet()
                if (ok == false) log.warn("[hvm-cluster-alert] lock ${id} not released on ${node}")
            } catch (Throwable t) {
                log.warn("[hvm-cluster-alert] lock ${id} not released on ${node}: ${t.class.simpleName}: ${t.message}")
            }
        }
        lockId = null
        state.reset()
    }

    static String hostName() {
        try { InetAddress.localHost.hostName } catch (Throwable ignored) { 'unknown-node' }
    }
}
