# Changelog

## 1.25.1

Fixes from the verification run of 1.25.0 (VME lab, VME 9.0.2, layout 1.3, agent 3.3.0, 2026-11-11: host reboot, host
isolated from its peers, all FC paths of a host down). What is alerted and the default levels are unchanged.

- **RESOLVED rows repeat the text that was mailed.** A change mail moves only the rows it showed (NEW, CHANGED)
  forward; before, a host status message or a peer age that changed without a mail appeared for the first time in the
  RESOLVED row. The "monitor started", daily and "mute ended" mails list every open row and move all of them forward.
- **A later storage row names what the host has open.** When a host's storage loss arrives over two mails, the second
  folded row reads, for example, "Storage: 5 GFS2 filesystem(s) withdrawn (now open on this host: 13 map(s) with all
  paths down, 6 GFS2 filesystem(s) withdrawn)"; a partial resolve adds "(still open: ...)".
- **Quorum lines from before a host's boot are ignored.** The host check prints the host's uptime (`@@UP`), and a
  quorum line logged before the last boot, read now or kept from an earlier check, no longer raises Quorum lost, DLM
  not active or Corosync not active after the host is back. "Agent quorum state unknown" waits 10 minutes after a
  boot (the first line came about 2-2.5 minutes after it).
  Output without `@@UP` is read as before.
- **Fence rows name every node.** An aggregated row whose lines name DLM nodes starts with "Nodes: ...", taken from all
  its lines, not only the 3 shown.
- **Text.** The minority Quorum lost row says the host is in the minority and that its agent fences it at the next
  quorum check if quorum is still lost. The derived "APD likely" row no longer says the host destroys its VMs: in the
  lab tests the agent shut VMs down only when its network heartbeat also failed. A datastore the manager marks not
  online is titled with its status ("Datastore not online: warning"). The datastore fold keeps the manager's reason
  (up to 3 distinct). The host status row of a fenced host is titled "Host fenced (manager host status)".

## 1.25.0

From the pre-production failure tests (VME lab, VME 9.0.2, layout 1.3, agent 3.3.0, 2026-11-10/11).

- **GFS2 blocked on the cluster (new row, Critical).** One row for the cluster when DLM waits on a fence for a node
  that is still reachable: an agent logs "NOT acking reachable node" (split-brain guard), or a fresh quorum line shows
  such a node while another host's heartbeat writes fail on all targets. In the test that ended with corosync stopped
  on one host, the other two hosts powered off 31 VMs, the manager VM among them, 106 s after the last mail, which
  carried three warnings.
- **Faster checks while DLM may be blocked.** The next check starts about 30 s after the previous one ends, instead of
  2 minutes after it started, while DLM waits, DLM or Corosync is not active (for up to 10 minutes), GFS2 is blocked,
  or the agents log heartbeat failures on all targets, lost DLM membership or quorum; at most 6 times in a row.
- **DLM waiting on a reachable node** is timed from the agent's log line, so it usually opens on the first check.
- **VMs powered off by the APD shutdown are counted** ("the agent powered off N VM(s)"), and a VM the agent could not
  power off ("Error destroying VM <id> - Exit Code", the local form only: a surviving host's "on remote host" line in
  an ordinary failover is not one) is a critical event; both under the APD row.
