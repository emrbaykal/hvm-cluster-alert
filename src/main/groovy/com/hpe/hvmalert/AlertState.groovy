package com.hpe.hvmalert

/**
 * In-memory state of the node that runs the checks. Lost on plugin reload or when another app node
 * takes over; the next cycle then sends a "monitor started" summary per cluster.
 *
 * Two layers:
 * - active: the confirmed open problems. A problem opens after CONFIRM_CYCLES consecutive cycles and
 *   resolves after CONFIRM_CYCLES consecutive cycles without it, so one transient read mails nothing.
 *   Native HVM alarms are already debounced by the appliance and pass straight through.
 * - mail: per cluster, what its recipients were last told. A mail diff is taken against that, and it
 *   moves forward only when the mail went out, so a failed send loses nothing: the next attempt carries
 *   the same changes and the events collected meanwhile.
 */
class AlertState {

    static final int CONFIRM_CYCLES = 2
    static final int MAX_PENDING_EVENTS = 50
    /** First read of a host: events this old are still reported, so a takeover after the failure that took
     *  the manager VM down (a field HA test: about 6 min) does not drop that failure's events. */
    static final long EVENT_LOOKBACK_MS = 15 * 60000L
    /** HPE: APD after 6 consecutive failed heartbeat writes (9 during stretch arbitration), 20 s apart
     *  (architecture.rst:114,141; vm_migration.rst:217-220). A run allows gaps up to 3 intervals, because
     *  blocked writes are logged late or not at all. */
    static final int APD_FAILURES = 6
    static final int APD_FAILURES_STRETCH = 9
    static final long APD_MAX_GAP_MS = 60000L
    /** A peer loss (unreachable, fence_ack, failover, peer finding) starting this close to a run means DLM held
     *  GFS2 until the fence. */
    static final long PEER_LOSS_WINDOW_MS = 5 * 60000L
    /** How long a finding seen once is kept while its host cannot be read (not across a long maintenance). */
    static final long PENDING_CARRY_MS = 10 * 60000L

    static class Diff {
        List<Finding> opened = []
        List<Finding> changed = []
        List<Finding> resolved = []
        List<Finding> events = []

        boolean isEmpty() { !opened && !changed && !resolved && !events }
    }

    /** Mail bookkeeping of one cluster. */
    static class ClusterMail {
        String announced                      // rule fingerprint the "monitor started" mail went out for
        String fingerprintSeen                // rule fingerprint of the previous cycle
        int cycles = 0                        // cycles since the cluster appeared or its rule changed
        Map<String, Finding> notified = [:]   // open problems as the recipients last heard them
        List<Finding> pendingEvents = []      // events not yet mailed
        Long lastOkMs
        Long lastErrorMs
        String lastError
        int failures = 0
        long nextAttemptMs = 0
        List<String> recipients = []          // who got the last mail; told once when the cluster stops being watched
    }

    Map<String, Finding> active = [:]
    Map<String, Long> firstSeen = [:]
    Map<String, Integer> seenCount = [:]
    Map<String, Integer> missCount = [:]
    Map<String, Long> lastEventMs = [:]
    Map<String, Set<String>> lastEventLines = [:]   // lines carrying the lastEventMs timestamp (same-ms events)
    Map<Long, List<Long>> heartbeatFailures = [:]  // host id -> recent "on all heartbeat targets" times
    Map<Long, Long> apdReportedMs = [:]
    Map<Long, Integer> lastGfs2 = [:]       // cluster id -> GFS2 datastores counted last time
    Map<Long, List> logPos = [:]            // host id -> [inode, size] where the agent log was last read
    Map<Long, String> quorumLine = [:]      // host id -> newest agent quorum line seen
    Map<Long, ClusterMail> mail = [:]       // by cluster id

    /**
     * @param current findings of this cycle
     * @param carryPrefixes key prefixes whose source could not be read this cycle (e.g. a host whose
     *        check did not run): their open problems are kept, neither re-opened nor resolved
     * @param carryClusters clusters whose check failed this cycle: all their open problems are kept
     */
    void update(List<Finding> current, long nowMs, Set<String> carryPrefixes = [] as Set, Set<Long> carryClusters = [] as Set,
                Set<List> carryScoped = [] as Set) {
        Map<String, Finding> cur = [:]
        Set<String> seen = current*.key as Set
        current.each { Finding f ->
            Long first = firstSeen[f.key]
            if (first == null) {
                first = nowMs
                firstSeen[f.key] = first
            }
            int n = (seenCount[f.key] ?: 0) + 1
            seenCount[f.key] = n
            if (f.source != 'native-alarm') f.since = new Date(first)   // native alarms keep their start date
            boolean held = f.holdSeconds <= 0 || nowMs - first >= f.holdSeconds * 1000L
            boolean confirmed = f.source == 'native-alarm' || active.containsKey(f.key) || n >= Math.max(1, f.confirmCycles)
            if (held && confirmed) cur[f.key] = f
        }
        // a host whose check could not run neither confirms nor forgets a finding seen once (VME lab T3, 2026-11-08: the
        // host rebooted itself before the 2nd read and its all-paths-down count was wiped)
        Set<String> carriedPending = seenCount.keySet().findAll { String k ->
            !seen.contains(k) && carryPrefixes.any { k.startsWith(it) } && (firstSeen[k] == null || nowMs - firstSeen[k] <= PENDING_CARRY_MS)
        } as Set
        seenCount.keySet().retainAll(seen + carriedPending)

        active.each { String k, Finding f ->
            if (cur.containsKey(k) || seen.contains(k)) {
                missCount.remove(k)
                if (!cur.containsKey(k)) cur[k] = f      // seen but still inside its hold time: stays open
                return
            }
            if (carryPrefixes.any { k.startsWith(it) } || (f.clusterId != null && carryClusters.contains(f.clusterId)) ||
                    carryScoped.any { it[0] == f.clusterId && k.startsWith(it[1] as String) }) {
                cur[k] = f
                return
            }
            int m = (missCount[k] ?: 0) + 1
            if (f.source != 'native-alarm' && m < CONFIRM_CYCLES) {
                missCount[k] = m
                cur[k] = f
            } else {
                missCount.remove(k)
            }
        }
        missCount.keySet().retainAll(cur.keySet())
        firstSeen.keySet().retainAll(seen + cur.keySet() + carriedPending)
        active = cur
    }

