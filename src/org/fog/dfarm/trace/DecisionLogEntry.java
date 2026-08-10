package org.fog.dfarm.trace;

/**
 * One row of Sheet 7 (DFARM Internal Decision Log) — one entry per
 * scheduling ATTEMPT (a call to doSchedule/doScheduleReplica/resubmitTask),
 * not per candidate VM considered. The candidate list is packed into
 * candidateSummary as "VM3:CT=24.1s(FEASIBLE) | VM7:CT=31.0s(INFEASIBLE)"
 * rather than exploded into one row per candidate, to keep this sheet at
 * the same grain as Sheet 1 (one row per task) and directly joinable with
 * it by taskId.
 */
public class DecisionLogEntry {
    public double timestamp;
    public int taskId;
    public boolean isReplica;
    public Double priority;                  // network-aware DPL at decision time
    public Boolean replicationDecision;       // true if this task/replica was itself the product of a replication decision
    public Double replicationThreshold;       // replRatio
    public Integer dplHistorySize;
    public String candidateSummary;           // packed "VMx:CT=..(FEASIBLE/INFEASIBLE) | ..." string
    public Double chosenCandidateCt;
    public Double chosenCandidateFinish;
    public Boolean chosenFeasible;
    public Integer chosenVmId;
    public String chosenNode;
    public String reasonSelected;
    public String rejectedCandidateReason;    // set only when no candidate could be chosen
    public boolean deepSearchUsed;
    public boolean queueAdjustmentUsed;
    public Double commitTime;
    public String replicaPlacementReason;     // node-diversity / soft-line / structural-failure notes, replicas only
}
