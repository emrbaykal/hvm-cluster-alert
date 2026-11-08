package com.hpe.hvmalert

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * One problem (a condition that stays until it clears) or one event (a log line that happened once).
 * The key identifies the same problem across cycles; a severity change on the same key is a change.
 */
@ToString(includeNames = true)
@EqualsAndHashCode(includes = ['key', 'severity'])
class Finding {
    static final String WARNING = 'warning'
    static final String CRITICAL = 'critical'

    String key
    String severity
    Long clusterId         // grouping key; the cluster name is for display only
    String cluster
    String resource
    String title
    String detail
    String source          // native-alarm | manager | host | event
    Date since = new Date()
    int holdSeconds = 0    // report only after the condition has lasted this long (e.g. DLM fencing wait)
    int confirmCycles = 2  // consecutive cycles before it opens; 1 when the source already debounced it
    boolean preStart       // event logged before this monitor started (may have been mailed by the previous one)
    Map meta = [:]         // parser details, e.g. how many nodes are missing

    static int rank(String severity) {
        severity == CRITICAL ? 2 : (severity == WARNING ? 1 : 0)
    }
}
