package org.fog.dfarm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a physical edge node (e.g. cell tower / MEC / micro DC), or the
 * cloud tier when nodeId = "CLOUD".
 *
 * This is a plain state container used by DFARMScheduler — not a SimEntity.
 * The scheduler queries this object to make VM assignment decisions. Each
 * EdgeNode is paired 1:1 with a DFARMFogDevice (same nodeId), which is the
 * real iFogSim/CloudSim entity that actually hosts and executes the VMs.
 *
 * Three VM lifecycle states per node:
 *   availableVmPool   — cold VMs, not yet acquired  (readyTime = bootTime + acqDelay)
 *   activeVms         — currently scheduled VMs
 *   releasedIdleVms   — previously active, now idle  (readyTime = bootTime only)
 */
public class EdgeNode {

    private final String nodeId;

    // VM lifecycle pools
    private final List<EdgeVm> availableVmPool;
    private final List<EdgeVm> activeVms;
    private final List<EdgeVm> releasedIdleVms;

    // Neighbour nodes for cross-node offload (Phase 4 / Source 3)
    private final List<EdgeNode> neighborNodes;

    // Node-level network config
    private final double wirelessBandwidthKBps;   // uplink/downlink to end devices
    private final double interNodeBandwidthKBps;  // backhaul link to neighbours
    private final double cloudBandwidthKBps;      // WAN link to the cloud tier (Source 4)

    private boolean healthy = true; // false while this node is down (see NodeFailureEvent)

    /**
     * @param nodeId                 unique node identifier ("A", "B", "C", "CLOUD")
     * @param wirelessBandwidthKBps  uplink/downlink bandwidth in KB/s
     * @param interNodeBandwidthKBps inter-node backhaul bandwidth in KB/s
     * @param cloudBandwidthKBps     edge-to-cloud WAN bandwidth in KB/s
     */
    public EdgeNode(String nodeId,
                    double wirelessBandwidthKBps,
                    double interNodeBandwidthKBps,
                    double cloudBandwidthKBps) {
        this.nodeId                 = nodeId;
        this.wirelessBandwidthKBps  = wirelessBandwidthKBps;
        this.interNodeBandwidthKBps = interNodeBandwidthKBps;
        this.cloudBandwidthKBps     = cloudBandwidthKBps;

        this.availableVmPool   = new ArrayList<>();
        this.activeVms         = new ArrayList<>();
        this.releasedIdleVms   = new ArrayList<>();
        this.neighborNodes     = new ArrayList<>();
    }

    // VM pool management

    public void addVm(EdgeVm vm) { availableVmPool.add(vm); }

    //Drop any VM that was constructed (addVm()'d) but never actually confirmed
    //created in CloudSim — e.g. a real "No Suitable Host Found" allocation
    //failure on every datacenter, which CAN happen with a zero-margin pool
    //(this topology's cloud tier requests exactly as much total MIPS as its
    //host has). addVm() runs at topology-construction time, before CloudSim
    //has attempted to create anything, so without this pruning the scheduler
    //keeps offering a VM as an acquisition candidate that doesn't physically
    //exist — every task routed to it silently vanishes (no datacenter mapping
    //to submit its cloudlet to), and nothing marks it lost or rejected.
    public void retainOnlyConfirmedVms(java.util.Set<Integer> confirmedVmIds) {
        availableVmPool.removeIf(vm -> !confirmedVmIds.contains(vm.getId()));
        activeVms.removeIf(vm -> !confirmedVmIds.contains(vm.getId()));
        releasedIdleVms.removeIf(vm -> !confirmedVmIds.contains(vm.getId()));
    }

    public void addNeighbor(EdgeNode node) { neighborNodes.add(node); }

    //find highest-MIPS released-idle VM (no acqDelay on reuse); null if none.
    //Ties on MIPS (e.g. a homogeneous pool like the cloud tier, where every
    //candidate has identical MIPS) are broken by LOWEST reuseCount, not list
    //order — otherwise the same first-indexed VM wins forever every time it
    //happens to be idle, starving the rest of an equally-capable pool that
    //never gets a turn (see DFARMScheduler's acquisition-cascade load gate).
    public EdgeVm findReleasedVm() {
        EdgeVm best = null;
        for (EdgeVm vm : releasedIdleVms) {
            if (best == null || vm.getMips() > best.getMips()
                    || (vm.getMips() == best.getMips() && vm.getReuseCount() < best.getReuseCount())) {
                best = vm;
            }
        }
        return best;
    }

