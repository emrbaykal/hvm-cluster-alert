package com.hpe.hvmalert

import com.morpheusdata.core.Plugin
import com.morpheusdata.views.HandlebarsRenderer

/**
 * HVM Cluster Alert: watches HVM clusters (layout 1.3 / 2.0) on VM Essentials and mails problems and recoveries through
 * the appliance's own mail settings.
 *
 * No plugin settings: every HVM cluster gets an "HVM Alert" tab with its own alert settings, saved
 * through HvmAlertController into Cypher; a template from any tab covers clusters without settings, including clusters
 * created later. The checks run in a background scheduler started here and stopped in onDestroy().
 */
class HvmClusterAlertPlugin extends Plugin {

    private AlertScheduler scheduler

    @Override
    String getCode() {
        return 'hvm-cluster-alert-plugin'
    }

    @Override
    void initialize() {
        this.setName('HVM Cluster Alert')
        this.setDescription('Mails HVM cluster problems and recoveries: HVM alarms, host agent and power state, ' +
                'datastore state, quorum, DLM fencing, GFS2 withdraw, multipath paths and failover events from the agent log.')
        scheduler = new AlertScheduler(plugin: this, morpheus: morpheus)
        HvmClusterTabProvider tab = new HvmClusterTabProvider(plugin: this, morpheus: morpheus)
        this.pluginProviders.put(tab.code, tab)
        // Own renderer (unused): for a plugin with controllers and no renderer, Morpheus adds a template loader to a list
        // that cannot grow (UnsupportedOperationException in DynamicTemplateLoader.addTemplateLoader, VME 9.0.2, 2026-11-08)
        // and rejects the upload with "Failed to parse plugin file" - after initialize() already ran.
        this.setRenderer(new HandlebarsRenderer('renderer', this.getClassLoader()))
        this.controllers.add(new HvmAlertController(plugin: this, morpheus: morpheus))     // saves the tab's settings form
        scheduler.start()
    }

    /** The running scheduler (the cluster tab reads its open problems), or null after onDestroy. */
    AlertScheduler getScheduler() {
        scheduler
    }

    @Override
    void onDestroy() {
        scheduler?.stop()
        scheduler = null
    }
}
