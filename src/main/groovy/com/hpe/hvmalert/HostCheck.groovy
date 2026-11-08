package com.hpe.hvmalert

import groovy.json.JsonSlurper

import java.time.Instant

/**
 * Host-side checks: one read-only shell script per host per cycle (run through the agent), and a
 * parser that turns its output into findings. Pure code, no Morpheus types, so it is unit-tested.
 *
 * Output shapes come from a layout 2.0 cluster on agent 4.0.1 and layout 1.3 on agent 3.3.0 (VME 9.0.2):
 * - GET https://localhost:7443/quorum returns a JSON array of nodes (hostname, lastReachedDate, me,
 *   serverTime), not the quorumAchieved object the HVM monitoring guide describes. Both are accepted.
 * - The quorumAchieved / reachableNodes fields appear in the agent log
 *   (/var/log/morpheus-node/morphd/current.log) as a Java map on "handleAction results" lines.
 * - Failover messages come from the agent log (QuorumCheckService, MvmHeartbeatFailover).
 *
 * Corosync quorum is not checked: on layouts 1.3 and 2.0 the Morpheus Agent decides quorum and
 * Corosync "Quorate: No" is expected on a healthy two-node or stretch cluster
 * (morpheus-docs infrastructure/clusters/hvm/troubleshooting.rst, architecture.rst).
 *
 * Load and safety on the host:
 * - every command runs under timeout(1), so a hung corosync/DLM/multipathd call cannot pile up;
 * - a flock lets only one copy run per host; a copy still running makes the next one exit at once (@@BUSY);
 * - the agent log is read only from the byte offset the previous cycle reached (same inode), so a
 *   cycle reads the few KB written since, not megabytes.
 */
class HostCheck {

    static final String AGENT_LOG = '/var/log/morpheus-node/morphd/current.log'

