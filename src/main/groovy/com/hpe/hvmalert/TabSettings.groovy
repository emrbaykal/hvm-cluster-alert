package com.hpe.hvmalert

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.cypher.CypherAccess
import com.morpheusdata.model.Account
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * The settings saved from a cluster's "HVM Alert" tab. They live in Cypher, the appliance's encrypted key/value store,
 * in the tenant that owns the cluster, so the plugin writes no Morpheus object:
 *   secret/hvm-cluster-alert/cluster-<id>    one cluster's own settings
 *   secret/hvm-cluster-alert/new-clusters    the template for clusters without their own settings
 * An admin sees the keys under Tools > Cypher; removing one there has the same effect as in the tab. Anyone with Cypher
 * access in that tenant can change or delete them there, and the plugin does not log such changes.
 */
class TabSettings {

    static final String KEY_PREFIX = 'secret/hvm-cluster-alert/cluster-'
    static final String TEMPLATE_KEY = 'secret/hvm-cluster-alert/new-clusters'
    // Cypher keys expire with their lease (UI default 32 days, morpheus-docs tools/cypher.rst). Plugin API javadoc:
    // leaseTimeout in milliseconds (MorpheusSynchronousCypherService.write). Ten years.
    static final long LEASE_MS = 10L * 365L * 24L * 3600L * 1000L
    // dot-atom local part (RFC 5322 atext) and at least two DNS labels: only what jakarta.mail InternetAddress parses (a@b.c. or a@b..c
    // would pass a looser pattern and then make every send of the cluster fail)
    static final String EMAIL = '[A-Za-z0-9!#$%&\'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&\'*+/=?^_`{|}~-]+)*@[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+'
    static final int MAX_RECIPIENTS = 50

    /** Fields the form posts and the limits each number must stay within. */
    // Lower bounds follow HPE's cadence: peers pinged every 20 s and unreachable at 60 s (hvm/architecture.rst:29-30),
    // DLM fence wait up to 30 s (architecture.rst:172-173), agent concern level 2 min (hvm/monitoring.rst:116-118).
    static final Map<String, List<Integer>> NUMBERS = [agentWarnMinutes: [2, 1440], agentCriticalMinutes: [2, 1440],
                                                       nodeUnreachableSeconds: [60, 3600], fencingWaitSeconds: [30, 3600]]
    static final List<String> CHECKBOXES = ['alertEnabled', 'relayNativeAlarms', 'hostChecks', 'dailyStatus']
    /** What a cluster without saved settings shows: HPE's defaults, no recipients. */
    static final Map DEFAULTS = [alertEnabled: true, recipients: [], relayNativeAlarms: true, hostChecks: true, dailyStatus: true,
                                 agentWarnMinutes: 2, agentCriticalMinutes: 5, nodeUnreachableSeconds: 60, fencingWaitSeconds: 30, levels: [:]]

    static String key(Long clusterId) {
        KEY_PREFIX + clusterId
    }

    /** Planned-work mute of one cluster, kept apart from the settings so muting does not change them (no new started mail). */
    static final String MUTE_PREFIX = 'secret/hvm-cluster-alert/mute-'
    static final List<Integer> MUTE_HOURS = [1, 2, 4, 8, 24]

    static String muteKey(Long clusterId) {
        MUTE_PREFIX + clusterId
    }

    static String muteJson(long untilMs, String user, long nowMs) {
        JsonOutput.toJson([until: untilMs, by: user ?: 'unknown', at: nowMs])
    }

    /** [until (ms), by, at] of a stored mute, or null; an expired mute is returned too (the caller compares with now). */
    static Map parseMute(String json) {
        if (!json?.trim()) return null
        try {
            Object m = new JsonSlurper().parseText(json)
            if (!(m instanceof Map) || !(((Map) m).until instanceof Number)) return null
            return [until: ((Number) ((Map) m).until).longValue(), by: ((Map) m).by?.toString() ?: '?',
                    at: ((Map) m).at instanceof Number ? ((Number) ((Map) m).at).longValue() : null]
        } catch (Exception ignored) {
            return null
        }
    }

