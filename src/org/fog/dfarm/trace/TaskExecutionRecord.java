package org.fog.dfarm.trace;

/**
 * One row of Sheet 1 (Complete Task Execution Log) — one instance per
 * original task AND per replica (never merged). Mutable and filled in
 * incrementally as the task moves through arrival -> scheduling decision ->
 * submission -> execution -> completion/failure, by whichever class knows
 * that piece of information at that point (DFARMController for arrival/
 * submission/actual-execution timestamps, DFARMScheduler for the scheduling
 * decision itself). Plain public fields deliberately — this is an internal
 * DTO consumed only by the workbook exporter, not a public API surface, so
 * getter/setter boilerplate would add nothing but noise.
 */
public class TaskExecutionRecord {

    // ── Task information ────────────────────────────────────────────────────
    public int taskId;
    public Integer originalTaskId;   // set only when isReplica == true
    public boolean isReplica;
    public int replicaNumber;        // 0 for an original, 1 for its (sole) replica
    public String sourceDevice;
    public String originNode;
    public double arrivalTime;
    public double deadline;
    public long mi;
    public double inputSizeKB;
    public double outputSizeKB;
    public Double dplValue;
    public Double schedulingPriority;
    public Integer priorityRank;     // rank within its batch, 1 = tightest DPL
    public Integer batchNumber;
    public Double batchTimestamp;

    // ── Scheduling decision ──────────────────────────────────────────────────
    public String algorithm = "DFARM";
    public String assignedNode;
    public Integer assignedVmId;
    public Double assignedVmMips;
    public String acquisitionSource; // RELEASED / FRESH / CROSS_NODE / CLOUD / DIRECT_ASSIGN / QUEUE_ADJUST
    public boolean releasedVm;
    public boolean freshVm;
    public boolean crossNode;
    public boolean cloud;
    public boolean reusedVm;
    public boolean crossNodeOffload;
    public boolean cloudOffload;
    public Double acquisitionDelay;
    public Double bootDelay;
    public Double uplinkDelay;
    public Double downlinkDelay;
    public Double interNodeDelay;
    public Double estimatedStartTime;
    public Double estimatedFinishTime;
    public Double actualStartTime;
    public Double actualFinishTime;
    public Double cpuTime;
    public Double completionTime;     // predicted CT (DFARM's own vmCtMap-style bookkeeping)
    public Integer vmQueuePosition;
    public Integer queueLengthBefore;
    public Integer queueLengthAfter;
    public Double vmCtBefore;
    public Double vmCtAfter;
    // Real wall-clock cost (System.nanoTime()) of DFARMScheduler's own
    // doSchedule() search — direct-assign check plus, if that fails, the
    // 4-source acquisition cascade — up to but excluding commit(). Set only
    // on a SUCCESSFUL search (a VM was found); failedSearchTimeMs covers the
    // rejected case. For a direct-assign success this is pure in-memory
    // algorithmic cost. When the acquisition cascade fires
    // (buildAcqCandidatesWithLogging), this also includes that method's
    // interleaved Log.printlnConcat(...) diagnostic I/O, which could not be
    // separated out without restructuring its control flow — treat cascade
    // (non-DIRECT_ASSIGN) values as "search + logging", not pure algorithm
    // time.
    public Double vmSearchTimeMs;
    public Double failedSearchTimeMs;

    // ── Deadline information ─────────────────────────────────────────────────
    public Boolean deadlineMet;
    public Double deadlineMissAmount;
    public boolean rejected;
    public String rejectReason;

    // ── Replica information (populated on the ORIGINAL's record, describing
    // its replica; a replica's own record instead carries originalTaskId) ────
    public boolean replicaCreated;
    public Integer replicaParentId;
    public Integer replicaVmId;
    public String replicaNode;
    public Double replicaReservedTime;
    public boolean replicaActivated;
    public Double replicaActivationTime;
    public boolean replicaDiscarded;
    public boolean replicaLost;
    public String replicaFailureReason;

    // ── Fault information ────────────────────────────────────────────────────
    public boolean originalNodeFailed;
    public Double failureTime;
    public Double detectionDelay;
    public Double recoveryTime;
    public boolean resubmitted;
    public boolean failoverUsed;
    public String failoverNode;
    public Integer failoverVmId;

    // ── Final status ──────────────────────────────────────────────────────────
    public boolean finishedSuccessfully;
    public boolean cancelled;
    public boolean lost;
    public boolean dropped;           // hard rejection / structural replica failure
    public boolean completedByReplica;
    public boolean completedByOriginal;

    // ── Performance ───────────────────────────────────────────────────────────
    public Double waitingTime;                       // submission time - arrival time
    public Double responseTime;                      // actual exec start - arrival time
    public Double turnaroundTime;                     // actual finish (post-downlink) - arrival time
    public Double networkDelay;                        // uplink + downlink + cross-node/cloud
    public Double totalDelay;                          // turnaround - CPU time
    public Double executionDelay;                       // actual finish - actual start
    public Double resourceUtilizationContribution;      // this task's cost / its VM's final CT
}
