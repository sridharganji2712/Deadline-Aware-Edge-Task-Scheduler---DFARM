package org.fog.dfarm;

import java.util.*;

import org.cloudbus.cloudsim.Log;
import org.fog.dfarm.trace.DecisionLogEntry;
import org.fog.dfarm.trace.ExecutionTraceManager;
import org.fog.dfarm.trace.TaskExecutionRecord;
import org.fog.dfarm.trace.TraceEventType;

/**
 * DFARM: Deadline and Fault-aware task Adjusting and Resource Managing scheduler.
 * Ported to iFogSim, with two changes on top of the CloudSim edge adaptation:
 *
 *  1. Acquisition cascade gains a 4th source — a cloud tier, tried only after
 *     released-idle / fresh / cross-node neighbor all fail to produce a
 *     feasible (or best-available) candidate.
 *  2. Replica placement (fault tolerance) is node-diversity aware: a replica
 *     may never be placed on the same physical node as its original. See
 *     doScheduleReplica().
 *
 *  CT formula:  uplinkDelay + bootTime + acqDelay + (MI/MIPS) + downlinkDelay
 *  DPL formula: (deadline - arrivalTime) / MI  — per-task, reflects true urgency
 *  acquireVm:   4-step cascade — releasedIdleVms → freshPool → cross-node → cloud
 *  replRatio:   5–10% recommended
 *
 *  Algorithm 7 (queue-adjustment / "deep search"), SIMPLE variant: IS
 *  implemented, gated by the deepSearch constructor flag — see
 *  QueueAdjustment for the pure walk/formula (verified in isolation against
 *  the paper's own worked example) and assignAcquire()'s comment for where
 *  it's wired in. An EARLIER attempt at this was removed as structurally
 *  incompatible with immediate, irrevocable CLOUDLET_SUBMIT dispatch (see
 *  the "REMOVED" history retained in assignAcquire()'s comment for why) —
 *  it only became viable once DFARMController stopped submitting a task's
 *  CLOUDLET_SUBMIT the instant it was scheduled, deferring it to a real
 *  per-VM holding queue instead (see DFARMController.enqueueForSubmission/
 *  isVmBusy/dispatchNextQueued). The AGGRESSIVE variant (re-verifying every
 *  task displaced further down a queue, not just the one at the insertion
 *  boundary) remains unimplemented.
 *
 * Scheduling is ARRIVAL-DRIVEN:
 *   - Call initScheduler() once when the VM pool is ready.
 *   - Call scheduleOneTask() each time a task arrives at its arrivalTime.
 *   - Call notifyTaskCompleted() whenever the destination device returns a finished cloudlet.
 *
 * This class is a pure scheduling logic container — it is NOT a SimEntity,
 * and deliberately has no CloudSim dependency (so it stays reusable outside
 * this simulator). Every scheduling decision it makes is also mirrored into
 * ExecutionTraceManager for the research-grade reporting workbook — the
 * timestamp for those trace calls comes from currentDecisionTime, set once
 * per top-level entry point (scheduleOneTask/resubmitTask) rather than
 * threaded as a parameter through every private helper, since this class is
 * only ever driven by a single sequential caller (DFARMController, itself
 * single-threaded under CloudSim's event loop) — there's never a second
 * decision in flight while one is being made.
 */
public class DFARMScheduler {

    // parameters
    private final double  replRatio;       // fraction of DPL history treated as tight → replicate
    private final boolean allowTaskToRun;  // soft-line: force on fastest VM; hard-line: drop
    private final boolean deepSearch;      // gates Algorithm 7 (queue-adjustment, simple variant) in
                                            // assignAcquire() — when a candidate VM's straight-append
                                            // fails Tx's deadline, try inserting Tx ahead of an
                                            // already-queued task instead of moving straight to the
                                            // next acquisition-cascade source. See QueueAdjustment.
    private final double  idleReleaseThresholdSec; // Algorithm 6 grace period — see ageOutIdleVms()
    private final List<EdgeNode> edgeNodes; // edge nodes only — used for cross-node offload
    private final EdgeNode cloudNode;       // cloud tier — Source 4 fallback (nullable)

    // scheduler state
    private List<EdgeVm> allVms;           // full VM pool (edge + cloud) seeded at init — used for ARUR
    private List<EdgeVm> availableVms;     // VMs currently active for scheduling
    private final Map<EdgeVm, LinkedList<EdgeCloudlet>> vmQueueMap; // per-VM task queue
    private final Map<EdgeVm, Double> vmCtMap;             // completion time per VM
    private final Map<EdgeCloudlet, EdgeVm> taskVmMap;     // task → assigned VM
    private final List<EdgeCloudlet> rejectedTasks;        // tasks that missed their deadline
    private final List<EdgeCloudlet> structuralReplicaFailures; // replicas dropped: no other node exists at all
    private final List<Double> dplHistory;                 // sorted DPL values (ascending)
    private final Set<Integer> reusedVmIds;                // VM IDs re-acquired (skip acqDelay)
    private final Set<Integer> crossNodeCloudletIds;       // cloudlet IDs routed cross-node (edge-to-edge)
    private final Set<Integer> cloudCloudletIds;           // cloudlet IDs routed to the cloud tier

    // warm-standby fault tolerance: a replica is placed and its VM capacity
    // reserved up front, but never executed unless its original's node fails
    // before the original completes — see activateStandbyReplica()/discardStandbyReplica().
    private final Map<EdgeCloudlet, EdgeCloudlet> standbyReplicaByOriginal; // original -> pending replica
    private final Map<EdgeCloudlet, EdgeCloudlet> originalByReplica;       // replica -> its original
    private final Map<EdgeCloudlet, Double> replicaReservedCost;          // replica -> VM-seconds it reserved
    private final List<EdgeCloudlet> activatedReplicas;   // replicas that were actually put to work
    private final List<EdgeCloudlet> discardedReplicas;   // replicas whose original finished cleanly, never needed
    private final List<EdgeCloudlet> lostStandbyReplicas; // replicas whose OWN node failed while still on standby

    // execution tracing (research-grade reporting workbook — see org.fog.dfarm.trace)
    private final ExecutionTraceManager trace = ExecutionTraceManager.getInstance();
    private double currentDecisionTime = 0.0; // set once per scheduleOneTask()/resubmitTask() call

    // Bridges assignAcquire()'s QUEUE_ADJUST branch to the commit() call that
    // immediately follows it within the same doSchedule() invocation (see
    // doSchedule(): assignAcquire() then commit() run back-to-back with
    // nothing else able to call commit() in between — single-threaded, no
    // other decision can interleave). Holds Tx's own Eq.6-computed CT at its
    // chosen mid-queue position so commit() can use it instead of deriving a
    // tail-append value that doesn't apply to a mid-queue insertion. Null for
    // every other acquisition source; read-and-cleared exactly once by the
    // very next commit() call so a value can never leak into an unrelated one.
    private Double pendingQueueAdjustedCt = null;

    // constructor

    public DFARMScheduler(double replRatio,
                          boolean allowTaskToRun,
                          boolean deepSearch,
                          double idleReleaseThresholdSec,
                          List<EdgeNode> edgeNodes,
                          EdgeNode cloudNode) {
        this.replRatio      = replRatio;
        this.allowTaskToRun = allowTaskToRun;
        this.deepSearch     = deepSearch;
        this.idleReleaseThresholdSec = idleReleaseThresholdSec;
        this.edgeNodes      = edgeNodes;
        this.cloudNode      = cloudNode;

        this.availableVms        = new ArrayList<>();
        this.vmQueueMap          = new HashMap<>();
        this.vmCtMap             = new HashMap<>();
        this.taskVmMap           = new HashMap<>();
        this.rejectedTasks       = new ArrayList<>();
        this.structuralReplicaFailures = new ArrayList<>();
        this.dplHistory          = new ArrayList<>();
        this.reusedVmIds         = new HashSet<>();
        this.crossNodeCloudletIds = new HashSet<>();
        this.cloudCloudletIds     = new HashSet<>();

        this.standbyReplicaByOriginal = new HashMap<>();
        this.originalByReplica        = new HashMap<>();
        this.replicaReservedCost      = new HashMap<>();
        this.activatedReplicas        = new ArrayList<>();
        this.discardedReplicas        = new ArrayList<>();
        this.lostStandbyReplicas      = new ArrayList<>();
    }

