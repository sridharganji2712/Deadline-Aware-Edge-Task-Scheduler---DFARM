package org.cloudbus.cloudsim;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RALBA — Resource-Aware Load Balancing Algorithm baseline scheduler.
 *
 * Two-phase scheduling:
 *
 *   Fill phase:
 *     Distribute the heaviest tasks evenly across VMs.
 *     VMShare (seconds) = totalComputeLoad / numVMs  where computeLoad uses
 *     each VM's own MIPS.  Tasks are sorted in descending MI order.
 *     For each task, assign it to the VM with the most remaining share capacity
 *     that still has room, so large tasks are spread evenly before small tasks.
 *
 *   Spill phase:
 *     Any task that did not fit inside the fill budget is handled with MCT:
 *     assign to the VM that would finish the task earliest (min vmCt + cost).
 *
 * All tasks are always assigned (no rejection). No deadline awareness.
 *
 * Reference: generalised form of the RALBA strategy from edge-computing literature.
 */
public class RALBAScheduler extends EdgeSchedulerBase {

    @Override
    public String getAlgorithmName() { return "RALBA"; }

    @Override
    public Map<EdgeCloudlet, EdgeVm> schedule(List<EdgeCloudlet> tasks,
                                              List<EdgeVm> vms,
                                              double currentTime) {
        initVmCtMap(vms);

        // ── Compute VMShare ───────────────────────────────────────────────────
        // Ideal makespan if workload is perfectly load-balanced across all VMs:
        //   targetDuration = totalMI / totalMIPS  (seconds)
        // Each VM is given exactly targetDuration seconds of budget.
        // A fast VM (high MIPS) will naturally accept more MI within that budget;
        // a slow VM will accept less — this correctly accounts for heterogeneity.
        double totalMI        = tasks.stream().mapToLong(EdgeCloudlet::getCloudletLength).sum();
        double totalMIPS      = vms.stream().mapToDouble(EdgeVm::getMips).sum();
        double targetDuration = totalMI / totalMIPS; // ideal per-VM seconds budget

        // Per-VM accumulated compute seconds during fill phase (VM-MIPS-weighted)
        Map<EdgeVm, Double> vmFillLoad = new HashMap<>();
        for (EdgeVm vm : vms) vmFillLoad.put(vm, 0.0);

        // ── Fill phase ────────────────────────────────────────────────────────
        // Sort tasks descending by MI (largest first)
        List<EdgeCloudlet> sortedTasks = tasks.stream()
                .sorted(Comparator.comparingLong(EdgeCloudlet::getCloudletLength).reversed())
                .collect(Collectors.toCollection(ArrayList::new));

        List<EdgeCloudlet> spillList = new ArrayList<>();

        for (EdgeCloudlet task : sortedTasks) {
            // Find VM with the most remaining fill capacity that still fits this task.
            // taskComputeSec is VM-specific: a 2000-MIPS VM finishes 10x faster than a 200-MIPS VM.
            EdgeVm  bestVm        = null;
            double  bestRemaining = -1;

            for (EdgeVm vm : vms) {
                double taskComputeSec = (double) task.getCloudletLength() / vm.getMips();
                double remaining      = targetDuration - vmFillLoad.get(vm);
                if (remaining >= taskComputeSec && remaining > bestRemaining) {
                    bestRemaining = remaining;
                    bestVm        = vm;
                }
            }

            if (bestVm != null) {
                double taskComputeSec = (double) task.getCloudletLength() / bestVm.getMips();
                vmFillLoad.merge(bestVm, taskComputeSec, Double::sum);
                assign(task, bestVm);
            } else {
                spillList.add(task);
            }
        }

        // ── Spill phase (MCT) ─────────────────────────────────────────────────
        // Spill list preserves the fill-phase descending MI order so largest
        // remaining tasks are still handled first.
        for (EdgeCloudlet task : spillList) {
            EdgeVm best     = null;
            double bestTime = Double.MAX_VALUE;

            for (EdgeVm vm : vms) {
                double ct = vmCtMap.getOrDefault(vm, 0.0) + taskCost(vm, task);
                if (ct < bestTime) {
                    bestTime = ct;
                    best     = vm;
                }
            }

            if (best != null) assign(task, best);
        }

        return taskVmMap;
    }
}