    /**
     * Checks the posted form. Only known fields are read; a checkbox that is not posted is off.
     * @return [errors: Map field -> message, values: Map ready to store]
     */
    static Map validate(Map params) {
        Map<String, String> errors = [:]
        Map values = [version: 1]
        CHECKBOXES.each { String k -> values[k] = AlertSettings.checkbox(params ?: [:], k, false) }
        String text = params?.recipients?.toString() ?: ''
        List<String> rcpt = AlertSettings.splitRecipients(text)
        if (text.length() > 2000) {
            errors.recipients = 'Mail recipients: at most 2000 characters.'
        } else if (rcpt.size() > MAX_RECIPIENTS) {
            errors.recipients = "Mail recipients: at most ${MAX_RECIPIENTS} addresses.".toString()
        } else if (rcpt.any { !(it ==~ EMAIL) }) {
            errors.recipients = "Not a mail address: ${rcpt.findAll { !(it ==~ EMAIL) }.take(5).join(', ')}".toString()
        } else if (!rcpt && values.alertEnabled) {
            errors.recipients = 'Enter at least one mail address, or clear "Watch this cluster".'
        }
        values.recipients = rcpt.unique()
        if (AlertSettings.checkbox(params ?: [:], 'useForNew', false) && !values.alertEnabled) {
            errors.useForNew = 'The new-cluster template must watch: tick "Watch this cluster", or clear "Use these settings for new ' +
                    'HVM clusters". Clearing it on the cluster the template came from removes the template, and clusters without ' +
                    'settings of their own stop being watched.'
        }
        NUMBERS.each { String k, List<Integer> range ->
            String v = params?.get(k)?.toString()?.trim()
            if (!(v ==~ /\d{1,5}/) || v.toInteger() < range[0] || v.toInteger() > range[1]) {
                errors[k] = "${k}: a whole number from ${range[0]} to ${range[1]}.".toString()
            } else {
                values[k] = v.toInteger()
            }
        }
        List<String> badLevels = Checks.ALL*.id.findAll { String id ->
            String lv = params?.get(Checks.FIELD + id)?.toString()?.trim()?.toLowerCase()
            lv && !(lv in Checks.LEVELS)
        }
        if (badLevels) errors.levels = "Alert level not known for: ${badLevels.join(', ')}.".toString()
        values.levels = Checks.clean(Checks.ALL*.id.collectEntries { String id ->
            [(id): params?.get(Checks.FIELD + id)?.toString()?.trim()?.toLowerCase()]
        })
        if (!errors.agentWarnMinutes && !errors.agentCriticalMinutes && values.agentCriticalMinutes < values.agentWarnMinutes) {
            errors.agentCriticalMinutes = 'Agent silent critical must not be lower than the warning.'
        }
        [errors: errors, values: values]
    }

    static String toJson(Map values, String user, long nowMs, Map extra = [:]) {
        JsonOutput.toJson(values + extra + [updatedBy: user ?: 'unknown', updatedAt: nowMs])
    }

    /** The stored settings as a map, or null when there are none or the value is not ours. */
    static Map parse(String json) {
        if (!json?.trim()) return null
        try {
            Object m = new JsonSlurper().parseText(json)
            if (!(m instanceof Map) || ((Map) m).version == null) return null
            Object r = ((Map) m).recipients
            // a hand-edited value of the wrong shape is treated like a foreign value (falls back to the template)
            return (r == null || (r instanceof List && ((List) r).every { it instanceof String && it ==~ EMAIL })) ? (Map) m : null
        } catch (Exception ignored) {
            return null
        }
    }

    /** The rule for a cluster from stored settings; enabled = false when "Watch this cluster" is cleared. */
    static AlertSettings.ClusterRule ruleFromJson(Long clusterId, String json, String source) {
        Map m = parse(json)
        if (m == null) return null
        Map values = m.findAll { k, v -> !(k in ['updatedBy', 'updatedAt', 'templateFrom', 'templateFromName', 'levels']) }
        Map<String, String> levels = Checks.clean(m.levels)
        if (levels) values.levels = levels      // settings saved before 1.22.0 keep their fingerprint
        new AlertSettings.ClusterRule(clusterId: clusterId, source: source, enabled: m.alertEnabled == true,
                fingerprint: source.take(1) + Integer.toHexString(new TreeMap(values.collectEntries { k, v -> [(k.toString()): v?.toString()] }).toString().hashCode()),
                recipients: (m.recipients ?: []).collect { it.toString() },
                relayNativeAlarms: m.relayNativeAlarms == true, hostChecks: m.hostChecks == true, dailyStatus: m.dailyStatus == true,
                agentWarnMinutes: AlertSettings.toInt(m.agentWarnMinutes?.toString(), 2, NUMBERS.agentWarnMinutes[0]),
                agentCriticalMinutes: AlertSettings.toInt(m.agentCriticalMinutes?.toString(), 5, NUMBERS.agentCriticalMinutes[0]),
                nodeUnreachableSeconds: AlertSettings.toInt(m.nodeUnreachableSeconds?.toString(), 60, NUMBERS.nodeUnreachableSeconds[0]),
                fencingWaitSeconds: AlertSettings.toInt(m.fencingWaitSeconds?.toString(), 30, NUMBERS.fencingWaitSeconds[0]),
                levels: Checks.clean(m.levels))
    }

    // Cypher access, in the tenant that owns the cluster

    static String read(MorpheusContext morpheus, Account owner, String key) {
        if (owner == null) return null
        morpheus.services.cypher.read(new CypherAccess(owner), key)
    }

    static void write(MorpheusContext morpheus, Account owner, String key, String json) {
        morpheus.services.cypher.write(new CypherAccess(owner), key, json, LEASE_MS)
    }

    static Boolean delete(MorpheusContext morpheus, Account owner, String key) {
        morpheus.services.cypher.delete(new CypherAccess(owner), key)
    }
}
