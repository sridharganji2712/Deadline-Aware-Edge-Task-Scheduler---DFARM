package org.cloudbus.cloudsim;

import java.util.*;

/**
 * MCT — Minimum Completion Time baseline scheduler.
 *
 * Each task is assigned to the VM whose CT would be earliest after accepting
 * this task: argmin over all VMs of (vmCt[vm] + taskCost(vm, task)).
 *
 * This is a classic greedy algorithm; it always assigns all tasks.
 * No deadline awareness.
 */
public class MCTScheduler extends EdgeSchedulerBase {

    @Override
    public String getAlgorithmName() { return "MCT"; }

    @Override
    public Map<EdgeCloudlet, EdgeVm> schedule(List<EdgeCloudlet> tasks,
                                              List<EdgeVm> vms,
                                              double currentTime) {
        initVmCtMap(vms);

        for (EdgeCloudlet task : tasks) {
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