    //Algorithm 3 — init scheduler (seeds vmCtMap, resets all state)
    public void initScheduler(List<EdgeVm> vms, double currentTime) {
        vmQueueMap.clear();
        vmCtMap.clear();
        taskVmMap.clear();
        rejectedTasks.clear();
        structuralReplicaFailures.clear();
        dplHistory.clear();
        reusedVmIds.clear();
        crossNodeCloudletIds.clear();
        cloudCloudletIds.clear();
        standbyReplicaByOriginal.clear();
        originalByReplica.clear();
        replicaReservedCost.clear();
        activatedReplicas.clear();
        discardedReplicas.clear();
        lostStandbyReplicas.clear();

        // full pool retained for ARUR (Eq. 11 averages over every VM, not just acquired ones)
        this.allVms = new ArrayList<>(vms);

        // empty active pool — VMs acquired on-demand to trigger acquisition cascade
        this.availableVms = new ArrayList<>();

        // `vms` is the CONFIRMED-created pool (DFARMController builds it from
        // getGuestsCreatedList(), after CloudSim's own VM_CREATE_ACK handshake)
        // — strictly a subset of what each EdgeNode's pool was seeded with at
        // topology-construction time (EdgeNode.addVm(), before any of that
        // ever ran). Prune every node down to only the VMs that actually
        // exist, so a VM that failed real allocation everywhere (possible
        // with a zero-margin pool, e.g. this topology's cloud tier) can never
        // be offered as an acquisition candidate again.
        Set<Integer> confirmedVmIds = new HashSet<>();
        for (EdgeVm vm : vms) confirmedVmIds.add(vm.getId());
        for (EdgeNode node : edgeNodes) node.retainOnlyConfirmedVms(confirmedVmIds);
        if (cloudNode != null) cloudNode.retainOnlyConfirmedVms(confirmedVmIds);
    }

    //Algorithm 1 — schedule one arriving task (called per TASK_ARRIVED event)
    //Returns []: rejected; [task]: assigned; [task, replica]: assigned with replica
    public List<EdgeCloudlet> scheduleOneTask(EdgeCloudlet task, double currentTime) {
        currentDecisionTime = currentTime;
        List<EdgeCloudlet> result = new ArrayList<>();

        // Maintain sorted DPL history — insert this task's own DPL first, so
        // Algorithm 8's percentile check ranks it against the full history
        // including itself, per the paper.
        double dpl = networkAwareDPL(task, currentTime);
        insertSortedDPL(task, currentTime);

        TaskExecutionRecord record = trace.getOrCreateRecord(task.getCloudletId());
        record.dplValue = dpl;
        record.schedulingPriority = dpl;
        trace.recordEvent(currentTime, TraceEventType.PRIORITY_EVALUATED, task.getCloudletId(), null, null,
                "DPL=" + String.format("%.4f", dpl));

        // Algorithm 8: fault-tolerance decision
        boolean replicate = determineFaultTolerance(task, dplHistory, replRatio, currentTime);
        trace.recordEvent(currentTime, TraceEventType.REPLICATION_DECISION, task.getCloudletId(), null, null,
                replicate ? "replicate (tightest " + (int) (replRatio * 100) + "% DPL percentile)" : "resubmission-only (not in tight percentile)");

        // Algorithm 4: direct assign → queue adjust → acquire
        EdgeVm vm = doSchedule(task, currentTime);

        if (vm == null) {
            rejectedTasks.add(task);
            record.rejected = true;
            record.rejectReason = "no VM could meet deadline " + String.format("%.2f", task.getDeadline()) + "s";
            if (allowTaskToRun) {
                // Soft-line: force onto the fastest VM despite deadline miss
                List<Map.Entry<EdgeVm, Double>> sorted = sortedVmCtEntries();
                if (!sorted.isEmpty()) {
                    vm = sorted.get(0).getKey();
                    commit(task, vm);
                    record.rejectReason += " — soft-line forced onto fastest VM anyway";
                }
            }
            if (vm == null) {
                record.dropped = true;
                trace.recordEvent(currentTime, TraceEventType.TASK_REJECTED, task.getCloudletId(), null, null, record.rejectReason);
            }
        }

        if (vm != null) {
            result.add(task);

            // Place a WARM-STANDBY replica on a DIFFERENT physical node if replication
            // was decided. The replica's VM is reserved now (node-diversity enforced),
            // but it is never executed unless the original's node fails before the
            // original completes — see activateStandbyReplica()/discardStandbyReplica().
            // This is the paper's "hybrid" fault tolerance: replication buys a ready
            // failover target, it does not simply double active compute.
            if (replicate && !task.isDuplicate()) {
                EdgeCloudlet replica = makeCopy(task);
                insertSortedDPL(replica, currentTime);
                TaskExecutionRecord replicaRecord = trace.getOrCreateRecord(replica.getCloudletId());
                replicaRecord.isReplica = true;
                replicaRecord.originalTaskId = task.getCloudletId();
                replicaRecord.replicaNumber = 1;
                replicaRecord.sourceDevice = task.getSourceDeviceId();
                replicaRecord.originNode = task.getOriginNode() != null ? task.getOriginNode().getNodeId() : null;
                replicaRecord.arrivalTime = task.getArrivalTime();
                replicaRecord.deadline = task.getDeadline();
                replicaRecord.mi = task.getCloudletLength();
                replicaRecord.inputSizeKB = task.getInputSizeKB();
                replicaRecord.outputSizeKB = task.getOutputSizeKB();

                EdgeVm replicaVm = doScheduleReplica(replica, currentTime, vm.getNodeId());
                if (replicaVm != null) {
                    standbyReplicaByOriginal.put(task, replica);
                    originalByReplica.put(replica, task);
                    double reservedCost = taskCost(replicaVm, replica);
                    replicaReservedCost.put(replica, reservedCost);

                    record.replicaCreated = true;
                    record.replicaParentId = task.getCloudletId();
                    record.replicaVmId = replicaVm.getId();
                    record.replicaNode = replicaVm.getNodeId();
                    record.replicaReservedTime = reservedCost;

                    trace.recordEvent(currentTime, TraceEventType.REPLICA_CREATED, replica.getCloudletId(),
                            replicaVm.getId(), replicaVm.getNodeId(),
                            "warm standby reserved for original #" + task.getCloudletId());
                } else {
                    record.replicaFailureReason = "structural: no VM exists on any node other than " + vm.getNodeId();
                }
                // Structural failures (no other node exists at all) are tracked
                // separately in structuralReplicaFailures, not rejectedTasks.
            }
        }

        return result;
    }

    //Algorithm 6 — task finished; release VM to idle pool if queue empty
    public void notifyTaskCompleted(EdgeCloudlet task) {
        EdgeVm vm = taskVmMap.get(task);
        if (vm == null) return;

        LinkedList<EdgeCloudlet> queue = vmQueueMap.get(vm);
        if (queue != null) {
            queue.remove(task);
            if (queue.isEmpty()) {
                releaseIdleVm(vm);
            }
        }
    }

    // ── warm-standby replica lifecycle ───────────────────────────────────────

    //peek whether `original` currently has a pending (not yet activated/discarded) standby replica
    public EdgeCloudlet peekStandbyReplica(EdgeCloudlet original) {
        return standbyReplicaByOriginal.get(original);
    }

    public boolean isStandbyReplica(EdgeCloudlet task) {
        return originalByReplica.containsKey(task);
    }

    public EdgeCloudlet getOriginalForReplica(EdgeCloudlet replica) {
        return originalByReplica.get(replica);
    }

    //Original completed cleanly — its standby replica was never needed. Refund
    //the VM capacity it had reserved (it never actually ran) and drop it.
    public void discardStandbyReplica(EdgeCloudlet original) {
        EdgeCloudlet replica = standbyReplicaByOriginal.remove(original);
        if (replica == null) return;
        originalByReplica.remove(replica);
        discardedReplicas.add(replica);
        releaseStandbyReservation(replica);

        TaskExecutionRecord record = trace.getRecord(replica.getCloudletId());
        if (record != null) record.replicaDiscarded = true;
        TaskExecutionRecord originalRecord = trace.getRecord(original.getCloudletId());
        if (originalRecord != null) originalRecord.completedByOriginal = true;
        trace.recordEvent(currentDecisionTime, TraceEventType.REPLICA_DISCARDED, replica.getCloudletId(),
                null, null, "original #" + original.getCloudletId() + " completed cleanly, standby no longer needed");
    }

