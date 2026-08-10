package org.fog.dfarm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudActionTags;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.core.predicates.Predicate;
import org.fog.dfarm.trace.ExecutionTraceManager;
import org.fog.dfarm.trace.TaskExecutionRecord;
import org.fog.dfarm.trace.TraceEventType;

/**
 * DFARMController — wires DFARMScheduler into the CloudSim/iFogSim event loop.
 * Near-mechanical port of the CloudSim reference's EdgeDFARMBroker, targeting
 * DFARMFogDevice instead of a plain Datacenter (see DFARMFogDevice for why
 * that substitution is safe), with one behavioural change from the
 * reference: scheduling decisions are made in DPL-priority BATCHES rather
 * than one-at-a-time on each individual arrival — see handleSchedulingTick().
 *
 * Flow:
 *   1. VMs created → submitCloudlets() fires
 *       dfarm.initScheduler() seeds the VM pool
 *       one TASK_ARRIVED event scheduled per cloudlet at its arrivalTime
 *       a periodic SCHEDULING_TICK loop starts
 *
 *   2. Each TASK_ARRIVED event just buffers the task into pendingQueue —
 *      no scheduling decision is made yet.
 *
 *   3. Each SCHEDULING_TICK drains pendingQueue in ascending DPL-priority
 *      order (Algorithm 2: tightest deadline-per-length first), running
 *      dfarm.scheduleOneTask() for each and submitting assigned cloudlets
 *      to the device with uplink+boot+acq(+interNode/cloud) delay.
 *
 *   4. CLOUDLET_RETURN fires when execution completes
 *       dfarm.notifyTaskCompleted() updates the VM queue
 *       shutdown triggered only when ALL arrivals processed, the pending
 *       queue is drained, AND all submissions have returned.
 *
 * Alongside scheduling itself, this class (together with DFARMScheduler) also
 * feeds ExecutionTraceManager — the centralized, timestamped execution trace
 * consumed after the run by the workbook exporter to reconstruct the whole
 * simulation. DFARMScheduler owns the SCHEDULING-DECISION side of each
 * record (assigned VM, acquisition source, replica placement); this class
 * owns the CLOUDSIM-EXECUTION side (actual submission/start/finish
 * timestamps, failure/failover outcomes) since it's the one actually driving
 * CloudSim events.
 */
public class DFARMController extends DatacenterBroker {

    // TASK_ARRIVED: fires at task.arrivalTime; SCHEDULING_TICK: periodic batch-drain;
    // DOWNLINK_COMPLETE: fires after result delivery; FAIL_NODE/RECOVER_NODE: fault injection
    private enum DFARMBrokerTags implements CloudSimTags {
        TASK_ARRIVED, SCHEDULING_TICK, DOWNLINK_COMPLETE, FAIL_NODE, RECOVER_NODE
    }

    private final DFARMScheduler dfarm;
    private final double schedulingInterval; // batch window between priority-sorted scheduling passes
    private int totalOriginalTasks = 0; // non-replica task count for metrics
    private int pendingArrivals = 0;    // arrival events not yet processed (prevents early shutdown)
    private final List<EdgeCloudlet> pendingQueue = new ArrayList<>(); // arrived, not yet scheduled
    private List<EdgeVm> allVms = new ArrayList<>(); // full VM pool, for actual-ARUR
    // Per-task "ready at" instant — computed once, at the moment DFARM decided
    // this task's VM (enqueue time), before it's known whether the VM is free
    // right now or the task must wait behind something else in its holding
    // queue (dfarm.getVmQueueMap()). Represents when this task's own network
    // transfer would complete if started immediately, plus boot/acquisition
    // cost if it's the very first real submission ever sent to this VM.
    // Reused at actual dispatch time (which may be later, if the VM was busy)
    // to reproduce the exact same real arrival instant the old per-VM
    // "next free" floor used to produce — see enqueueForSubmission()/dispatchNow().
    private final Map<Integer, Double> taskReadyAt = new HashMap<>();
    private final Set<Integer> vmEverSubmitted = new HashSet<>(); // vmId -> has ANY real cloudlet ever been sent to it
    private final Set<Integer> submittedCloudletIds = new HashSet<>(); // cloudlets sent to CloudSim, not yet returned/canceled
    private final Set<Integer> cpuCompletedIds = new HashSet<>(); // CPU execution finished, awaiting downlink delivery only
    private final Map<String, EdgeNode> nodesById = new HashMap<>(); // for failure-event lookups
    // nodeId -> the CloudSim datacenter (DFARMFogDevice) entity ID that VM was
    // actually built for. Populated via setNodeDatacenterIds() before
    // startSimulation(); see createVmsInDatacenter() override below for why
    // this exists — without it, the base DatacenterBroker's generic VM-
    // creation retry sweep can (and does) place a VM on the WRONG physical
    // node's host.
    private final Map<String, Integer> nodeDatacenterIds = new HashMap<>();

    private final Map<EdgeCloudlet, Double> actualDeliveryTime = new HashMap<>(); // task -> real CloudSim.clock() at delivery

    private final ExecutionTraceManager trace = ExecutionTraceManager.getInstance();

    // ── fault-injection state/metrics ────────────────────────────────────────
    private final List<NodeFailureEvent> plannedFailures = new ArrayList<>(); // registered pre-run, actually sent from submitCloudlets()
    private final List<NodeFailureEvent> nodeFailureLog = new ArrayList<>();
    private final Map<String, List<double[]>> downIntervals = new HashMap<>(); // nodeId -> [start,end] (end=-1 while still down)
    // FAIL_NODE/RECOVER_NODE self-events already sent but not yet fired. A
    // planned failure's fire time is independent of task completion — if the
    // whole workload finishes before some armed failure/recovery fires (real
    // with fast, non-quantized completion detection), checkShutdown() must
    // not let the controller call finishExecution()/shutdownEntity() while
    // one is still outstanding: CloudSim nulls a finished entity's incoming
    // queue, so a stray self-targeted event arriving afterward crashes the
    // whole simulation (NPE in CloudSim.dispatchEvent) rather than just
    // silently no-opping.
    private int pendingFailureEvents = 0;
    private int failoverCount = 0;
    private int resubmissionCount = 0;
    private final List<EdgeCloudlet> tasksLost = new ArrayList<>();
    private final List<Double> recoveryLatencies = new ArrayList<>();          // failure time -> replica submitted
    private final List<Double> replicaActivationLatencies = new ArrayList<>(); // original's arrival -> replica activated

