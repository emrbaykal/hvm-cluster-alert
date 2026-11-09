# Changelog

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
