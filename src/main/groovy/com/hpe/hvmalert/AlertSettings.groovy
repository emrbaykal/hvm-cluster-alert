package com.hpe.hvmalert

/**
 * Settings of the watched clusters. The plugin has no plugin settings: each HVM cluster's own
 * "HVM Alert" tab saves its settings in Cypher (TabSettings). A cluster without its own settings uses the new-cluster
 * template, saved from any cluster's tab with "Use these settings for new HVM clusters", so clusters created after the
 * install are watched from their first cycle. Without either, a cluster is not watched. The check interval is fixed.
 */
class AlertSettings {

    static final String OWN_SOURCE = 'this cluster'
    static final String TEMPLATE_SOURCE = 'new-cluster template'

    /** Settings applied to one watched cluster. Thresholds default to HPE's HVM guide. */
    static class ClusterRule {
        Long clusterId
        String name                       // cluster name, filled in when the cycle resolves the clusters
        String source = OWN_SOURCE        // OWN_SOURCE or TEMPLATE_SOURCE
        boolean enabled = true            // false: "Watch this cluster" cleared
        String fingerprint = ''           // hash of the settings; a change sends "monitor started"
        boolean dailyStatus = true        // a status mail when nothing was mailed for 24 h (proves the mail path)
        List<String> recipients = []
        boolean relayNativeAlarms = true
        boolean hostChecks = true
        int agentWarnMinutes = 2
        int agentCriticalMinutes = 5
        int nodeUnreachableSeconds = 60
        int fencingWaitSeconds = 30
    }

    int intervalMinutes = 2                            // fixed: there are no plugin settings
    List<ClusterRule> rules = []                       // one per watched HVM cluster in this cycle

    /**
     * The rule for a cluster: its own tab settings, else the new-cluster template; null when neither exists or the
     * applicable settings do not watch the cluster.
     */
    static ClusterRule ruleFor(Long clusterId, String ownJson, String templateJson) {
        ClusterRule r = TabSettings.ruleFromJson(clusterId, ownJson, OWN_SOURCE)
        if (r == null) r = TabSettings.ruleFromJson(clusterId, templateJson, TEMPLATE_SOURCE)
        (r != null && r.enabled && r.recipients) ? r : null
    }

    ClusterRule ruleForId(Long clusterId) {
        rules.find { it.clusterId == clusterId }
    }

    static List<String> splitRecipients(String text) {
        (text ?: '').split(/[,;\s]+/).collect { it.trim() }.findAll { it }
    }

    /** A posted checkbox is "on"; a missing key means the default applies. */
    static boolean checkbox(Map m, String k, boolean dflt) {
        if (!m.containsKey(k)) return dflt
        String v = m[k]?.toString()?.toLowerCase()
        v == 'on' || v == 'true' || v == '1'
    }

    static int toInt(String v, int dflt, int min) {
        if (!v?.trim()) return dflt
        try { Math.max(min, new BigDecimal(v.trim()).intValue()) } catch (NumberFormatException ignored) { dflt }
    }
}