    public DFARMController(String name,
                           List<EdgeNode> edgeNodes,
                           EdgeNode cloudNode,
                           double replRatio,
                           boolean allowTaskToRun,
                           boolean deepSearch,
                           double idleReleaseThresholdSec,
                           double schedulingInterval) throws Exception {
        super(name);
        this.dfarm = new DFARMScheduler(replRatio, allowTaskToRun, deepSearch, idleReleaseThresholdSec, edgeNodes, cloudNode);
        this.schedulingInterval = schedulingInterval;
        for (EdgeNode n : edgeNodes) nodesById.put(n.getNodeId(), n);
        if (cloudNode != null) nodesById.put(cloudNode.getNodeId(), cloudNode);
    }

    public DFARMController(String name,
                           List<EdgeNode> edgeNodes,
                           EdgeNode cloudNode,
                           double replRatio,
                           boolean allowTaskToRun,
                           boolean deepSearch,
                           double idleReleaseThresholdSec) throws Exception {
        this(name, edgeNodes, cloudNode, replRatio, allowTaskToRun, deepSearch, idleReleaseThresholdSec, 1.0);
    }

    //Must be called before submitGuestList()/startSimulation() so
    //createVmsInDatacenter() below can route each VM to its own intended
    //node's datacenter. Keys are EdgeNode/EdgeVm nodeId strings ("A","B","C",
    //or the cloud tier's own id); values are each corresponding
    //DFARMFogDevice's real CloudSim entity id (DFARMFogDevice.getId()).
    public void setNodeDatacenterIds(Map<String, Integer> nodeDatacenterIds) {
        this.nodeDatacenterIds.putAll(nodeDatacenterIds);
    }

    //The base DatacenterBroker.createVmsInDatacenter() (see CloudSim's
    //DatacenterBroker.java) is a generic "try every not-yet-created VM
    //against whichever datacenter is currently being attempted" sweep — it
    //has no notion that a VM was built FOR a specific node. Left unmodified,
    //this silently strays VMs onto the wrong physical node's host whenever
    //an earlier-tried datacenter happens to have a free-enough PE (confirmed
    //empirically: with 3 nodes and 14 VMs submitted via one submitGuestList()
    //call, only 8/14 VMs ever got created, several ended up hosted on the
    //wrong node's real PowerHost, and Node C's own host sat completely
    //unused for the entire run). Overriding this to filter by nodeId keeps
    //every VM bound to the datacenter matching where it was actually built,
    //so cross-node/network-delay modeling reflects what's really simulated.
    //Falls back to the base (unfiltered) behavior for any VM whose nodeId
    //isn't in nodeDatacenterIds (e.g. setNodeDatacenterIds() was never
    //called), so this degrades safely rather than silently breaking VM
    //creation for callers that don't opt in.
    @Override
    protected void createVmsInDatacenter(int datacenterId) {
        int requestedVms = 0;
        String datacenterName = CloudSim.getEntityName(datacenterId);
        for (GuestEntity guest : getGuestList()) {
            if (getVmsToDatacentersMap().containsKey(guest.getId())) continue;

            if (guest instanceof EdgeVm vm) {
                Integer homeDatacenterId = nodeDatacenterIds.get(vm.getNodeId());
                if (homeDatacenterId != null && !homeDatacenterId.equals(datacenterId)) {
                    continue; // this VM belongs to a different node's datacenter
                }
            }

            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Trying to Create ", guest.getClassName(),
                    " #", guest.getId(), " in ", datacenterName);
            sendNow(datacenterId, CloudActionTags.VM_CREATE_ACK, guest);
            requestedVms++;
        }