    /**
     * Agent log lines that are worth a mail on their own. Order matters: first match wins.
     * "on all heartbeat targets" is a WARNING: in a normal host loss the surviving hosts log it about
     * five times while DLM holds GFS2 until the fence (field HA test); HPE's APD rule is six
     * consecutive failed heartbeat writes (architecture.rst:141, vm_migration.rst:217-222). The script
     * prints the times of these lines on their own (@@LOGDATA "H <ts>") so AlertCycle can derive a
     * possible APD from a consecutive run; see AlertCycle.evaluateApd. The agent's own APD text has not
     * been seen, so APD/isolation is matched only on MvmHeartbeatFailover lines. Matching is
     * case-insensitive in the shell and here.
     */
    static final List<Map> EVENT_RULES = [
        [id: 'peer-unreachable', re: ~/(?i)is unreachable, taking action/, sev: Finding.CRITICAL, title: 'Peer unreachable, agent is taking action'],
        [id: 'failover', re: ~/(?i)is offline, moving vms/, sev: Finding.CRITICAL, title: 'HA failover: host offline, VMs are being moved'],
        // Agent 3.3.0, VME lab 2026-11-08 (storage partition): after 2 cycles without DLM lockspace membership the agent
        // reboots its host ("... for 2 consecutive cycles - rebooting to cleanly rejoin cluster", "Recovering self node via
        // reboot in 5 seconds"). self-reboot must come before dlm-member-lost: the 2/2 line contains the same words.
        [id: 'self-reboot', re: ~/(?i)rebooting to cleanly rejoin cluster|recovering self node via reboot/, sev: Finding.CRITICAL,
         title: 'Host is rebooting itself to rejoin the cluster (DLM lockspace membership lost)'],
        [id: 'dlm-member-lost', re: ~/(?i)DLM lockspace membership lost/, sev: Finding.WARNING,
         title: 'DLM lockspace membership lost (the agent reboots the host at the 2nd confirmation)'],
        [id: 'quorum-lost', re: ~/(?i)quorum lost/, sev: Finding.CRITICAL, title: 'Quorum lost message'],
        // The agent's own APD text (agent 3.3.0, layout 1.3, VME lab storage-partition tests 2026-11-07/08): at the 6th
        // failed check "All heartbeat datastore paths have been unhealthy for host X for 6 checks (budget 6). However,
        // network heartbeat is still responsive. leaving VMs alone for now."; from the 7th check "... for 7 checks
        // (budget 6). Shutting down all VMs to protect data integrity." The shutdown variant comes first.
        [id: 'apd-shutdown', re: ~/(?i)all heartbeat datastore paths have been unhealthy.*shutting down all vms/, sev: Finding.CRITICAL,
         title: 'APD: agent is shutting down all VMs on this host'],
        [id: 'apd-budget', re: ~/(?i)all heartbeat datastore paths have been unhealthy/, sev: Finding.CRITICAL,
         title: 'APD: all heartbeat datastore paths unhealthy (agent budget reached, VMs left running for now)'],
        [id: 'hb-all', re: ~/(?i)on all heartbeat targets/, sev: Finding.WARNING,
         title: 'Heartbeat write failed on all heartbeat targets (expected for up to ~2 min while DLM waits on fencing after a host loss; APD after 6 in a row)'],
        [id: 'fence-ack', re: ~/(?i)fence_ack/, sev: Finding.WARNING, title: 'DLM fence_ack attempted for a lost node'],
        [id: 'hb-late', re: ~/(?i)failed to update heartbeat in/, sev: Finding.WARNING, title: 'Host heartbeat not updated'],
        [id: 'hb-path', re: ~/(?i)skipping unhealthy heartbeat datastore path/, sev: Finding.WARNING, title: 'Unhealthy heartbeat datastore path skipped'],
        // HPE's own phrases only (vm_migration.rst:217-218, monitoring.rst:233,282, troubleshooting.rst:249): a host or
        // VM name such as "hv-apd" can never form them. Last, so heartbeat lines naming such a host stay warnings.
        [id: 'apd', re: ~/(?i)MvmHeartbeatFailover.*(?:all paths down|\bAPD (?:protection|threshold|activated|triggered|mode)\b|isolation (?:failure|counter|threshold|detected))/,
         sev: Finding.CRITICAL, title: 'All Paths Down (APD) / isolation message'],
    ]
    /** Shell pre-filter (grep -i, awk tolower); kept in step with EVENT_RULES by a unit test. The DLM-membership 1/2
     *  line is a WARNING rule but goes through the critical ring on purpose: it is the only notice (~60 s) before the
     *  agent reboots the host, and repeated warnings must not push it out. */
    static final String CRITICAL_GREP = 'is unreachable, taking action|is offline, moving vms|rebooting to cleanly rejoin cluster|recovering self node via reboot|dlm lockspace membership lost|quorum lost|all heartbeat datastore paths have been unhealthy|MvmHeartbeatFailover.*(all paths down|apd (protection|threshold|activated|triggered|mode)|isolation (failure|counter|threshold|detected))'
    static final String WARNING_GREP = 'on all heartbeat targets|fence_ack|failed to update heartbeat in|skipping unhealthy heartbeat datastore path'

    /** Lines kept per cycle: repeated warnings can never push a critical line out. */
    static final int MAX_CRITICAL_EVENTS = 40
    static final int MAX_WARNING_EVENTS = 20
    static final int MAX_HEARTBEAT_TIMES = 60
    /** The quorum line with 16 hosts and 50 lockspaces is about 7.5 KB (16 hosts: HPE a00157488enw). */
    static final int MAX_QUORUM_LINE = 20000

    /** Ignore an agent quorum log line older than this (the manager asks roughly every two minutes). */
    static final long AGENT_QUORUM_MAX_AGE_MS = 10 * 60 * 1000L

