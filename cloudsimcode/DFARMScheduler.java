package org.cloudbus.cloudsim;

import java.util.*;

/**
 * DFARM: Deadline and Fault-aware task Adjusting and Resource Managing scheduler.
 * Adapted for the edge layer — implements all 8 algorithms from the paper with
 * the following edge modifications:
 *
 *  1. CT formula:  uplinkDelay + bootTime + acqDelay + (MI/MIPS) + downlinkDelay
 *  2. DPL formula: (deadline - arrivalTime) / MI  — per-task, reflects true urgency
 *  3. acquireVm:   4-step cascade — releasedIdleVms → freshPool → cross-node → cloud
 *  4. replRatio:   5–10% recommended 
 *
 * Scheduling is ARRIVAL-DRIVEN:
 *   - Call initScheduler() once when the VM pool is ready.
 *   - Call scheduleOneTask() each time a task arrives at its arrivalTime.
 *   - Call notifyTaskCompleted() whenever CloudSim returns a finished cloudlet.
 *
 * This class is a pure scheduling logic container — it is NOT a SimEntity.
 * EdgeDFARMBroker wires this into the CloudSim event loop.
 */
public class DFARMScheduler {

    // parameters
    private final double  replRatio;       // fraction of DPL history treated as tight → replicate
    private final boolean allowTaskToRun;  // soft-line: force on fastest VM; hard-line: drop
    private final boolean deepSearch;      // aggressive queue search vs break-on-first-fail
    private final List<EdgeNode> edgeNodes; // all nodes — used for cross-node offload

    // scheduler state
    private List<EdgeVm> availableVms;     // VMs currently active for scheduling
    private final Map<EdgeVm, LinkedList<EdgeCloudlet>> vmQueueMap; // per-VM task queue
    private final Map<EdgeVm, Double> vmCtMap;             // completion time per VM
    private final Map<EdgeCloudlet, EdgeVm> taskVmMap;     // task → assigned VM
    private final List<EdgeCloudlet> rejectedTasks;        // tasks that missed their deadline
    private final List<Double> dplHistory;                 // sorted DPL values (ascending)
    private final Set<Integer> reusedVmIds;                // VM IDs re-acquired (skip acqDelay)
    private final Set<Integer> crossNodeCloudletIds;       // cloudlet IDs routed cross-node

    // constructor

    public DFARMScheduler(double replRatio,
                          boolean allowTaskToRun,
                          boolean deepSearch,
                          List<EdgeNode> edgeNodes) {
        this.replRatio      = replRatio;
        this.allowTaskToRun = allowTaskToRun;
        this.deepSearch     = deepSearch;
        this.edgeNodes      = edgeNodes;

        this.availableVms        = new ArrayList<>();
        this.vmQueueMap          = new HashMap<>();
        this.vmCtMap             = new HashMap<>();
        this.taskVmMap           = new HashMap<>();
        this.rejectedTasks       = new ArrayList<>();
        this.dplHistory          = new ArrayList<>();
        this.reusedVmIds         = new HashSet<>();
        this.crossNodeCloudletIds = new HashSet<>();
    }

    //Algorithm 3 — init scheduler (seeds vmCtMap, resets all state)
    public void initScheduler(List<EdgeVm> vms, double currentTime) {
        vmQueueMap.clear();
        vmCtMap.clear();
        taskVmMap.clear();
        rejectedTasks.clear();
        dplHistory.clear();
        reusedVmIds.clear();
        crossNodeCloudletIds.clear();

        // empty active pool — VMs acquired on-demand to trigger acquisition cascade
        this.availableVms = new ArrayList<>();
    }