    //find highest-MIPS fresh VM from cold pool; null if exhausted. Same
    //reuseCount tie-break as findReleasedVm() — fresh VMs all start at 0 so
    //this only matters once a fresh VM has previously cycled through
    //active->released->active again without ever leaving the fresh pool
    //semantics, but kept consistent for the same reason.
    public EdgeVm findFreshVm() {
        EdgeVm best = null;
        for (EdgeVm vm : availableVmPool) {
            if (best == null || vm.getMips() > best.getMips()
                    || (vm.getMips() == best.getMips() && vm.getReuseCount() < best.getReuseCount())) {
                best = vm;
            }
        }
        return best;
    }

    //uplinkDelay = inputSizeKB / wirelessBandwidthKBps (always origin node's bandwidth)
    public double computeUplinkDelay(double inputSizeKB) {
        return inputSizeKB / wirelessBandwidthKBps;
    }

    //uplinkDelay = half-RTT (device<->this node) + inputSizeKB / wirelessBandwidthKBps.
    //deviceLatencyMs is the ONLY place device-side RTT enters the model — it
    //does not apply to computeInterNodeDelay/computeCloudUplinkDelay, which
    //are backhaul links, not the originating device's own wireless hop.
    public double computeUplinkDelay(double inputSizeKB, double deviceLatencyMs) {
        return (deviceLatencyMs / 2000.0) + computeUplinkDelay(inputSizeKB);
    }

    //promote released-idle VM to active (skips acqDelay)
    public void activateReleasedVm(EdgeVm vm) {
        releasedIdleVms.remove(vm);
        activeVms.add(vm);
        vm.setIdleSince(-1.0); // no longer idle — grace-period clock stops
    }

    //promote fresh VM to active (cold start)
    public void activateFreshVm(EdgeVm vm) {
        availableVmPool.remove(vm);
        activeVms.add(vm);
    }

    //Algorithm 6 — move active VMs with empty queues to releasedIdleVms.
    //NOTE: this method is not currently called anywhere — the actual
    //active->released move happens in DFARMScheduler.releaseIdleVm() (a
    //separate, private, per-VM version). Kept here as public API and updated
    //for consistency with that method (currentTime param, idleSince stamping)
    //rather than left to silently drift out of sync — see the currentTime
    //parameter's use in ageOutIdleVms() below for why this stamp matters.
    public void releaseIdleVms(Map<EdgeVm, List<?>> vmQueueMap, double currentTime) {
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
            vm.setIdleSince(currentTime);
        }
    }

    //Demote any released-idle VM that has sat past the grace threshold back
    //into the cold pool (availableVmPool), so it pays full boot+acqDelay on
    //next use instead of staying permanently "warm" — see DFARM paper 3.2 /
    //Algorithm 6: resources may stay idle-but-warm for a grace period, not
    //indefinitely.
    public void ageOutIdleVms(double currentTime, double idleReleaseThresholdSec) {
        List<EdgeVm> expired = new ArrayList<>();
        for (EdgeVm vm : releasedIdleVms) {
            if (vm.getIdleSince() >= 0 && currentTime - vm.getIdleSince() >= idleReleaseThresholdSec) {
                expired.add(vm);
            }
        }
        for (EdgeVm vm : expired) {
            releasedIdleVms.remove(vm);
            vm.setIdleSince(-1.0);
            availableVmPool.add(vm);
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

    //cloudUplinkDelay = inputSizeKB / cloudBandwidthKBps (edge node -> cloud tier WAN hop)
    public double computeCloudUplinkDelay(double inputSizeKB) {
        return inputSizeKB / cloudBandwidthKBps;
    }

    // getters

    public boolean isHealthy()             { return healthy; }
    public void    setHealthy(boolean h)   { this.healthy = h; }

    public String          getNodeId()                 { return nodeId; }
    public double          getWirelessBandwidthKBps()  { return wirelessBandwidthKBps; }
    public double          getInterNodeBandwidthKBps() { return interNodeBandwidthKBps; }
    public double          getCloudBandwidthKBps()     { return cloudBandwidthKBps; }
    public List<EdgeVm>    getAvailableVmPool()        { return availableVmPool; }
    public List<EdgeVm>    getActiveVms()              { return activeVms; }
    public List<EdgeVm>    getReleasedIdleVms()        { return releasedIdleVms; }
    public List<EdgeNode>  getNeighborNodes()          { return neighborNodes; }
}