    /** Bytes read on the first cycle, after a log rotation, and at most per cycle (the newest bytes win). */
    static final long FIRST_READ_BYTES = 4000000L
    static final long MAX_READ_BYTES = 8000000L

    /**
     * Runs as root: AlertCycle passes sudo=true to executeCommandOnServer, as the agent does for its own
     * commands. Without root the privileged sections are skipped and reported; sudo is never probed (on
     * HVM OS 26.04 a refused "sudo -n" leaves orphaned root processes, lab 2026-11-07).
     */
    static final String TEMPLATE = '''LI='@LOG_INODE@'; LO=@LOG_OFFSET@; E=@EXPECTED_GFS2@
L=@AGENT_LOG@
if command -v timeout >/dev/null 2>&1; then TO='timeout -k 2 6'; else TO=''; fi
if [ "$(id -u)" = 0 ]; then M=root; LK=/run/hvm-cluster-alert.lock; else M=none; LK=/tmp/.hvm-cluster-alert.$(id -u).lock; fi
R() { [ "$M" = none ] || $TO "$@"; }
if command -v flock >/dev/null 2>&1 && ( : >>"$LK" ) 2>/dev/null; then
  exec 9>>"$LK"
  if ! flock -n 9; then echo "@@BUSY"; echo "@@END"; exit 0; fi
fi
echo "@@ID $(id -u) $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "@@PRIV $M"
G=$($TO findmnt -rn -t gfs2 -o TARGET 2>/dev/null | wc -l)
echo "@@GFS2MOUNTS $G"
if [ "$G" -gt 0 ] || [ "$E" -gt 0 ]; then
  echo '@@QUORUM_API'; curl -sk -m 6 https://localhost:7443/quorum 2>&1 | head -c 20000; echo
fi
set -- $(R stat -c '%i %s' "$L" 2>/dev/null)
CI=$1; CS=$2
echo "@@LOG $CI $CS"
if [ -n "$CS" ]; then
  if [ "$CI" = "$LI" ] && [ "$LO" -ge 0 ] && [ "$CS" -ge "$LO" ]; then
    if [ $((CS - LO)) -gt @MAX_READ@ ]; then F=@MAX_READ@; else F="+$((LO + 1))"; fi
  elif [ -n "$LI" ]; then F=@MAX_READ@
  else F=@FIRST_READ@; fi
  echo '@@LOGDATA'
  R tail -c "$F" "$L" 2>/dev/null | tail -c @MAX_READ@ | grep -a -i -E 'quorumAchieved=|@CRIT@|@WARN@' | sed 's/\\x1b\\[[0-9;]*m//g' | awk -v CR='@CRIT@' '
    BEGIN { CR = tolower(CR) }
    !/^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T/ { next }
    /quorumAchieved=/ { q = $0; next }
    { l = tolower($0) }
    l ~ /on all heartbeat targets/ { h[nh % @MAXH@] = $1; nh++ }
    l ~ CR && l !~ /on all heartbeat targets|failed to update heartbeat in/ { c[nc % @MAXC@] = $0; nc++; next }
    { w[nw % @MAXW@] = $0; nw++ }
    END {
      if (q != "") {
        gsub(/keyHash=[^,}]*, /, "", q); gsub(/bootId=[^,}]*, /, "", q); gsub(/witnessOnly=false, /, "", q)
        gsub(/witnessUrl=null, /, "", q); gsub(/site=null, /, "", q); gsub(/, leftReason=unknown/, "", q)
        gsub(/, fenceWalltime=0/, "", q); gsub(/agentStartDate=[0-9]*, /, "", q)
        print "Q " substr(q, 1, @MAXQ@)
      }
      for (i = (nc > @MAXC@ ? nc - @MAXC@ : 0); i < nc; i++) print "E " substr(c[i % @MAXC@], 1, 400)
      for (i = (nw > @MAXW@ ? nw - @MAXW@ : 0); i < nw; i++) print "E " substr(w[i % @MAXW@], 1, 400)
      for (i = (nh > @MAXH@ ? nh - @MAXH@ : 0); i < nh; i++) print "H " h[i % @MAXH@]
    }'
fi
if [ "$G" -gt 0 ] || [ "$E" -gt 0 ]; then
  echo '@@DLM'; R dlm_tool status 2>&1 | grep -i -E 'fenc|wait' | head -n 20; R dlm_tool ls 2>&1 | grep -i -E 'fenc|wait' | head -n 20
  echo '@@GFS2'; for f in /sys/fs/gfs2/*/withdraw; do [ -r "$f" ] && echo "$(basename "$(dirname "$f")") $(cat "$f")"; done
fi
if command -v multipathd >/dev/null 2>&1; then
  echo '@@MPATH'; R multipathd show paths format '%w|%d|%t|%T' 2>&1 | tail -n +2 | awk -F'|' '
    { w = $1; t = $3; st = $4; gsub(/ /, "", w); gsub(/ /, "", t); gsub(/ /, "", st); if (w == "") next; n[w]++
      if (t == "failed" || st == "faulty" || st == "shaky") { bad[++k] = $0; wb[w] = 1 } }
    END { for (i = 1; i <= k && i <= 500; i++) print bad[i]; for (w in wb) print "T|" w "|" n[w] }'
fi
echo '@@END'
'''

