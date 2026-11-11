package com.hpe.hvmalert

/**
 * The list of checks shown on the cluster tab, and the alert level a cluster's settings give each of them.
 * A level changes only what is mailed: the checks themselves, their confirmation and the rules that combine them
 * (maintenance restart, Possible APD) always work with the default severity, so a level set here cannot hide or
 * change another check. "off" mails nothing; the problem still shows on the tab, marked.
 */
class Checks {

    static final String DEFAULT = 'default'
    static final String OFF = 'off'
    static final List<String> LEVELS = [DEFAULT, Finding.WARNING, Finding.CRITICAL, OFF]
    /** Form field prefix; no dot, so Grails does not turn the names into a nested map. */
    static final String FIELD = 'level_'

    static final String W = 'Warning', C = 'Critical'

    /** id, group, title, default level as shown. Order is the order on the tab. */
    static final List<Map> ALL = [
        [id: 'hvm-alarm', group: 'HVM alarms', title: 'Active HVM alarm (memory, CPU, datastore, host unreachable, ...); not for a host in maintenance',
         level: 'Critical when the alarm status is error, critical or failed; otherwise Warning'],

        [id: 'host-power', group: 'Manager view', title: 'Host power state off or unknown', level: C],
        [id: 'host-status', group: 'Manager view', title: 'Host status warning / error or failed (Critical also when the manager says the host was fenced)', level: "${W} / ${C}"],
        [id: 'host-member', group: 'Manager view', title: 'Cluster member offline, unclean or fenced', level: C],
        [id: 'agent-silent', group: 'Manager view', title: 'Agent silent past the warning / critical threshold', level: "${W} / ${C}"],
        [id: 'datastore', group: 'Manager view', title: 'Datastore warning / offline, failed or error', level: "${W} / ${C}"],
        [id: 'maint-unreachable', group: 'Manager view', title: 'Host in maintenance not reachable (restart in maintenance)', level: W],

        [id: 'quorum', group: 'Host checks', title: "Quorum lost (reported by the host's agent)", level: C],
        [id: 'fenced', group: 'Host checks', title: 'Host fenced', level: C],
        [id: 'dlm-wait', group: 'Host checks', title: 'DLM waiting on fencing longer than the fencing wait', level: C],
        [id: 'cluster-services', group: 'Host checks', title: 'DLM or Corosync not active', level: C],
        [id: 'peer-reach', group: 'Host checks', title: 'Peer not reached longer than the node unreachable time; nodes or hosts reported down', level: C],
        [id: 'gfs2-withdraw', group: 'Host checks', title: 'GFS2 filesystem withdrawn', level: C],
        [id: 'gfs2-mounts', group: 'Host checks', title: 'Expected GFS2 datastores not mounted: some / none', level: "${W} / ${C}"],
        [id: 'multipath', group: 'Host checks', title: 'Multipath: some paths failed / all paths down', level: "${W} / ${C}"],
        [id: 'gfs2-blocked', group: 'Host checks', title: 'GFS2 blocked on the whole cluster: DLM waits on a fence for a node that is still reachable (the other hosts shut their VMs down after about 2 min)', level: C],

        [id: 'peer-unreachable', group: 'Agent log events', title: 'Peer unreachable, agent is taking action', level: C],
        [id: 'failover', group: 'Agent log events', title: 'HA failover: host offline, VMs are being moved', level: C],
        [id: 'self-reboot', group: 'Agent log events', title: 'Host is rebooting itself: DLM lockspace membership lost, or quorum lost (self-fence)', level: C],
        [id: 'quorum-lost', group: 'Agent log events', title: 'Quorum not achieved or lost (agent log)', level: C],
        [id: 'fence-refused', group: 'Agent log events', title: 'DLM fence refused for a reachable node that has not rebooted (split-brain guard)', level: C],
        [id: 'apd-agent', group: 'Agent log events', title: 'All Paths Down (APD): VMs being shut down (with the number powered off), a VM the agent could not power off, heartbeat budget reached, or APD / isolation message', level: C],
        [id: 'dlm-member-lost', group: 'Agent log events', title: 'DLM lockspace membership lost (first notice before the self-reboot)', level: W],
        [id: 'hb-all', group: 'Agent log events', title: 'Heartbeat write failed on all heartbeat targets', level: W],
        [id: 'fence-ack', group: 'Agent log events', title: 'DLM fence_ack attempted for a lost node', level: W],
        [id: 'hb-late', group: 'Agent log events', title: 'Host heartbeat not updated', level: W],
        [id: 'hb-path', group: 'Agent log events', title: 'Unhealthy heartbeat datastore path skipped', level: W],
        [id: 'apd-derived', group: 'Agent log events', title: 'Possible APD / APD likely (derived by this plugin)', level: "${W} / ${C}"],

        [id: 'plugin-health', group: 'Plugin', title: "The plugin's own check problems: host check could not run, not run as root, output incomplete, " +
                'quorum state unknown, previous check still running, cluster or datastore state not readable. Cannot be set to Off: ' +
                'without it the monitor could stop checking with no mail', level: W, noOff: true],
    ]

