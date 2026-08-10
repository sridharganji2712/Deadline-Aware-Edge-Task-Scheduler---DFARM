package org.fog.dfarm.trace;

/**
 * One timestamped row of Sheet 2 (Task Event Timeline). Immutable — every
 * scheduling-relevant action creates exactly one of these and appends it to
 * {@link ExecutionTraceManager}; nothing about the simulation state is
 * re-derived from it later, it's a pure append-only log.
 */
public final class TraceEvent {
    public final double timestamp;
    public final TraceEventType type;
    public final Integer taskId;   // cloudlet id, nullable (node-level events have none)
    public final Integer vmId;     // nullable
    public final String nodeId;    // nullable
    public final String description;

    public TraceEvent(double timestamp, TraceEventType type, Integer taskId, Integer vmId,
                      String nodeId, String description) {
        this.timestamp   = timestamp;
        this.type        = type;
        this.taskId      = taskId;
        this.vmId        = vmId;
        this.nodeId      = nodeId;
        this.description = description;
    }
}