    /**
     * @param logInode / logOffset where the previous cycle stopped reading the agent log (null / -1: first read)
     * @param expectedGfs2 GFS2 datastores the manager lists for the cluster: the cluster checks run even
     *        when none is mounted right now
     * @param agentLog the agent log path (tests only)
     */
    static String script(String logInode = null, long logOffset = -1L, int expectedGfs2 = 0, String agentLog = AGENT_LOG) {
        String inode = (logInode ?: '').replaceAll(/[^0-9]/, '')
        TEMPLATE.replace('@LOG_INODE@', inode)
                .replace('@LOG_OFFSET@', (inode ? Math.max(-1L, logOffset) : -1L).toString())
                .replace('@EXPECTED_GFS2@', Math.max(0, expectedGfs2).toString())
                .replace('@AGENT_LOG@', agentLog)
                .replace('@CRIT@', CRITICAL_GREP)
                .replace('@WARN@', WARNING_GREP)
                .replace('@MAXC@', MAX_CRITICAL_EVENTS.toString())
                .replace('@MAXW@', MAX_WARNING_EVENTS.toString())
                .replace('@MAXH@', MAX_HEARTBEAT_TIMES.toString())
                .replace('@MAXQ@', MAX_QUORUM_LINE.toString())
                .replace('@FIRST_READ@', FIRST_READ_BYTES.toString())
                .replace('@MAX_READ@', MAX_READ_BYTES.toString())
    }

    static class Result {
        boolean complete              // the script reached @@END
        boolean busy                  // the previous copy still runs on this host; nothing was read
        String privilege              // root | none
        boolean clustered             // GFS2 expected or mounted: quorum, DLM and GFS2 checks apply
        int gfs2Mounts
        String hostNow                // host clock (UTC) when the script ran
        String logInode               // where this cycle stopped reading the agent log
        Long logSize
        String quorumLine             // newest agent quorum line read this cycle (null: none in the new bytes)
        List<String> heartbeatFailureTimes = []   // "on all heartbeat targets" times read this cycle
        boolean stretch               // the agent quorum map lists sites (stretch cluster): APD limit 9
        List<Finding> findings = []
        List<Map> events = []         // [ts, severity, title, line, rule], oldest first
    }

    static Map<String, List<String>> sections(String output) {
        Map<String, List<String>> out = [:]
        String current = null
        (output ?: '').readLines().each { String line ->
            if (line.startsWith('@@')) {
                String name = line.substring(2).split(/\s+/)[0]
                current = name
                out[name] = []
                String rest = line.substring(2 + name.length()).trim()
                if (rest) out[name] << rest
            } else if (current) {
                out[current] << line
            }
        }
        return out
    }