        getDatacenterRequestedIdsList().add(datacenterId);
        setVmsRequested(requestedVms);
        setVmsAcks(0);
    }

    //Step 1 — VMs ready: init DFARM, schedule arrival events, start the tick loop
    @Override
    protected void submitCloudlets() {
        trace.reset(); // clean slate for this run's execution trace

        if (dfarm.isDeepSearch()) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                    ": NOTE — deepSearch=true: Algorithm 7 (queue-adjustment, simple variant) is active. See DFARMScheduler/QueueAdjustment.");
        }

        List<EdgeVm> vms = new ArrayList<>();
        for (GuestEntity g : getGuestsCreatedList()) {
            if (g instanceof EdgeVm) vms.add((EdgeVm) g);
        }
        this.allVms = vms;

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
            send(getId(), task.getArrivalTime(), DFARMBrokerTags.TASK_ARRIVED, task);
        }

        getCloudletList().clear(); // prevent base class from re-triggering VM creation

        send(getId(), schedulingInterval, DFARMBrokerTags.SCHEDULING_TICK, null);

        for (NodeFailureEvent event : plannedFailures) {
            // The node actually dies at event.getTime(), but the controller
            // doesn't react until detectionDelay later — handleNodeFailure()
            // uses event.getTime() itself (carried in the event object) for
            // true-failure bookkeeping (down-interval start) and CloudSim.clock()
            // at the time it actually runs for everything reactive.
            send(getId(), event.getTime() + event.getDetectionDelay(), DFARMBrokerTags.FAIL_NODE, event);
            pendingFailureEvents++;
        }

        Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                ": Scheduled ", totalOriginalTasks, " task arrival events across ",
                vms.size(), " VMs. Batch scheduling interval=", schedulingInterval, "s.",
                plannedFailures.isEmpty() ? "" : (" " + plannedFailures.size() + " failure event(s) armed."));
    }

    //Event dispatch — intercept our own tags, pass everything else to super
    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() == DFARMBrokerTags.TASK_ARRIVED) {
            handleTaskArrival(ev);
        } else if (ev.getTag() == DFARMBrokerTags.SCHEDULING_TICK) {
            handleSchedulingTick();
        } else if (ev.getTag() == DFARMBrokerTags.DOWNLINK_COMPLETE) {
            handleDownlinkComplete(ev);
        } else if (ev.getTag() == DFARMBrokerTags.FAIL_NODE) {
            handleNodeFailure((NodeFailureEvent) ev.getData());
        } else if (ev.getTag() == DFARMBrokerTags.RECOVER_NODE) {
            handleNodeRecovery((String) ev.getData());
        } else {
            super.processEvent(ev);
        }
    }

    //Step 2 — task arrives: just buffer it, no scheduling decision yet
    private void handleTaskArrival(SimEvent ev) {
        pendingArrivals--;
        EdgeCloudlet task = (EdgeCloudlet) ev.getData();
        pendingQueue.add(task);

        TaskExecutionRecord record = trace.getOrCreateRecord(task.getCloudletId());
        record.sourceDevice = task.getSourceDeviceId();
        record.originNode = task.getOriginNode() != null ? task.getOriginNode().getNodeId() : null;
        record.arrivalTime = task.getArrivalTime();
        record.deadline = task.getDeadline();
        record.mi = task.getCloudletLength();
        record.inputSizeKB = task.getInputSizeKB();
        record.outputSizeKB = task.getOutputSizeKB();
        trace.recordEvent(CloudSim.clock(), TraceEventType.TASK_ARRIVED, task.getCloudletId(), null,
                record.originNode, "entered pending queue");
    }

    //Step 3 — periodic batch drain: process the whole pending queue in ascending
    //DPL-priority order (Algorithm 2 — tightest deadline-per-length first),
    //instead of committing to scheduling decisions in raw arrival order.
    private void handleSchedulingTick() {
        double currentTime = CloudSim.clock();

        // Algorithm 6 grace period — age out any released-idle VM that's sat
        // warm past the threshold, demoting it back to the cold pool. This
        // periodic tick (already firing every schedulingInterval) is a
        // natural, sufficient cadence — no new event type needed.
        dfarm.ageOutIdleVms(currentTime);

        if (!pendingQueue.isEmpty()) {
            List<EdgeCloudlet> batch = new ArrayList<>(pendingQueue);
            pendingQueue.clear();
            batch.sort(Comparator.comparingDouble(t -> dfarm.computeSchedulingPriority(t, currentTime)));

            int batchNumber = trace.nextBatchNumber();
            Log.printlnConcat(currentTime, ": ", getName(),
                    ": Scheduling tick #", batchNumber, " — draining ", batch.size(), " pending task(s) in DPL-priority order.");

            int rank = 0;
            for (EdgeCloudlet task : batch) {
                rank++;
                TaskExecutionRecord record = trace.getOrCreateRecord(task.getCloudletId());
                record.batchNumber = batchNumber;
                record.batchTimestamp = currentTime;
                record.priorityRank = rank;
                trace.recordEvent(currentTime, TraceEventType.SCHEDULING_TICK, task.getCloudletId(), null, null,
                        "batch #" + batchNumber + ", priority rank " + rank + "/" + batch.size());

                processTask(task, currentTime);
            }
        }

        if (pendingArrivals > 0 || !pendingQueue.isEmpty()) {
            send(getId(), schedulingInterval, DFARMBrokerTags.SCHEDULING_TICK, null);
        } else {
            checkShutdown();
        }
    }

    //Run one task through DFARM and submit whatever it decided (original ± replica).
    private void processTask(EdgeCloudlet task, double currentTime) {
        List<EdgeCloudlet> scheduled = dfarm.scheduleOneTask(task, currentTime);

        if (scheduled.isEmpty()) {
            // Hard-line rejection
            Log.printlnConcat(currentTime, ": ", getName(),
                    ": REJECTED EdgeCloudlet #", task.getCloudletId(),
                    " [deadline=", task.getDeadline(), "s]");
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

            enqueueForSubmission(cloudlet, vm, datacenterId);
        }
    }

    //Step 3/F — a real scheduling decision was just made for `cloudlet` on
    //`vm`. It's already sitting in DFARM's own per-VM holding queue
    //(DFARMScheduler.commit() added it to dfarm.getVmQueueMap() before this
    //method is ever called) — this only decides WHEN it actually gets sent
    //to CloudSim: immediately, if the VM has nothing else executing right
    //now, or later (from processCloudletReturn(), once the VM's current
    //occupant finishes) if it doesn't. Nothing about the DECISION of which
    //task runs next is made ahead of time anymore — that's what makes this
    //queue genuinely reorderable later (Phase 2), unlike the old scheme
    //where every task's real CLOUDLET_SUBMIT was sent (with a pre-computed
    //delay) the instant it was scheduled, regardless of the VM's real state.
    private void enqueueForSubmission(EdgeCloudlet cloudlet, EdgeVm vm, int datacenterId) {
        // uplink is device→ORIGIN node; use origin node's wireless bandwidth, not VM's node
        EdgeNode origin = cloudlet.getOriginNode();
        double uplinkDelay = (origin != null)
                ? origin.computeUplinkDelay(cloudlet.getInputSizeKB(), cloudlet.getDeviceLatencyMs())
                : vm.computeUplinkDelay(cloudlet.getInputSizeKB(), cloudlet.getDeviceLatencyMs());

        boolean isCrossNode    = dfarm.getCrossNodeCloudletIds().contains(cloudlet.getCloudletId());
        boolean isCloud        = dfarm.getCloudCloudletIds().contains(cloudlet.getCloudletId());
        double  extraNetworkDelay = 0.0;
        if (isCrossNode) {
            extraNetworkDelay = vm.computeInterNodeDelay(cloudlet.getInputSizeKB());
        } else if (isCloud && origin != null) {
            extraNetworkDelay = origin.computeCloudUplinkDelay(cloudlet.getInputSizeKB());
        }

        // "First on this VM" means the first REAL submission ever sent to it —
        // not DFARM's internal commit/queue position. Standby replicas occupy a
        // queue slot without ever being submitted (see warm-standby fault
        // tolerance in DFARMScheduler), which would corrupt a queue-position
        // based check; Set.add()'s return value gives an atomic, order-proof
        // "is this truly the first time" test instead. Evaluated here (enqueue
        // time), not at actual dispatch time, since dispatch order always
        // matches enqueue order in this phase (no reordering yet) — the first
        // task ever enqueued for a VM is always the first one ever dispatched.
        boolean isFirstOnVm = vmEverSubmitted.add(vm.getId());
        boolean isReused    = dfarm.getReusedVmIds().contains(vm.getId());
        double  startupCost = isFirstOnVm
                ? (isReused ? vm.getBootTime() : vm.getBootTime() + vm.getAcquisitionDelay())
                : 0.0;
        double naturalDelay = uplinkDelay + extraNetworkDelay;

        // This task's own network transfer (+ boot cost if first) conceptually
        // starts now, independently of whether the VM is free to run it yet —
        // exactly like a real device doesn't wait for the destination VM
        // before starting to upload. dispatchNow() uses this to compute the
        // actual submissionDelay whenever dispatch actually happens (now or
        // later), so a task that waited a while in the holding queue pays
        // little-to-no additional delay if its own transfer already would have
        // finished by the time the VM frees up — reproducing the same real
        // arrival instant as the old per-VM "next free" floor did.
        taskReadyAt.put(cloudlet.getCloudletId(), CloudSim.clock() + naturalDelay + startupCost);

        TaskExecutionRecord record = trace.getOrCreateRecord(cloudlet.getCloudletId());
        record.uplinkDelay = uplinkDelay;
        if (isCrossNode || isCloud) record.interNodeDelay = extraNetworkDelay;
        record.acquisitionDelay = isFirstOnVm && !isReused ? vm.getAcquisitionDelay() : 0.0;
        record.bootDelay = isFirstOnVm ? vm.getBootTime() : 0.0;

        if (!isVmBusy(vm)) {
            dispatchNow(cloudlet, vm, datacenterId);
        }
        // else: leave it in dfarm.getVmQueueMap() — dispatchNextQueued() will
        // send it once the VM's current occupant's CLOUDLET_RETURN fires.
    }

    //Is `vm` currently executing something for real right now? True iff some
    //task assigned to it (per DFARM's own holding queue, dfarm.getVmQueueMap())
    //has already been dispatched to CloudSim (submittedCloudletIds) but hasn't
    //finished CPU execution yet (cpuCompletedIds) — i.e. still actually
    //occupying its one PE. A task whose CPU is done but whose downlink
    //delivery is still pending does NOT count as busy — the VM's own compute
    //resource is free the instant CLOUDLET_RETURN fires, well before that
    //task's result reaches the device (see processCloudletReturn()). Reserved-
    //but-inactive standby replicas never satisfy this (they're never
    //dispatched at all unless activated via failover), so they correctly
    //never block a real task from being recognized as dispatchable.
    private boolean isVmBusy(EdgeVm vm) {
        LinkedList<EdgeCloudlet> queue = dfarm.getVmQueueMap().get(vm);
        if (queue == null) return false;
        for (EdgeCloudlet t : queue) {
            if (submittedCloudletIds.contains(t.getCloudletId()) && !cpuCompletedIds.contains(t.getCloudletId())) {
                return true;
            }
        }
        return false;
    }

    //Actually sends `cloudlet`'s CLOUDLET_SUBMIT to CloudSim now, using its
    //precomputed readyAt (see enqueueForSubmission()) to derive submissionDelay
    //relative to the CURRENT clock.
    private void dispatchNow(EdgeCloudlet cloudlet, EdgeVm vm, int datacenterId) {
        double readyAt = taskReadyAt.getOrDefault(cloudlet.getCloudletId(), CloudSim.clock());
        double submissionDelay = Math.max(0.0, readyAt - CloudSim.clock());

        boolean isCrossNode = dfarm.getCrossNodeCloudletIds().contains(cloudlet.getCloudletId());
        boolean isCloud     = dfarm.getCloudCloudletIds().contains(cloudlet.getCloudletId());

        cloudlet.setGuestId(vm.getId());
        send(datacenterId, submissionDelay, CloudActionTags.CLOUDLET_SUBMIT, cloudlet);
        cloudletsSubmitted++;
        getCloudletSubmittedList().add(cloudlet);
        submittedCloudletIds.add(cloudlet.getCloudletId());

        trace.recordEvent(CloudSim.clock(), TraceEventType.TASK_SUBMITTED, cloudlet.getCloudletId(),
                vm.getId(), vm.getNodeId(), "delay=" + String.format("%.3f", submissionDelay) + "s"
                        + (isCrossNode ? " [CROSS-NODE]" : "") + (isCloud ? " [CLOUD]" : ""));

        Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                ": Submitted #", cloudlet.getCloudletId(),
                (cloudlet.isDuplicate() ? " [REPLICA]" : ""),
                " → VM #", vm.getId(), " (Node ", vm.getNodeId(), ")",
                " | delay=", String.format("%.3f", submissionDelay), "s",
                isCrossNode ? " [CROSS-NODE]" : "",
                isCloud ? " [CLOUD]" : "");
    }

    //A VM's occupant just finished (processCloudletReturn) — if something else
    //is waiting in its holding queue (assigned via DFARMScheduler.commit() but
    //not yet actually sent to CloudSim), dispatch the FIFO-next one now.
    //Standby replicas sitting in the same queue (reserved, never normally
    //dispatched) are skipped — they only ever get sent via failOver()'s own
    //explicit activation path.
    private void dispatchNextQueued(EdgeVm vm) {
        LinkedList<EdgeCloudlet> queue = dfarm.getVmQueueMap().get(vm);
        if (queue == null) return;
        for (EdgeCloudlet next : queue) {
            if (dfarm.isStandbyReplica(next)) continue;
            if (submittedCloudletIds.contains(next.getCloudletId())) continue;
            Integer datacenterId = getVmsToDatacentersMap().get(vm.getId());
            if (datacenterId != null) {
                dispatchNow(next, vm, datacenterId);
            }
            return; // one at a time, FIFO
        }
    }

    //Step 4a — CPU done: schedule DOWNLINK_COMPLETE to model result delivery delay
    @Override
    protected void processCloudletReturn(SimEvent ev) {
        Cloudlet cloudlet = (Cloudlet) ev.getData();
        getCloudletReceivedList().add(cloudlet);

        if (cloudlet instanceof EdgeCloudlet ec) {
            cpuCompletedIds.add(ec.getCloudletId());
            EdgeVm vm = dfarm.getTaskVmMap().get(ec);
            double downlinkDelay = (vm != null) ? vm.computeDownlinkDelay(ec.getOutputSizeKB(), ec.getDeviceLatencyMs()) : 0.0;

            TaskExecutionRecord record = trace.getRecord(ec.getCloudletId());
            if (record != null) {
                record.actualStartTime = ec.getExecStartTime();
                record.actualFinishTime = ec.getExecFinishTime(); // provisional — overwritten with post-downlink time on delivery
                record.cpuTime = ec.getActualCPUTime();
                record.executionDelay = ec.getExecFinishTime() - ec.getExecStartTime();
            }
            trace.recordEvent(ec.getExecStartTime(), TraceEventType.EXECUTION_STARTED, ec.getCloudletId(),
                    vm != null ? vm.getId() : null, vm != null ? vm.getNodeId() : null, "CPU execution began");
            trace.recordEvent(CloudSim.clock(), TraceEventType.EXECUTION_FINISHED, ec.getCloudletId(),
                    vm != null ? vm.getId() : null, vm != null ? vm.getNodeId() : null,
                    "CPU done, downlink=" + String.format("%.3f", downlinkDelay) + "s");

            Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                    ": Cloudlet #", ec.getCloudletId(), " CPU done.",
                    " Downlink=", String.format("%.3f", downlinkDelay), "s");

            send(getId(), downlinkDelay, DFARMBrokerTags.DOWNLINK_COMPLETE, ec);

            // This VM's PE is free again right now (compute done), independent
            // of the downlink delivery still pending above — dispatch whatever
            // else is waiting in its holding queue, if anything.
            if (vm != null) {
                dispatchNextQueued(vm);
            }
        } else {
            cloudletsSubmitted--;
            checkShutdown();
        }
    }

    //Step 4b — downlink done: task complete from device perspective
    private void handleDownlinkComplete(SimEvent ev) {
        EdgeCloudlet cloudlet = (EdgeCloudlet) ev.getData();

        dfarm.notifyTaskCompleted(cloudlet);
        // This task finished cleanly — if it had a standby replica riding
        // shotgun, that replica is no longer needed. No-op if `cloudlet` never
        // had one (or is itself a replica).
        dfarm.discardStandbyReplica(cloudlet);
        logActualVsPredicted(cloudlet);
        actualDeliveryTime.put(cloudlet, CloudSim.clock());
        submittedCloudletIds.remove(cloudlet.getCloudletId());
        cpuCompletedIds.remove(cloudlet.getCloudletId());

        TaskExecutionRecord record = trace.getRecord(cloudlet.getCloudletId());
        if (record != null) {
            record.actualFinishTime = CloudSim.clock();
            record.finishedSuccessfully = true;
            record.deadlineMet = record.actualFinishTime <= record.deadline;
            record.deadlineMissAmount = Math.max(0.0, record.actualFinishTime - record.deadline);
            // responseTime: arrival -> scheduling decision made; waitingTime: decision -> execution start
            if (record.batchTimestamp != null) {
                record.responseTime = record.batchTimestamp - record.arrivalTime;
                if (record.actualStartTime != null) {
                    record.waitingTime = record.actualStartTime - record.batchTimestamp;
                }
            }
            record.turnaroundTime = record.actualFinishTime - record.arrivalTime;
            double network = 0.0;
            if (record.uplinkDelay != null) network += record.uplinkDelay;
            if (record.downlinkDelay != null) network += record.downlinkDelay;
            if (record.interNodeDelay != null) network += record.interNodeDelay;
            record.networkDelay = network;
            if (record.cpuTime != null) record.totalDelay = record.turnaroundTime - record.cpuTime;
        }
        trace.recordEvent(CloudSim.clock(), TraceEventType.DOWNLINK_COMPLETE, cloudlet.getCloudletId(),
                null, null, "delivered to device");

        cloudletsSubmitted--;

        Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                ": Cloudlet #", cloudlet.getCloudletId(), " delivered to device.",
                " Submitted=", cloudletsSubmitted, " PendingArrivals=", pendingArrivals);

        checkShutdown();
    }

    // ── fault injection ───────────────────────────────────────────────────────

    //Register a node failure to fire at event.getTime(). Call any time before
    //CloudSim.startSimulation() (e.g. right after constructing this
    //controller). The actual send() happens later from submitCloudlets(),
    //once the simulation is actually running — CloudSim.SimEntity.schedule()
    //silently drops events sent while CloudSim.running() is false, so sending
    //straight from this method (before startSimulation()) would never fire.
    public void scheduleFailure(NodeFailureEvent event) {
        plannedFailures.add(event);
    }

    //Step F1 — a node just went down: mark it unhealthy (scheduler stops
    //routing new work there), cancel/redirect whatever it was holding, and
    //arm a recovery event if this is a transient failure.
    private void handleNodeFailure(NodeFailureEvent event) {
        pendingFailureEvents--; // this FAIL_NODE event just fired — no longer outstanding
        EdgeNode node = nodesById.get(event.getNodeId());
        if (node == null) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                    ": WARNING — failure event for unknown node ", event.getNodeId(), " ignored.");
            return;
        }

        double detectionTime = CloudSim.clock();     // when the controller actually reacts
        double failureTime   = event.getTime();      // when the node actually went down
        node.setHealthy(false);
        nodeFailureLog.add(event);
        downIntervals.computeIfAbsent(event.getNodeId(), k -> new ArrayList<>())
                .add(new double[]{ failureTime, -1 });
        trace.recordEvent(failureTime, TraceEventType.NODE_FAILURE, null, null, event.getNodeId(),
                event.getFailureType() + ", detected at t=" + detectionTime + "s");

        Log.printlnConcat("\n", detectionTime, ": ", getName(),
                ": *** NODE FAILURE DETECTED *** Node ", event.getNodeId(), " is DOWN (", event.getFailureType(), ")",
                event.getDetectionDelay() > 0 ? (" [failed at t=" + failureTime + "s, detected " + event.getDetectionDelay() + "s later]") : "");

        List<EdgeCloudlet> affected = dfarm.getInProgressTasksOnNode(event.getNodeId());
        Log.printlnConcat(detectionTime, ": ", getName(),
                ": ", affected.size(), " task(s) held a VM slot on Node ", event.getNodeId(), " at detection time.");

        for (EdgeCloudlet task : affected) {
            TaskExecutionRecord affectedRecord = trace.getRecord(task.getCloudletId());
            if (affectedRecord != null) {
                affectedRecord.originalNodeFailed = true;
                affectedRecord.failureTime = failureTime;
                affectedRecord.detectionDelay = event.getDetectionDelay();
                if (event.getFailureType() == NodeFailureEvent.FailureType.TRANSIENT) {
                    affectedRecord.recoveryTime = event.getRecoveryTime();
                }
            }

            if (dfarm.isStandbyReplica(task)) {
                // its own node died while it was still parked on standby, never activated
                dfarm.loseStandbyReplica(task);
                Log.printlnConcat(detectionTime, ": ", getName(),
                        ": standby replica #", task.getCloudletId(), " LOST — its own node (",
                        event.getNodeId(), ") failed before it was ever needed.");
                continue;
            }

            if (cpuCompletedIds.contains(task.getCloudletId())) {
                // Its compute already finished before the node died — only the
                // (already-scheduled) result delivery remains. There's nothing
                // to fail over or resubmit; forcing one here would burn a
                // replica for no reason and let the original's own pending
                // DOWNLINK_COMPLETE double-count the task's delivery. Let it
                // complete normally — handleDownlinkComplete() will discard
                // its standby replica (if any) the ordinary way.
                Log.printlnConcat(detectionTime, ": ", getName(),
                        ": task #", task.getCloudletId(),
                        " had already finished computing before the node failed — letting its pending delivery complete normally.");
                continue;
            }

            // Cancel the real in-flight execution. It's in one of two places:
            // (a) already delivered to the datacenter and sitting in the VM's
            //     CloudletScheduler (exec/waiting/paused) — cloudletCancel()
            //     handles this, or
            // (b) still in transit — the CLOUDLET_SUBMIT event we sent via
            //     submitWithDelay() hasn't fired yet, so the datacenter has
            //     never heard of it and cloudletCancel() would silently find
            //     nothing while the stray event goes on to execute normally
            //     on a node we've just declared dead (and, worse, its later
            //     completion would decrement cloudletsSubmitted a second
            //     time). CloudSim.cancel() pulls the pending event straight
            //     out of the future-event queue before that can happen.
            if (submittedCloudletIds.remove(task.getCloudletId())) {
                final int cloudletId = task.getCloudletId();
                SimEvent pending = CloudSim.cancel(getId(), new Predicate() {
                    @Override
                    public boolean match(SimEvent event) {
                        return event.getTag() == CloudActionTags.CLOUDLET_SUBMIT
                                && event.getData() instanceof Cloudlet c
                                && c.getCloudletId() == cloudletId;
                    }
                });
                if (pending == null) {
                    EdgeVm vm = dfarm.getTaskVmMap().get(task);
                    if (vm != null) {
                        vm.getCloudletScheduler().cloudletCancel(cloudletId);
                    }
                }
                cloudletsSubmitted--;
            }
            // Stop tracking it on the dead VM regardless of what happens next —
            // resubmitTask() also does this internally, but the failover path
            // doesn't reschedule the original at all, so without this it would
            // stay "in progress" on the failed node forever.
            dfarm.abandonTask(task);

            EdgeCloudlet replica = dfarm.peekStandbyReplica(task);
            if (replica != null) {
                failOver(task, replica, failureTime, detectionTime);
            } else {
                resubmit(task, detectionTime);
            }
        }

        if (event.getFailureType() == NodeFailureEvent.FailureType.TRANSIENT) {
            // recoveryTime is measured from the true failure instant, not from
            // detection — subtract however much of it has already elapsed.
            double remaining = Math.max(0.0, (failureTime + event.getRecoveryTime()) - detectionTime);
            send(getId(), remaining, DFARMBrokerTags.RECOVER_NODE, event.getNodeId());
            pendingFailureEvents++;
        }

        checkShutdown();
    }

    //Failover: activate an original's standby replica and submit it for real now.
    private void failOver(EdgeCloudlet original, EdgeCloudlet replica, double failureTime, double currentTime) {
        EdgeCloudlet activated = dfarm.activateStandbyReplica(original, currentTime);
        if (activated == null) return; // shouldn't happen — we just peeked it above

        EdgeVm replicaVm = dfarm.getTaskVmMap().get(activated);
        Integer datacenterId = (replicaVm != null) ? getVmsToDatacentersMap().get(replicaVm.getId()) : null;

        TaskExecutionRecord originalRecord = trace.getRecord(original.getCloudletId());

        if (replicaVm == null || datacenterId == null) {
            tasksLost.add(original);
            if (originalRecord != null) originalRecord.lost = true;
            trace.recordEvent(currentTime, TraceEventType.TASK_LOST, original.getCloudletId(), null, null,
                    "replica had no usable VM/datacenter mapping");
            Log.printlnConcat(currentTime, ": ", getName(),
                    ": WARNING — replica #", activated.getCloudletId(), " has no usable VM/datacenter mapping. Task #",
                    original.getCloudletId(), " LOST.");
            return;
        }

        enqueueForSubmission(activated, replicaVm, datacenterId);
        failoverCount++;
        // True end-to-end recovery latency: from the moment the node actually
        // failed to the moment its replacement work is actually submitted —
        // this now reflects detectionDelay + reaction time, not just ~0.
        recoveryLatencies.add(CloudSim.clock() - failureTime);
        replicaActivationLatencies.add(currentTime - original.getArrivalTime());

        if (originalRecord != null) {
            originalRecord.failoverUsed = true;
            originalRecord.failoverNode = replicaVm.getNodeId();
            originalRecord.failoverVmId = replicaVm.getId();
        }
        trace.recordEvent(currentTime, TraceEventType.FAILOVER, original.getCloudletId(),
                replicaVm.getId(), replicaVm.getNodeId(), "handed off to replica #" + activated.getCloudletId());

        Log.printlnConcat(currentTime, ": ", getName(),
                ": FAILOVER — replica #", activated.getCloudletId(), " activated for original #",
                original.getCloudletId(), " -> VM#", replicaVm.getId(), " (Node ", replicaVm.getNodeId(), ")");
    }

    //Reactive resubmission: `task` had no replica — give it one fresh scheduling
    //attempt against the surviving, healthy topology (paper's "hybrid" fault
    //tolerance, the other half of replication).
    private void resubmit(EdgeCloudlet task, double currentTime) {
        EdgeVm newVm = dfarm.resubmitTask(task, currentTime);
        if (newVm == null) {
            tasksLost.add(task);
            TaskExecutionRecord record = trace.getRecord(task.getCloudletId());
            if (record != null) record.lost = true;
            trace.recordEvent(currentTime, TraceEventType.TASK_LOST, task.getCloudletId(), null, null,
                    "no replica existed, resubmission found no healthy VM");
            Log.printlnConcat(currentTime, ": ", getName(),
                    ": task #", task.getCloudletId(),
                    " LOST — node failed, no replica existed, and resubmission found no healthy VM in time.");
            return;
        }

        Integer datacenterId = getVmsToDatacentersMap().get(newVm.getId());
        if (datacenterId == null) {
            tasksLost.add(task);
            TaskExecutionRecord record = trace.getRecord(task.getCloudletId());
            if (record != null) record.lost = true;
            trace.recordEvent(currentTime, TraceEventType.TASK_LOST, task.getCloudletId(), null, null,
                    "resubmitted VM has no datacenter mapping");
            Log.printlnConcat(currentTime, ": ", getName(),
                    ": WARNING — resubmitted VM#", newVm.getId(), " has no datacenter mapping. Task #",
                    task.getCloudletId(), " LOST.");
            return;
        }

        enqueueForSubmission(task, newVm, datacenterId);
        resubmissionCount++;
        Log.printlnConcat(currentTime, ": ", getName(),
                ": RESUBMITTED #", task.getCloudletId(), " -> VM#", newVm.getId(), " (Node ", newVm.getNodeId(), ")");
    }

    //Step F2 — a transient failure's recovery timer fired: node is healthy again.
    private void handleNodeRecovery(String nodeId) {
        pendingFailureEvents--; // this RECOVER_NODE event just fired — no longer outstanding
        EdgeNode node = nodesById.get(nodeId);
        if (node == null) {
            checkShutdown();
            return;
        }

        node.setHealthy(true);
        trace.recordEvent(CloudSim.clock(), TraceEventType.NODE_RECOVERY, null, null, nodeId, "healthy again");
        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Node ", nodeId, " RECOVERED — healthy again.\n");

        List<double[]> intervals = downIntervals.get(nodeId);
        if (intervals != null && !intervals.isEmpty()) {
            intervals.get(intervals.size() - 1)[1] = CloudSim.clock();
        }

        // This may be the last thing checkShutdown() was waiting on (all real
        // work already finished while this recovery was still pending).
        checkShutdown();
    }

    // ── failure/failover metrics ────────────────────────────────────────────

    public int                  getFailoverCount()      { return failoverCount; }
    public int                  getResubmissionCount()  { return resubmissionCount; }
    public List<EdgeCloudlet>   getTasksLost()          { return tasksLost; }
    public List<NodeFailureEvent> getNodeFailureLog()   { return nodeFailureLog; }

    //fraction of simulated time each ever-failed node spent healthy, averaged
    //across those nodes (nodes that never failed are 100% available and add
    //nothing new to report, so they're excluded from this average)
    public double computeAvailability() {
        if (downIntervals.isEmpty()) return 1.0;
        double totalSim = Math.max(computeActualMakespan(), 1e-9);
        double sumAvail = 0.0;
        int n = 0;
        for (List<double[]> intervals : downIntervals.values()) {
            double down = 0.0;
            for (double[] interval : intervals) {
                double end = interval[1] < 0 ? totalSim : interval[1];
                down += Math.max(0.0, end - interval[0]);
            }
            sumAvail += Math.max(0.0, 1.0 - down / totalSim);
            n++;
        }
        return n > 0 ? sumAvail / n : 1.0;
    }

    //Mean Time To Recovery — averaged over TRANSIENT failures that actually
    //recovered during the run. NaN if none occurred (permanent-only runs).
    public double computeMTTR() {
        List<Double> repairTimes = new ArrayList<>();
        for (List<double[]> intervals : downIntervals.values()) {
            for (double[] interval : intervals) {
                if (interval[1] >= 0) repairTimes.add(interval[1] - interval[0]);
            }
        }
        return repairTimes.isEmpty() ? Double.NaN
                : repairTimes.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    public double computeAvgRecoveryLatency() {
        return recoveryLatencies.isEmpty() ? 0.0
                : recoveryLatencies.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    public double computeAvgReplicaActivationTime() {
        return replicaActivationLatencies.isEmpty() ? 0.0
                : replicaActivationLatencies.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    //shut down only when all arrivals processed, the pending queue is drained,
    //all submissions have returned, AND no armed FAIL_NODE/RECOVER_NODE
    //self-event is still outstanding — a planned failure's fire time is
    //independent of task completion, so with fast/non-quantized completion
    //detection the whole workload can legitimately finish before some armed
    //failure/recovery does. Shutting down anyway would leave that event
    //targeting an already-FINISHED (incomingEvents==null) entity — CloudSim
    //throws an NPE trying to dispatch it rather than silently dropping it.
    private void checkShutdown() {
        if (cloudletsSubmitted == 0 && pendingArrivals == 0 && pendingQueue.isEmpty() && pendingFailureEvents == 0) {
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

    // ── metrics — derived from real CloudSim.clock() delivery timestamps.
    // This is the single, authoritative result set: DFARM's own scheduler
    // keeps a separate ANALYTICAL vmCtMap purely to make live scheduling
    // decisions (feasibility checks, replica placement) — that bookkeeping
    // is intentionally not reported here, since it can diverge materially
    // from what actually happened once real CloudSim queueing/contention is
    // in play. Everything below reflects what was actually simulated. ──

    public double computeActualMakespan() {
        return actualDeliveryTime.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    }

    public double computeActualThroughput() {
        int rejected = (int) dfarm.getRejectedTasks().stream().filter(t -> !t.isDuplicate()).count();
        int accepted = totalOriginalTasks - rejected;
        double makespan = computeActualMakespan();
        return makespan > 0 ? (double) accepted / makespan : 0.0;
    }

    //each VM's actual CT = the latest real delivery timestamp among the
    //tasks actually assigned to it (0 if unused/never touched)
    public Map<EdgeVm, Double> computeActualVmCtMap() {
        Map<EdgeVm, Double> vmActualCt = new HashMap<>();
        for (Map.Entry<EdgeCloudlet, Double> e : actualDeliveryTime.entrySet()) {
            EdgeVm vm = dfarm.getTaskVmMap().get(e.getKey());
            if (vm == null) continue;
            vmActualCt.merge(vm, e.getValue(), Math::max);
        }
        return vmActualCt;
    }

    //ARUR over actual completion times, averaged over the FULL VM pool
    //exactly like the paper's Eq. 11 (VMs never used contribute 0).
    public double computeActualARUR() {
        double makespan = computeActualMakespan();
        if (makespan == 0 || allVms.isEmpty()) return 0.0;

        Map<EdgeVm, Double> vmActualCt = computeActualVmCtMap();
        double sum = 0.0;
        for (EdgeVm vm : allVms) sum += vmActualCt.getOrDefault(vm, 0.0);
        return (sum / allVms.size()) / makespan;
    }

    public Map<EdgeCloudlet, Double> getActualDeliveryTime() { return actualDeliveryTime; }
    public List<EdgeVm> getAllVms() { return allVms; }

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
        Log.printlnConcat("  DFARM iFogSim Edge Simulation Results");
        Log.printlnConcat("========================================");
        Log.printlnConcat("  Total tasks (original) : ", totalOriginalTasks);
        Log.printlnConcat("  Accepted               : ", accepted);
        Log.printlnConcat("  Rejected               : ", rejected);
        Log.printlnConcat("  Structural replica fails: ", dfarm.getStructuralReplicaFailures().size());
        Log.printlnConcat("  TRR (Task Rejection)   : ",
                String.format("%.4f", dfarm.computeTRR(totalOriginalTasks)));
        Log.printlnConcat("  Makespan               : ",
                String.format("%.2f", computeActualMakespan()), " s");
        Log.printlnConcat("  Throughput             : ",
                String.format("%.4f", computeActualThroughput()), " tasks/s");
        Log.printlnConcat("  ARUR                   : ",
                String.format("%.4f", computeActualARUR()));
        Log.printlnConcat("========================================");
        Log.printlnConcat("  Fault tolerance / failure injection:");
        Log.printlnConcat("  Node failures injected  : ", nodeFailureLog.size());
        Log.printlnConcat("  Successful failovers    : ", failoverCount);
        Log.printlnConcat("  Reactive resubmissions  : ", resubmissionCount);
        Log.printlnConcat("  Tasks lost              : ", tasksLost.size());
        Log.printlnConcat("  Standby replicas created: ",
                dfarm.getActivatedReplicas().size() + dfarm.getDiscardedReplicas().size() + dfarm.getLostStandbyReplicas().size(),
                " (activated=", dfarm.getActivatedReplicas().size(),
                ", discarded=", dfarm.getDiscardedReplicas().size(),
                ", lost-on-standby=", dfarm.getLostStandbyReplicas().size(), ")");
        Log.printlnConcat("  Replica utilization     : ", String.format("%.2f%%", dfarm.computeReplicaUtilization() * 100));
        Log.printlnConcat("  Extra cost of replication: ", String.format("%.2f", dfarm.computeTotalReplicaReservedCost()), " VM-seconds reserved");
        Log.printlnConcat("  Avg recovery latency    : ", String.format("%.4f", computeAvgRecoveryLatency()), " s");
        Log.printlnConcat("  Avg replica activation  : ", String.format("%.2f", computeAvgReplicaActivationTime()), " s (standby idle time before use)");
        Log.printlnConcat("  Availability (failed nodes): ", String.format("%.4f%%", computeAvailability() * 100));
        double mttr = computeMTTR();
        Log.printlnConcat("  MTTR                    : ", Double.isNaN(mttr) ? "N/A (no transient recoveries)" : String.format("%.2f s", mttr));
        Log.printlnConcat("========================================\n");

        Log.printlnConcat("  VM CT breakdown by Node:");
        for (Map.Entry<EdgeVm, Double> entry : computeActualVmCtMap().entrySet()) {
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
