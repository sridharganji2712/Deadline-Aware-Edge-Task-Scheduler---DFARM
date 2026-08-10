package org.fog.dfarm;

/**
 * Describes a single physical-node failure to inject into a running
 * simulation. Deliberately a plain, reusable data object (not a hardcoded
 * "if (time==X)" check in the controller) so the same failure-injection
 * pathway supports permanent failures, transient failures with recovery,
 * multiple independent failures, and — since it's just a value object —
 * randomly generated failure schedules built by whatever's driving the
 * simulation.
 */
public class NodeFailureEvent {

    public enum FailureType { PERMANENT, TRANSIENT }

    private final String nodeId;
    private final double time;              // simulation time the node goes down
    private final FailureType failureType;
    private final double recoveryTime;       // seconds after `time` until it comes back; ignored for PERMANENT
    private final double detectionDelay;     // seconds between the failure and the controller noticing it

    public NodeFailureEvent(String nodeId, double time, FailureType failureType, double recoveryTime) {
        this(nodeId, time, failureType, recoveryTime, 0.0);
    }

    public NodeFailureEvent(String nodeId, double time, FailureType failureType,
                            double recoveryTime, double detectionDelay) {
        this.nodeId = nodeId;
        this.time = time;
        this.failureType = failureType;
        this.recoveryTime = recoveryTime;
        this.detectionDelay = detectionDelay;
    }

    public String      getNodeId()         { return nodeId; }
    public double       getTime()           { return time; }
    public FailureType getFailureType()    { return failureType; }
    public double       getRecoveryTime()   { return recoveryTime; }
    public double       getDetectionDelay() { return detectionDelay; }

    @Override
    public String toString() {
        return "NodeFailureEvent{node=" + nodeId + ", t=" + time + ", type=" + failureType
                + (failureType == FailureType.TRANSIENT ? ", recoveryTime=" + recoveryTime : "") + "}";
    }
}
