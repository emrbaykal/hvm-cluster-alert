package com.hpe.hvmalert

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Permission
import com.morpheusdata.model.User
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/**
 * Saves or removes one cluster's tab settings (Cypher), and the new-cluster template when "Use these settings for new
 * HVM clusters" is ticked. The tab's form posts here with fetch():
 * POST /plugin/hvm-cluster-alert/settings, form-encoded: clusterId, op (save | reset), the form fields, useForNew.
 * The field is "op", not "action": Grails puts its own "controller"/"action" into the params map (VME 9.0.2, 2026-11-08:
 * a posted action=save arrived as another value and the save answered "Unknown action").
 *
 * Plugin API 1.4.x routing (web/Dispatcher.java) only checks that the user's permission map contains the route's
 * permission code, and never the HTTP method, so this handler checks everything itself: POST only, the custom
 * X-Requested-With header (forces a CORS preflight cross-site) and, as defence in depth, a same-origin Origin when one
 * is sent (an absent Origin or "null" passes; Morpheus's CSRF token is the real protection), the user's
 * "Clusters" (infrastructure-cluster) permission at full, the user's tenant against the cluster owner (or the
 * master tenant; the master-tenant override is unverified, the lab has one tenant), a form token proving the user opened
 * this cluster's page (FormToken: group access), an HVM cluster, and every field. Unknown fields are ignored. Each change is logged with the user.
 * Field-verified on VME 9.0.2 (2026-11-08): Morpheus serves /plugin routes only to a browser session and refuses a POST
 * without the page's CSRF token (/error/invalid-csrf) before this code runs.
 */
@Slf4j
class HvmAlertController implements PluginController {

    static final String PATH = '/hvm-cluster-alert/settings'
    static final String URL = '/plugin' + PATH         // where Morpheus serves plugin routes
    static final String PERMISSION = 'infrastructure-cluster'

    Plugin plugin
    MorpheusContext morpheus

    @Override Plugin getPlugin() { plugin }
    @Override MorpheusContext getMorpheus() { morpheus }
    @Override String getCode() { 'hvm-cluster-alert-controller' }
    @Override String getName() { 'HVM Cluster Alert settings' }

    @Override
    List<Route> getRoutes() {
        [Route.build(PATH, 'settings', Permission.build(PERMISSION, 'full'))]
    }

    def settings(ViewModel<Map> model) {
        try {
            return handle(model)
        } catch (Throwable t) {
            log.error("[hvm-cluster-alert] tab settings request failed: ${t.class.simpleName}: ${t.message}")
            return reply(500, false, "Not saved: ${t.class.simpleName}: ${t.message}".toString())
        }
    }

