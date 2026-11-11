package com.hpe.hvmalert

import java.text.SimpleDateFormat

/** Subject and HTML body of the alert mails. English; one table per mail. */
class MailComposer {

    static final String PREFIX = '[HVM Cluster Alert]'

    /** @param open the cluster's open problems now (as mailed), so a folded storage row can name what its host has open */
    static Map change(AlertState.Diff d, String appliance, Collection<Finding> open = null) {
        List<List> rows = []
        d.opened.each { rows << ['NEW', it] }
        d.changed.each { rows << ['CHANGED', it] }
        List<Finding> eventRows = aggregate(d.events)
        eventRows.each { rows << [eventState(it), it] }
        d.resolved.each { rows << ['RESOLVED', it] }
        rows = foldStorage(rows, open)
        // the subject counts the rows of the table, not the log lines or storage items folded into them
        Map<String, Integer> n = rows.countBy { (it[0] as String).endsWith('EVENT') ? 'EVENT' : it[0] as String }
        List<String> parts = []
        if (n.NEW) parts << "${n.NEW} new"
        if (n.CHANGED) parts << "${n.CHANGED} changed"
        if (n.EVENT) parts << "${n.EVENT} event${n.EVENT == 1 ? '' : 's'}"
        if (n.RESOLVED) parts << "${n.RESOLVED} resolved"
        boolean critical = (d.opened + d.changed + d.events).any { it.severity == Finding.CRITICAL }
        String clusters = rows.collect { (it[1] as Finding).cluster }.findAll { it }.unique().join(', ')
        String subject = "${PREFIX}${critical ? ' CRITICAL' : ''} ${clusters}: ${parts.join(', ')}"
        [subject: subject.toString(), html: page("Changes on ${esc(appliance)}", table(rows), appliance).toString(), rows: rows]
    }

    /**
     * First mail for a cluster after start, takeover or a change of its integration: the open problems
     * as a summary instead of one alert each, plus agent-log events not yet mailed.
     */
    static Map started(List<Finding> open, List<Finding> events, String node, String cluster, List<String> off = []) {
        String intro = "The HVM cluster monitor started on ${esc(node)} and watches ${esc(cluster)}. "
        intro += open ? "${open.size()} problem(s) are open now:" : 'No open problems.'
        List<List> rows = foldStorage(open.collect { ['OPEN', it] } + aggregate(events ?: []).collect { [eventState(it), it] })
        String note = (events ?: []).any { it.preStart } ?
                "<p>EARLIER EVENT rows were logged in the ${AlertState.EVENT_LOOKBACK_MS.intdiv(60000L)} minutes before this monitor started; a previous monitor may already have mailed them.</p>" : ''
        String body = "<p>${intro}</p>" + (rows ? table(rows) : '') + note + offNote(off)
        [subject: "${PREFIX} ${cluster}: monitor started on ${node}, ${open.size()} open problem(s)".toString(),
         html: page('Monitor started', body, node).toString(), rows: rows]
    }

