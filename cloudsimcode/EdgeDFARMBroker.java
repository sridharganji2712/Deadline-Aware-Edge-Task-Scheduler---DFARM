package org.cloudbus.cloudsim;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudActionTags;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.core.SimEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * EdgeDFARMBroker — wires DFARMScheduler into CloudSim's event loop
 * using ARRIVAL-DRIVEN scheduling.
 *
 * Flow:
 *   1. VMs created → submitCloudlets() fires
 *       dfarm.initScheduler() seeds the VM pool
 *       one TASK_ARRIVED event scheduled per cloudlet at its arrivalTime
 *
 *   2. Each TASK_ARRIVED event fires at the right simulation time
 *       dfarm.scheduleOneTask() makes the assignment decision
 *       cloudlet submitted to datacenter with uplink+boot+acq delay
 *
 *   3. CLOUDLET_RETURN fires when execution completes
 *       dfarm.notifyTaskCompleted() updates the VM queue
 *       shutdown triggered only when ALL arrivals processed AND submitted == 0
 */
public class EdgeDFARMBroker extends DatacenterBroker {

    // TASK_ARRIVED: fires at task.arrivalTime; DOWNLINK_COMPLETE: fires after result delivery
    private enum EdgeBrokerTags implements CloudSimTags { TASK_ARRIVED, DOWNLINK_COMPLETE }

    private final DFARMScheduler dfarm;
    private final List<EdgeNode> edgeNodes;
    private int totalOriginalTasks = 0; // non-replica task count for metrics
    private int pendingArrivals = 0;    // arrival events not yet processed (prevents early shutdown)

    public EdgeDFARMBroker(String name,
                           List<EdgeNode> edgeNodes,
                           double replRatio,
                           boolean allowTaskToRun,
                           boolean deepSearch) throws Exception {
        super(name);
        this.edgeNodes = edgeNodes;
        this.dfarm = new DFARMScheduler(replRatio, allowTaskToRun, deepSearch, edgeNodes);
    }