    /** What changed between what the recipients were told and the current open problems of one cluster. */
    static Diff diff(Map<String, Finding> notified, Collection<Finding> open) {
        Diff d = new Diff()
        Map<String, Finding> now = open.collectEntries { [(it.key): it] }
        now.each { String k, Finding f ->
            Finding prev = notified[k]
            if (prev == null) d.opened << f
            else if (prev.severity != f.severity) d.changed << f
        }
        notified.each { String k, Finding f -> if (!now.containsKey(k)) d.resolved << f }
        return d
    }

    /**
     * Agent-log events not handled yet for this host. The first call for a host reports only the last
     * EVENT_LOOKBACK_MS (marked preStart), so old history is not mailed but the events of a failure that
     * restarted the monitor are. Events with the same millisecond as the newest handled one are told
     * apart by their text.
     */
    List<Map> newEvents(String hostKey, List<Map> events, String hostNow) {
        List<Map> timed = events.findAll { HostCheck.parseTime(it.ts as String) != null }
        Long last = lastEventMs[hostKey]
        boolean first = last == null
        if (first) last = (HostCheck.parseTime(hostNow) ?: System.currentTimeMillis()) - EVENT_LOOKBACK_MS
        Set<String> seenAtLast = lastEventLines[hostKey] ?: ([] as Set)
        List<Map> fresh = timed.findAll {
            long t = HostCheck.parseTime(it.ts as String)
            t > last || (t == last && !seenAtLast.contains(it.line as String))
        }
        Long newest = timed.collect { HostCheck.parseTime(it.ts as String) }.max()
        if (newest != null && newest >= last) {
            Set<String> lines = timed.findAll { HostCheck.parseTime(it.ts as String) == newest }.collect { it.line as String } as Set
            if (newest == last) lines.addAll(seenAtLast)
            lastEventMs[hostKey] = newest
            lastEventLines[hostKey] = lines
        } else if (first) {
            lastEventMs[hostKey] = last
            lastEventLines[hostKey] = [] as Set
        }
        if (first) fresh = fresh.collect { it + [preStart: true] }
        return fresh
    }

    /**
     * Records the "on all heartbeat targets" times one host logged about itself and returns the newest
     * consecutive run (neighbours at most APD_MAX_GAP_MS apart) not yet reported, as [count, firstMs, lastMs];
     * null when there is none of at least APD_FAILURES. Times older than 10 min are forgotten.
     */
    List<Long> heartbeatRun(Long hostId, List<Long> times) {
        List<Long> all = ((heartbeatFailures[hostId] ?: []) + (times ?: [])).unique().sort()
        if (!all) return null
        long newest = all.last()
        all = all.findAll { newest - it <= 10 * 60000L }
        heartbeatFailures[hostId] = all
        Long reported = apdReportedMs[hostId]
        List<Long> run = []
        all.each { Long t ->
            if (run && t - run.last() > APD_MAX_GAP_MS) run = []
            run << t
        }
        if (reported != null && run && (run.first() <= reported || run.first() - reported <= APD_MAX_GAP_MS)) {
            // the incident already decided is still going on: extend it, do not report it again
            apdReportedMs[hostId] = Math.max(reported, run.last())
            return null
        }
        if (run.size() < APD_FAILURES) return null
        return [run.size() as Long, run.first(), run.last()]
    }

    void markApdReported(Long hostId, long lastMs) {
        apdReportedMs[hostId] = lastMs
    }

    void reset() {
        active.clear()
        firstSeen.clear()
        seenCount.clear()
        missCount.clear()
        lastEventMs.clear()
        lastEventLines.clear()
        heartbeatFailures.clear()
        apdReportedMs.clear()
        lastGfs2.clear()
        logPos.clear()
        quorumLine.clear()
        mail.clear()
    }
}