    /**
     * One row per host and event type: a host loss logs the same line many times (VME lab: 6 heartbeat failures and
     * about 16 "skipping unhealthy path" lines per surviving host), which buried the one line that matters.
     */
    static List<Finding> aggregate(List<Finding> events) {
        SimpleDateFormat t = new SimpleDateFormat('HH:mm:ss')
        t.setTimeZone(TimeZone.getTimeZone('UTC'))
        events.groupBy { [it.resource, it.meta?.rule ?: it.title, it.preStart] }.collect { k, List<Finding> v ->
            if (v.size() == 1) return v[0]
            List<Finding> byTime = v.sort(false) { it.since?.time ?: 0L }
            Finding last = byTime.last()
            // distinct lines, not only the last: a fence line per node, a path per heartbeat line (newest kept)
            List<String> lines = byTime.collect { it.detail ?: '' }.findAll { it }.unique()
            String detail = lines.size() <= 1 ? last.detail :
                    lines.takeRight(3).join(' | ') + (lines.size() > 3 ? " (+${lines.size() - 3} more distinct lines)" : '')
            // the DLM nodes named in all the lines, not only the 3 shown (VME lab 2026-11-11: an isolated host acked fences
            // for both healthy peers, and the first peer's lines fell into "+4 more")
            List<String> nodes = lines.collectMany { String l -> (l =~ /([A-Za-z0-9][A-Za-z0-9_.-]*) \(dlmNodeId=\d+\)/).collect { it[1] as String } }.unique()
            if (nodes) detail = "Nodes: ${nodes.join(', ')}. ${detail ?: ''}".toString()
            new Finding(key: last.key, severity: v.any { it.severity == Finding.CRITICAL } ? Finding.CRITICAL : Finding.WARNING,
                    clusterId: last.clusterId, cluster: last.cluster, resource: last.resource,
                    title: "${last.title} (x${v.size()}, ${byTime.first().since ? t.format(byTime.first().since) : '?'}-${last.since ? t.format(last.since) : '?'} UTC)".toString(),
                    detail: detail, source: last.source, since: byTime.first().since, preStart: last.preStart, meta: last.meta)
        }.sort { it.since?.time ?: 0L }
    }

    static String eventState(Finding f) {
        f.preStart ? 'EARLIER EVENT' : 'EVENT'
    }

    /** Sent when nothing was mailed to a cluster for 24 hours: proves the monitor and the mail path work. */
    /** The cluster is still there but no settings watch it any more: told once to the last recipients. */
    static Map stopped(String node, String cluster) {
        String body = "<p>The settings no longer watch <b>${esc(cluster)}</b> (\"Watch this cluster\" cleared, or its settings or the " +
                'new-cluster template removed). No alerts are mailed for it until an HVM Alert tab watches it again.</p>'
        [subject: "${PREFIX} ${cluster}: monitor stopped".toString(), html: page('Monitor stopped', body, node).toString(), rows: []]
    }

    static Map daily(List<Finding> open, String node, String cluster, List<String> off = []) {
        String intro = "The HVM cluster monitor on ${esc(node)} is running and watches ${esc(cluster)}. "
        intro += open ? "${open.size()} problem(s) are open:" : 'No open problems.'
        List<List> rows = foldStorage(open.collect { ['OPEN', it] })
        String body = "<p>${intro}</p>" + (rows ? table(rows) : '') + offNote(off)
        [subject: "${PREFIX} ${cluster}: daily status, ${open.size()} open problem(s)".toString(),
         html: page('Daily status', body, node).toString(), rows: rows]
    }

    static final int FOLD_MIN = 3