    static final Set<String> IDS = ALL*.id as Set
    static final Set<String> NO_OFF = ALL.findAll { it.noOff }*.id as Set

    /** Agent-log rules (HostCheck.EVENT_RULES) that share one row. */
    static final Map<String, String> EVENT_ROWS = ['apd-shutdown': 'apd-agent', 'apd-budget': 'apd-agent', 'apd': 'apd-agent',
                                                   'self-fence': 'self-reboot',
                                                   'apd-vm-off': 'apd-agent', 'apd-vm-error': 'apd-agent']

    /** The plugin's own host-check problems (HostCheck add() keys and AlertCycle "exec"). */
    static final Set<String> HOST_HEALTH = ['exec', 'busy', 'privilege', 'script', 'quorum-unknown', 'quorum-api', 'quorum-truncated'] as Set

    /** Host-check keys (after "hc:<host id>:") and their rows. */
    static final Map<String, String> HOST_ROWS = [quorum: 'quorum', fenced: 'fenced', 'dlm-wait': 'dlm-wait',
                                                 'dlm-active': 'cluster-services', 'corosync-active': 'cluster-services',
                                                 peer: 'peer-reach', reachable: 'peer-reach', 'down-hosts': 'peer-reach',
                                                 gfs2: 'gfs2-withdraw', 'gfs2-mounts': 'gfs2-mounts', mpath: 'multipath']

    static final Map<String, String> MANAGER_ROWS = [power: 'host-power', status: 'host-status', member: 'host-member', agent: 'agent-silent']

    /** The row a problem or event belongs to, or null when it has none (then it is mailed as it is). */
    static String idFor(Finding f) {
        String k = f?.key ?: ''
        if (k.startsWith('event:')) {
            String rule = f.meta?.rule?.toString()
            String id = EVENT_ROWS[rule] ?: rule
            return id in IDS ? id : null
        }
        List<String> p = k.split(':') as List
        switch (p[0]) {
            case 'alarm': return 'hvm-alarm'
            case 'ds': return 'datastore'
            case 'maint': return 'maint-unreachable'
            case 'cluster': return p.size() > 2 && p[2] == 'gfs2-blocked' ? 'gfs2-blocked' : 'plugin-health'
            case 'host': return p.size() > 2 ? MANAGER_ROWS[p[2]] : null
            // an unknown key has no row (mailed at its own level), so a new check is never silenced by another row's Off
            case 'hc': return p.size() <= 2 ? null : (HOST_ROWS[p[2]] ?: (p[2] in HOST_HEALTH ? 'plugin-health' : null))
            default: return null
        }
    }

    /** Only known rows and levels, without the defaults, sorted (the settings fingerprint uses its text). */
    static Map<String, String> clean(Object levels) {
        if (!(levels instanceof Map)) return [:]
        Map<String, String> out = new TreeMap<>()
        ((Map) levels).each { k, v ->
            String id = k?.toString(), lv = v?.toString()?.toLowerCase()
            if (id in IDS && lv in LEVELS && lv != DEFAULT && !(lv == OFF && id in NO_OFF)) out[id] = lv
        }
        out
    }

    static String levelOf(Map<String, String> levels, Finding f) {
        String id = idFor(f)
        (id ? levels?.get(id) : null) ?: DEFAULT
    }

    static boolean isOff(Map<String, String> levels, Finding f) {
        levelOf(levels, f) == OFF
    }

    /** The finding as it is mailed: null when its row is off, a copy with the set severity, or the finding itself. */
    static Finding apply(Map<String, String> levels, Finding f) {
        String lv = levelOf(levels, f)
        if (lv == OFF) return null
        if (lv in [Finding.WARNING, Finding.CRITICAL] && lv != f.severity) return f.withSeverity(lv)
        f
    }

    static List<Finding> applyAll(Map<String, String> levels, Collection<Finding> findings) {
        (findings ?: []).findResults { apply(levels, it) }
    }

    /** Titles of the rows set to off, for the tab and the summary mails. */
    static List<String> offTitles(Map<String, String> levels) {
        ALL.findAll { levels?.get(it.id) == OFF }.collect { it.title as String }
    }
}