    /**
     * @param ctx cluster, clusterId, hostId, hostName, nodeUnreachableSeconds, fencingWaitSeconds,
     *        expectedGfs2, and lastQuorumLine (the newest quorum line an earlier cycle read)
     */
    static Result parse(String output, Map ctx) {
        Result r = new Result()
        Map<String, List<String>> s = sections(output)
        r.complete = s.containsKey('END')
        String host = ctx.hostName
        String prefix = "hc:${ctx.hostId}"
        // o: hold (seconds), confirm (cycles), meta (map)
        def add = { String key, String sev, String title, String detail, Map o = [:] ->
            r.findings << new Finding(key: "${prefix}:${key}", severity: sev, clusterId: ctx.clusterId as Long, cluster: ctx.cluster,
                    resource: host, title: title, detail: detail, source: 'host', holdSeconds: (o.hold ?: 0) as int,
                    confirmCycles: (o.confirm ?: 2) as int, meta: (o.meta ?: [:]) as Map)
        }
        if (s.containsKey('BUSY')) {
            r.busy = true
            add('busy', Finding.WARNING, 'Previous host check still running',
                    'The read-only check of the previous cycle has not finished on this host (a command is hanging); this cycle read nothing.')
            return r
        }

        List<String> id = s['ID'] ?: []
        r.hostNow = id ? id[0].split(/\s+/).last() : null
        long nowMs = parseTime(r.hostNow) ?: System.currentTimeMillis()

        r.privilege = (s['PRIV'] ?: [null])[0]?.trim()
        if (r.privilege == 'none') {
            add('privilege', Finding.WARNING, 'Host checks incomplete: the agent did not run them as root',
                    'Agent log, DLM and multipath could not be read. The plugin asks the agent for sudo; check the agent user\'s sudo rights.')
        }
        if (!r.complete) {
            add('script', Finding.WARNING, 'Host check output incomplete',
                    'The read-only check script did not finish; output was cut or the command timed out.')
        }

        String mounts = (s['GFS2MOUNTS'] ?: ['0'])[0].trim()
        r.gfs2Mounts = mounts.isInteger() ? mounts as Integer : 0
        int expected = (ctx.expectedGfs2 ?: 0) as int
        r.clustered = r.gfs2Mounts > 0 || expected > 0
        if (r.complete && expected > 0 && r.gfs2Mounts < expected) {
            add('gfs2-mounts', r.gfs2Mounts == 0 ? Finding.CRITICAL : Finding.WARNING,
                    "Expected ${expected} GFS2 datastore(s), ${r.gfs2Mounts} mounted",
                    'The manager lists GFS2 datastores for this cluster that are not mounted on this host.')
        }

        List<String> logPos = ((s['LOG'] ?: [''])[0] ?: '').trim().split(/\s+/) as List
        if (logPos.size() == 2 && logPos[0].isLong() && logPos[1].isLong()) {
            r.logInode = logPos[0]
            r.logSize = logPos[1] as Long
        }
        List<String> data = s['LOGDATA'] ?: []
        r.quorumLine = data.find { it.startsWith('Q ') }?.substring(2)
        r.heartbeatFailureTimes = data.findAll { it.startsWith('H ') }.collect { it.substring(2).trim() }.findAll { parseTime(it) != null }
        String ql = r.quorumLine ?: (ctx.lastQuorumLine as String)
        r.stretch = ql != null && (ql =~ /\bsites=\[[^\]\s]/).find()

        int unreachable = (ctx.nodeUnreachableSeconds ?: 60) as int
        int fencing = (ctx.fencingWaitSeconds ?: 30) as int
        if (r.clustered) {
            boolean nodeList = parseQuorumApi(s['QUORUM_API'] ?: [], nowMs, unreachable, fencing, add)
            if (r.privilege != 'none' && r.complete) {
                String q = r.quorumLine ?: (ctx.lastQuorumLine as String)
                // with a /quorum node list the peer ages (pinged every 20 s) already report a lost host; the map's
                // reachable/down-hosts counts lag ~2-4 min and doubled every host loss (VME lab T2/T3, 2026-11-08)
                if (!parseAgentQuorum(q, nowMs, fencing, add, nodeList)) {
                    add('quorum-unknown', Finding.WARNING, 'Agent quorum state unknown',
                            "No agent quorum line newer than ${AGENT_QUORUM_MAX_AGE_MS.intdiv(60000L)} min in ${AGENT_LOG}; quorum cannot be confirmed.")
                }
            }
            parseDlm(s['DLM'] ?: [], fencing, add)
            parseGfs2(s['GFS2'] ?: [], add)
        }
        parseMultipath(s['MPATH'] ?: [], add)
        r.events = parseEvents(data.findAll { it.startsWith('E ') }.collect { it.substring(2) })
        // Several sources can report the same condition (e.g. quorum from the endpoint and the log): keep one.
        r.findings = r.findings.groupBy { it.key }.collect { k, v -> v.max { Finding.rank(it.severity) } }
        return r
    }