    /**
     * One row per host for its storage (multipath maps and GFS2 withdraw) and one per cluster for its datastores when a
     * state has FOLD_MIN or more of them: an all-paths-down mailed 19 storage rows for one host, then 5 datastore rows
     * and 23 resolved rows (VME lab T4, 2026-11-11). The items are listed in the detail.
     */
    static List<List> foldStorage(List<List> rows, Collection<Finding> open = null) {
        Closure<List> group = { List r ->
            Finding f = r[1] as Finding
            def m = (f.key ?: '') =~ /^hc:(\d+):(mpath|gfs2):/
            if (m.find()) return [r[0], 'host', m.group(1)]
            (f.key ?: '') ==~ /^ds:\d+:state$/ ? [r[0], 'ds', f.clusterId] : null
        }
        Map<List, List<Finding>> groups = rows.findAll { group(it) != null }.groupBy { group(it) }
                .collectEntries { k, v -> [(k): v.collect { it[1] as Finding }] }
        Set<List> done = [] as Set
        List<List> out = []
        rows.each { List r ->
            List g = group(r)
            if (g == null || groups[g].size() < FOLD_MIN) { out << r; return }
            if (!done.add(g)) return
            List<Finding> items = groups[g]
            Finding first = items[0]
            String title
            if (g[1] == 'host') {
                title = "Storage: ${storageSummary(items)}"
                // a later mail of the same loss folds only its own items (VME lab 2026-11-11: "5 GFS2 filesystem(s)
                // withdrawn" after "13 map(s) ..., 1 GFS2"): name what the host has open now
                if (open != null && r[0] in ['NEW', 'RESOLVED']) {
                    Set<String> mine = items*.key as Set
                    List<Finding> hostOpen = open.findAll { Finding o ->
                        String k = o.key ?: ''
                        k.startsWith("hc:${g[2]}:mpath:") || k.startsWith("hc:${g[2]}:gfs2:")
                    } as List<Finding>
                    if (hostOpen.any { !(it.key in mine) }) {
                        title += r[0] == 'NEW' ? " (now open on this host: ${storageSummary(hostOpen)})" : " (still open: ${storageSummary(hostOpen)})"
                    }
                }
            } else {
                title = "${items.size()} datastores: ${items*.title.unique().join(', ')}"
            }
            List<String> list = items.collect { g[1] == 'host' ? it.title : "${it.resource} (${it.title})" }
            String detail = list.take(12).join('; ') + (list.size() > 12 ? " (+${list.size() - 12} more)" : '')
            if (g[1] == 'ds') {
                // the manager's reason, e.g. "Check host(s) vmenode03 status" (VME lab 2026-11-11)
                List<String> reasons = items.collect { it.detail ?: '' }.findAll { it }.unique()
                if (reasons) detail += "; reason${reasons.size() == 1 ? '' : 's'}: ${reasons.take(3).join(' | ')}" +
                        (reasons.size() > 3 ? " (+${reasons.size() - 3} more)" : '')
            }
            out << [r[0], new Finding(key: g[1] == 'host' ? "hc:${g[2]}:storage" : "ds:cluster-${g[2]}:storage",
                    severity: items.any { it.severity == Finding.CRITICAL } ? Finding.CRITICAL : Finding.WARNING,
                    clusterId: first.clusterId, cluster: first.cluster, resource: g[1] == 'host' ? first.resource : first.cluster,
                    title: title.toString(), detail: detail.toString(), source: first.source,
                    since: items.collect { it.since }.findAll { it != null }.min())]
        }
        out
    }

    /** "13 map(s) with all paths down, 6 GFS2 filesystem(s) withdrawn" for one host's multipath and GFS2 items. */
    static String storageSummary(Collection<Finding> items) {
        int down = items.count { it.key.contains(':mpath:') && it.severity == Finding.CRITICAL } as int
        int part = items.count { it.key.contains(':mpath:') && it.severity != Finding.CRITICAL } as int
        int gfs = items.count { it.key.contains(':gfs2:') } as int
        List<String> what = []
        if (down) what << "${down} map(s) with all paths down"
        if (part) what << "${part} map(s) with failed paths"
        if (gfs) what << "${gfs} GFS2 filesystem(s) withdrawn"
        what.join(', ')
    }

    /** The checks set to Off on the cluster tab, so a summary never hides that something is not mailed. */
    static String offNote(List<String> off) {
        if (!off) return ''
        "<p>${off.size()} check(s) set to Off on the HVM Alert tab are not mailed: ${off.collect { esc(it) }.join('; ')}.</p>"
    }

    /** Planned work: alerts of the cluster are held until the given time. */
    static Map muted(String node, String cluster, long untilMs, String by) {
        String body = "<p>Alerts for <b>${esc(cluster)}</b> are muted until ${esc(utc(untilMs))} by ${esc(by)}. No mails are " +
                'sent until then or until someone clicks Unmute on the HVM Alert tab. Then one mail lists the open problems and the ' +
                'events of that time.</p>'
        [subject: "${PREFIX} ${cluster}: alerts muted until ${utc(untilMs)}".toString(), html: page('Alerts muted', body, node).toString(), rows: []]
    }