    //Original's node failed before it completed — activate its standby replica
    //for real execution. Returns the replica (already committed to its own VM
    //by doScheduleReplica at scheduling time) for the controller to submit now,
    //or null if `original` never had one placed.
    public EdgeCloudlet activateStandbyReplica(EdgeCloudlet original, double currentTime) {
        EdgeCloudlet replica = standbyReplicaByOriginal.remove(original);
        if (replica == null) return null;
        originalByReplica.remove(replica);
        activatedReplicas.add(replica);

        TaskExecutionRecord record = trace.getRecord(replica.getCloudletId());
        if (record != null) {
            record.replicaActivated = true;
            record.replicaActivationTime = currentTime - original.getArrivalTime();
        }
        TaskExecutionRecord originalRecord = trace.getRecord(original.getCloudletId());
        if (originalRecord != null) originalRecord.completedByReplica = true;
        trace.recordEvent(currentTime, TraceEventType.REPLICA_ACTIVATED, replica.getCloudletId(),
                null, null, "activated for original #" + original.getCloudletId());
        return replica;
    }

    //A replica's OWN node failed while it was still on standby (never activated,
    //never needed yet) — it's simply lost; there's no second-order failover.
    public void loseStandbyReplica(EdgeCloudlet replica) {
        EdgeCloudlet original = originalByReplica.remove(replica);
        if (original == null) return;
        standbyReplicaByOriginal.remove(original);
        lostStandbyReplicas.add(replica);
        releaseStandbyReservation(replica);

        TaskExecutionRecord record = trace.getRecord(replica.getCloudletId());
        if (record != null) {
            record.replicaLost = true;
            record.lost = true;
        }
        trace.recordEvent(currentDecisionTime, TraceEventType.REPLICA_LOST, replica.getCloudletId(),
                null, null, "own node failed while still on standby, never activated");
    }

    private void releaseStandbyReservation(EdgeCloudlet replica) {
        EdgeVm vm = taskVmMap.get(replica);
        if (vm == null) return;
        Double cost = replicaReservedCost.get(replica);
        if (cost != null) {
            vmCtMap.put(vm, vmCtMap.getOrDefault(vm, 0.0) - cost);
        }
        LinkedList<EdgeCloudlet> queue = vmQueueMap.get(vm);
        if (queue != null) {
            queue.remove(replica);
            if (queue.isEmpty()) releaseIdleVm(vm);
        }
    }

    // ── node-failure reaction ────────────────────────────────────────────────

    //every task still holding a VM slot on `nodeId` (in-flight execution,
    //queued behind something else on the same VM, or a standby replica
    //parked there) — used by the controller to react to a node failure
    public List<EdgeCloudlet> getInProgressTasksOnNode(String nodeId) {
        List<EdgeCloudlet> result = new ArrayList<>();
        for (Map.Entry<EdgeVm, LinkedList<EdgeCloudlet>> e : vmQueueMap.entrySet()) {
            if (!e.getKey().getNodeId().equals(nodeId)) continue;
            result.addAll(e.getValue());
        }
        return result;
    }

    //Remove a task's bookkeeping entirely, without attempting to reschedule
    //it — used when a task is being handed off to its replica via failover,
    //so there's nothing left to resubmit; without this it stays "in progress"
    //on its dead VM's queue forever (notifyTaskCompleted() will never fire
    //for a canceled cloudlet).
    public void abandonTask(EdgeCloudlet task) {
        EdgeVm vm = taskVmMap.remove(task);
        if (vm == null) return;
        detachFromVm(task, vm);
        TaskExecutionRecord record = trace.getRecord(task.getCloudletId());
        if (record != null) record.cancelled = true;
        trace.recordEvent(currentDecisionTime, TraceEventType.TASK_CANCELLED, task.getCloudletId(),
                vm.getId(), vm.getNodeId(), "abandoned — node failed");
    }

    //Reactive resubmission (the other half of the paper's "hybrid" fault
    //tolerance): a task that was NOT selected for replication and whose VM's
    //node just failed gets one fresh scheduling attempt against the surviving,
    //healthy topology — same deadline, no new replication decision. Returns
    //the new VM, or null if no healthy VM can take it either (task is lost).
    public EdgeVm resubmitTask(EdgeCloudlet task, double currentTime) {
        currentDecisionTime = currentTime;
        EdgeVm oldVm = taskVmMap.remove(task);
        if (oldVm != null) {
            detachFromVm(task, oldVm);
        }
        EdgeVm newVm = doSchedule(task, currentTime, null);

        TaskExecutionRecord record = trace.getRecord(task.getCloudletId());
        if (record != null) {
            record.resubmitted = true;
            if (newVm != null) {
                trace.recordEvent(currentTime, TraceEventType.TASK_RESUBMITTED, task.getCloudletId(),
                        newVm.getId(), newVm.getNodeId(), "resubmitted after node failure");
            }
        }
        return newVm;
    }

    //Common cleanup for pulling a task off a VM it can no longer usefully sit
    //on (dead node): refunds its reserved cost from that VM's vmCtMap — a
    //stale, un-refunded cost would otherwise make the VM look permanently
    //busier than it really is to every future scheduling decision, including
    //after the node recovers — and releases the VM to the idle pool if
    //nothing else is left queued on it, so a recovered node's VMs are found
    //again by findReleasedVm() instead of sitting orphaned in "active" limbo.
    private void detachFromVm(EdgeCloudlet task, EdgeVm vm) {
        double cost = taskCost(vm, task);
        vmCtMap.put(vm, vmCtMap.getOrDefault(vm, 0.0) - cost);
        LinkedList<EdgeCloudlet> queue = vmQueueMap.get(vm);
        if (queue != null) {
            queue.remove(task);
            if (queue.isEmpty()) {
                releaseIdleVm(vm);
            }
        }
    }

