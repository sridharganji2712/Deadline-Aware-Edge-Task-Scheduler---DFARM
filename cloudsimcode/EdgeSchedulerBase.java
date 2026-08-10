package org.cloudbus.cloudsim;

import java.util.*;

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
 * Baselines do NOT run inside CloudSim — they compute metrics analytically
 * using the same edge CT model so results are directly comparable to DFARM.
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

    //per-task cost: uplink + compute + downlink (boot/acq already in vmCtMap base)
    protected double taskCost(EdgeVm vm, EdgeCloudlet task) {
        return vm.computeUplinkDelay(task.getInputSizeKB())
             + (double) task.getCloudletLength() / vm.getMips()
             + vm.computeDownlinkDelay(task.getOutputSizeKB());
    }

    //assign task to VM: update vmCtMap, record timestamps, flag deadline misses
    protected void assign(EdgeCloudlet task, EdgeVm vm) {
        double startCt = vmCtMap.getOrDefault(vm, 0.0);
        double cost    = taskCost(vm, task);
        double endCt   = startCt + cost;

        vmCtMap.put(vm, endCt);
        taskVmMap.put(task, vm);
        taskTimestamps.put(task, new double[]{startCt, endCt});

        if (endCt > task.getDeadline()) {
            deadlineMissedTasks.add(task);
        }
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
