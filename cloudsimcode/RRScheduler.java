package org.cloudbus.cloudsim;

import java.util.*;

/**
 * RR — Round Robin baseline scheduler.
 *
 * Tasks are assigned to VMs in cyclic order (VM 0, 1, 2, …, n-1, 0, 1, …).
 * No deadline awareness. All tasks are always assigned.
 */
public class RRScheduler extends EdgeSchedulerBase {

    @Override
    public String getAlgorithmName() { return "RR"; }

    @Override
    public Map<EdgeCloudlet, EdgeVm> schedule(List<EdgeCloudlet> tasks,
                                              List<EdgeVm> vms,
                                              double currentTime) {
        initVmCtMap(vms);

        int index = 0;
        for (EdgeCloudlet task : tasks) {
            EdgeVm vm = vms.get(index % vms.size());
            assign(task, vm);
            index++;
        }

        return taskVmMap;
    }
}
