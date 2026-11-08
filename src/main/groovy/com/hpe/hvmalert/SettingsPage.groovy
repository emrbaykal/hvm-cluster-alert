package com.hpe.hvmalert

import java.text.SimpleDateFormat

/**
 * The cluster tab: this cluster's alert settings form, then its open problems. The markup uses the
 * appliance's own classes (info-list, form-horizontal, form-control, help-block, table) and no inline font sizes, so it
 * reads like the other cluster tabs. The script carries the page nonce (Content-Security-Policy), sends the page's CSRF
 * token and writes messages with textContent only.
 */
class SettingsPage {

    /**
     * @param f clusterId, clusterName, url, nonce, formToken, own (stored map or null), template (stored map or null), error,
     *          open (null, or [checkedAt: Date, findings: List<Finding>] from the last check of this node)
     */
    static String render(Map f) {
        Map own = f.own as Map
        Map tpl = f.template as Map
        Map v = (own ?: tpl ?: TabSettings.DEFAULTS) as Map
        String id = "hvmca-${f.clusterId}"
        boolean watched
        String state, saved = null
        if (own && own.alertEnabled == true) {
            watched = true
            state = 'Watched with the settings of this cluster'
        } else if (own) {
            watched = false
            state = 'Not watched ("Watch this cluster" is off)'
        } else if (tpl && tpl.alertEnabled == true) {
            watched = true
            state = 'Watched with the new-cluster template' + (tpl.templateFromName ? " of cluster ${tpl.templateFromName}" : '') +
                    '; save to give this cluster its own settings'
        } else {
            watched = false
            state = 'Not watched yet; enter the mail recipients and save'
        }
        if (own) saved = "${own.updatedBy ?: '?'}, ${at(own.updatedAt)}"
        StringBuilder sb = new StringBuilder('<div class="hvm-cluster-alert">')
        sb << '<dl class="info-list">' << item('Alerting', esc(state))
        if (saved) sb << item('Saved by', esc(saved))
        sb << '</dl>'
        if (f.error) sb << "<p class=\"text-danger\">${esc(f.error)}</p>"

        def box = { String name, String label, boolean on ->
            "<div class=\"checkbox\"><label><input type=\"checkbox\" name=\"${name}\"${on ? ' checked' : ''}> ${label}</label></div>"
        }
        def number = { String name, String label ->
            List<Integer> r = TabSettings.NUMBERS[name]
            Object shown = v[name] instanceof Number ? Math.max(r[0], ((Number) v[name]).intValue()) : v[name]   // the value in force
            group(label, "<input type=\"number\" class=\"form-control\" name=\"${name}\" min=\"${r[0]}\" max=\"${r[1]}\" required " +
                    "value=\"${esc(shown)}\">", 'col-sm-2')
        }
        boolean templateHere = tpl && tpl.templateFrom?.toString() == f.clusterId?.toString()
        String tplNote = tpl && !templateHere && tpl.templateFromName ? " (now from cluster ${esc(tpl.templateFromName)})" : ''
        sb << "<form id=\"${id}\" class=\"form-horizontal\" method=\"post\" action=\"${esc(f.url)}\">"
        if (f.formToken) sb << "<input type=\"hidden\" name=\"formToken\" value=\"${esc(f.formToken)}\">"
        sb << group('Watch this cluster', box('alertEnabled', 'Send alert mails for this cluster', v.alertEnabled == true))
        sb << group('Mail recipients', "<input type=\"text\" class=\"form-control\" name=\"recipients\" maxlength=\"2000\" " +
                "placeholder=\"ops@example.com, noc@example.com\" value=\"${esc(((v.recipients ?: []) as List).join(', '))}\">" +
                '<span class="help-block">Comma-separated addresses.</span>', 'col-sm-6')
        sb << group('Checks', box('relayNativeAlarms', 'Relay HVM alarms', v.relayNativeAlarms == true) +
                box('hostChecks', 'Host checks (quorum, DLM, GFS2, multipath, agent log)', v.hostChecks == true) +
                box('dailyStatus', 'Daily status mail', v.dailyStatus == true))
        sb << number('agentWarnMinutes', 'Agent silent, warning (min)')
        sb << number('agentCriticalMinutes', 'Agent silent, critical (min)')
        sb << number('nodeUnreachableSeconds', 'Node unreachable (s)')
        sb << number('fencingWaitSeconds', 'DLM fencing wait (s)')
        sb << group('New HVM clusters', box('useForNew', 'Use these settings for new HVM clusters of this tenant' + tplNote, templateHere) +
                '<span class="help-block">Clusters without settings of their own, including clusters created later, are watched with ' +
                'these settings.</span>')
        StringBuilder buttons = new StringBuilder('<button type="submit" class="btn btn-primary">Save</button> ')
        if (own) buttons << '<button type="button" class="btn btn-default" data-action="reset">Remove the settings of this cluster</button> '
        buttons << '<span class="hvmca-msg"></span>'
        sb << group('', buttons.toString())
        sb << '</form>'
        sb << '<p class="help-block">Checks run every 2 minutes. HPE defaults, also the minimums: agent silent 2 / 5 min, node unreachable ' +
              '60 s, DLM fencing wait 30 s. Saving needs the role permission Clusters: Full. The settings are kept in Cypher (' +
              "${esc(TabSettings.key(f.clusterId as Long))}; template ${esc(TabSettings.TEMPLATE_KEY)}).</p>"
        if (watched) sb << openProblems(f.open as Map)
        sb << script(id, f.clusterId.toString(), f.url.toString(), f.nonce?.toString(), templateHere)
        sb << '</div>'
        sb.toString()
    }