    //Algorithm 4 — assign (phase1: direct) then acquire (phase3).
    //
    //Historical note — an EARLIER attempt at Phase 2 (queue-adjustment /
    //Algorithm 7 — swap ahead of an already-committed task on the same VM)
    //was removed as dead weight, kept here only as a record of why a naive
    //re-attempt would fail the same way: it could never have any real
    //effect in the THEN-current event-driven port, because
    //DFARMController.processTask() submitted each task's real
    //CLOUDLET_SUBMIT event to CloudSim synchronously, immediately, as
    //handleSchedulingTick()'s batch loop reached it — so by the time a later
    //task in the batch decided to "swap ahead" of an earlier one, the
    //earlier one's real submission was already irrevocably sent. Traced
    //concretely at the time: task #11 (batch #5, priority rank 13/20) was
    //logged as swapping ahead of task #116 (rank 8/20, committed one loop
    //iteration earlier) — but in the real Task Event Timeline, #116 started
    //executing at t=61.50s and #11 at t=63.32s, the OPPOSITE order. That
    //made the old attempt silently admit tasks that failed the honest
    //direct-assign deadline check under a promise the architecture
    //couldn't keep.
    //
    //That blocker is gone: DFARMController no longer submits a task's
    //CLOUDLET_SUBMIT the instant it's scheduled — it defers to a real
    //per-VM holding queue (enqueueForSubmission/isVmBusy/dispatchNextQueued),
    //dispatching each task only once its VM is genuinely free. Reordering
    //that queue before dispatch is now a real, enforceable decision, not a
    //promise the event loop can silently break — see the deepSearch-gated
    //queue-adjustment call a few lines below, and QueueAdjustment for the
    //Algorithm 7 (simple variant) walk itself.
    private EdgeVm assignAcquire(EdgeCloudlet task,
                                 List<EdgeVm> availableVms,
                                 List<Map.Entry<EdgeVm, Double>> sortedVmCts,
                                 double currentTime,
                                 String excludeNodeId) {
        // Uplink is device→origin-node, same for every VM candidate in this loop.
        EdgeNode origin   = task.getOriginNode();
        double taskUplink = (origin != null)
                ? origin.computeUplinkDelay(task.getInputSizeKB(), task.getDeviceLatencyMs())
                : 0.0;

        for (Map.Entry<EdgeVm, Double> entry : sortedVmCts) {
            EdgeVm vm   = entry.getKey();
            double vmCt = entry.getValue();
            double estimatedFinish = vmCt + taskUplink + taskCost(vm, task);

            if (estimatedFinish <= task.getDeadline()) {
                TaskExecutionRecord record = trace.getOrCreateRecord(task.getCloudletId());
                record.acquisitionSource = "DIRECT_ASSIGN";
                record.uplinkDelay = taskUplink;
                record.estimatedFinishTime = estimatedFinish;
                trace.recordEvent(currentTime, TraceEventType.DIRECT_ASSIGN, task.getCloudletId(),
                        vm.getId(), vm.getNodeId(), "fits on already-active VM within deadline");
                logDecision(task, currentTime, vm.getId(), vm.getNodeId(), estimatedFinish,
                        estimatedFinish <= task.getDeadline(), "direct-assign — already active, fits deadline",
                        null, false);
                return vm;
            }

            // Algorithm 7, SIMPLE variant only (paper's "deep search" —
            // gated by the deepSearch flag). Straight tail-append onto this
            // VM missed the deadline, but Tx may still fit by inserting it
            // ahead of an already-queued task, displacing that task (and
            // everything behind it) later — as long as BOTH Tx and the one
            // task at the insertion boundary still meet their own
            // deadlines. Tried here, on this same CT-ascending active-VM
            // loop direct-assign already uses — not as a separate search
            // across the whole topology (see QueueAdjustment's own javadoc
            // for the exact walk/formula, verified in isolation against the
            // paper's worked example before ever being wired in here).
            if (deepSearch) {
                AdjustmentResult adj = tryQueueAdjustment(task, vm);
                if (adj != null) {
                    adjustToVm(task, vm, adj.insertionIndex());
                    TaskExecutionRecord record = trace.getOrCreateRecord(task.getCloudletId());
                    record.acquisitionSource = "QUEUE_ADJUST";
                    record.uplinkDelay = taskUplink;
                    // commit() (called right after this by doSchedule()) would
                    // otherwise derive completionTime/vmCtAfter from a tail-
                    // append formula that doesn't apply to a mid-queue
                    // insertion — hand it Tx's own Eq.6-computed CT instead.
                    pendingQueueAdjustedCt = adj.txRevisedCt();
                    trace.recordEvent(currentTime, TraceEventType.QUEUE_ADJUSTMENT, task.getCloudletId(),
                            vm.getId(), vm.getNodeId(),
                            "Algorithm 7 (simple) — inserted ahead of queue position " + adj.insertionIndex());
                    logDecision(task, currentTime, vm.getId(), vm.getNodeId(), adj.txRevisedCt(),
                            true, "queue adjustment (Algorithm 7, simple) — displaced task at position "
                                    + adj.insertionIndex(), null, true);
                    return vm;
                }
            }
        }

        return pickBestAcquisition(task, availableVms, excludeNodeId);
    }

    //Algorithm 7 (simple variant) — see QueueAdjustment.findInsertionPosition
    //for the exact walk. Reconstructs each currently-queued task's CURRENT
    //completion time from vm's persisted (uplink-excluded, per this class's
    //existing convention — see taskCost()/vmCtMap) tail CT: taskCost() sums
    //are order-independent, so subtracting every queued task's own cost
    //recovers the VM's base CT exactly, and walking back FORWARD through the
    //queue's ACTUAL current order naturally reflects any earlier adjustment
    //insertion (it's already baked into that order). Returns null if no
    //valid position exists — caller falls through to the next candidate VM /
    //acquisition source, unchanged.
    //
    //The queue's REAL, already-dispatched-or-about-to-be head is EXCLUDED
    //from consideration entirely — by the time assignAcquire() is even
    //evaluating this VM as an existing busy candidate for a DIFFERENT task,
    //whatever is really running (or about to be, per dispatchNextQueued())
    //was already committed by some EARLIER decision, and DFARMController
    //dispatches a newly-committed real task to CloudSim immediately whenever
    //the VM is free (see enqueueForSubmission/isVmBusy) — well before this
    //(later) task's own scheduling decision ever runs. So that real head is
    //always already irrevocably in flight in real CloudSim terms: Eq. 5's
    //"q_k's CT increases by Tx's ET" assumes q_k's start can still shift,
    //which is physically false for something already executing.
    //
    //Critically, "the real head" is NOT always vmQueueMap's literal index 0:
    //warm-standby replicas occupy a queue slot for CT-reservation bookkeeping
    //without ever being dispatched unless activated by failover (see
    //isStandbyReplica()/DFARMController.isVmBusy()/dispatchNextQueued(),
    //which both skip them when deciding what's really running or next).
    //Excluding only literal index 0 reproduces the exact same bug one level
    //deeper whenever a standby replica sits ahead of the genuinely-dispatched
    //task in the list: the replica (harmless, phantom) gets excluded, but
    //the real in-flight task right behind it is wrongly left eligible as a
    //"waiting" displacement target. Confirmed concretely: task #201 was
    //inserted "ahead of" task #25 believing #25 was still-waiting tail, when
    //#25 (the true dispatched head, sitting right after a standby-replica
    //placeholder at index 0) was already executing — the real dispatch order
    //never actually changed, so #201 landed behind #25 for real and missed
    //its own deadline despite the adjustment reporting success. The fix:
    //walk forward to the first non-standby-replica entry — that one, and
    //everything before it, is untouchable; only genuinely-still-waiting
    //entries after it are eligible.
    private AdjustmentResult tryQueueAdjustment(EdgeCloudlet task, EdgeVm vm) {
        LinkedList<EdgeCloudlet> queue = vmQueueMap.get(vm);
        if (queue == null) return null;

        int n = queue.size();
        int realHeadIdx = -1;
        int idx = 0;
        for (EdgeCloudlet q : queue) {
            if (!isStandbyReplica(q)) { realHeadIdx = idx; break; }
            idx++;
        }
        if (realHeadIdx == -1 || realHeadIdx >= n - 1) return null; // no real head yet, or nothing WAITING behind it

        int tailCount = n - realHeadIdx - 1;
        double[] currentCts = new double[tailCount];
        double[] deadlines  = new double[tailCount];

        double vmBaseCt = vmCtMap.getOrDefault(vm, 0.0);
        for (EdgeCloudlet q : queue) vmBaseCt -= taskCost(vm, q);

        double running = vmBaseCt;
        double headCt = Double.NaN;
        int i = 0;
        for (EdgeCloudlet q : queue) {
            running += taskCost(vm, q);
            if (i == realHeadIdx) {
                headCt = running; // the untouchable real head's own CT — used
                                   // as the floor/"predecessor" for testing
                                   // insertion right after it
            } else if (i > realHeadIdx) {
                currentCts[i - realHeadIdx - 1] = running;
                deadlines[i - realHeadIdx - 1] = q.getDeadline();
            }
            i++;
        }

        double txEt = (double) task.getCloudletLength() / vm.getMips();
        int truncatedPos = QueueAdjustment.findInsertionPosition(currentCts, deadlines, headCt, task.getDeadline(), txEt);
        if (truncatedPos == QueueAdjustment.NO_VALID_POSITION) return null;

        int realPos = realHeadIdx + 1 + truncatedPos; // shift back into the real queue's own indexing
        double predecessorCt = (truncatedPos == 0) ? headCt : currentCts[truncatedPos - 1];
        return new AdjustmentResult(realPos, predecessorCt + txEt);
    }

    private record AdjustmentResult(int insertionIndex, double txRevisedCt) {}

    //Inserts `task` into vm's holding queue at `index` (Algorithm 7, simple
    //variant — displacing whatever was already there onward). commit()
    //(called right after this, by doSchedule()) sees the task already
    //present in the queue and skips its own addLast() — see commit()'s own
    //comment for why that ordering is safe and already anticipated.
    private void adjustToVm(EdgeCloudlet task, EdgeVm vm, int index) {
        LinkedList<EdgeCloudlet> queue = vmQueueMap.computeIfAbsent(vm, v -> new LinkedList<>());
        int safeIndex = Math.max(0, Math.min(index, queue.size()));
        queue.add(safeIndex, task);
    }