    //Algorithm 1 — schedule one arriving task (called per TASK_ARRIVED event)
    //Returns []: rejected; [task]: assigned; [task, replica]: assigned with replica
    public List<EdgeCloudlet> scheduleOneTask(EdgeCloudlet task, double currentTime) {
        List<EdgeCloudlet> result = new ArrayList<>();

        // Algorithm 8: fault-tolerance decision (before inserting this task's DPL)
        boolean replicate = determineFaultTolerance(task, dplHistory, replRatio, currentTime);

        // Maintain sorted DPL history
        insertSortedDPL(task, currentTime);

        // Algorithm 4: direct assign → queue adjust → acquire
        EdgeVm vm = doSchedule(task, currentTime);

        if (vm == null) {
            rejectedTasks.add(task);
            if (allowTaskToRun) {
                // Soft-line: force onto the fastest VM despite deadline miss
                List<Map.Entry<EdgeVm, Double>> sorted = sortedVmCtEntries();
                if (!sorted.isEmpty()) {
                    vm = sorted.get(0).getKey();
                    commit(task, vm);
                }
            }
        }

        if (vm != null) {
            result.add(task);

            // Schedule replica on a different VM if replication was decided
            if (replicate && !task.isDuplicate()) {
                EdgeCloudlet replica = makeCopy(task);
                insertSortedDPL(replica, currentTime);
                EdgeVm replicaVm = doSchedule(replica, currentTime);
                if (replicaVm != null) {
                    result.add(replica);
                }
                // Failed replicas are silently dropped — not added to rejectedTasks
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

    //Algorithm 4 — assign (phase1: direct, phase2: queue adjust) then acquire (phase3)
    private EdgeVm assignAcquire(EdgeCloudlet task,
                                 List<EdgeVm> availableVms,
                                 List<Map.Entry<EdgeVm, Double>> sortedVmCts,
                                 double currentTime) {
        double bestLocalCt = Double.MAX_VALUE;

        // Uplink is device→origin-node, same for every VM candidate in this loop.
        EdgeNode origin   = task.getOriginNode();
        double taskUplink = (origin != null)
                ? origin.computeUplinkDelay(task.getInputSizeKB())
                : 0.0;

        for (Map.Entry<EdgeVm, Double> entry : sortedVmCts) {
            EdgeVm vm   = entry.getKey();
            double vmCt = entry.getValue();
            double estimatedFinish = vmCt + taskUplink + taskCost(vm, task);

            if (estimatedFinish < bestLocalCt) bestLocalCt = estimatedFinish;

            if (estimatedFinish <= task.getDeadline()) return vm;

            EdgeCloudlet candidate = adjustPossible(task, vm);
            if (candidate != null) {
                adjustToVm(task, vm, candidate);
                return vm;
            }
        }

        return pickBestAcquisition(task, availableVms, bestLocalCt);
    }

    //Unified acquisition — builds candidates from all sources, picks best feasible
    private EdgeVm pickBestAcquisition(EdgeCloudlet task,
                                       List<EdgeVm> availableVms,
                                       double bestLocalCt) {
        List<AcqCandidate> candidates = buildAcqCandidatesWithLogging(task);
        if (candidates.isEmpty()) return null;

        AcqCandidate bestFeasible   = null;
        AcqCandidate bestInfeasible = null;

        for (AcqCandidate c : candidates) {
            if (c.ct <= task.getDeadline()) {
                if (bestFeasible == null || c.ct < bestFeasible.ct) bestFeasible = c;
            } else {
                if (bestInfeasible == null || c.ct < bestInfeasible.ct) bestInfeasible = c;
            }
        }

        AcqCandidate winner = (bestFeasible != null) ? bestFeasible
                : (bestInfeasible != null
                        && bestLocalCt < Double.MAX_VALUE
                        && bestInfeasible.ct < bestLocalCt) ? bestInfeasible
                : null;

        if (winner == null) return null;

        activateCandidate(winner, task, availableVms);
        return winner.vm;
    }

    //buildAcqCandidates with per-source diagnostic logging
    private List<AcqCandidate> buildAcqCandidatesWithLogging(EdgeCloudlet task) {
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
                    + " activeVms=" + availableVms.size());

        double taskUplink = origin.computeUplinkDelay(task.getInputSizeKB());

        // Source 1: released-idle VM on origin node
        EdgeVm rv = origin.findReleasedVm();
        Log.printlnConcat("    [S1-released] " + (rv != null ? "VM#" + rv.getId() + " active=" + availableVms.contains(rv) : "none"));
        if (rv != null && !availableVms.contains(rv) && seen.add(rv)) {
            AcqCandidate c = new AcqCandidate(rv, origin, true, false, 0.0, taskUplink, rv.getBootTime(), taskCost(rv, task));
            candidates.add(c);
            Log.printlnConcat("      → candidate CT=" + String.format("%.2f", c.ct) + "s");
        }

        // Source 2: fresh VM from origin node pool
        EdgeVm fv = origin.findFreshVm();
        Log.printlnConcat("    [S2-fresh]    " + (fv != null ? "VM#" + fv.getId() + " active=" + availableVms.contains(fv) : "none"));
        if (fv != null && !availableVms.contains(fv) && seen.add(fv)) {
            double base = fv.getBootTime() + fv.getAcquisitionDelay();
            AcqCandidate c = new AcqCandidate(fv, origin, false, false, 0.0, taskUplink, base, taskCost(fv, task));
            candidates.add(c);
            Log.printlnConcat("      → candidate CT=" + String.format("%.2f", c.ct) + "s");
        }

        // Source 3: ALL neighbors of origin node
        for (EdgeNode neighbor : origin.getNeighborNodes()) {
            boolean isReused = neighbor.findReleasedVm() != null;
            EdgeVm  vm       = isReused ? neighbor.findReleasedVm() : neighbor.findFreshVm();
            Log.printlnConcat("    [S3-xnode]    neighbor=" + neighbor.getNodeId()
                        + " " + (vm != null ? "VM#" + vm.getId() + " active=" + availableVms.contains(vm) : "no VM"));
            if (vm == null || availableVms.contains(vm) || !seen.add(vm)) continue;
            double interNode = origin.computeInterNodeDelay(task.getInputSizeKB());
            double base      = isReused ? vm.getBootTime() : vm.getBootTime() + vm.getAcquisitionDelay();
            AcqCandidate c = new AcqCandidate(vm, neighbor, isReused, true, interNode, taskUplink, base, taskCost(vm, task));
            candidates.add(c);
            Log.printlnConcat("      → CROSS-NODE candidate CT=" + String.format("%.2f", c.ct) + "s");
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

        if (winner.isReused)    reusedVmIds.add(winner.vm.getId());
        if (winner.isCrossNode) {
            crossNodeCloudletIds.add(task.getCloudletId());
            Log.printlnConcat("    [CROSS-NODE] task #", task.getCloudletId(),
                    " from Node ", task.getOriginNode().getNodeId(),
                    " → VM#", winner.vm.getId(), " on Node ", winner.activateOn.getNodeId(),
                    " CT=", String.format("%.2f", winner.ct), "s",
                    " deadline=", String.format("%.2f", task.getDeadline()), "s");
        }

        vmCtMap.put(winner.vm, winner.base);
        availableVms.add(winner.vm);
    }

    //immutable metadata for one acquisition candidate
    private static final class AcqCandidate {
        final EdgeVm   vm;
        final EdgeNode activateOn;
        final boolean  isReused;
        final boolean  isCrossNode;
        final double   base;
        final double   ct;   // interNodeDelay + uplinkDelay + base + taskCost (comparison only)

        AcqCandidate(EdgeVm vm, EdgeNode activateOn, boolean isReused, boolean isCrossNode,
                     double interNodeDelay, double uplinkDelay, double base, double taskCost) {
            this.vm          = vm;
            this.activateOn  = activateOn;
            this.isReused    = isReused;
            this.isCrossNode = isCrossNode;
            this.base        = base;
            this.ct          = interNodeDelay + uplinkDelay + base + taskCost;
        }
    }

    //Algorithm 7 — check if queue adjustment is possible
    private EdgeCloudlet adjustPossible(EdgeCloudlet newTask, EdgeVm vm) {
        LinkedList<EdgeCloudlet> queue = vmQueueMap.get(vm);
        if (queue == null || queue.isEmpty()) return null;

        double       vmCt        = vmCtMap.get(vm);
        double       newTaskCost = taskCostWithUplink(newTask, vm);
        EdgeCloudlet selectedCandidate = null;

        ListIterator<EdgeCloudlet> it = queue.listIterator(queue.size());
        while (it.hasPrevious()) {
            EdgeCloudlet candidate     = it.previous();
            double       candidateCost = taskCostWithUplink(candidate, vm);

            double newCandidateVmCt = vmCt + newTaskCost;
            double newTaskVmCt      = vmCt - candidateCost + newTaskCost;

            boolean newTaskOk   = newTaskVmCt      <= newTask.getDeadline();
            boolean candidateOk = newCandidateVmCt <= candidate.getDeadline();

            if (deepSearch) {
                if (candidateOk) {
                    vmCt -= candidateCost;
                    if (newTaskOk) selectedCandidate = candidate;
                } else {
                    break;
                }
            } else {
                if (newTaskOk && candidateOk) {
                    selectedCandidate = candidate;
                    vmCt -= candidateCost;
                } else {
                    break;
                }
            }
        }

        return selectedCandidate;
    }

    //insert newTask before candidate in the VM queue
    private void adjustToVm(EdgeCloudlet newTask, EdgeVm vm, EdgeCloudlet candidate) {
        LinkedList<EdgeCloudlet> queue =
            vmQueueMap.computeIfAbsent(vm, v -> new LinkedList<>());
        int idx = queue.indexOf(candidate);
        if (idx >= 0) queue.add(idx, newTask);
        else          queue.addLast(newTask);
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

    //run assignAcquire for one task and commit if successful
    private EdgeVm doSchedule(EdgeCloudlet task, double currentTime) {
        List<Map.Entry<EdgeVm, Double>> sortedVmCts = sortedVmCtEntries();
        EdgeVm vm = assignAcquire(task, availableVms, sortedVmCts, currentTime);
        if (vm != null) {
            commit(task, vm);
        }
        return vm;
    }

    //record task→vm in all tracking maps
    private void commit(EdgeCloudlet task, EdgeVm vm) {
        taskVmMap.put(task, vm);
        vmCtMap.put(vm, vmCtMap.getOrDefault(vm, 0.0) + taskCost(vm, task));
        vmQueueMap.computeIfAbsent(vm, v -> new LinkedList<>()).addLast(task);
    }

    //move idle VM (empty queue) to releasedIdleVms — skips acqDelay on reuse
    private void releaseIdleVm(EdgeVm vm) {
        availableVms.remove(vm);
        for (EdgeNode node : edgeNodes) {
            if (node.getActiveVms().contains(vm)) {
                node.getActiveVms().remove(vm);
                node.getReleasedIdleVms().add(vm);
                break;
            }
        }
    }

    private double taskCost(EdgeVm vm, EdgeCloudlet task) {
        return task.getCloudletLength() / vm.getMips()
             + vm.computeDownlinkDelay(task.getOutputSizeKB());
    }

    //taskCost + uplink — for feasibility checks that must match scheduler's model
    private double taskCostWithUplink(EdgeCloudlet task, EdgeVm vm) {
        EdgeNode origin = task.getOriginNode();
        double uplink   = (origin != null) ? origin.computeUplinkDelay(task.getInputSizeKB()) : 0.0;
        return uplink + taskCost(vm, task);
    }

    //network-aware DPL: subtracts uplink from deadline slack before dividing by MI
    private double networkAwareDPL(EdgeCloudlet task, double currentTime) {
        EdgeNode origin = task.getOriginNode();
        double uplink   = (origin != null) ? origin.computeUplinkDelay(task.getInputSizeKB()) : 0.0;
        return (task.getDeadline() - currentTime - uplink) / (double) task.getCloudletLength();
    }

    private List<Map.Entry<EdgeVm, Double>> sortedVmCtEntries() {
        List<Map.Entry<EdgeVm, Double>> entries = new ArrayList<>();
        for (Map.Entry<EdgeVm, Double> e : vmCtMap.entrySet()) {
            if (availableVms.contains(e.getKey())) entries.add(e);
        }
        entries.sort(Map.Entry.comparingByValue());
        return entries;
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

    public double computeARUR() {
        double makespan = computeMakespan();
        if (makespan == 0) return 0.0;
        double avgCt = vmCtMap.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return avgCt / makespan;
    }

  

    public Map<EdgeCloudlet, EdgeVm>             getTaskVmMap()           { return taskVmMap; }
    public List<EdgeCloudlet>                    getRejectedTasks()        { return rejectedTasks; }
    public Map<EdgeVm, Double>                   getVmCtMap()              { return vmCtMap; }
    public Map<EdgeVm, LinkedList<EdgeCloudlet>> getVmQueueMap()           { return vmQueueMap; }
    public Set<Integer>                          getReusedVmIds()          { return reusedVmIds; }
    public Set<Integer>                          getCrossNodeCloudletIds()  { return crossNodeCloudletIds; }
}