    //Step 1 — VMs ready: init DFARM and schedule arrival events
    @Override
    protected void submitCloudlets() {
        List<EdgeVm> vms = new ArrayList<>();
        for (GuestEntity g : getGuestsCreatedList()) {
            if (g instanceof EdgeVm) vms.add((EdgeVm) g);
        }

        if (vms.isEmpty()) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": No EdgeVms available. Aborting.");
            finishExecution();
            return;
        }

        List<EdgeCloudlet> tasks = new ArrayList<>();
        for (Cloudlet c : getCloudletList()) {
            if (c instanceof EdgeCloudlet) tasks.add((EdgeCloudlet) c);
        }

        if (tasks.isEmpty()) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": No tasks to schedule.");
            finishExecution();
            return;
        }

        totalOriginalTasks = tasks.size();

        dfarm.initScheduler(vms, CloudSim.clock());

        // schedule one TASK_ARRIVED event per task at its arrivalTime
        pendingArrivals = tasks.size();
        for (EdgeCloudlet task : tasks) {
            send(getId(), task.getArrivalTime(), EdgeBrokerTags.TASK_ARRIVED, task);
        }

        getCloudletList().clear(); // prevent base class from re-triggering VM creation

        Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                ": Scheduled ", totalOriginalTasks, " task arrival events across ",
                vms.size(), " VMs.");
    }

    //Event dispatch — intercept TASK_ARRIVED, pass everything else to super
    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == EdgeBrokerTags.TASK_ARRIVED) {
            handleTaskArrival(ev);
        } else if (ev.getTag() == EdgeBrokerTags.DOWNLINK_COMPLETE) {
            handleDownlinkComplete(ev);
        } else {
            super.processEvent(ev);
        }
    }

    //Step 2 — task arrives: run DFARM, submit assigned cloudlets to datacenter
    private void handleTaskArrival(SimEvent ev) {
        pendingArrivals--;

        EdgeCloudlet task = (EdgeCloudlet) ev.getData();
        double currentTime = CloudSim.clock();

        List<EdgeCloudlet> scheduled = dfarm.scheduleOneTask(task, currentTime);

        if (scheduled.isEmpty()) {
            // Hard-line rejection
            Log.printlnConcat(currentTime, ": ", getName(),
                    ": REJECTED EdgeCloudlet #", task.getCloudletId(),
                    " [deadline=", task.getDeadline(), "s]");
            checkShutdown();
            return;
        }

        for (EdgeCloudlet cloudlet : scheduled) {
            EdgeVm vm = dfarm.getTaskVmMap().get(cloudlet);
            if (vm == null) continue;

            Integer datacenterId = getVmsToDatacentersMap().get(vm.getId());
            if (datacenterId == null) {
                Log.printlnConcat(currentTime, ": ", getName(),
                        ": WARNING — VM #", vm.getId(), " has no datacenter mapping. Skipping #",
                        cloudlet.getCloudletId());
                continue;
            }

            submitWithDelay(cloudlet, vm, datacenterId);
        }
    }

    //submit with delay = uplink + boot+acq (first task on VM only) + inter-node (cross-node only)
    private void submitWithDelay(EdgeCloudlet cloudlet, EdgeVm vm, int datacenterId) {
        // uplink is device→ORIGIN node; use origin node's wireless bandwidth, not VM's node
        EdgeNode origin = cloudlet.getOriginNode();
        double uplinkDelay = (origin != null)
                ? origin.computeUplinkDelay(cloudlet.getInputSizeKB())
                : vm.computeUplinkDelay(cloudlet.getInputSizeKB());

        boolean isCrossNode    = dfarm.getCrossNodeCloudletIds().contains(cloudlet.getCloudletId());
        double  interNodeDelay = isCrossNode ? vm.computeInterNodeDelay(cloudlet.getInputSizeKB()) : 0.0;

        boolean isFirstOnVm = dfarm.getVmQueueMap().get(vm) != null
                           && dfarm.getVmQueueMap().get(vm).getFirst() == cloudlet;
        boolean isReused    = dfarm.getReusedVmIds().contains(vm.getId());
        double  startupCost = isFirstOnVm
                ? (isReused ? vm.getBootTime() : vm.getBootTime() + vm.getAcquisitionDelay())
                : 0.0;

        double submissionDelay = uplinkDelay + startupCost + interNodeDelay;

        cloudlet.setGuestId(vm.getId());
        send(datacenterId, submissionDelay, CloudActionTags.CLOUDLET_SUBMIT, cloudlet);
        cloudletsSubmitted++;
        getCloudletSubmittedList().add(cloudlet);

        Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                ": Submitted #", cloudlet.getCloudletId(),
                (cloudlet.isDuplicate() ? " [REPLICA]" : ""),
                " → VM #", vm.getId(), " (Node ", vm.getNodeId(), ")",
                " | delay=", String.format("%.3f", submissionDelay), "s",
                isCrossNode ? " [CROSS-NODE]" : "");
    }

    //Step 3a — CPU done: schedule DOWNLINK_COMPLETE to model result delivery delay
    @Override
    protected void processCloudletReturn(SimEvent ev) {
        Cloudlet cloudlet = (Cloudlet) ev.getData();
        getCloudletReceivedList().add(cloudlet);

        if (cloudlet instanceof EdgeCloudlet ec) {
            EdgeVm vm = dfarm.getTaskVmMap().get(ec);
            double downlinkDelay = (vm != null) ? vm.computeDownlinkDelay(ec.getOutputSizeKB()) : 0.0;

            Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                    ": Cloudlet #", ec.getCloudletId(), " CPU done.",
                    " Downlink=", String.format("%.3f", downlinkDelay), "s");

            send(getId(), downlinkDelay, EdgeBrokerTags.DOWNLINK_COMPLETE, ec);
        } else {
            cloudletsSubmitted--;
            checkShutdown();
        }
    }

    //Step 3b — downlink done: task complete from device perspective
    private void handleDownlinkComplete(SimEvent ev) {
        EdgeCloudlet cloudlet = (EdgeCloudlet) ev.getData();

        dfarm.notifyTaskCompleted(cloudlet);
        logActualVsPredicted(cloudlet);

        cloudletsSubmitted--;

        Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                ": Cloudlet #", cloudlet.getCloudletId(), " delivered to device.",
                " Submitted=", cloudletsSubmitted, " PendingArrivals=", pendingArrivals);

        checkShutdown();
    }

    //shut down only when all arrivals processed AND all submissions returned
    private void checkShutdown() {
        if (cloudletsSubmitted == 0 && pendingArrivals == 0) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": All tasks complete. Shutting down.");
            clearDatacenters();
            finishExecution();
        }
    }

    private void logActualVsPredicted(EdgeCloudlet cloudlet) {
        EdgeVm vm = dfarm.getTaskVmMap().get(cloudlet);
        if (vm == null) return;

        double predictedCt = dfarm.getVmCtMap().getOrDefault(vm, 0.0);
        double actualEndToEnd = CloudSim.clock();

        Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                ": Cloudlet #", cloudlet.getCloudletId(),
                " | actual e2e=", String.format("%.3f", actualEndToEnd), "s",
                " | predicted CT=", String.format("%.3f", predictedCt), "s",
                " | delta=", String.format("%.3f", Math.abs(actualEndToEnd - predictedCt)), "s");
    }

    //metrics output at simulation end
    @Override
    public void shutdownEntity() {
        super.shutdownEntity();
        printDFARMMetrics();
    }

    private void printDFARMMetrics() {
        int rejected = (int) dfarm.getRejectedTasks().stream().filter(t -> !t.isDuplicate()).count();
        int accepted = totalOriginalTasks - rejected;

        Log.printlnConcat("\n========================================");
        Log.printlnConcat("  DFARM Edge Simulation Results");
        Log.printlnConcat("========================================");
        Log.printlnConcat("  Total tasks (original) : ", totalOriginalTasks);
        Log.printlnConcat("  Accepted               : ", accepted);
        Log.printlnConcat("  Rejected               : ", rejected);
        Log.printlnConcat("  TRR (Task Rejection)   : ",
                String.format("%.4f", dfarm.computeTRR(totalOriginalTasks)));
        Log.printlnConcat("  Makespan               : ",
                String.format("%.2f", dfarm.computeMakespan()), " s");
        Log.printlnConcat("  Throughput             : ",
                String.format("%.4f", dfarm.computeThroughput(totalOriginalTasks)), " tasks/s");
        Log.printlnConcat("  ARUR                   : ",
                String.format("%.4f", dfarm.computeARUR()));
        Log.printlnConcat("========================================\n");

        Log.printlnConcat("  VM CT breakdown by Node:");
        for (Map.Entry<EdgeVm, Double> entry : dfarm.getVmCtMap().entrySet()) {
            EdgeVm vm = entry.getKey();
            Log.printlnConcat("    Node ", vm.getNodeId(),
                    " | VM #", vm.getId(),
                    " | CT = ", String.format("%.2f", entry.getValue()), "s",
                    dfarm.getReusedVmIds().contains(vm.getId()) ? " [reused]" : "");
        }
        Log.printlnConcat("========================================\n");
    }

    // accessors

    public DFARMScheduler getDfarm()              { return dfarm; }
    public int            getTotalOriginalTasks() { return totalOriginalTasks; }
}
