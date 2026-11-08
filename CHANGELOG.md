# Changelog

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