- **Storage rows folded.** 3 or more multipath / GFS2 rows of one host in a mail become one row ("13 map(s) with all
  paths down, 6 GFS2 filesystem(s) withdrawn"), and 3 or more datastore rows of a cluster one row; the items are in
  the detail. The subject counts the rows of the table.
- **Aggregated event rows keep up to 3 distinct lines** (for example each node a fence was refused for).
- **A host the manager reports as fenced** ("Host has been fenced (nodedown)") is Critical in the host status row.

## 1.24.0

From the pre-production failure tests on the VME lab (VME 9.0.2, layout 1.3, agent 3.3.0, 2026-11-10/11).

- **New agent-log detections (agent 3.3.0 phrases).**
  - "Quorum Not Achieved: 1 of 3 nodes reachable (need 2)" and "Quorum loss confirmation 1/2" now raise the quorum
    row of the agent log (Critical), renamed "Quorum not achieved or lost (agent log)". Before, only the words "quorum
    lost" matched, which the agent never logged.
  - "Fencing myself from cluster due to loss of quorum" and "Self-fencing via immediate reboot" are mailed as "Host is
    fencing itself: quorum lost, immediate reboot" under the self-reboot row (Critical), now titled "Host is rebooting
    itself: DLM lockspace membership lost, or quorum lost (self-fence)".
  - New row **DLM fence refused for a reachable node that has not rebooted (split-brain guard)**, Critical: the agent
    line "DLM fence_wait: NOT acking reachable node ...". In the corosync-stop test GFS2 stayed blocked on every host
    until that node rebooted, and the other hosts shut their VMs down 100 s after the first such line.
  - Quorum lost is also raised when the agent's quorum state lists half or fewer of the nodes as reachable on a cluster
    of 3 or more nodes without witness or sites: the isolated host reported quorumAchieved=true with 1 of 3 reachable
    while its own check logged "Quorum Not Achieved" and fenced the host.
- **DLM or Corosync not active opens on the first check.** In the corosync-stop test the state was read once and the
  agent rebooted the host before a second check, so it was never mailed.
- **"Host checks could not run" needs two consecutive failures.** A failure, a cycle in which the host was not asked,
  and one more failure after the host's reboot no longer confirm it (mailed after recovery in the isolation test).
- Wording: the heartbeat "on all heartbeat targets" title no longer calls the failures expected; it says that 6 in a
  row, about 2 minutes, can make the agent shut down the host's non-pinned VMs. "Host power state unknown" says that
  the manager also shows unknown when it loses the agent of a running host. The subject counts event rows, as in the
  table, not log lines.
- README: worst-case timing (a host that cannot answer delays the mails of a check by up to 60 s; first mail up to
  about 3.5 minutes), the effective DLM fencing wait, rows that cannot fire before an agent self-reboot, and the
  corosync-stop result under "Manager on the watched cluster".

## 1.23.1

- The collapsed Alert levels heading is drawn as a bar with an arrow and a "Show levels" / "Hide levels" label, so it
  reads as something to click (the appliance hides the browser's own disclosure arrow).

## 1.23.0

- **Send test mail.** A button on the HVM Alert tab sends one mail to the recipients in the form, saved or not, and
  shows at once an SMTP error or addresses the relay refused. At most 10 addresses, one per cluster every 30 seconds,
  10 per user per hour.
- **Last mail on the tab.** The tab shows when the last alert mail for the cluster went out, or that it failed, with
  the error and the next try. Before, a failing mail path was visible only in the appliance log.
- **Mute for planned work.** "Planned work" on the tab mutes every mail of the cluster for 1, 2, 4, 8 or 24 hours.
  - The recipients get one "alerts muted until ..." mail when it starts, then nothing.
  - When the time is up or someone clicks Unmute, one "mute ended" mail lists the open problems (NEW or still OPEN),
    the problems RESOLVED meanwhile and the agent-log events of that time (at most the last 50). Problems that opened
    and cleared during the mute are not listed.
  - A running mute cannot be replaced (Unmute first). "Remove the settings of this cluster" also removes its mute.
  - The checks keep running. The mute is stored apart from the settings (`secret/hvm-cluster-alert/mute-<id>`), so
    muting sends no "monitor started" mail and the template is not affected.
- **Alert levels table collapsed.** It opens on a click; its heading says how many rows are not at Default, changed
  rows are highlighted, and "Reset all to Default" sets every row back (then Save).

## 1.22.1

- Tab layout: labels are left-aligned, the Alert levels table has its own full-width section under its heading, and
  table headers are white on the green row (the appliance's grey header text was hard to read).

## 1.22.0

- **An alert level per check.** The HVM Alert tab lists every check, with its default level, in an **Alert levels**
  table. Each row can be set to Default, Warning, Critical or Off. The table is also the full list of what the
  plugin watches; the README has the same list under "Checks and alert levels".
  - A level changes only the mails. The checks and the rules that combine them (restart in maintenance, Possible APD)
    still use the default levels.
  - Off mails nothing for that check. The tab still lists the problem, marked "(Off: not mailed)", and says how many
    checks are off. The "monitor started" and daily status mails name them too.
  - Warning or Critical on a two-level check (agent silent, multipath, GFS2 mounts, datastore, host status, HVM alarm)
    fixes it at that level.
  - The plugin's own check problems (host check could not run, ...) cannot be set to Off.
  - Agent-log events waiting for a mail follow the level in force when the mail goes out.
  - The levels are stored with the cluster's settings in Cypher (`levels`, only rows not at Default) and carried by
    the new-cluster template. Settings saved before 1.22.0 keep every default and send no new "monitor started" mail.
    Going back to 1.21.x sends one "monitor started" mail per cluster with levels set; Off checks are mailed again.

## 1.21.3

- **A restart in maintenance mode is one warning.** When a host fully in maintenance goes down (power not on, agent
  silent past the warning threshold, or not reached by the other hosts), the cluster gets one WARNING, "Host in
  maintenance not reachable", and a resolved row when it is back. Agent silence alone needs two checks.
  - The other hosts' agent-log lines naming it (peer unreachable, fence_ack, HA failover, heartbeat) are not mailed
    for the whole maintenance period. Anonymous heartbeat warnings are not mailed within 5 minutes of it going down,
    unless another host shows a loss of its own.
  - Its own agent-log lines from the maintenance period (from 4 minutes before it was first seen fully in maintenance)
    are not mailed when it is checked again.
  - A host still evacuating ("maintenancing") is not covered: if it fails, its VMs are really lost, so its events
    stay.
  - Before, such a restart sent a CRITICAL "HA failover" mail (field, layout 2.0, agent 4.0.1). Tested on layout 1.3,
    agent 3.3.0 (VME lab); layout 2.0 only through those field mails.
- **No "Quorum lost" after every restart.** A host rejoining after its own restart logged one quorumAchieved=false with
  every node reachable, true two minutes later (VME lab, layout 1.3, agent 3.3.0, two reboots; not seen on layout 2.0
  or a stretch cluster). With every node reachable, the problem now needs a second check with a new line.
- Agent-silent detail: "> 5 min agent may be down" (HPE wording).

## 1.20.0

- **A powered-off host no longer slows the checks.** When a host's check got no answer from its agent and the agent is
  still silent past the warning threshold (2 min by default), the host is not asked again until its agent reports.
  Before, every check waited the full 60 s for it until the manager marked its power state unknown, which delayed the
  mails of the whole cycle. Its open problems are kept, as for any host whose check cannot run.

## 1.19.0 - first public release

- **HVM Alert tab.** Each HVM cluster gets an HVM Alert tab with its alert settings, kept in Cypher. A template can be
  saved from any tab and watches clusters without settings of their own, including clusters created later.
- **Open problems.** The tab lists the cluster's open problems from the last check, in the same look as the alert
  mail. An HVM alarm acknowledged while open is marked "(acknowledged)" until it clears.
- **What it checks.** HVM alarm relay, manager-side host and datastore state, and host-side checks through the agent:
  quorum, fencing, DLM, peer reachability, GFS2, multipath and agent-log events. The agent-log events include APD
  budget, VM shutdown, DLM membership loss and the host's self-reboot.
- **Mail.** Mails go through the appliance SMTP settings: one mail per cluster with new, changed and resolved problems
  and events. A failed send is retried with backoff. There are "monitor started", "monitor stopped" and daily status
  mails.
- **Leadership.** A distributed lock picks the node that checks. A new version takes over in about 60 s after an
  upload, and an instance left behind by a failed upload stops itself.
- **Saving.** Saving needs the Clusters: Full permission, the owner tenant and a token signed by the tab for that
  cluster, so a user who cannot open the cluster page cannot save its settings.

Versions before 1.19.0 were internal builds and were not published.