    /** Epoch ms from an ISO string, an epoch number or a numeric string; null otherwise. */
    static Long toMs(Object v) {
        if (v instanceof Number) return ((Number) v).longValue()
        String t = v?.toString()?.trim()
        if (!t) return null
        if (t.isLong()) return t.toLong()
        return parseTime(t)
    }

    /** @return true when the endpoint answered with the per-node list (field shape on layouts 1.3 and 2.0) */
    static boolean parseQuorumApi(List<String> lines, long nowMs, int unreachableSeconds, int fencingSeconds, Closure add) {
        String text = lines.join('\n').trim()
        if (!text) {
            add('quorum-api', Finding.WARNING, 'Agent quorum endpoint did not answer', 'curl https://localhost:7443/quorum returned nothing.')
            return false
        }
        Object json
        try {
            json = new JsonSlurper().parseText(text)
        } catch (Exception ignored) {
            add('quorum-api', Finding.WARNING, 'Agent quorum endpoint answer not readable', text.take(200))
            return false
        }
        if (json instanceof Map) {
            // Documented shape (monitoring.rst:52-61).
            Map q = (Map) json
            if (q.quorumAchieved == false) add('quorum', Finding.CRITICAL, 'Quorum lost', 'Agent quorum endpoint reports quorumAchieved=false.', [confirm: 1])
            if (q.fenced == true) add('fenced', Finding.CRITICAL, 'Host is fenced', 'Agent quorum endpoint reports fenced=true.', [confirm: 1])
            if (q.waitFencing == true) add('dlm-wait', Finding.CRITICAL, 'DLM waiting on fencing', 'Agent quorum endpoint reports waitFencing=true.', [hold: fencingSeconds, confirm: 1])
            if (q.reachableNodes instanceof Number && q.totalNodes instanceof Number && q.reachableNodes < q.totalNodes) {
                add('reachable', Finding.CRITICAL, "Only ${q.reachableNodes} of ${q.totalNodes} nodes reachable", 'Agent quorum endpoint.',
                        [hold: unreachableSeconds, confirm: 1, meta: [missing: (q.totalNodes - q.reachableNodes) as int]])
            }
            return false
        }
        if (json instanceof List) {
            // Field shape (agent 4.0.1): one entry per node; peer age is the debounce.
            List<Map> peers = ((List) json).findAll { it instanceof Map && it.me != true } as List<Map>
            peers.each { Map n ->
                String name = n.hostname?.toString() ?: 'unknown'
                Long reached = toMs(n.lastReachedDate)
                long ref = (n.serverTime instanceof Number) ? ((Number) n.serverTime).longValue() : nowMs
                if (reached == null) {
                    add("peer:${name}", Finding.CRITICAL, "Peer ${name} never reached", 'The agent quorum endpoint has no last-reached time for this peer.',
                            [hold: unreachableSeconds, confirm: 1, meta: [peer: name, addresses: (n.addresses ?: []) as List]])
                } else {
                    long age = (ref - reached).intdiv(1000L)
                    if (age > unreachableSeconds) {
                        add("peer:${name}", Finding.CRITICAL, "Peer ${name} not reached for ${age} s",
                                "Last reached ${n.lastReachedDate} (agent quorum endpoint).", [confirm: 1, meta: [peer: name, addresses: (n.addresses ?: []) as List]])
                    }
                }
            }
            return !peers.isEmpty()      // an empty list says nothing about the peers
        }
        return false
    }

