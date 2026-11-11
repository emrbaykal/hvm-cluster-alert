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
     *          open (null, or [checkedAt: Date, findings: List<Finding>] from the last check of this node),
     *          mute (null, or [until, by, at] from Cypher), mail (null, or the leader's mail status of this cluster), now (ms, tests)
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
        Map<String, String> levels = Checks.clean(v.levels)
        List<String> off = Checks.offTitles(levels)
        StringBuilder sb = new StringBuilder('<div class="hvm-cluster-alert">')
        sb << '<dl class="info-list">' << item('Alerting', esc(state))
        if (saved) sb << item('Saved by', esc(saved))
        if (off) sb << item('Checks off', "<span class=\"text-danger\">${off.size()} check(s) set to Off: not mailed</span>")
        long now = (f.now ?: System.currentTimeMillis()) as long
        Map mute = f.mute as Map
        boolean muted = mute != null && (mute.until as long) > now
        if (watched && muted) sb << item('Muted', "<span class=\"text-danger\">until ${at(mute.until)} by ${esc(mute.by)}</span>")
        if (watched) sb << item('Last mail', mailStatus(f.mail as Map, f.leader == true || f.open != null))
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
        // full width under its own heading, like "Open problems": a 3-column table is too narrow beside the label column
        int changed = levels.size()
        // collapsed: 28 rows made the page long; the summary says how many rows are not at Default
        // the appliance hides the summary's disclosure marker, so the bar draws its own arrow and a Show/Hide label
        sb << '<details class="hvmca-levels" style="margin:24px 0 16px 0"><summary style="cursor:pointer;display:block;list-style:none;' +
                'padding:10px 14px;border:1px solid #01a982;border-radius:4px;background:#f2fbf8">' +
                '<span class="hvmca-chev" style="display:inline-block;width:1.2em;color:#01a982;font-weight:bold">&#9656;</span>' +
                '<h5 style="display:inline">Alert levels</h5> &nbsp; ' +
                "<span class=\"hvmca-changed ${changed ? 'text-danger' : 'text-muted'}\">${changed ? "${changed} changed from Default" : 'all at Default'}</span>" +
                '<span class="hvmca-toggle" style="float:right;color:#01a982;font-weight:bold">Show levels &#9662;</span>' +
                '</summary><div style="margin-top:8px">' +
                '<p class="help-block">Default keeps the level in the "Default level" column. A level changes only the mails: a ' +
                'Critical row adds CRITICAL to the subject of alert mails and colours the row red. Off mails nothing; the problem ' +
                'still shows in the list of open problems, marked. Host checks and agent log events run only with "Host checks" ticked, HVM ' +
                'alarms only with "Relay HVM alarms".</p>' + levelTable(levels) + '</div></details>'
        sb << group('New HVM clusters', box('useForNew', 'Use these settings for new HVM clusters of this tenant' + tplNote, templateHere) +
                '<span class="help-block">Clusters without settings of their own, including clusters created later, are watched with ' +
                'these settings.</span>')
        StringBuilder buttons = new StringBuilder('<button type="submit" class="btn btn-primary">Save</button> ' +
                '<button type="button" class="btn btn-default" data-action="test">Send test mail</button> ')
        if (own) buttons << '<button type="button" class="btn btn-default" data-action="reset">Remove the settings of this cluster</button> '
        buttons << '<span class="hvmca-msg"></span>'
        sb << group('', buttons.toString())
        if (watched) {
            String control = muted ?
                    "<span class=\"text-danger\">Muted until ${at(mute.until)} by ${esc(mute.by)}.</span> " +
                    '<button type="button" class="btn btn-default" data-action="unmute">Unmute</button>' :
                    '<select class="form-control input-sm" name="muteHours" aria-label="Mute for" style="display:inline-block;width:auto">' +
                    TabSettings.MUTE_HOURS.collect { "<option value=\"${it}\">${it} h</option>" }.join('') + '</select> ' +
                    '<button type="button" class="btn btn-default" data-action="mute">Mute alerts</button>'
            sb << group('Planned work', control + '<span class="help-block">Mute holds every mail of this cluster: the recipients get ' +
                    'one mail when it starts and one summary of the open problems and events when it ends or on Unmute. The checks keep ' +
                    'running.</span>')
        }
        sb << '</form>'
        sb << '<p class="help-block">Checks run every 2 minutes. HPE defaults, also the minimums: agent silent 2 / 5 min, node unreachable ' +
              '60 s, DLM fencing wait 30 s. Saving needs the role permission Clusters: Full. The settings are kept in Cypher (' +
              "${esc(TabSettings.key(f.clusterId as Long))}; template ${esc(TabSettings.TEMPLATE_KEY)}).</p>"
        if (watched) sb << openProblems(f.open as Map, levels)
        sb << script(id, f.clusterId.toString(), f.url.toString(), f.nonce?.toString(), templateHere)
        sb << '</div>'
        sb.toString()
    }

    /** The leader's last mail result for this cluster: sent, failed with the next try, or nothing yet. */
    static String mailStatus(Map m, boolean leaderHere) {
        if (m == null) return leaderHere ? 'none yet from this monitor' : '<span class="text-muted">not known on this node</span>'
        Long ok = m.lastOkMs as Long, err = m.lastErrorMs as Long
        if (err != null && (ok == null || err > ok)) {
            String next = (m.nextAttemptMs as Long) ? "; next try ${at(m.nextAttemptMs)}" : ''
            return "<span class=\"text-danger\">failed ${at(err)}: ${esc(m.lastError)}${next}</span>"
        }
        ok != null ? "sent ${at(ok)}" : 'none yet from this monitor'
    }

    /** One row per check: group, what it watches, default level, and the level selected for this cluster. */
    static String levelTable(Map<String, String> levels) {
        String cell = 'padding:4px 8px;text-align:left;vertical-align:middle'
        StringBuilder sb = new StringBuilder('<table style="border-collapse:collapse;width:100%"><thead><tr style="background:#01a982;color:#fff">')
        // the appliance styles th text grey; white keeps the header readable on the green row
        [['Check', ''], ['Default level', 'width:30%'], ['Level', 'width:10em']].each {
            sb << "<th style=\"${cell};color:#fff;${it[1]}\">${it[0]}</th>"
        }
        sb << '</tr></thead><tbody>'
        String group = null
        Checks.ALL.each { Map c ->
            if (c.group != group) {
                group = c.group
                sb << "<tr><td colspan=\"3\" style=\"${cell};font-weight:bold;padding-top:10px\">${esc(group)}</td></tr>"
            }
            String sel = levels?.get(c.id) ?: Checks.DEFAULT
            String mark = sel == Checks.DEFAULT ? '' : 'background:#fff8e1;'
            StringBuilder opts = new StringBuilder()
            (c.noOff ? Checks.LEVELS - Checks.OFF : Checks.LEVELS).each { String lv ->
                opts << "<option value=\"${lv}\"${lv == sel ? ' selected' : ''}>${lv.capitalize()}</option>"
            }
            sb << "<tr class=\"hvmca-level\" style=\"border-bottom:1px solid #ddd;${mark}\">" +
                  "<td style=\"${cell}\">${esc(c.title)}</td><td style=\"${cell}\">${esc(c.level)}</td>" +
                  "<td style=\"${cell}\"><select class=\"form-control input-sm\" name=\"${Checks.FIELD}${c.id}\" " +
                  "aria-label=\"Level: ${esc(c.title)}\">${opts}</select></td></tr>"
        }
        sb << '</tbody></table>'
        sb << '<p style="margin-top:8px"><button type="button" class="btn btn-default btn-sm" data-action="reset-levels">Reset all to ' +
              'Default</button> <span class="help-block" style="display:inline">then Save.</span></p>'
        sb.toString()
    }

    /** The confirmed open problems of the last check; an alarm acknowledged in VME stays listed, marked, until it clears. */
    static String openProblems(Map open, Map<String, String> levels = [:]) {
        StringBuilder sb = new StringBuilder('<h5>Open problems</h5>')
        if (open == null) {
            return sb << '<p>No check result yet. The first check runs within 2 minutes of saving or of a plugin load.</p>'
        }
        // shown with the level the mails use; a check set to Off keeps its own severity, marked
        List<Finding> list = ((open.findings ?: []) as List<Finding>).collect { Finding p -> Checks.apply(levels, p) ?: p }
                .sort(false) { Finding a, Finding b ->
            Finding.rank(b.severity) <=> Finding.rank(a.severity) ?: (a.since <=> b.since) ?: (a.key <=> b.key)
        }
        String checked = "Last check: ${at(open.checkedAt instanceof Date ? ((Date) open.checkedAt).time : open.checkedAt)}."
        if (!list) return sb << "<p>No open problems. ${checked}</p>"
        sb << "<p>${list.size()} open. ${checked}</p>"
        // same look as the alert mail (MailComposer.table): green header, bold coloured state; font from the page
        String cell = 'padding:6px 8px;text-align:left;vertical-align:top'
        sb << '<table style="border-collapse:collapse;width:100%;margin-bottom:20px"><thead><tr style="background:#01a982;color:#fff">'
        ['State', 'Severity', 'Resource', 'Problem', 'Detail', 'Since'].each { sb << "<th style=\"${cell};color:#fff\">${it}</th>" }
        sb << '</tr></thead><tbody>'
        list.each { Finding p ->
            String color = p.severity == Finding.CRITICAL ? '#c62828' : '#ef6c00'
            String ack = (p.meta?.acknowledged == true ? ' <span class="text-muted">(acknowledged)</span>' : '') +
                    (Checks.isOff(levels, p) ? ' <span class="text-muted">(Off: not mailed)</span>' : '')
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
        // left-aligned labels: every field starts on the same line as the full-width sections (Alert levels, Open problems)
        String lab = label ? "<label class=\"control-label col-sm-3\" style=\"text-align:left\">${esc(label)}</label>" : ''
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
show(true,{save:'Saving...',reset:'Removing...',test:'Sending...',mute:'Muting...',unmute:'Unmuting...'}[op]||'...');
fetch(${js(url)},{method:'POST',credentials:'same-origin',headers:h,body:b.toString()}).then(function(r){
return r.text().then(function(x){var j=null;try{j=JSON.parse(x);}catch(err){}return {s:r.status,j:j,x:x};});
}).then(function(r){if(r.j&&r.j.success){show(true,r.j.msg);}else{show(false,(r.j&&r.j.msg)||('HTTP '+r.s+(r.x?': '+r.x.substring(0,200):'')));}
}).catch(function(err){show(false,'Request failed: '+err);});
}
var tplHere=${templateHere ? 'true' : 'false'};
f.addEventListener('submit',function(e){e.preventDefault();var u=f.querySelector('input[name="useForNew"]');
if(tplHere&&u&&!u.checked&&!window.confirm('Clearing "Use these settings for new HVM clusters" removes the new-cluster template. Clusters without settings of their own stop being watched. Continue?'))return;
send('save');});
var rb=f.querySelector('[data-action="reset"]');
if(rb)rb.addEventListener('click',function(){if(window.confirm('Remove the settings of this cluster?'))send('reset');});
function on(a,fn){var x=f.querySelector('[data-action="'+a+'"]');if(x)x.addEventListener('click',fn);}
on('test',function(){send('test');});
on('mute',function(){if(window.confirm('Hold every alert mail of this cluster for the chosen time?'))send('mute');});
on('unmute',function(){send('unmute');});
var sels=f.querySelectorAll('tr.hvmca-level select');
function marks(){var n=0;for(var i=0;i<sels.length;i++){var d=sels[i].value==='default';if(!d)n++;
var tr=sels[i].parentNode.parentNode;tr.style.background=d?'':'#fff8e1';}
var c=f.querySelector('.hvmca-changed');if(c){c.textContent=n?(n+' changed from Default (not saved yet)'):'all at Default (not saved yet)';
c.className='hvmca-changed '+(n?'text-danger':'text-muted');}}
for(var i=0;i<sels.length;i++)sels[i].addEventListener('change',marks);
var dl=f.querySelector('details.hvmca-levels');
if(dl)dl.addEventListener('toggle',function(){var o=dl.open;var ch=dl.querySelector('.hvmca-chev'),tg=dl.querySelector('.hvmca-toggle');
if(ch)ch.textContent=o?'\u25BE':'\u25B8';if(tg)tg.textContent=o?'Hide levels \u25B4':'Show levels \u25BE';});
on('reset-levels',function(){for(var i=0;i<sels.length;i++)sels[i].value='default';marks();});
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
