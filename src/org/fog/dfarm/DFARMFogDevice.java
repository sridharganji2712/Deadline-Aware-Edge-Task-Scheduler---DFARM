package org.fog.dfarm;

import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.core.CloudActionTags;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.core.HostEntity;
import org.fog.dfarm.trace.ExecutionTraceManager;
import org.fog.dfarm.trace.TraceEventType;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;

/**
 * FogDevice subclass used to physically host EdgeVms and execute EdgeCloudlets
 * for the DFARM port.
 *
 * iFogSim's FogDevice implements a "distributed data flow" (DDF) continuous
 * operator model: FogDevice.checkCloudletCompletion() unconditionally ends
 * with updateAllocatedMips(null), which strips every VM's individually
 * allocated MIPS and hands the ENTIRE host's MIPS to whichever VM currently
 * has a running cloudlet (0 to everything else). That model is incompatible
 * with DFARM, which depends on each VM keeping its own fixed, heterogeneous
 * MIPS cap for the whole simulation (e.g. Node A's 4 VMs are 100/200/300/500
 * MIPS).
 *
 * checkCloudletCompletion() is dispatched virtually by the inherited
 * Datacenter/PowerDatacenter machinery on EVERY cloudlet completion,
 * regardless of how the cloudlet was submitted (confirmed: both
 * Datacenter.processCloudletSubmit and PowerDatacenter's processing loop
 * call it) — so it is not enough to merely avoid iFogSim's TUPLE_ARRIVAL /
 * Sensor / ModulePlacement path. This override replicates plain
 * Datacenter.checkCloudletCompletion()'s behaviour (just return finished
 * cloudlets to their owner) with no MIPS reallocation, which is what
 * actually keeps every EdgeVm's MIPS cap fixed for the whole run.
 */
public class DFARMFogDevice extends FogDevice {

    public DFARMFogDevice(
            String name,
            FogDeviceCharacteristics characteristics,
            VmAllocationPolicy vmAllocationPolicy,
            List<Storage> storageList,
            double schedulingInterval,
            double uplinkBandwidth,
            double downlinkBandwidth,
            double uplinkLatency,
            double ratePerMips) throws Exception {
        super(name, characteristics, vmAllocationPolicy, storageList,
                schedulingInterval, uplinkBandwidth, downlinkBandwidth, uplinkLatency, ratePerMips);
    }

    @Override
    protected void checkCloudletCompletion() {
        for (HostEntity host : getVmAllocationPolicy().getHostList()) {
            for (GuestEntity vm : host.getGuestList()) {
                while (vm.getCloudletScheduler().isFinishedCloudlets()) {
                    Cloudlet cl = vm.getCloudletScheduler().getNextFinishedCloudlet();
                    if (cl != null) {
                        // device-level trace entry, for Sheet 2 audit completeness —
                        // DFARMController.processCloudletReturn records the same
                        // moment with scheduling context; this is the physical-device
                        // side of the same event.
                        ExecutionTraceManager.getInstance().recordEvent(CloudSim.clock(),
                                TraceEventType.EXECUTION_FINISHED, cl.getCloudletId(), vm.getId(), getName(),
                                "cloudlet finished on device " + getName());
                        sendNow(cl.getUserId(), CloudActionTags.CLOUDLET_RETURN, cl);
                    }
                }
            }
        }
    }
}
