package org.fog.dfarm;

import java.util.*;

import org.fog.dfarm.trace.ExecutionTraceManager;
import org.fog.dfarm.trace.TraceEventType;

/**
 * Abstract base for edge scheduling algorithms compared against DFARM.
 *
 * All subclasses share:
 *   - CT tracking per VM (initialized with bootTime + acquisitionDelay)
 *   - Task cost formula (uplink + compute + downlink) — same as DFARMScheduler
 *   - Estimated start/finish timestamps per task (derived from CT sequence)
 *   - Deadline-miss tracking
 *   - Uniform metrics: makespan, miss-rate, throughput, ARUR
 *
 * Baselines do NOT run inside the CloudSim/iFogSim event loop — they compute
 * metrics analytically using the same edge CT model so results are directly
 * comparable to DFARM. They intentionally only see the edge-tier VM pool
 * (no cloud tier), since cloud overflow is a DFARM-specific capability being
 * measured against these comparators.
 */
public abstract class EdgeSchedulerBase {

    // shared state (populated by schedule())
    protected final Map<EdgeVm, Double> vmCtMap = new LinkedHashMap<>();           // VM → CT
    protected final Map<EdgeCloudlet, EdgeVm> taskVmMap = new LinkedHashMap<>();  // task → VM
    protected final Map<EdgeCloudlet, double[]> taskTimestamps = new LinkedHashMap<>(); // task → [start, finish]
    protected final List<EdgeCloudlet> deadlineMissedTasks = new ArrayList<>();   // tasks past deadline

    public abstract Map<EdgeCloudlet, EdgeVm> schedule(List<EdgeCloudlet> tasks,
                                                       List<EdgeVm> vms,
                                                       double currentTime);
    public abstract String getAlgorithmName();

    // CT model helpers

    //init vmCtMap with cold-start overhead (bootTime + acqDelay) per VM
    protected void initVmCtMap(List<EdgeVm> vms) {
        vmCtMap.clear();
        for (EdgeVm vm : vms) {
            vmCtMap.put(vm, vm.getBootTime() + vm.getAcquisitionDelay());
        }
    }

    //per-task PERSISTED cost: compute + downlink only (boot/acq already in
    //vmCtMap base) — matches DFARMScheduler.taskCost() exactly. DFARM never
    //bakes a task's uplink (or cross-node/cloud hop) into a VM's running
    //completion time — see DFARMScheduler.commit(), which only ever adds
    //taskCost(vm, task) = compute + downlink to vmCtMap; uplink is used
    //transiently, once, for that one task's own feasibility check
    //(assignAcquire's estimatedFinish = vmCt + taskUplink + taskCost(...))
    //and then never stored. Baselines mirror that here instead of stacking
    //every task's uplink permanently onto every later task queued on the
    //same VM, which would otherwise make DFARM look better purely from a
    //network-accounting asymmetry rather than smarter scheduling.
    protected double taskCost(EdgeVm vm, EdgeCloudlet task) {
        return (double) task.getCloudletLength() / vm.getMips()
             + vm.computeDownlinkDelay(task.getOutputSizeKB(), task.getDeviceLatencyMs());
    }

    //uplink + any cross-node/cloud hop this task incurs, charged from its
    //ORIGIN node — not the destination VM's node — exactly like
    //DFARMScheduler does, so baselines aren't unfairly undercosted when they
    //place a task off its origin node. Transient by design (see taskCost()
    //above): only used to judge THIS task's own deadline feasibility, never
    //persisted into the VM's running completion time.
    protected double networkDelay(EdgeVm vm, EdgeCloudlet task) {
        EdgeNode origin = task.getOriginNode();
        double uplink = (origin != null)
                ? origin.computeUplinkDelay(task.getInputSizeKB(), task.getDeviceLatencyMs())
                : vm.computeUplinkDelay(task.getInputSizeKB(), task.getDeviceLatencyMs());

        double crossNodeDelay = 0.0;
        if (origin != null && !origin.getNodeId().equals(vm.getNodeId())) {
            crossNodeDelay = origin.computeInterNodeDelay(task.getInputSizeKB());
        }

        return uplink + crossNodeDelay;
    }

    //assign task to VM: update vmCtMap with the PERSISTED cost only (compute +
    //downlink — matches DFARM), but record/report this task's own full
    //estimated finish (including its own transient network delay) so
    //deadline-miss tracking and per-task reporting stay internally consistent
    //with each other, even though network delay never stacks onto later
    //tasks on the same VM.
    protected void assign(EdgeCloudlet task, EdgeVm vm) {
        double startCt          = vmCtMap.getOrDefault(vm, 0.0);
        double cost              = taskCost(vm, task);
        double persistedEndCt    = startCt + cost;
        double estimatedFinish   = startCt + networkDelay(vm, task) + cost;

        vmCtMap.put(vm, persistedEndCt);
        taskVmMap.put(task, vm);
        taskTimestamps.put(task, new double[]{startCt, estimatedFinish});

        boolean missed = estimatedFinish > task.getDeadline();
        if (missed) {
            deadlineMissedTasks.add(task);
        }

        // Baselines run analytically after the CloudSim simulation ends, all
        // at once — there's no real per-task clock to report, so every entry
        // lands at the single instant schedule() was invoked. Still valuable
        // for Sheet 2 audit purposes: which algorithm put which task on which
        // VM, and whether that would have met the deadline.
        ExecutionTraceManager.getInstance().recordEvent(startCt, TraceEventType.TASK_COMMITTED,
                task.getCloudletId(), vm.getId(), vm.getNodeId(),
                "[" + getAlgorithmName() + "] estimatedFinish=" + String.format("%.2f", estimatedFinish)
                        + "s, deadline " + (missed ? "MISSED" : "met"));
    }

    // metrics (same definitions as DFARMScheduler)

    public double computeMakespan() {
        return taskVmMap.values().stream()
                .mapToDouble(vm -> vmCtMap.getOrDefault(vm, 0.0))
                .max().orElse(0.0);
    }

    //miss rate: tasks past deadline / total (TRR-equivalent for baselines)
    public double computeMissRate(int totalOriginalTasks) {
        long missed = deadlineMissedTasks.stream().filter(t -> !t.isDuplicate()).count();
        return totalOriginalTasks > 0 ? (double) missed / totalOriginalTasks : 0.0;
    }

    //throughput = accepted originals / makespan
    public double computeThroughput(int totalOriginalTasks) {
        long missed   = deadlineMissedTasks.stream().filter(t -> !t.isDuplicate()).count();
        long accepted = totalOriginalTasks - missed;
        double makespan = computeMakespan();
        return makespan > 0 ? (double) accepted / makespan : 0.0;
    }

    //ARUR = avg(VM CT) / makespan
    public double computeARUR() {
        double makespan = computeMakespan();
        if (makespan == 0) return 0.0;
        OptionalDouble avg = taskVmMap.values().stream()
                .mapToDouble(vm -> vmCtMap.getOrDefault(vm, 0.0))
                .average();
        return avg.isPresent() ? avg.getAsDouble() / makespan : 0.0;
    }

    // accessors

    public Map<EdgeCloudlet, EdgeVm>   getTaskVmMap()          { return taskVmMap; }
    public Map<EdgeVm, Double>         getVmCtMap()             { return vmCtMap; }
    public Map<EdgeCloudlet, double[]> getTaskTimestamps()      { return taskTimestamps; }
    public List<EdgeCloudlet>          getDeadlineMissedTasks() { return deadlineMissedTasks; }
}
