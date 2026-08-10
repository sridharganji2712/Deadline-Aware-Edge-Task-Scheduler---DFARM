package org.fog.dfarm;

import java.util.List;

import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.core.HostEntity;

/**
 * Minimal single/few-host allocation policy for DFARMFogDevice nodes.
 *
 * Each DFARM node has exactly one PowerHost, so no multi-host selection
 * heuristic is needed. This deliberately avoids VmAllocationPolicySimple's
 * default SelectionPolicyLeastFull, which for a PowerHost candidate compares
 * PowerHost.getUtilizationOfCpu() against a starting sentinel of
 * Double.MIN_VALUE (the smallest POSITIVE double, not 0 or -infinity): an
 * idle host's utilization is exactly 0.0, which is never "greater than" that
 * positive sentinel, so it can never be selected for the very first VM
 * placed on it. That is a chicken-and-egg bug in this CloudSim fork when
 * VmAllocationPolicySimple is combined with PowerHost (required here since
 * PowerDatacenter casts every host to PowerHost) — this policy just picks
 * the first suitable host directly instead.
 */
public class DFARMVmAllocationPolicy extends VmAllocationPolicy {

    public DFARMVmAllocationPolicy(List<? extends HostEntity> list) {
        super(list);
    }

    @Override
    public HostEntity findHostForGuest(GuestEntity guest) {
        for (HostEntity host : getHostList()) {
            if (host.isSuitableForGuest(guest)) {
                return host;
            }
        }
        return null;
    }
}