    //Unified acquisition — builds candidates from all sources, picks best FEASIBLE one
    //(Algorithm 5: acquisition only succeeds if the deadline is actually met; a task
    //that can't be met by any source falls through to hard-line rejection / soft-line
    //allowTaskToRun in scheduleOneTask, rather than being silently accepted here).
    private EdgeVm pickBestAcquisition(EdgeCloudlet task,
                                       List<EdgeVm> availableVms,
                                       String excludeNodeId) {
        List<AcqCandidate> candidates = buildAcqCandidatesWithLogging(task, excludeNodeId);
        if (candidates.isEmpty()) {
            logDecision(task, currentDecisionTime, null, null, null, false,
                    "no acquisition candidates available", "no released/fresh/cross-node/cloud VM found", false);
            return null;
        }

        AcqCandidate bestFeasible = null;
        for (AcqCandidate c : candidates) {
            if (c.ct <= task.getDeadline() && (bestFeasible == null || c.ct < bestFeasible.ct)) {
                bestFeasible = c;
            }
        }

        if (bestFeasible == null) {
            logDecision(task, currentDecisionTime, null, null, null, false,
                    "acquisition candidates exist, none feasible", candidateSummary(candidates, task), false);
            return null;
        }

        activateCandidate(bestFeasible, task, availableVms);
        logDecision(task, currentDecisionTime, bestFeasible.vm.getId(), bestFeasible.activateOn.getNodeId(),
                bestFeasible.ct, true, acquisitionReason(bestFeasible), candidateSummary(candidates, task), false);
        return bestFeasible.vm;
    }

    private String acquisitionReason(AcqCandidate winner) {
        if (winner.isCloud) return "cloud-tier fallback (Source 4)";
        if (winner.isCrossNode) return "cross-node offload (Source 3)";
        return winner.isReused ? "released-idle VM on origin node (Source 1)" : "fresh VM on origin node (Source 2)";
    }

