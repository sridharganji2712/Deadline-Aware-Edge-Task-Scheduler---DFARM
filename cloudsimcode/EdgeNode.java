package org.cloudbus.cloudsim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a physical edge node (e.g. cell tower / MEC / micro DC).
 *
 * This is a plain state container used by DFARMScheduler — not a SimEntity.
 * The scheduler queries this object to make VM assignment decisions.
 *
 * Three VM lifecycle states per node:
 *   availableVmPool   — cold VMs, not yet acquired  (readyTime = bootTime + acqDelay)
 *   activeVms         — currently scheduled VMs
 *   releasedIdleVms   — previously active, now idle  (readyTime = bootTime only)
 *
 * Edge node config from PPT:
 *   Node A: 4 VMs, 1000 MIPS max, bootTime=5s, acqDelay=10s, wireless=20 Mbps
 *   Node B: 6 VMs, 2000 MIPS max, bootTime=3s, acqDelay=8s,  wireless=50 Mbps
 *   Node C: 4 VMs,  750 MIPS max, bootTime=8s, acqDelay=15s, wireless=10 Mbps
 */
public class EdgeNode {

    private final String nodeId;

    // VM lifecycle pools
    private final List<EdgeVm> availableVmPool;
    private final List<EdgeVm> activeVms;
    private final List<EdgeVm> releasedIdleVms;

    // Neighbour nodes for cross-node offload (Phase 4)
    private final List<EdgeNode> neighborNodes;

    // Node-level network config
    private final double wirelessBandwidthKBps;   // uplink/downlink to end devices
    private final double interNodeBandwidthKBps;  // backhaul link to neighbours

    /**
     * @param nodeId                 unique node identifier ("A", "B", "C")
     * @param wirelessBandwidthKBps  uplink/downlink bandwidth in KB/s
     * @param interNodeBandwidthKBps inter-node backhaul bandwidth in KB/s
     */
    public EdgeNode(String nodeId,
                    double wirelessBandwidthKBps,
                    double interNodeBandwidthKBps) {
        this.nodeId                 = nodeId;
        this.wirelessBandwidthKBps  = wirelessBandwidthKBps;
        this.interNodeBandwidthKBps = interNodeBandwidthKBps;

        this.availableVmPool   = new ArrayList<>();
        this.activeVms         = new ArrayList<>();
        this.releasedIdleVms   = new ArrayList<>();
        this.neighborNodes     = new ArrayList<>();
    }

    // VM pool management

    public void addVm(EdgeVm vm) { availableVmPool.add(vm); }

    public void addNeighbor(EdgeNode node) { neighborNodes.add(node); }

    //find highest-MIPS released-idle VM (no acqDelay on reuse); null if none
    public EdgeVm findReleasedVm() {
        EdgeVm best = null;
        for (EdgeVm vm : releasedIdleVms) {
            if (best == null || vm.getMips() > best.getMips()) best = vm;
        }
        return best;
    }

    //find highest-MIPS fresh VM from cold pool; null if exhausted
    public EdgeVm findFreshVm() {
        EdgeVm best = null;
        for (EdgeVm vm : availableVmPool) {
            if (best == null || vm.getMips() > best.getMips()) best = vm;
        }
        return best;
    }

    //uplinkDelay = inputSizeKB / wirelessBandwidthKBps (always origin node's bandwidth)
    public double computeUplinkDelay(double inputSizeKB) {
        return inputSizeKB / wirelessBandwidthKBps;
    }

    //promote released-idle VM to active (skips acqDelay)
    public void activateReleasedVm(EdgeVm vm) {
        releasedIdleVms.remove(vm);
        activeVms.add(vm);
    }

    //promote fresh VM to active (cold start)
    public void activateFreshVm(EdgeVm vm) {
        availableVmPool.remove(vm);
        activeVms.add(vm);
    }

    //Algorithm 6 — move active VMs with empty queues to releasedIdleVms
    public void releaseIdleVms(Map<EdgeVm, List<?>> vmQueueMap) {
        List<EdgeVm> toRelease = new ArrayList<>();

        for (EdgeVm vm : activeVms) {
            List<?> queue = vmQueueMap.get(vm);
            if (queue == null || queue.isEmpty()) {
                toRelease.add(vm);
            }
        }

        for (EdgeVm vm : toRelease) {
            activeVms.remove(vm);
            releasedIdleVms.add(vm);
        }
    }

    //find first neighbour node with a free VM (released or fresh); null if all exhausted
    public EdgeNode findNeighborWithFreeVm() {
        for (EdgeNode neighbor : neighborNodes) {
            if (neighbor.findReleasedVm() != null || neighbor.findFreshVm() != null) {
                return neighbor;
            }
        }
        return null;
    }

    //interNodeDelay = inputSizeKB / interNodeBandwidthKBps
    public double computeInterNodeDelay(double inputSizeKB) {
        return inputSizeKB / interNodeBandwidthKBps;
    }

    // getters

    public String          getNodeId()                 { return nodeId; }
    public double          getWirelessBandwidthKBps()  { return wirelessBandwidthKBps; }
    public double          getInterNodeBandwidthKBps() { return interNodeBandwidthKBps; }
    public List<EdgeVm>    getAvailableVmPool()        { return availableVmPool; }
    public List<EdgeVm>    getActiveVms()              { return activeVms; }
    public List<EdgeVm>    getReleasedIdleVms()        { return releasedIdleVms; }
    public List<EdgeNode>  getNeighborNodes()          { return neighborNodes; }
}
