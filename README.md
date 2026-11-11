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
- **Mail recipients**
- **Relay HVM alarms**
- **Host checks**
- **Daily status mail**
- an **alert level** for each check (see [Checks and alert levels](#checks-and-alert-levels))
- four thresholds, with HPE's defaults as the minimums: agent silent 2 / 5 min, node unreachable 60 s, DLM fencing
  wait 30 s

Below the settings is the list of the cluster's **open problems** from the last check.

The top of the tab also shows **Last mail**: when the last alert mail for the cluster went out, or that it failed,
with the error and the next try. It is known only on the node that runs the checks.

- **Send test mail** sends one mail to the recipients in the form, saved or not. If SMTP fails, or the relay refuses
  some of the addresses, the tab says so at once. Limits: at most 10 addresses, one test mail per cluster every 30
  seconds, 10 per user per hour.
- **Planned work: Mute alerts** holds every mail of the cluster for 1, 2, 4, 8 or 24 hours.
  - The recipients get one "alerts muted until ..." mail when it starts, then nothing.
  - When the time is up, or someone clicks **Unmute**, one "mute ended" mail compares with what the recipients were
    told before the mute: problems open now (NEW or still OPEN), problems RESOLVED meanwhile, and the agent-log events
    of the mute (at most the last 50). Problems that opened and cleared during the mute are not listed.
  - A running mute cannot be replaced: Unmute first. A "monitor stopped" mail (the cluster no longer watched) still
    goes out during a mute.
  - The checks keep running and the tab keeps showing open problems.
  - The mute is stored in Cypher as `secret/hvm-cluster-alert/mute-<id>`, apart from the settings, so muting sends no
    "monitor started" mail and does not touch the new-cluster template.
- The **Alert levels** table is collapsed; its heading says how many rows are not at Default. Changed rows are
  highlighted. **Reset all to Default** sets every row back; Save stores it.

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
| Manager view | Agent silent > 2 min (warning) / > 5 min (critical); host power off or unknown; cluster member offline, unclean or fenced; datastore offline, warning or failed |
| Host, through the agent (as root, read-only) | Quorum lost (also when the agent reports half or fewer of the nodes reachable; 3 or more nodes, no witness, not stretched), host fenced, DLM or Corosync not active, DLM waiting on fencing longer than the fencing wait (see Timing), peer not reached > 60 s (agent `/quorum` endpoint), GFS2 withdraw, expected GFS2 datastores not mounted, failed multipath paths |
| Agent log events | Peer unreachable, HA failover ("host offline, moving vms"), host rebooting itself after losing DLM lockspace membership or quorum, quorum not achieved, DLM fence refused for a reachable node (split-brain guard), APD budget reached / VM shutdown, heartbeat write failed on all targets, DLM fence_ack; "Possible APD" (derived, see below) |

### Checks and alert levels

Each row is one line of the **Alert levels** table on the tab. Each row can be set to **Default**, **Warning**,
**Critical** or **Off**. Default keeps the level shown here. Where two levels are shown, the first is for the milder
case and the second for the worse one.

| Group | Check | Default level |
|---|---|---|
| HVM alarms | Active HVM alarm (not for a host in maintenance) | Critical when the alarm status is error, critical or failed; otherwise Warning |
| Manager view | Host power state off or unknown | Critical |
| | Host status warning / error or failed (Critical also when the manager says the host was fenced) | Warning / Critical |
| | Cluster member offline, unclean or fenced | Critical |
| | Agent silent past the warning / critical threshold | Warning / Critical |
| | Datastore warning / offline, failed or error | Warning / Critical |
| | Host in maintenance not reachable | Warning |
| Host checks | Quorum lost (reported by the host's agent) | Critical |
| | Host fenced | Critical |
| | DLM waiting on fencing longer than the fencing wait | Critical |
| | DLM or Corosync not active | Critical |
| | Peer not reached longer than the node unreachable time; nodes or hosts reported down | Critical |
| | GFS2 filesystem withdrawn | Critical |
| | Expected GFS2 datastores not mounted: some / none | Warning / Critical |
| | Multipath: some paths failed / all paths down | Warning / Critical |
| | GFS2 blocked on the whole cluster: DLM waits on a fence for a node that is still reachable | Critical |
| Agent log events | Peer unreachable, agent is taking action | Critical |
| | HA failover: host offline, VMs are being moved | Critical |
| | Host is rebooting itself: DLM lockspace membership lost, or quorum lost (self-fence) | Critical |
| | Quorum not achieved or lost (agent log) | Critical |
| | DLM fence refused for a reachable node that has not rebooted (split-brain guard) | Critical |
| | All Paths Down (APD): VMs being shut down (with the number powered off), a VM the agent could not power off, heartbeat budget reached, or APD / isolation message | Critical |
| | DLM lockspace membership lost (first notice before the self-reboot) | Warning |
| | Heartbeat write failed on all heartbeat targets | Warning |
| | DLM fence_ack attempted for a lost node | Warning |
| | Host heartbeat not updated | Warning |
| | Unhealthy heartbeat datastore path skipped | Warning |
| | Possible APD / APD likely (derived) | Warning / Critical |
| Plugin | The plugin's own check problems (host check could not run, not run as root, output incomplete, quorum state unknown, previous check still running, cluster or datastore state not readable). Cannot be set to Off | Warning |

How a level works:

- **It changes only the mails.** A Critical row adds CRITICAL to the subject of alert mails and colours the row red. The checks, the
  confirmation over two cycles, and the rules that combine checks (restart in maintenance, Possible APD) always use
  the default levels, so a level cannot hide or change another check.
- **Warning or Critical on a two-level row fixes it at that level.** The change between the milder and the worse case
  is then not mailed. Example: Agent silent set to Critical mails CRITICAL from the warning threshold (2 min).
- **The plugin's own check problems cannot be set to Off.** Without them the monitor could stop checking a host with
  no mail.
- **Off mails nothing for that check.**
  - The problem still shows in the tab's open problems, marked "(Off: not mailed)".
  - The tab names how many checks are off.
  - The "monitor started" and daily status mails list the checks that are off.
  - Agent-log events still waiting for a mail (for example while SMTP fails) follow the level in force when the mail
    goes out.
- **Saving a change sends a new "monitor started" mail**, as any settings change does.
- **Group switches.** Host checks and agent log events run only with **Host checks** ticked; HVM alarms only with
  **Relay HVM alarms**.
- **Upgrade.** Settings saved before 1.22.0 have no levels: every check stays at its default and no new "monitor
  started" mail is sent. Going back to 1.21.x sends one "monitor started" mail per cluster with levels set, and checks
  set to Off are mailed again.

The full list, with the default level of each check, is in [Checks and alert levels](#checks-and-alert-levels).

Corosync "Quorate" is not checked: on layouts 1.3 and 2.0 the agent decides quorum, and Corosync may show "No" on a
healthy cluster (HPE troubleshooting guide). Quorum, DLM and GFS2 checks run when the cluster has GFS2 datastores.

**Storage loss (APD).** In the storage-cut tests (agent 3.3.0), these were the signals:

- **Real-time.** All multipath paths down and GFS2 withdraw were mailed on the first check, about 1.5 to 2 minutes
  after the cut. That is a typical value, not a bound: see Timing for the worst case.
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
or a withdrawn GFS2. It stays silent when the host itself reported a peer loss within 5 minutes, because survivors log these lines while
DLM holds GFS2 until a fence. As a precaution it also stays silent within 5 minutes of a host in maintenance going
down (in a lab restart in maintenance the other hosts' heartbeat writes did not fail). It also stays silent when another host lost its storage at that time.

## Behaviour

- **Timing**
  - Checks run every 2 minutes.
  - A problem opens after 2 consecutive checks and resolves after 2 checks without it. Conditions whose source already
    waited (quorum lost, fenced, DLM or Corosync not active, GFS2 withdraw, all paths down, peer age, agent silent, DLM
    wait, a host in maintenance seen down) open on the first check. A "quorum lost" logged while every node is reachable,
    as a host logs once when it rejoins after a restart, needs a second check with a new line. A quorum line logged
    before the host's last boot is not used, and "quorum state unknown" waits 10 minutes after a boot.
  - DLM waiting on fencing opens when a check still sees it at least the fencing wait (30 s) after the check that first
    saw it. With checks 2 minutes apart that is in practice the second check, about 2 minutes. For a node that is still
    reachable (the agents do not fence it, see "GFS2 blocked" below) the wait counts from the time the agent logged it,
    so it usually opens on the first check.
  - **Faster checks while DLM may be blocked.** While a check shows DLM waiting, DLM or Corosync not active, GFS2
    blocked, or new agent lines about heartbeat writes failing on all targets, lost DLM membership or quorum, the next
    check starts about 30 s after the previous one ends instead of 2 minutes after it started, at most 6 times in a row
    (a host that does not answer still adds its 60 s timeout). Problems that need two checks then open and resolve
    sooner. A condition open longer than 10 minutes no longer triggers it.
  - A host that cannot answer delays the whole check, and every mail of it, by up to the 60 s command timeout (each
    host loss in the lab tests). From the next check on, a host whose agent is silent and that did not answer is not
    asked again. So a first mail can come up to about 3.5 minutes after a failure: the 2 minute interval, the check
    itself, and that wait.
  - The agent reboots its host about 60 s after its first notice (DLM lockspace membership lost, quorum loss
    confirmation 1/2). A condition that needs two checks can therefore not be mailed for that host before the reboot,
    and the host's own log lines are mostly read after it (agent 3.3.0, lab tests).
  - HVM alarms and agent-log events are mailed as they come.
- **GFS2 blocked on the cluster.** When a host's DLM leaves (for example its corosync stopped) but the host is still
  reachable, the agents do not fence it (split-brain guard) and GFS2 waits on every host until it reboots. The other
  hosts' heartbeat writes then fail, and after 6 failures in a row, about 2 minutes, their agents shut down all
  non-pinned VMs. In a lab test this powered off 31 VMs and the manager VM (VME 9.0.2, agent 3.3.0). The plugin raises
  one critical row for the cluster when an agent logs that it refuses such a fence, or when a fresh quorum line shows
  such a node while another host's heartbeat writes fail on all targets. The row names the node; the agent usually
  reboots it itself within about 2 minutes. It stays open for 3 minutes after its last signal, or until a new quorum
  line shows no such node. A node the same check reports lost (an ordinary host loss), a host in maintenance and lines
  re-read after a reload do not raise it.
- **Storage rows.** When one host has 3 or more multipath or GFS2 rows in a mail (or a cluster 3 or more datastore
  rows), they are shown as one row with the items listed in its detail; a datastore row also gives the manager's
  reason (up to 3 distinct). When the host already had other storage items open, the row title adds what is open on
  the host now ("now open on this host: ...", or "still open: ..." on a partial resolve). Repeated agent-log lines
  are shown as one row per host and event type with up to 3 distinct lines; a row whose lines name DLM nodes starts
  with "Nodes: ..." taken from all its lines.
- **Mail**
  - One mail per cluster to its recipients, carrying what is new, what changed severity, what resolved and the events.
    A RESOLVED row shows the problem as it was last mailed.
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
  - A host fully in maintenance that goes down (a restart, say) gets one warning, "Host in maintenance not reachable",
    resolved when it is back. It is a warning because its pinned VMs are down and not failed over, and it still counts
    toward quorum.
  - Not mailed for such a host: the other hosts' agent-log lines naming it (peer unreachable, fence_ack, HA failover,
    heartbeat), for the whole maintenance period; the anonymous heartbeat warnings, within 5 minutes of it going down
    and only while no other host shows a loss of its own; its own agent-log lines from the maintenance period, read when
    it is checked again. The period starts 4 minutes before the host was first seen fully in maintenance and ends 4
    minutes after it was last seen there. A host still evacuating ("maintenancing") keeps its events.
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
- **Open problems on one node.** The open-problem list and Last mail come from the node running the checks. On a
  multi-node manager, a page served by another node shows "No check result yet" and "not known on this node".
- **Mute after a reload.** A plugin reload or a takeover by another app node during a mute sends the "alerts muted
  until ..." mail again (the mail state is kept in memory).
- **Manager on the watched cluster.** A manager VM running on the cluster it watches cannot mail the loss of its own
  host until it is running again. A fault that blocks GFS2 on every host takes it down with the other VMs: in a lab
  test, `systemctl stop corosync` on one host of a 3-host GFS2 cluster (agent 3.3.0) left DLM on the other two hosts
  waiting on a fence their agents refused (the host was still reachable), and about 2 minutes later both agents shut
  down their non-pinned VMs, the manager VM among them. Only the first warnings were mailed. Run the manager outside the
  watched cluster where possible, and never stop corosync or dlm on a GFS2 host as a "single-host" test.
- **Restart in maintenance and a plugin reload.** The plugin keeps its maintenance records in memory. After an upload or
  a `morpheus-ui` restart, the "monitor started" mail can list the other hosts' lines about a maintenance restart of the
  last 15 minutes as earlier events.
- **Tab visibility.** Anyone who can open a cluster's page sees its recipients and who last saved the settings.

## License

Apache License 2.0, see `LICENSE`. Changes per version: `CHANGELOG.md`.
