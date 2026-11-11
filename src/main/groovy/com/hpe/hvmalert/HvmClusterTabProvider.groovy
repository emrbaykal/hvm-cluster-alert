package com.hpe.hvmalert

import com.morpheusdata.core.AbstractClusterTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse

/**
 * "HVM Alert" tab on every HVM cluster's detail page (Infrastructure > Clusters > cluster): the alert settings of this
 * cluster, then its open problems. The form posts to HvmAlertController, which stores them in Cypher
 * (TabSettings). The open problems come from this node's scheduler (the VME manager is one node).
 */
class HvmClusterTabProvider extends AbstractClusterTabProvider {

    static final String CODE = 'hvm-cluster-alert-tab'

    Plugin plugin
    MorpheusContext morpheus

    @Override Plugin getPlugin() { plugin }
    @Override MorpheusContext getMorpheus() { morpheus }
    @Override String getCode() { CODE }
    @Override String getName() { 'HVM Alert' }

    @Override
    Boolean show(ComputeServerGroup cluster, User user, Account account) {
        String type = cluster?.type?.code
        return type == null || type == AlertCycle.CLUSTER_TYPE
    }

    @Override
    HTMLResponse renderTemplate(ComputeServerGroup cluster) {
        if (cluster?.id == null) return HTMLResponse.success('<p>Cluster not found.</p>')
        Map f = [clusterId: cluster.id, clusterName: cluster.name, url: HvmAlertController.URL]
        try { f.nonce = morpheus.getWebRequest()?.getNonceToken() } catch (Throwable ignored) { }
        if (cluster.owner == null) {
            f.error = 'The cluster has no owner tenant; settings cannot be stored.'
        } else {
            try {
                f.own = TabSettings.parse(TabSettings.read(morpheus, cluster.owner, TabSettings.key(cluster.id)))
                f.template = TabSettings.parse(TabSettings.read(morpheus, cluster.owner, TabSettings.TEMPLATE_KEY))
                f.mute = TabSettings.parseMute(TabSettings.read(morpheus, cluster.owner, TabSettings.muteKey(cluster.id)))
                f.formToken = FormToken.issue(FormToken.secret(morpheus, cluster.owner, true), cluster.id, System.currentTimeMillis())
            } catch (Throwable t) {
                f.error = "Settings not readable from Cypher: ${t.class.simpleName}: ${t.message}".toString()
            }
        }
        try { f.open = (plugin as HvmClusterAlertPlugin)?.scheduler?.openFor(cluster.id) } catch (Throwable ignored) { }
        try {
            AlertScheduler sch = (plugin as HvmClusterAlertPlugin)?.scheduler
            f.mail = sch?.mailFor(cluster.id)
            f.leader = sch?.isLeader() == true
        } catch (Throwable ignored) { }
        HTMLResponse.success(SettingsPage.render(f))
    }
}
