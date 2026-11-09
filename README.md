# HVM Cluster Alert

A plugin for **HPE Morpheus VM Essentials (VME)** that watches HVM clusters and mails problems and recoveries through
the appliance's own mail settings. Each cluster gets an **HVM Alert** tab with its alert settings and its open
problems.

It changes nothing in Morpheus or on the hosts. It writes only a 0-byte lock file per host
(`/run/hvm-cluster-alert.lock`) and its own Cypher keys.

> Community plugin, not an HPE product and not supported by HPE. Use at your own risk; read "Known limitations"
> before running it on a production cluster.

## Requirements

| | |
|---|---|
| Appliance | VM Essentials manager, tested on **9.0.2** (single manager VM); Morpheus Enterprise **9.1.0.1** with three app nodes |
| Clusters | HVM clusters, tested on layout 1.3 (HVM OS 24.04, Morpheus agent 3.3.0) with GFS2 datastores on FC, and on layout 2.0 (HVM OS 26.04, agent 4.0.1) with an NFS heartbeat datastore. Quorum, DLM and GFS2 checks run only where the cluster has GFS2 datastores, so they were not exercised on layout 2.0 |
| Mail | Administration > Settings > Email filled in; `morpheus-ui` restarted after a change (the plugin reads the SMTP settings that Morpheus loaded at start) |
| Permissions | To save a cluster's settings: role permission **Clusters: Full** and access to that cluster's page |
| Build (only to build from source) | JDK 11 to 17 (Gradle 7.6.4 wrapper). Plugin API `morpheus-plugin-api:1.4.1`, Groovy |

## Install

1. Download `hvm-cluster-alert-<version>-all.jar` from the
   [Releases](https://github.com/emrbaykal/hvm-cluster-alert/releases) page and compare its SHA-256 with the
   `.sha256` file next to it (`shasum -a 256 -c hvm-cluster-alert-<version>-all.jar.sha256`). To build it yourself:
   `./gradlew shadowJar` writes the same file to `build/libs/`; `./gradlew test` runs the unit tests.
2. Upload the jar under **Administration > Plugins > Add**.
3. Open an HVM cluster (**Infrastructure > Clusters >** cluster) and select the **HVM Alert** tab.
4. Fill in the mail recipients. Tick **Use these settings for new HVM clusters of this tenant** if every other
   cluster, including clusters created later, should be watched with the same settings. Then **Save**.
5. Within about 4 minutes the recipients receive a "monitor started" mail listing the open problems. The appliance log
   (**Administration > Health > Logs**, filter `hvm-cluster-alert`) shows one `cycle on <manager>` line every 2 minutes.

## Upgrade

Upload the new jar the same way. The new version takes over within 1 to 3 minutes (its first check is 60 s after the
load). Then check the log: one `scheduler stopped` / `scheduler started` pair and a `cycle on` line every 2 minutes.

If an upload fails ("Failed to parse plugin file"), Morpheus has still started the new code and never stops it, and it
loads that jar again on every restart. Delete the failed jar from `/var/opt/morpheus/morpheus-ui/plugins/` on the
manager, then run `morpheus-ctl restart morpheus-ui`. A failed instance stops its own checks after two ticks.

## Uninstall

1. Delete the plugin under **Administration > Plugins**.
2. If the log still shows `[hvm-cluster-alert]` lines afterwards, run `morpheus-ctl restart morpheus-ui`.
3. Remove the Cypher keys under `secret/hvm-cluster-alert/` (**Tools > Cypher**) in each tenant that saved settings.
4. Optional: remove `/run/hvm-cluster-alert.lock` on the hosts. It is on tmpfs and disappears at the next reboot.

## The HVM Alert tab

Every HVM cluster's page has an **HVM Alert** tab with these settings:

- **Watch this cluster**
- mail recipients
- relay HVM alarms
- host checks
- daily status mail
- four thresholds, with HPE's defaults as the minimums: agent silent 2 / 5 min, node unreachable 60 s, DLM fencing
  wait 30 s

Below the settings is the list of the cluster's **open problems** from the last check.

- **Save** stores the settings in Cypher, `secret/hvm-cluster-alert/cluster-<id>`, in the tenant that owns the
  cluster. **Remove the settings of this cluster** deletes that key.
- **Use these settings for new HVM clusters of this tenant** also saves them as the template
  `secret/hvm-cluster-alert/new-clusters`. Every cluster without settings of its own is watched with the template,
  including clusters created later.
  - Clearing the box on the cluster the template came from removes the template, after a confirmation.
  - A template must watch. To pause only its source cluster, first save the template from another cluster.
- A cluster with neither its own settings nor a template is not watched. The tab says which settings apply.
- An HVM alarm acknowledged in VME while it is open stays in the list, marked "(acknowledged)", until it clears. An
  alarm acknowledged before the plugin saw it is not listed or mailed.
- Recipients must be plain addresses (`name@domain.tld`, what jakarta.mail parses).

## What it watches

| Source | Checks |
|---|---|
| HVM alarms | Active alarms with category `mvm.alarm.<clusterId>` (memory, CPU, datastore, host unreachable, ...), mailed when they open and when they clear |
| Manager state | Agent silent > 2 min (warning) / > 5 min (critical); host power off or unknown; cluster member offline, unclean or fenced; datastore offline, warning or failed |
| Host, through the agent (as root, read-only) | Quorum lost, host fenced, DLM waiting on fencing > 30 s, peer not reached > 60 s (agent `/quorum` endpoint), GFS2 withdraw, expected GFS2 datastores not mounted, failed multipath paths |
| Agent log events | Peer unreachable, HA failover ("host offline, moving vms"), host rebooting itself after losing DLM lockspace membership, APD budget reached / VM shutdown, heartbeat write failed on all targets, DLM fence_ack, quorum lost; "Possible APD" (derived, see below) |

Corosync "Quorate" is not checked: on layouts 1.3 and 2.0 the agent decides quorum, and Corosync may show "No" on a
healthy cluster (HPE troubleshooting guide). Quorum, DLM and GFS2 checks run when the cluster has GFS2 datastores.

**Storage loss (APD).** In the storage-cut tests (agent 3.3.0), these were the signals:

- **Real-time.** All multipath paths down and GFS2 withdraw were mailed on the first check, about 1.5 to 2 minutes
  after the cut.
- **The agent's own lines.** At the 6th failed heartbeat check the agent reports its budget as reached.
  - Whether it then shuts its VMs down followed its network heartbeat, not the count. With the network heartbeat
    still answering it left the VMs alone; without it, the agent started shutting them down at the 6th or 7th check.
  - After two checks without DLM lockspace membership the agent rebooted the host, about 3 minutes after the cut. So
    its own lines are often read only after that reboot.
- **Gaps.** HPE's documentation describes the shutdown of non-pinned VMs after 6 failed writes (9 during stretch
  arbitration) and does not mention the network-heartbeat condition or the self-reboot. A shutdown of running VMs was
  not observed: the test host had no running VMs.

