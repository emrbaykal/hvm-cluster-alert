package com.hpe.hvmalert

import java.text.SimpleDateFormat

/** Subject and HTML body of the alert mails. English; one table per mail. */
class MailComposer {

    static final String PREFIX = '[HVM Cluster Alert]'

    static Map change(AlertState.Diff d, String appliance) {
        List<List> rows = []
        d.opened.each { rows << ['NEW', it] }
        d.changed.each { rows << ['CHANGED', it] }
        aggregate(d.events).each { rows << [eventState(it), it] }
        d.resolved.each { rows << ['RESOLVED', it] }
        List<String> parts = []
        if (d.opened) parts << "${d.opened.size()} new"
        if (d.changed) parts << "${d.changed.size()} changed"
        if (d.events) parts << "${d.events.size()} event${d.events.size() == 1 ? '' : 's'}"
        if (d.resolved) parts << "${d.resolved.size()} resolved"
        boolean critical = (d.opened + d.changed + d.events).any { it.severity == Finding.CRITICAL }
        String clusters = rows.collect { (it[1] as Finding).cluster }.findAll { it }.unique().join(', ')
        String subject = "${PREFIX}${critical ? ' CRITICAL' : ''} ${clusters}: ${parts.join(', ')}"
        [subject: subject.toString(), html: page("Changes on ${esc(appliance)}", table(rows), appliance).toString(), rows: rows]
    }

    /**
     * First mail for a cluster after start, takeover or a change of its integration: the open problems
     * as a summary instead of one alert each, plus agent-log events not yet mailed.
     */
    static Map started(List<Finding> open, List<Finding> events, String node, String cluster) {
        String intro = "The HVM cluster monitor started on ${esc(node)} and watches ${esc(cluster)}. "
        intro += open ? "${open.size()} problem(s) are open now:" : 'No open problems.'
        List<List> rows = open.collect { ['OPEN', it] } + aggregate(events ?: []).collect { [eventState(it), it] }
        String note = (events ?: []).any { it.preStart } ?
                "<p>EARLIER EVENT rows were logged in the ${AlertState.EVENT_LOOKBACK_MS.intdiv(60000L)} minutes before this monitor started; a previous monitor may already have mailed them.</p>" : ''
        String body = "<p>${intro}</p>" + (rows ? table(rows) : '') + note
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
            new Finding(key: last.key, severity: v.any { it.severity == Finding.CRITICAL } ? Finding.CRITICAL : Finding.WARNING,
                    clusterId: last.clusterId, cluster: last.cluster, resource: last.resource,
                    title: "${last.title} (x${v.size()}, ${byTime.first().since ? t.format(byTime.first().since) : '?'}-${last.since ? t.format(last.since) : '?'} UTC)".toString(),
                    detail: last.detail, source: last.source, since: byTime.first().since, preStart: last.preStart, meta: last.meta)
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

    static Map daily(List<Finding> open, String node, String cluster) {
        String intro = "The HVM cluster monitor on ${esc(node)} is running and watches ${esc(cluster)}. "
        intro += open ? "${open.size()} problem(s) are open:" : 'No open problems.'
        String body = "<p>${intro}</p>" + (open ? table(open.collect { ['OPEN', it] }) : '')
        [subject: "${PREFIX} ${cluster}: daily status, ${open.size()} open problem(s)".toString(),
         html: page('Daily status', body, node).toString(), rows: open.collect { ['OPEN', it] }]
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