    private String candidateSummary(List<AcqCandidate> candidates, EdgeCloudlet task) {
        StringBuilder sb = new StringBuilder();
        for (AcqCandidate c : candidates) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("VM").append(c.vm.getId()).append("(Node ").append(c.activateOn.getNodeId()).append("):CT=")
              .append(String.format("%.2f", c.ct)).append("s(")
              .append(c.ct <= task.getDeadline() ? "FEASIBLE" : "INFEASIBLE").append(")");
        }
        return sb.length() > 0 ? sb.toString() : "none";
    }

    private void logDecision(EdgeCloudlet task, double timestamp, Integer chosenVmId, String chosenNode,
                             Double chosenCt, boolean feasible, String reason, String candidateSummary,
                             boolean queueAdjustment) {
        DecisionLogEntry entry = new DecisionLogEntry();
        entry.timestamp = timestamp;
        entry.taskId = task.getCloudletId();
        entry.isReplica = task.isDuplicate();
        entry.priority = networkAwareDPL(task, timestamp);
        entry.replicationThreshold = replRatio;
        entry.dplHistorySize = dplHistory.size();
        entry.candidateSummary = candidateSummary;
        entry.chosenCandidateCt = chosenCt;
        entry.chosenCandidateFinish = chosenCt;
        entry.chosenFeasible = feasible;
        entry.chosenVmId = chosenVmId;
        entry.chosenNode = chosenNode;
        entry.reasonSelected = chosenVmId != null ? reason : null;
        entry.rejectedCandidateReason = chosenVmId == null ? reason : null;
        entry.deepSearchUsed = deepSearch;
        entry.queueAdjustmentUsed = queueAdjustment;
        entry.commitTime = timestamp;
        if (task.isDuplicate()) {
            entry.replicaPlacementReason = reason;
        }
        trace.addDecisionLogEntry(entry);
    }

    // A released-idle VM's vmQueueMap depth is always 0 by definition (that's
    // the release criterion in releaseIdleVm()) — so "queue depth" can't be
    // read straight off it as a load signal. reuseCount (how many times this
    // VM has already been re-acquired from the idle pool) is the closest
    // available proxy for "is this VM actually carrying its share of load or
    // just always the one that happens to be free right now" — see
    // EdgeVm.reuseCount / EdgeNode.findReleasedVm()'s tie-break.
    private static final int RELEASED_VM_REUSE_THRESHOLD = 3;

    //Load-gated released-VM lookup: only offers a node's best released-idle
    //VM as an acquisition candidate if it hasn't already absorbed a
    //disproportionate share of reuse cycles. Returns null when over the
    //threshold, which naturally makes buildAcqCandidatesWithLogging's caller
    //fall through to the fresh-VM candidate at the same source (or the next
    //source entirely) instead of piling yet another task onto whichever VM
    //happens to be warm.
    //
    //The gate is a PREFERENCE, not a hard cutoff: if every VM at this node
    //has cycled past the threshold AND the fresh pool is exhausted too (a
    //real possibility once a whole homogeneous pool — e.g. the cloud tier —
    //has cycled through release/reacquire enough times), falling through to
    //null here would make this source contribute zero candidates even though
    //genuinely idle capacity exists, just over the soft cap. That would show
    //up as spurious rejections or unnecessary cross-node/cloud fallback
    //pressure late in a run, for no reason better than an arbitrary counter.
    //So the over-threshold VM is only withheld when a fresher alternative
    //actually exists at this node; otherwise it's offered anyway.
    private EdgeVm loadGatedReleasedVm(EdgeNode node) {
        EdgeVm rv = node.findReleasedVm();
        if (rv == null) return null;
        if (rv.getReuseCount() < RELEASED_VM_REUSE_THRESHOLD) return rv;
        return node.findFreshVm() != null ? null : rv;
    }

    //buildAcqCandidates with per-source diagnostic logging; excludeNodeId filters out
    //every candidate physically located on that node (used for replica placement).
    private List<AcqCandidate> buildAcqCandidatesWithLogging(EdgeCloudlet task, String excludeNodeId) {
        List<AcqCandidate> candidates = new ArrayList<>();
        Set<EdgeVm>        seen       = new HashSet<>();

        EdgeNode origin = task.getOriginNode();
        if (origin == null) {
            Log.printlnConcat(">>> [ACQ] task #" + task.getCloudletId() + " has no originNode — acquisition skipped");
            return candidates;
        }

        Log.printlnConcat(">>> [ACQ] task #" + task.getCloudletId()
                    + " origin=Node " + origin.getNodeId()
                    + " deadline=" + String.format("%.2f", task.getDeadline()) + "s"
                    + " activeVms=" + availableVms.size()
                    + (excludeNodeId != null ? " excludeNode=" + excludeNodeId : ""));

        double taskUplink = origin.computeUplinkDelay(task.getInputSizeKB(), task.getDeviceLatencyMs());
        boolean originExcluded = (excludeNodeId != null && origin.getNodeId().equals(excludeNodeId))
                || !origin.isHealthy();

        // Source 1: released-idle VM on origin node (load-gated — see loadGatedReleasedVm)
        if (!originExcluded) {
            EdgeVm rv = loadGatedReleasedVm(origin);
            Log.printlnConcat("    [S1-released] " + (rv != null ? "VM#" + rv.getId() + " active=" + availableVms.contains(rv) : "none (unavailable or over reuse threshold)"));
            if (rv != null && !availableVms.contains(rv) && seen.add(rv)) {
                // base must be an ABSOLUTE simulation-time "VM ready at" mark
                // (deadlines are absolute — see EdgeCloudlet.deadline), not just
                // the raw bootTime/acqDelay constant. Without currentDecisionTime
                // here, every VM acquired after t=0 has its readiness — and every
                // later commit's completion time derived from it — silently
                // understated by however much sim time had already elapsed
                // before this acquisition. Confirmed empirically: a cloud VM
                // first acquired at t=5.04s had its analytical finish times
                // understated by ~5.02s versus real execution.
                AcqCandidate c = new AcqCandidate(rv, origin, true, false, false, 0.0, taskUplink, currentDecisionTime + rv.getBootTime(), taskCost(rv, task));
                candidates.add(c);
                Log.printlnConcat("      → candidate CT=" + String.format("%.2f", c.ct) + "s");
            }
        } else {
            Log.printlnConcat("    [S1-released] skipped — origin node excluded");
        }

        // Source 2: fresh VM from origin node pool
        if (!originExcluded) {
            EdgeVm fv = origin.findFreshVm();
            Log.printlnConcat("    [S2-fresh]    " + (fv != null ? "VM#" + fv.getId() + " active=" + availableVms.contains(fv) : "none"));
            if (fv != null && !availableVms.contains(fv) && seen.add(fv)) {
                double base = currentDecisionTime + fv.getBootTime() + fv.getAcquisitionDelay(); // see Source 1's comment — absolute-time mark
                AcqCandidate c = new AcqCandidate(fv, origin, false, false, false, 0.0, taskUplink, base, taskCost(fv, task));
                candidates.add(c);
                Log.printlnConcat("      → candidate CT=" + String.format("%.2f", c.ct) + "s");
            }
        } else {
            Log.printlnConcat("    [S2-fresh] skipped — origin node excluded");
        }

        // Source 3: ALL neighbors of origin node
        for (EdgeNode neighbor : origin.getNeighborNodes()) {
            if ((excludeNodeId != null && neighbor.getNodeId().equals(excludeNodeId)) || !neighbor.isHealthy()) {
                Log.printlnConcat("    [S3-xnode]    neighbor=" + neighbor.getNodeId()
                        + (neighbor.isHealthy() ? " skipped — excluded" : " skipped — node DOWN"));
                continue;
            }
            EdgeVm  releasedVm = loadGatedReleasedVm(neighbor);
            boolean isReused   = releasedVm != null;
            EdgeVm  vm         = isReused ? releasedVm : neighbor.findFreshVm();
            Log.printlnConcat("    [S3-xnode]    neighbor=" + neighbor.getNodeId()
                        + " " + (vm != null ? "VM#" + vm.getId() + " active=" + availableVms.contains(vm) : "no VM"));
            if (vm == null || availableVms.contains(vm) || !seen.add(vm)) continue;
            double interNode = origin.computeInterNodeDelay(task.getInputSizeKB());
            double base      = currentDecisionTime + (isReused ? vm.getBootTime() : vm.getBootTime() + vm.getAcquisitionDelay()); // see Source 1's comment
            AcqCandidate c = new AcqCandidate(vm, neighbor, isReused, true, false, interNode, taskUplink, base, taskCost(vm, task));
            candidates.add(c);
            Log.printlnConcat("      → CROSS-NODE candidate CT=" + String.format("%.2f", c.ct) + "s");
        }

        // Source 4: cloud tier fallback
        if (cloudNode != null && cloudNode.isHealthy()
                && !(excludeNodeId != null && cloudNode.getNodeId().equals(excludeNodeId))) {
            EdgeVm  releasedVm = loadGatedReleasedVm(cloudNode);
            boolean isReused   = releasedVm != null;
            EdgeVm  vm         = isReused ? releasedVm : cloudNode.findFreshVm();
            Log.printlnConcat("    [S4-cloud]    " + (vm != null ? "VM#" + vm.getId() + " active=" + availableVms.contains(vm) : "no VM"));
            if (vm != null && !availableVms.contains(vm) && seen.add(vm)) {
                double cloudDelay = origin.computeCloudUplinkDelay(task.getInputSizeKB());
                double base       = currentDecisionTime + (isReused ? vm.getBootTime() : vm.getBootTime() + vm.getAcquisitionDelay()); // see Source 1's comment
                AcqCandidate c = new AcqCandidate(vm, cloudNode, isReused, false, true, cloudDelay, taskUplink, base, taskCost(vm, task));
                candidates.add(c);
                Log.printlnConcat("      → CLOUD candidate CT=" + String.format("%.2f", c.ct) + "s");
            }
        }

        Log.printlnConcat("    → " + candidates.size() + " candidate(s) total");
        return candidates;
    }

    //activate winning candidate: register with node, seed vmCtMap, add to pool
    private void activateCandidate(AcqCandidate winner,
                                   EdgeCloudlet task,
                                   List<EdgeVm> availableVms) {
        if (winner.isReused) winner.activateOn.activateReleasedVm(winner.vm);
        else                 winner.activateOn.activateFreshVm(winner.vm);

        if (winner.isReused) {
            reusedVmIds.add(winner.vm.getId());
            winner.vm.incrementReuseCount();
        }
        if (winner.isCrossNode) {
            crossNodeCloudletIds.add(task.getCloudletId());
            Log.printlnConcat("    [CROSS-NODE] task #", task.getCloudletId(),
                    " from Node ", task.getOriginNode().getNodeId(),
                    " → VM#", winner.vm.getId(), " on Node ", winner.activateOn.getNodeId(),
                    " CT=", String.format("%.2f", winner.ct), "s",
                    " deadline=", String.format("%.2f", task.getDeadline()), "s");
            trace.recordEvent(currentDecisionTime, TraceEventType.CROSS_NODE_OFFLOAD, task.getCloudletId(),
                    winner.vm.getId(), winner.activateOn.getNodeId(),
                    "offloaded from Node " + task.getOriginNode().getNodeId());
        } else if (winner.isCloud) {
            cloudCloudletIds.add(task.getCloudletId());
            Log.printlnConcat("    [CLOUD] task #", task.getCloudletId(),
                    " from Node ", task.getOriginNode().getNodeId(),
                    " → VM#", winner.vm.getId(), " on cloud tier",
                    " CT=", String.format("%.2f", winner.ct), "s",
                    " deadline=", String.format("%.2f", task.getDeadline()), "s");
            trace.recordEvent(currentDecisionTime, TraceEventType.CLOUD_OFFLOAD, task.getCloudletId(),
                    winner.vm.getId(), winner.activateOn.getNodeId(),
                    "offloaded to cloud from Node " + task.getOriginNode().getNodeId());
        } else {
            trace.recordEvent(currentDecisionTime,
                    winner.isReused ? TraceEventType.VM_ACQUIRED_RELEASED : TraceEventType.VM_ACQUIRED_FRESH,
                    task.getCloudletId(), winner.vm.getId(), winner.activateOn.getNodeId(),
                    winner.isReused ? "released-idle VM reactivated" : "fresh VM cold-started");
        }

        TaskExecutionRecord record = trace.getOrCreateRecord(task.getCloudletId());
        record.acquisitionSource = winner.isCloud ? "CLOUD" : winner.isCrossNode ? "CROSS_NODE" : winner.isReused ? "RELEASED" : "FRESH";
        record.releasedVm = winner.isReused && !winner.isCrossNode && !winner.isCloud;
        record.freshVm = !winner.isReused && !winner.isCrossNode && !winner.isCloud;
        record.crossNode = winner.isCrossNode;
        record.cloud = winner.isCloud;
        record.reusedVm = winner.isReused;
        record.crossNodeOffload = winner.isCrossNode;
        record.cloudOffload = winner.isCloud;
        record.acquisitionDelay = winner.isReused ? 0.0 : winner.vm.getAcquisitionDelay();
        record.bootDelay = winner.vm.getBootTime();
        // Not one of Task B's explicitly-named 3 call sites, but winner.ct was
        // built from a deviceLatencyMs-aware uplink (see buildAcqCandidatesWithLogging's
        // taskUplink) — subtracting the plain (non-RTT) uplink here would silently
        // leak the RTT term into interNodeDelay. Updated for correctness, not just consistency.
        record.interNodeDelay = winner.isCrossNode || winner.isCloud ? (winner.ct - winner.base - winner.vm.computeUplinkDelay(task.getInputSizeKB(), task.getDeviceLatencyMs())) : 0.0;

        vmCtMap.put(winner.vm, winner.base);
        availableVms.add(winner.vm);
    }

    //immutable metadata for one acquisition candidate
    private static final class AcqCandidate {
        final EdgeVm   vm;
        final EdgeNode activateOn;
        final boolean  isReused;
        final boolean  isCrossNode;
        final boolean  isCloud;
        final double   base;
        final double   ct;   // extraNetworkDelay + uplinkDelay + base + taskCost (comparison only)

        AcqCandidate(EdgeVm vm, EdgeNode activateOn, boolean isReused, boolean isCrossNode, boolean isCloud,
                     double extraNetworkDelay, double uplinkDelay, double base, double taskCost) {
            this.vm          = vm;
            this.activateOn  = activateOn;
            this.isReused    = isReused;
            this.isCrossNode = isCrossNode;
            this.isCloud     = isCloud;
            this.base        = base;
            this.ct          = extraNetworkDelay + uplinkDelay + base + taskCost;
        }
    }

    //Algorithm 8 — low DPL → replicate; high DPL → resubmit only
    private boolean determineFaultTolerance(EdgeCloudlet task,
                                            List<Double> history,
                                            double ratio,
                                            double currentTime) {
        if (history.isEmpty()) return false;

        double taskDPL = networkAwareDPL(task, currentTime);
        int    index   = (int)(ratio * (history.size() - 1));
        return taskDPL <= history.get(index);
    }

    // private helpers

    //run assignAcquire for one task and commit if successful (no node exclusion)
    private EdgeVm doSchedule(EdgeCloudlet task, double currentTime) {
        return doSchedule(task, currentTime, null);
    }

    //run assignAcquire restricted away from excludeNodeId and commit if successful
    private EdgeVm doSchedule(EdgeCloudlet task, double currentTime, String excludeNodeId) {
        // Real wall-clock timing of DFARM's own search computation (direct-
        // assign check, then the 4-source acquisition cascade if that fails)
        // — distinct from the *simulated* CloudSim delays (uplink/downlink/
        // compute) recorded elsewhere. Stops the instant a VM is decided,
        // before commit() runs. See the vmSearchTimeMs javadoc on
        // TaskExecutionRecord for the caveat about Log I/O interleaved into
        // the acquisition-cascade branch.
        long searchStartNanos = System.nanoTime();
        List<Map.Entry<EdgeVm, Double>> sortedVmCts = sortedVmCtEntries(excludeNodeId);
        EdgeVm vm = assignAcquire(task, availableVms, sortedVmCts, currentTime, excludeNodeId);
        double searchElapsedMs = (System.nanoTime() - searchStartNanos) / 1_000_000.0;

        TaskExecutionRecord searchRecord = trace.getOrCreateRecord(task.getCloudletId());
        if (vm != null) {
            searchRecord.vmSearchTimeMs = searchElapsedMs;
        } else {
            searchRecord.failedSearchTimeMs = searchElapsedMs;
        }

        if (vm != null) {
            commit(task, vm);
        }
        return vm;
    }

    //Replica placement: node diversity is a HARD constraint, deadline is not.
    //1) Try the normal deadline-respecting path restricted to other nodes.
    //2) On failure, force placement on the best available other-node candidate
    //   (active or acquirable) regardless of deadline ("soft-line replica").
    //3) Only if no VM exists on ANY other node, drop the replica — tracked in
    //   structuralReplicaFailures (a structural placement failure, distinct
    //   from a normal deadline-based rejection).
    private EdgeVm doScheduleReplica(EdgeCloudlet replica, double currentTime, String excludeNodeId) {
        EdgeVm vm = doSchedule(replica, currentTime, excludeNodeId);
        if (vm != null) return vm;

        vm = pickBestOtherNodeIgnoringDeadline(replica, excludeNodeId);
        if (vm != null) {
            commit(replica, vm);
            Log.printlnConcat("    [REPLICA-SOFT] replica of task #", -(replica.getCloudletId()) - 1,
                    " forced onto VM#", vm.getId(), " (Node ", vm.getNodeId(), ")",
                    " past its deadline ", String.format("%.2f", replica.getDeadline()), "s",
                    " — node diversity preserved, deadline not");
            return vm;
        }

        structuralReplicaFailures.add(replica);
        Log.printlnConcat("    [REPLICA-STRUCTURAL-FAIL] no VM exists on any node other than ",
                excludeNodeId, " — replica of task #", -(replica.getCloudletId()) - 1, " dropped");
        return null;
    }

    //Best other-node candidate (local active VM or acquirable), ignoring deadline entirely.
    private EdgeVm pickBestOtherNodeIgnoringDeadline(EdgeCloudlet task, String excludeNodeId) {
        EdgeNode origin    = task.getOriginNode();
        double   taskUplink = (origin != null) ? origin.computeUplinkDelay(task.getInputSizeKB(), task.getDeviceLatencyMs()) : 0.0;

        EdgeVm bestLocalVm = null;
        double bestLocalCt = Double.MAX_VALUE;
        for (Map.Entry<EdgeVm, Double> entry : sortedVmCtEntries(excludeNodeId)) {
            EdgeVm vm = entry.getKey();
            double estimatedFinish = entry.getValue() + taskUplink + taskCost(vm, task);
            if (estimatedFinish < bestLocalCt) {
                bestLocalCt = estimatedFinish;
                bestLocalVm = vm;
            }
        }

        List<AcqCandidate> acqCandidates = buildAcqCandidatesWithLogging(task, excludeNodeId);
        AcqCandidate bestAcq = null;
        for (AcqCandidate c : acqCandidates) {
            if (bestAcq == null || c.ct < bestAcq.ct) bestAcq = c;
        }

        if (bestLocalVm == null && bestAcq == null) return null;

        if (bestAcq == null || (bestLocalVm != null && bestLocalCt <= bestAcq.ct)) {
            return bestLocalVm;
        }

        activateCandidate(bestAcq, task, availableVms);
        return bestAcq.vm;
    }

    //record task→vm in all tracking maps
    private void commit(EdgeCloudlet task, EdgeVm vm) {
        double vmCtBefore = vmCtMap.getOrDefault(vm, 0.0);
        taskVmMap.put(task, vm);
        vmCtMap.put(vm, vmCtBefore + taskCost(vm, task));
        // adjustToVm() (Algorithm 7) may have already inserted this task at a
        // specific queue position ahead of a bumped candidate — don't blindly
        // addLast() a second copy on top of that, or the VM's queue ends up
        // with the same task twice (breaks notifyTaskCompleted's removal,
        // leaks the VM so it never returns to the idle pool, and double-counts
        // it anywhere the queue is enumerated, e.g. getInProgressTasksOnNode).
        LinkedList<EdgeCloudlet> queue = vmQueueMap.computeIfAbsent(vm, v -> new LinkedList<>());
        int queueLenBefore = queue.size();
        if (!queue.contains(task)) {
            queue.addLast(task);
        }

        TaskExecutionRecord record = trace.getOrCreateRecord(task.getCloudletId());
        record.assignedNode = vm.getNodeId();
        record.assignedVmId = vm.getId();
        record.assignedVmMips = vm.getMips();
        // vmCtMap.get(vm) is the VM's aggregate tail-end total (correct for
        // vmCtMap itself regardless of insertion position — see
        // tryQueueAdjustment()'s comment: total work, so total tail CT, is
        // order-independent). But it's only THIS task's own completion time
        // when the task really is at the tail. A queue-adjustment insertion
        // (pendingQueueAdjustedCt set by assignAcquire's QUEUE_ADJUST branch,
        // just now) places it mid-queue, where its real finish is Tx's own
        // Eq.6-computed CT, not the (now-later) tail total that really
        // belongs to whatever task got displaced.
        if (pendingQueueAdjustedCt != null) {
            record.completionTime = pendingQueueAdjustedCt;
            record.vmCtAfter = pendingQueueAdjustedCt;
            pendingQueueAdjustedCt = null;
        } else {
            record.completionTime = vmCtMap.get(vm);
            record.vmCtAfter = vmCtMap.get(vm);
        }
        record.vmCtBefore = vmCtBefore;
        record.queueLengthBefore = queueLenBefore;
        record.queueLengthAfter = queue.size();
        record.vmQueuePosition = queue.indexOf(task);
        // Not one of Task B's explicitly-named call sites, but this reports the
        // same persisted downlink component taskCost() below now computes
        // RTT-aware — kept consistent rather than silently drifting.
        record.downlinkDelay = vm.computeDownlinkDelay(task.getOutputSizeKB(), task.getDeviceLatencyMs());
        record.estimatedStartTime = vmCtBefore;
        record.estimatedFinishTime = record.completionTime;
        trace.recordEvent(currentDecisionTime, TraceEventType.TASK_COMMITTED, task.getCloudletId(),
                vm.getId(), vm.getNodeId(),
                "queue position " + record.vmQueuePosition + "/" + queue.size());
    }

    //move idle VM (empty queue) to releasedIdleVms — skips acqDelay on reuse,
    //but only within the grace window; see ageOutIdleVms() for the timeout.
    private void releaseIdleVm(EdgeVm vm) {
        availableVms.remove(vm);
        for (EdgeNode node : edgeNodes) {
            if (node.getActiveVms().contains(vm)) {
                node.getActiveVms().remove(vm);
                node.getReleasedIdleVms().add(vm);
                vm.setIdleSince(currentDecisionTime);
                return;
            }
        }
        if (cloudNode != null && cloudNode.getActiveVms().contains(vm)) {
            cloudNode.getActiveVms().remove(vm);
            cloudNode.getReleasedIdleVms().add(vm);
            vm.setIdleSince(currentDecisionTime);
        }
    }

    //Algorithm 6 grace period — demote any released-idle VM across the whole
    //topology that's sat past idleReleaseThresholdSec back to the cold pool
    //(pays full boot+acqDelay again next time). Called periodically from
    //DFARMController.handleSchedulingTick(), which already runs on
    //schedulingInterval — a natural, sufficient cadence for this check.
    public void ageOutIdleVms(double currentTime) {
        for (EdgeNode node : edgeNodes) node.ageOutIdleVms(currentTime, idleReleaseThresholdSec);
        if (cloudNode != null) cloudNode.ageOutIdleVms(currentTime, idleReleaseThresholdSec);
    }

    private double taskCost(EdgeVm vm, EdgeCloudlet task) {
        return task.getCloudletLength() / vm.getMips()
             + vm.computeDownlinkDelay(task.getOutputSizeKB(), task.getDeviceLatencyMs());
    }

    //network-aware DPL: paper's Eq. 1/15 is DPL = (deadline - arrivalTime) / MI,
    //a FIXED property of a task computed once — not recomputed against
    //whatever the current decision-time clock happens to be. Anchoring on
    //currentTime instead (as this used to) meant identical tasks got
    //different priority/fault-tolerance treatment purely based on when they
    //happened to reach a scheduling decision — most visibly in
    //resubmitTask(), where recomputing DPL long after the original arrival
    //produced an artificially tighter (lower) value than the paper's fixed
    //one would. currentTime is kept as a parameter (unused for the anchor
    //now) so every call site keeps compiling unchanged. The uplink term
    //remains a legitimate edge-specific addition on top of the paper's
    //formula, not something being removed here.
    private double networkAwareDPL(EdgeCloudlet task, double currentTime) {
        EdgeNode origin = task.getOriginNode();
        // Not one of Task B's explicitly-named 3 call sites, but this is the
        // SAME physical device->origin-node uplink trip the CT-side formula
        // now models with RTT — using the plain (non-RTT) version here would
        // make the priority/DPL calculation and the feasibility/CT calculation
        // disagree about how long the same hop takes. Flagged in this task's
        // final report as a deliberate extra fix, since unlike the other
        // straggler call sites this one does shift scheduling priority
        // ordering, not just a reported number.
        double uplink   = (origin != null) ? origin.computeUplinkDelay(task.getInputSizeKB(), task.getDeviceLatencyMs()) : 0.0;
        return (task.getDeadline() - task.getArrivalTime() - uplink) / (double) task.getCloudletLength();
    }

    private List<Map.Entry<EdgeVm, Double>> sortedVmCtEntries() {
        return sortedVmCtEntries(null);
    }

    private List<Map.Entry<EdgeVm, Double>> sortedVmCtEntries(String excludeNodeId) {
        List<Map.Entry<EdgeVm, Double>> entries = new ArrayList<>();
        for (Map.Entry<EdgeVm, Double> e : vmCtMap.entrySet()) {
            if (!availableVms.contains(e.getKey())) continue;
            if (excludeNodeId != null && e.getKey().getNodeId().equals(excludeNodeId)) continue;
            if (!isNodeHealthy(e.getKey().getNodeId())) continue; // dead node — never hand out its VMs, even ones "already active"
            entries.add(e);
        }
        entries.sort(Map.Entry.comparingByValue());
        return entries;
    }

    //looks up node health by id across edge nodes + cloud tier; unknown id treated as healthy
    private boolean isNodeHealthy(String nodeId) {
        for (EdgeNode node : edgeNodes) {
            if (node.getNodeId().equals(nodeId)) return node.isHealthy();
        }
        if (cloudNode != null && cloudNode.getNodeId().equals(nodeId)) return cloudNode.isHealthy();
        return true;
    }

    private void insertSortedDPL(EdgeCloudlet task, double currentTime) {
        double dpl = networkAwareDPL(task, currentTime);
        int pos = Collections.binarySearch(dplHistory, dpl);
        if (pos < 0) pos = -(pos + 1);
        dplHistory.add(pos, dpl);
    }

    private EdgeCloudlet makeCopy(EdgeCloudlet original) {
        int replicaId = -(Math.abs(original.getCloudletId()) + 1);
        EdgeCloudlet copy = new EdgeCloudlet(
            replicaId,
            original.getCloudletLength(),
            original.getInputSizeKB(),
            original.getOutputSizeKB(),
            original.getDeadline(),
            original.getArrivalTime(),
            original.getSourceDeviceId(),
            original.getDeviceLatencyMs()
        );
        copy.setUserId(original.getUserId());
        copy.setOriginNode(original.getOriginNode());
        copy.setDuplicate(true);
        return copy;
    }

    // performance metrics

    public double computeTRR(int totalOriginalTasks) {
        long rejected = rejectedTasks.stream().filter(t -> !t.isDuplicate()).count();
        return (double) rejected / totalOriginalTasks;
    }

    public double computeMakespan() {
        return vmCtMap.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    }

    public double computeThroughput(int totalOriginalTasks) {
        long accepted = totalOriginalTasks -
                        rejectedTasks.stream().filter(t -> !t.isDuplicate()).count();
        double makespan = computeMakespan();
        return makespan > 0 ? (double) accepted / makespan : 0.0;
    }

    //ARUR = avg(VM.CT) / makespan, averaged over the FULL VM pool (paper Eq. 11) —
    //VMs never acquired contribute 0, they are not excluded from the average.
    public double computeARUR() {
        double makespan = computeMakespan();
        if (makespan == 0 || allVms == null || allVms.isEmpty()) return 0.0;
        double sum = 0.0;
        for (EdgeVm vm : allVms) {
            sum += vmCtMap.getOrDefault(vm, 0.0);
        }
        return (sum / allVms.size()) / makespan;
    }

    //Algorithm 2 priority key — tightest (lowest) network-aware DPL goes first.
    //Exposed so the controller can batch and sort pending arrivals before
    //committing scheduling decisions, instead of processing strict arrival order.
    public double computeSchedulingPriority(EdgeCloudlet task, double currentTime) {
        return networkAwareDPL(task, currentTime);
    }



    //extra resource cost due to replication: total VM-seconds reserved for
    //standby replicas across the whole run, whether they were ever activated or not
    public double computeTotalReplicaReservedCost() {
        return replicaReservedCost.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    //replica utilization: fraction of created replicas that were actually needed (activated)
    public double computeReplicaUtilization() {
        int activated = activatedReplicas.size();
        int total = activatedReplicas.size() + discardedReplicas.size() + lostStandbyReplicas.size();
        return total > 0 ? (double) activated / total : 0.0;
    }

    public Map<EdgeCloudlet, EdgeVm>             getTaskVmMap()               { return taskVmMap; }
    public List<EdgeCloudlet>                    getRejectedTasks()           { return rejectedTasks; }
    public List<EdgeCloudlet>                    getStructuralReplicaFailures() { return structuralReplicaFailures; }
    public Map<EdgeVm, Double>                   getVmCtMap()                 { return vmCtMap; }
    public Map<EdgeVm, LinkedList<EdgeCloudlet>> getVmQueueMap()              { return vmQueueMap; }
    public Set<Integer>                          getReusedVmIds()             { return reusedVmIds; }
    public Set<Integer>                          getCrossNodeCloudletIds()    { return crossNodeCloudletIds; }
    public Set<Integer>                          getCloudCloudletIds()       { return cloudCloudletIds; }
    public List<EdgeCloudlet>                    getActivatedReplicas()      { return activatedReplicas; }
    public List<EdgeCloudlet>                    getDiscardedReplicas()      { return discardedReplicas; }
    public List<EdgeCloudlet>                    getLostStandbyReplicas()    { return lostStandbyReplicas; }

    public double  getReplRatio()      { return replRatio; }
    public boolean isAllowTaskToRun()  { return allowTaskToRun; }
    public boolean isDeepSearch()      { return deepSearch; }
}