    /** The mute is over: the open problems now and the events held meanwhile (at most the last MAX_PENDING_EVENTS). */
    static Map muteEnded(List<Finding> open, List<Finding> events, String node, String cluster, long sinceMs, List<String> off = [],
                         AlertState.Diff d = null) {
        String intro = "Alerts for ${esc(cluster)} were muted from ${esc(utc(sinceMs))}. " +
                (open ? "${open.size()} problem(s) are open now:" : 'No open problems now.')
        // against what the recipients heard before the mute: NEW, CHANGED, still OPEN, and RESOLVED meanwhile
        Set<String> opened = (d?.opened ?: [])*.key as Set, changed = (d?.changed ?: [])*.key as Set
        List<List> rows = open.collect { [d == null ? 'OPEN' : (it.key in opened ? 'NEW' : (it.key in changed ? 'CHANGED' : 'OPEN')), it] } +
                aggregate(events ?: []).collect { [eventState(it), it] } + (d?.resolved ?: []).collect { ['RESOLVED', it] }
        rows = foldStorage(rows)
        String held = events ? "<p>EVENT rows were logged while the alerts were muted (at most the last ${AlertState.MAX_PENDING_EVENTS}).</p>" : ''
        boolean critical = (open + (events ?: [])).any { it.severity == Finding.CRITICAL }
        [subject: "${PREFIX}${critical ? ' CRITICAL' : ''} ${cluster}: mute ended, ${open.size()} open problem(s)".toString(),
         html: page('Mute ended', "<p>${intro}</p>" + (rows ? table(rows) : '') + held + offNote(off), node).toString(), rows: rows]
    }

    /** Sent from the tab's "Send test mail": proves the appliance SMTP settings and the recipients. */
    static Map test(String node, String cluster, String user) {
        String body = "<p>Test mail for <b>${esc(cluster)}</b>, requested by ${esc(user)} on the HVM Alert tab. Alert mails for this " +
                'cluster come from the same sender to the same recipients.</p>'
        [subject: "${PREFIX} ${cluster}: test mail".toString(), html: page('Test mail', body, node).toString(), rows: []]
    }

    static String utc(long ms) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'")
        fmt.setTimeZone(TimeZone.getTimeZone('UTC'))
        fmt.format(new Date(ms))
    }

    static String table(List<List> rows) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'")
        fmt.setTimeZone(TimeZone.getTimeZone('UTC'))
        StringBuilder sb = new StringBuilder()
        sb << '<table style="border-collapse:collapse;font-family:Arial,sans-serif;font-size:13px">'
        sb << '<tr style="background:#01a982;color:#fff">'
        ['State', 'Severity', 'Cluster', 'Resource', 'Problem', 'Detail', 'Since'].each {
            sb << "<th style=\"padding:6px 8px;text-align:left\">${it}</th>"
        }
        sb << '</tr>'
        rows.each { List row ->
            String state = row[0]
            Finding f = row[1] as Finding
            String color = state == 'RESOLVED' ? '#2e7d32' : (f.severity == Finding.CRITICAL ? '#c62828' : '#ef6c00')
            sb << '<tr style="border-bottom:1px solid #ddd">'
            sb << "<td style=\"padding:6px 8px;color:${color};font-weight:bold\">${state}</td>"
            sb << "<td style=\"padding:6px 8px\">${esc(f.severity)}</td>"
            sb << "<td style=\"padding:6px 8px\">${esc(f.cluster)}</td>"
            sb << "<td style=\"padding:6px 8px\">${esc(f.resource)}</td>"
            sb << "<td style=\"padding:6px 8px\">${esc(f.title)}</td>"
            sb << "<td style=\"padding:6px 8px\">${esc(f.detail)}</td>"
            sb << "<td style=\"padding:6px 8px;white-space:nowrap\">${f.since ? fmt.format(f.since) : ''}</td>"
            sb << '</tr>'
        }
        sb << '</table>'
        sb.toString()
    }

    static String page(String heading, String body, String appliance) {
        """<html><body style="font-family:Arial,sans-serif">
<h3 style="margin:0 0 12px 0">${heading}</h3>
${body}
<p style="color:#777;font-size:11px;margin-top:16px">Sent by the HVM Cluster Alert plugin on ${esc(appliance)}. Community plugin, not an HPE product.</p>
</body></html>"""
    }

    static String esc(Object o) {
        (o == null ? '' : o.toString())
                .replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
    }
}