    /** The confirmed open problems of the last check; an alarm acknowledged in VME stays listed, marked, until it clears. */
    static String openProblems(Map open) {
        StringBuilder sb = new StringBuilder('<h5>Open problems</h5>')
        if (open == null) {
            return sb << '<p>No check result yet. The first check runs within 2 minutes of saving or of a plugin load.</p>'
        }
        List<Finding> list = ((open.findings ?: []) as List<Finding>).sort(false) { Finding a, Finding b ->
            Finding.rank(b.severity) <=> Finding.rank(a.severity) ?: (a.since <=> b.since) ?: (a.key <=> b.key)
        }
        String checked = "Last check: ${at(open.checkedAt instanceof Date ? ((Date) open.checkedAt).time : open.checkedAt)}."
        if (!list) return sb << "<p>No open problems. ${checked}</p>"
        sb << "<p>${list.size()} open. ${checked}</p>"
        // same look as the alert mail (MailComposer.table): green header, bold coloured state; font from the page
        String cell = 'padding:6px 8px;text-align:left;vertical-align:top'
        sb << '<table style="border-collapse:collapse;width:100%;margin-bottom:20px"><thead><tr style="background:#01a982;color:#fff">'
        ['State', 'Severity', 'Resource', 'Problem', 'Detail', 'Since'].each { sb << "<th style=\"${cell}\">${it}</th>" }
        sb << '</tr></thead><tbody>'
        list.each { Finding p ->
            String color = p.severity == Finding.CRITICAL ? '#c62828' : '#ef6c00'
            String ack = p.meta?.acknowledged == true ? ' <span class="text-muted">(acknowledged)</span>' : ''
            sb << '<tr style="border-bottom:1px solid #ddd">' +
                  "<td style=\"${cell};color:${color};font-weight:bold\">OPEN</td><td style=\"${cell}\">${esc(p.severity)}</td>" +
                  "<td style=\"${cell}\">${esc(p.resource)}</td><td style=\"${cell}\">${esc(p.title)}${ack}</td>" +
                  "<td style=\"${cell}\">${esc(p.detail)}</td><td style=\"${cell};white-space:nowrap\">${p.since ? at(p.since.time) : ''}</td></tr>"
        }
        sb << '</tbody></table>'
        sb.toString()
    }

    static String item(String label, String html) {
        "<div><dt>${esc(label)}:</dt> <dd>${html}</dd></div>"
    }

    static String group(String label, String control, String width = 'col-sm-9') {
        String lab = label ? "<label class=\"control-label col-sm-3\">${esc(label)}</label>" : ''
        String offset = label ? '' : ' col-sm-offset-3'
        "<div class=\"form-group\">${lab}<div class=\"${width}${offset}\">${control}</div></div>"
    }

    static String script(String formId, String clusterId, String url, String nonce, boolean templateHere = false) {
        String n = nonce ? " nonce=\"${esc(nonce)}\"" : ''
        """<script${n}>(function(){
var f=document.getElementById(${js(formId)});if(!f||f.getAttribute('data-bound'))return;f.setAttribute('data-bound','1');
var msg=f.querySelector('.hvmca-msg');
function show(ok,text){msg.className='hvmca-msg '+(ok?'text-success':'text-danger');msg.textContent=text;}
function send(op){
var b=new URLSearchParams();var els=f.elements;
for(var i=0;i<els.length;i++){var e=els[i];if(!e.name)continue;if(e.type==='checkbox'){if(e.checked)b.append(e.name,'on');}else b.append(e.name,e.value);}
b.set('clusterId',${js(clusterId)});b.set('op',op);
var h={'X-Requested-With':'XMLHttpRequest','Content-Type':'application/x-www-form-urlencoded;charset=UTF-8','Accept':'application/json'};
var t=document.querySelector('meta[name="_csrf"]'),hn=document.querySelector('meta[name="_csrf_header"]');
if(t&&t.content){b.set('_csrf',t.content);h[(hn&&hn.content)||'X-CSRF-TOKEN']=t.content;}
show(true,'Saving...');
fetch(${js(url)},{method:'POST',credentials:'same-origin',headers:h,body:b.toString()}).then(function(r){
return r.text().then(function(x){var j=null;try{j=JSON.parse(x);}catch(err){}return {s:r.status,j:j,x:x};});
}).then(function(r){if(r.j&&r.j.success){show(true,r.j.msg);}else{show(false,(r.j&&r.j.msg)||('HTTP '+r.s+(r.x?': '+r.x.substring(0,200):'')));}
}).catch(function(err){show(false,'Not saved: '+err);});
}
var tplHere=${templateHere ? 'true' : 'false'};
f.addEventListener('submit',function(e){e.preventDefault();var u=f.querySelector('input[name="useForNew"]');
if(tplHere&&u&&!u.checked&&!window.confirm('Clearing "Use these settings for new HVM clusters" removes the new-cluster template. Clusters without settings of their own stop being watched. Continue?'))return;
send('save');});
var rb=f.querySelector('[data-action="reset"]');
if(rb)rb.addEventListener('click',function(){if(window.confirm('Remove the settings of this cluster?'))send('reset');});
})();</script>"""
    }

    static String at(Object ms) {
        if (!(ms instanceof Number)) return '?'
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'")
        fmt.setTimeZone(TimeZone.getTimeZone('UTC'))
        esc(fmt.format(new Date(((Number) ms).longValue())))
    }

    static String esc(Object o) {
        MailComposer.esc(o)
    }

    /** A JavaScript string literal that is also safe inside a script element. */
    static String js(String s) {
        '"' + (s ?: '').replace('\\', '\\\\').replace('"', '\\"').replace('<', '\\u003c').replace('>', '\\u003e')
                .replace('\n', '\\n').replace('\r', '\\r') + '"'
    }
}
