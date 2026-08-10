package org.cloudbus.cloudsim;

import java.util.*;

/**
 * RS — Random Selection baseline scheduler.
 *
 * Each incoming task is assigned to a randomly chosen VM from the pool.
 * No deadline awareness. Tasks are assigned in the order they arrive.
 * All tasks are always assigned (no rejection).
 */
public class RSScheduler extends EdgeSchedulerBase {

    private final Random rng;

    public RSScheduler() {
        this.rng = new Random(42); // fixed seed for reproducibility
    }

    public RSScheduler(long seed) {
        this.rng = new Random(seed);
    }

    @Override
    public String getAlgorithmName() { return "RS"; }

    @Override
    public Map<EdgeCloudlet, EdgeVm> schedule(List<EdgeCloudlet> tasks,
                                              List<EdgeVm> vms,
                                              double currentTime) {
        initVmCtMap(vms);

        for (EdgeCloudlet task : tasks) {
            EdgeVm vm = vms.get(rng.nextInt(vms.size()));
            assign(task, vm);
        }

        return taskVmMap;
    }
}