    /**
     * The quorum map the agent logs on "handleAction results" lines. Top-level keys come before and after
     * the nodes=[...] list (waitFencing and downHosts follow it, agent 4.0.1); the node entries are skipped.
     * @return false when there is no quorum line or it is older than AGENT_QUORUM_MAX_AGE_MS (state unknown)
     */
    static boolean parseAgentQuorum(String line, long nowMs, int fencingSeconds, Closure add, boolean skipHostCounts = false) {
        if (!line?.contains('quorumAchieved=')) return false
        Long ts = parseTime(line.trim().split(/\s+/)[0])
        if (ts == null || nowMs - ts > AGENT_QUORUM_MAX_AGE_MS) return false
        String top = line
        int start = line.indexOf('nodes=[')
        if (start >= 0) {
            int depth = 0, end = -1
            for (int i = start + 'nodes='.length(); i < line.length(); i++) {
                char ch = line.charAt(i)
                if (ch == '[' as char) depth++
                else if (ch == ']' as char && --depth == 0) { end = i; break }
            }
            top = line.substring(0, start) + (end > 0 ? line.substring(end + 1) : '')
            if (end < 0) {
                add('quorum-truncated', Finding.WARNING, 'Agent quorum line truncated',
                        'The agent quorum line was cut inside the node list; waitFencing, downHosts and later nodes could not be read.')
            }
        }
        def val = { String k -> def m = top =~ /\b${k}=([A-Za-z0-9]+)/; m.find() ? m.group(1) : null }
        if (val('quorumAchieved') == 'false') add('quorum', Finding.CRITICAL, 'Quorum lost', 'Agent reports quorumAchieved=false.', [confirm: 1])
        if (val('fenced') == 'true') add('fenced', Finding.CRITICAL, 'Host is fenced', 'Agent reports fenced=true.', [confirm: 1])
        if (val('waitFencing') == 'true') add('dlm-wait', Finding.CRITICAL, 'DLM waiting on fencing', 'Agent reports waitFencing=true.', [hold: fencingSeconds, confirm: 1])
        if (val('dlmActive') == 'false') add('dlm-active', Finding.CRITICAL, 'DLM not active', 'Agent reports dlmActive=false.')
        if (val('corosyncActive') == 'false') add('corosync-active', Finding.CRITICAL, 'Corosync service not active', 'Agent reports corosyncActive=false; DLM depends on it.')
        String reach = val('reachableNodes'), total = val('totalNodes')
        if (!skipHostCounts && reach?.isInteger() && total?.isInteger() && (reach as int) < (total as int)) {
            add('reachable', Finding.CRITICAL, "Only ${reach} of ${total} nodes reachable", 'Agent quorum state.',
                    [confirm: 1, meta: [missing: (total as int) - (reach as int)]])
        }
        def down = top =~ /\bdownHosts=\[([^\]]*)\]/
        if (!skipHostCounts && down.find()) {
            List<String> hosts = down.group(1).split(/,/).collect { it.trim() }.findAll { it }
            if (hosts) add('down-hosts', Finding.CRITICAL, "Agent reports host(s) down: ${hosts.join(', ')}", 'Agent quorum state (downHosts).',
                    [confirm: 1, meta: [hosts: hosts]])
        }
        if (line.contains('needFencing=true')) {
            add('dlm-wait', Finding.CRITICAL, 'DLM node needs fencing', 'Agent reports needFencing=true for a node.', [hold: fencingSeconds, confirm: 1])
        }
        return true
    }

    static void parseDlm(List<String> lines, int holdSeconds, Closure add) {
        String daemon = lines.find { it.contains('fence_pid') }
        def m = daemon =~ /fence_pid\s+(\d+)/
        boolean fencing = (m.find() && m.group(1) != '0')
        List<String> waits = lines.findAll { it =~ /(?i)wait.*fenc|fenc.*wait/ }
        if (fencing || waits) {
            add('dlm-wait', Finding.CRITICAL, 'DLM waiting on fencing',
                    ((fencing ? [daemon.trim()] : []) + waits.collect { it.trim() }).join('; ').take(300), [hold: holdSeconds, confirm: 1])
        }
    }

    static void parseGfs2(List<String> lines, Closure add) {
        lines.each { String l ->
            List<String> p = l.trim().split(/\s+/) as List
            if (p.size() == 2 && p[1] == '1') {
                add("gfs2:${p[0]}", Finding.CRITICAL, "GFS2 filesystem withdrawn: ${p[0]}",
                        '/sys/fs/gfs2/<fs>/withdraw is 1; the filesystem needs unmount/remount or a host reboot.', [confirm: 1])
            }
        }
    }

    /**
     * The script prints only failed paths plus "T|wwid|total" for each map that has one (a healthy host
     * prints nothing: the agent writes command output into its own log, so output is kept small). Plain
     * full path lists (older output, tests) are still read.
     */
    static void parseMultipath(List<String> lines, Closure add) {
        Map<String, List<List<String>>> byMap = [:].withDefault { [] }
        Map<String, Integer> totals = [:]
        lines.each { String l ->
            List<String> p = l.split(/\|/).collect { it.trim() }
            if (p.size() == 3 && p[0] == 'T' && p[2].isInteger()) { totals[p[1]] = p[2] as int; return }
            if (p.size() >= 4 && p[0] && !p[0].startsWith('uuid')) byMap[p[0]] << p
        }
        byMap.each { String wwid, List<List<String>> paths ->
            List<List<String>> bad = paths.findAll { it[2] == 'failed' || it[3] in ['faulty', 'shaky'] }
            if (bad) {
                int total = Math.max(totals[wwid] ?: paths.size(), bad.size())
                boolean none = bad.size() == total
                add("mpath:${wwid}", none ? Finding.CRITICAL : Finding.WARNING,
                        none ? "All ${total} paths down on ${wwid}" : "${bad.size()} of ${total} paths failed on ${wwid}",
                        'Failed paths: ' + bad.collect { "${it[1]} (${it[2]}/${it[3]})" }.join(', '),
                        none ? [confirm: 1] : [:])      // all paths down = HPE's APD trigger, already debounced by multipathd
            }
        }
    }

    static List<Map> parseEvents(List<String> lines) {
        List<Map> events = lines.findResults { String l ->
            String line = l.trim()
            if (!line) return null
            Map rule = EVENT_RULES.find { line =~ it.re }
            if (!rule) return null
            String ts = line.split(/\s+/)[0]
            [ts: ts, severity: rule.sev, title: rule.title, rule: rule.id, line: line.replaceFirst(/^\S+\s+\[[^\]]*\]\s+\[[^\]]*\]\s+/, '')]
        }
        events.sort { parseTime(it.ts as String) ?: 0L }
    }

    static Long parseTime(String s) {
        if (!s) return null
        try {
            return Instant.parse(s).toEpochMilli()
        } catch (Exception ignored) {
            return null
        }
    }
}
