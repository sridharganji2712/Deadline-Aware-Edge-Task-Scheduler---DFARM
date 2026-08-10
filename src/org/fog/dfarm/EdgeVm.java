package org.fog.dfarm;

import org.cloudbus.cloudsim.CloudletScheduler;
import org.cloudbus.cloudsim.Vm;

/**
 * Extends Vm with edge-specific fields needed by DFARM:
 *  - bootTime              : seconds for VM to become ready after acquisition
 *  - acquisitionDelay      : seconds to acquire VM from the pool (cold start)
 *  - wirelessBandwidthKBps : uplink/downlink bandwidth of this node (KB/s)
 *  - interNodeBandwidthKBps: bandwidth for cross-node offload (KB/s)
 *  - nodeId                : which edge node (or "CLOUD") this VM belongs to
 *
 * Edge CT formula (used by DFARMScheduler):
 *   CT = uplinkDelay + bootTime + acquisitionDelay + (MI / MIPS) + downlinkDelay
 *
 * For re-acquired (released-idle) VMs:
 *   readyTime = bootTime only (no acquisitionDelay)
 */
public class EdgeVm extends Vm {

    private double bootTime;
    private double acquisitionDelay;
    private double wirelessBandwidthKBps;
    private double interNodeBandwidthKBps;
    private String nodeId;

    // how many times this VM has been re-acquired from releasedIdleVms —
    // a load signal for EdgeNode's candidate selection (see findReleasedVm())
    // and DFARMScheduler's acquisition-cascade load gate, since a released VM's
    // vmQueueMap depth is always 0 by definition (that's what "released" means).
    private int reuseCount = 0;

    // sim time this VM entered releasedIdleVms; -1 = not currently idle. Paper
    // Section 3.2 / Algorithm 6: idle VMs stay warm for a grace period before
    // de-provisioning, not indefinitely — see EdgeNode.ageOutIdleVms().
    private double idleSince = -1.0;

    public EdgeVm(
            int id,
            int userId,
            double mips,
            int numberOfPes,
            int ram,
            long bw,
            long size,
            String vmm,
            CloudletScheduler cloudletScheduler,
            double bootTime,
            double acquisitionDelay,
            double wirelessBandwidthKBps,
            double interNodeBandwidthKBps,
            String nodeId) {

        super(id, userId, mips, numberOfPes, ram, bw, size, vmm, cloudletScheduler);

        this.bootTime               = bootTime;
        this.acquisitionDelay       = acquisitionDelay;
        this.wirelessBandwidthKBps  = wirelessBandwidthKBps;
        this.interNodeBandwidthKBps = interNodeBandwidthKBps;
        this.nodeId                 = nodeId;
    }

    // CT formula helpers

    //uplinkDelay = inputSizeKB / wirelessBandwidthKBps
    public double computeUplinkDelay(double inputSizeKB) {
        return inputSizeKB / wirelessBandwidthKBps;
    }

    //uplinkDelay = half-RTT (device<->this VM's node) + inputSizeKB / wirelessBandwidthKBps.
    //See EdgeNode.computeUplinkDelay(double,double) — same model, VM-side copy.
    public double computeUplinkDelay(double inputSizeKB, double deviceLatencyMs) {
        return (deviceLatencyMs / 2000.0) + computeUplinkDelay(inputSizeKB);
    }

    //downlinkDelay = outputSizeKB / wirelessBandwidthKBps
    public double computeDownlinkDelay(double outputSizeKB) {
        return outputSizeKB / wirelessBandwidthKBps;
    }

    //downlinkDelay = half-RTT (device<->this VM's node) + outputSizeKB / wirelessBandwidthKBps
    public double computeDownlinkDelay(double outputSizeKB, double deviceLatencyMs) {
        return (deviceLatencyMs / 2000.0) + computeDownlinkDelay(outputSizeKB);
    }

    //interNodeDelay = inputSizeKB / interNodeBandwidthKBps
    public double computeInterNodeDelay(double inputSizeKB) {
        return inputSizeKB / interNodeBandwidthKBps;
    }

    //CT for fresh VM: uplink + bootTime + acqDelay + MI/MIPS + downlink
    public double computeEdgeCT(EdgeCloudlet cloudlet, double currentVmCt) {
        double uplinkDelay   = computeUplinkDelay(cloudlet.getInputSizeKB(), cloudlet.getDeviceLatencyMs());
        double computeTime   = cloudlet.getCloudletLength() / getMips();
        double downlinkDelay = computeDownlinkDelay(cloudlet.getOutputSizeKB(), cloudlet.getDeviceLatencyMs());

        return currentVmCt
             + uplinkDelay
             + bootTime
             + acquisitionDelay
             + computeTime
             + downlinkDelay;
    }

    //CT for reused VM: uplink + bootTime + MI/MIPS + downlink (no acqDelay)
    public double computeEdgeCTReused(EdgeCloudlet cloudlet, double currentVmCt) {
        double uplinkDelay   = computeUplinkDelay(cloudlet.getInputSizeKB(), cloudlet.getDeviceLatencyMs());
        double computeTime   = cloudlet.getCloudletLength() / getMips();
        double downlinkDelay = computeDownlinkDelay(cloudlet.getOutputSizeKB(), cloudlet.getDeviceLatencyMs());

        return currentVmCt
             + uplinkDelay
             + bootTime
             + computeTime
             + downlinkDelay;
    }

    // getters & setters

    public double getBootTime()                         { return bootTime; }
    public void   setBootTime(double t)                 { this.bootTime = t; }

    public double getAcquisitionDelay()                 { return acquisitionDelay; }
    public void   setAcquisitionDelay(double d)         { this.acquisitionDelay = d; }

    public double getWirelessBandwidthKBps()            { return wirelessBandwidthKBps; }
    public void   setWirelessBandwidthKBps(double bw)   { this.wirelessBandwidthKBps = bw; }

    public double getInterNodeBandwidthKBps()           { return interNodeBandwidthKBps; }
    public void   setInterNodeBandwidthKBps(double bw)  { this.interNodeBandwidthKBps = bw; }

    public String getNodeId()                           { return nodeId; }
    public void   setNodeId(String id)                  { this.nodeId = id; }

    public int  getReuseCount()       { return reuseCount; }
    public void incrementReuseCount() { reuseCount++; }

    public double getIdleSince()         { return idleSince; }
    public void   setIdleSince(double t) { this.idleSince = t; }
}