    private JsonResponse handle(ViewModel<Map> model) {
        def req = model?.request
        String refused = refusal(req)
        if (refused) return reply(403, false, refused)
        User user = model.user
        if (user == null || user.enabled == false) return reply(403, false, 'Not signed in.')
        if (user.permissions?.get(PERMISSION) != 'full') {
            return reply(403, false, 'Your role needs the Clusters permission at Full to change these settings.')
        }
        Map p = params(model.object, req)
        String cid = p.clusterId?.toString()
        ComputeServerGroup cluster = cid ==~ /\d{1,18}/ ? morpheus.services.cluster.get(cid.toLong()) : null
        if (cluster == null) return reply(404, false, 'Cluster not found.')
        if (cluster.type?.code != null && cluster.type.code != AlertCycle.CLUSTER_TYPE) return reply(400, false, 'Not an HVM cluster.')
        if (cluster.owner == null) return reply(400, false, 'The cluster has no owner tenant; settings cannot be stored.')
        boolean sameTenant = user.account?.id != null && user.account.id == cluster.owner.id
        if (!sameTenant && user.account?.masterAccount != true) return reply(403, false, 'The cluster belongs to another tenant.')
        if (!FormToken.valid(FormToken.secret(morpheus, cluster.owner, false), cluster.id, p.formToken, System.currentTimeMillis())) {
            return reply(403, false, "The form has expired or was not opened from this cluster's page. Reload the page and save again.")
        }
        String who = user.username ?: "user ${user.id}"
        if (p.op == 'reset') {
            TabSettings.delete(morpheus, cluster.owner, TabSettings.key(cluster.id))
            log.info("[hvm-cluster-alert] cluster '${cluster.name}' (${cluster.id}): own settings removed by ${who}")
            return reply(200, true, 'Removed. From the next check this cluster uses the new-cluster template, or is not watched without one.')
        }
        if (p.op != 'save') return reply(400, false, 'Unknown operation.')
        Map v = TabSettings.validate(p)
        if (v.errors) return reply(400, false, (v.errors as Map).values().join(' '), v.errors as Map)
        long now = System.currentTimeMillis()
        TabSettings.write(morpheus, cluster.owner, TabSettings.key(cluster.id), TabSettings.toJson(v.values as Map, who, now))
        String template = templateChange(cluster, AlertSettings.checkbox(p, 'useForNew', false), v.values as Map, who, now)
        Map s = v.values as Map
        log.info("[hvm-cluster-alert] cluster '${cluster.name}' (${cluster.id}): tab settings saved by ${who}: watch=${s.alertEnabled} " +
                "recipients=${(s.recipients as List).size()} alarms=${s.relayNativeAlarms} hostChecks=${s.hostChecks} daily=${s.dailyStatus} " +
                "agent=${s.agentWarnMinutes}/${s.agentCriticalMinutes}m node=${s.nodeUnreachableSeconds}s dlm=${s.fencingWaitSeconds}s${template}")
        String tplText = template == '; template saved' ? ' New-cluster template saved.' :
                template == '; template removed' ? ' New-cluster template removed: clusters without settings of their own are no longer watched.' : ''
        reply(200, true, 'Saved. The settings apply from the next check.' + tplText)
    }

    /** Writes the template when asked; removes it when this cluster was its source and the box was cleared. */
    private String templateChange(ComputeServerGroup cluster, boolean useForNew, Map values, String who, long now) {
        if (useForNew) {
            TabSettings.write(morpheus, cluster.owner, TabSettings.TEMPLATE_KEY,
                    TabSettings.toJson(values, who, now, [templateFrom: cluster.id, templateFromName: cluster.name]))
            return '; template saved'
        }
        Map current = TabSettings.parse(TabSettings.read(morpheus, cluster.owner, TabSettings.TEMPLATE_KEY))
        if (current?.templateFrom?.toString() == cluster.id.toString()) {
            TabSettings.delete(morpheus, cluster.owner, TabSettings.TEMPLATE_KEY)
            return '; template removed'
        }
        return ''
    }

    /** Null when the request may change settings, else why not. */
    static String refusal(def req) {
        if (req == null) return 'No request.'
        if (req.method?.toString()?.toUpperCase() != 'POST') return 'POST only.'
        if (req.getHeader('X-Requested-With') != 'XMLHttpRequest') return 'Use the form on the cluster tab.'
        String origin = req.getHeader('Origin')
        if (origin && origin != 'null') {
            String authority
            try { authority = new URI(origin).authority } catch (Exception ignored) { authority = null }
            List<String> hosts = [req.getHeader('Host'), req.getHeader('X-Forwarded-Host')]
                    .findAll { it }.collectMany { it.toString().split(',').collect { h -> h.trim() } }
            if (authority == null || !(authority in hosts)) return 'Cross-site request refused.'
        }
        return null
    }

    /** Request parameters as single strings: the posted servlet parameters first, then the model's (Grails) map. */
    static Map params(Object o, def req) {
        Map out = [:]
        if (req != null) {
            try {
                req.parameterMap?.each { k, v -> out[k.toString()] = one(v) }
            } catch (Throwable ignored) { }
        }
        if (o instanceof Map) {
            ((Map) o).each { k, v -> if (!out.containsKey(k.toString())) out[k.toString()] = one(v) }
        }
        out
    }

    private static Object one(Object v) {
        if (v instanceof String[]) return ((String[]) v).length ? ((String[]) v)[0] : null
        if (v instanceof Collection) return ((Collection) v) ? ((Collection) v).first()?.toString() : null
        v
    }

    private static JsonResponse reply(int status, boolean ok, String msg, Map errors = null) {
        JsonResponse r = JsonResponse.of([success: ok, msg: msg] + (errors ? [errors: errors] : [:]))
        r.status = status
        r
    }
}