**Possible APD (derived).** Where the agent's own lines are missing, the plugin raises a warning when one host logs 6
consecutive "on all heartbeat targets" failures. It raises a critical when that host also has all multipath paths down
or a withdrawn GFS2. It stays silent when the host itself reported a peer loss within 5 minutes, because survivors log
these lines while DLM holds GFS2 until a fence. It also stays silent when another host lost its storage at that time.

## Behaviour

- **Timing**
  - Checks run every 2 minutes.
  - A problem opens after 2 consecutive checks and resolves after 2 checks without it. Conditions whose source already
    waited (quorum lost, fenced, GFS2 withdraw, all paths down, peer age, agent silent, DLM wait) open on the first
    check.
  - HVM alarms and agent-log events are mailed as they come.
- **Mail**
  - One mail per cluster to its recipients, carrying what is new, what changed severity, what resolved and the events.
  - A failed send loses nothing. The retry waits one interval and doubles per failure, up to an hour.
  - A "monitor started" summary goes out on the second check after a start, a takeover, a settings change or a new
    cluster. It includes agent-log events from the 15 minutes before the start, so a failure that also restarted the
    manager VM is still reported.
  - The daily status mail, if ticked, is sent when nothing was mailed to a cluster for 24 hours, so a broken mail path
    gets noticed.
  - One "monitor stopped" mail goes to the last recipients when a cluster stops being watched.
  - A relay that refuses one recipient does not block the others.
- **Hosts**
  - Hosts in maintenance are not checked, and what other hosts report about them is dropped.
  - A host whose check cannot run keeps its open problems; they are not reported as resolved.
- **Leadership.** A distributed lock picks the node that checks and mails. On a single manager VM that is the manager.

## Load

Per check and host:

- one agent command; the script runs in about 0.06 s and uses 0.05 to 0.07 s of CPU, its largest process about 13 MB of
  memory (measured on layout 1.3 hosts);
- the agent log, read from the last offset (about 0.5 MB per check on the lab hosts; up to 4 MB on the first check after a
  start);
- about 2 KB of output back to the manager on a quiet check; the output is capped at roughly 100 KB;
- every root command runs under `timeout -k 2 6`, and only one copy runs per host (flock).

The agent runs the script through sudo, so each check adds about 6 KB per host to the host's auth.log, about 4.3 MB a
day; on the lab hosts that was 6 to 10 % of the file, the rest being the agent's own sudo calls. Worth knowing if
auth.log goes to a SIEM.

On the manager, the plugin thread used about 12 ms of CPU per check on the node running the checks and a few ms per
interval on the others, and the plugin's objects took under 20 KB of heap, with about 150 classes loaded (Morpheus
Enterprise 9.1.0.1, three app nodes, one 2-host cluster).

## Security model

- The tab posts to the plugin route `/plugin/hvm-cluster-alert/settings`. Morpheus serves that route only to a browser
  session, with the page's CSRF token.
- The plugin itself checks:
  - POST only, the `X-Requested-With` header and the same origin;
  - the Clusters: Full permission;
  - the user's tenant against the cluster owner;
  - a token the tab signs for that cluster. Only users who can open the cluster's page can save, which covers
    group access. The token is valid for 8 hours and signed with a per-tenant key in Cypher
    (`secret/hvm-cluster-alert/form-key`);
  - every field.
- Every change is logged with the user's name.
- Anyone with Tools: Cypher access in the tenant can also change the keys there, and the plugin does not log that.
- The host script is fixed and read-only. Only numbers from the plugin's own state go into it.

## Known limitations

- **Template source deleted.** If the cluster a template was saved from is deleted, the template stays and can be
  removed only under Tools > Cypher (`secret/hvm-cluster-alert/new-clusters`).
- **Open problems on one node.** The open-problem list comes from the node running the checks. On a multi-node manager,
  a page served by another node shows "No check result yet".
- **Manager on the watched cluster.** A manager VM running on the cluster it watches cannot mail the loss of its own
  host until it is running again.
- **Tab visibility.** Anyone who can open a cluster's page sees its recipients and who last saved the settings.

## License

Apache License 2.0, see `LICENSE`. Changes per version: `CHANGELOG.md`.
