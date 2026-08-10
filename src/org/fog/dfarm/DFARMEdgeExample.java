package org.fog.dfarm;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.fog.dfarm.trace.ExecutionTraceManager;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;

/**
 * DFARMEdgeExample — this is the runnable, end-to-end demo of DFARM working
 * on a small 3-node edge topology, built on real iFogSim FogDevices (not a
 * simplified stand-in).
 *
 * The topology mirrors the original CloudSim reference (EdgeDFARMExample),
 * so results stay comparable:
 *   Node A: 4 VMs, up to 1000 MIPS, boots in 5s,  takes 10s to acquire, 50 Mbps wireless
 *   Node B: 6 VMs, up to 2000 MIPS, boots in 3s,  takes  8s to acquire, 35 Mbps wireless
 *   Node C: 4 VMs, up to  750 MIPS, boots in 8s,  takes 15s to acquire, 10 Mbps wireless
 * There's deliberately no cloud tier here. DFARMScheduler does have a 4th
 * "fall back to the cloud" acquisition source, and DFARMController/EdgeNode
 * both know how to talk to one, but we just never give them one to talk to
 * (cloudNode stays null everywhere below) — a cloud-augmented scenario is a
 * different project.
 *
 * Each node is built as a DFARMFogDevice with one PowerHost, and that host
 * gets one Pe (processing element) per VM slot — this matches how the
 * reference implementation modelled a node as its own little datacenter, so
 * the completion-time numbers line up. See DFARMFogDevice's own comments for
 * why it's safe to build it on top of iFogSim's FogDevice class at all.
 *
 * DFARM is configured the same way the reference was:
 *   replRatio               = 0.15  (replicate the tightest 15% of deadlines)
 *   allowTaskToRun          = false (hard-line: a task that misses its
 *                             deadline check gets dropped, not forced through)
 *   deepSearch              = true  (Algorithm 7's queue-adjustment is turned
 *                             on — just the simple variant. See DFARMScheduler's
 *                             class comment and QueueAdjustment for how the
 *                             walk/formula actually works.)
 *   idleReleaseThresholdSec = 15.0  (Algorithm 6's grace period — how long an
 *                             idle VM is allowed to sit around before it gets
 *                             released back to the cold pool)
 *
 * One thing added on top of the reference: replica placement now enforces
 * node diversity — a replica is never allowed to land on the same physical
 * node as the task it's backing up. Otherwise the whole point of having a
 * backup would be defeated. See DFARMScheduler.doScheduleReplica for how.
 */
public class DFARMEdgeExample {

    // Just converting wireless bandwidth from Mbps to KBps (Mbps * 1024 / 8)
    // so every delay calculation elsewhere in the codebase can stay in KB/KBps.
    private static final double BW_NODE_A_KBps    = 50.0  * 1024 / 8;  // 6400  KBps
    private static final double BW_NODE_B_KBps    = 35.0  * 1024 / 8;  // 4480  KBps
    private static final double BW_NODE_C_KBps    = 10.0  * 1024 / 8;  // 1280  KBps
    private static final double BW_INTERNODE_KBps = 500.0 * 1024 / 8;  // 64000 KBps (fibre backhaul between nodes)
    // We don't actually have a cloud tier in this project. This constant only
    // exists because EdgeNode's constructor asks for a cloudBandwidthKBps
    // value regardless — since no cloud EdgeNode ever gets built, this number
    // is never actually used for anything.
    private static final double BW_CLOUD_KBps     = 200.0 * 1024 / 8;  // 25600 KBps

    private static int nextVmId = 0;

    // Fixed seed so the workload is reproducible run to run. This is the same
    // value that used to be called SEED_BASE for "trial 0" back when this ran
    // multiple seeded trials, kept as-is so results stay comparable to that
    // earlier trial-0 data.
    private static final long SIMULATION_SEED = 42;

    // Turns fault injection (the two scheduled node failures below) on or
    // off. Right now it's on, and the failure times are aimed on purpose:
    // Node C's transient failure at t=30s and Node B's permanent one at
    // t=40s were chosen so the second one lands squarely inside a real,
    // replicated task's execution window — see the big comment right above
    // configuredFailures for the full story on why. Flip this to false if you
    // just want to run the workload cleanly with no faults at all — useful,
    // for instance, if you change the workload size or seed and need to
    // re-find a good task/window to target (same comment explains how).
    private static final boolean INJECT_FAILURES = true;

    private static final double ARRIVAL_WINDOW_SEC = 60.0; // tasks arrive spread across this whole window, from t=0

    public static void main(String[] args) {
        Log.println("Starting DFARMEdgeExample...\n");

        try {
            runOneTrial(SIMULATION_SEED, true);
        } catch (Exception e) {
            e.printStackTrace();
            Log.println("error: " + e.getMessage());
        }

        Log.println("DFARMEdgeExample finished.");
    }

    //Runs one complete simulation from start to finish: fresh CloudSim state,
    //a fresh topology, a freshly-seeded workload, DFARM running on it plus
    //all four baseline schedulers running on the same workload for
    //comparison. Everything worth reporting (the DFARM summary, the baseline
    //comparison, VM-search-time stats) just gets printed to the console as we
    //go — there's no separate step that collects results afterward.
    private static void runOneTrial(long seed, boolean exportDetailed) throws Exception {
        nextVmId = 0; // start VM ids fresh each trial — they shouldn't carry over if this ever runs more than once per JVM

        // This is the normal way to run several CloudSim simulations back to
        // back in the same JVM: CloudSim.init() wipes all of CloudSim's own
        // internal state (its entities, its event queue, the clock — see
        // CloudSim.initialize()) so this trial starts from a genuinely clean
        // slate instead of inheriting anything from a previous run.
        CloudSim.init(1, Calendar.getInstance(), false);

        // The three edge nodes, as plain logical objects (no cloud tier here).
        EdgeNode nodeA = new EdgeNode("A", BW_NODE_A_KBps, BW_INTERNODE_KBps, BW_CLOUD_KBps);
        EdgeNode nodeB = new EdgeNode("B", BW_NODE_B_KBps, BW_INTERNODE_KBps, BW_CLOUD_KBps);
        EdgeNode nodeC = new EdgeNode("C", BW_NODE_C_KBps, BW_INTERNODE_KBps, BW_CLOUD_KBps);

        // Let each node offload work to either of the other two.
        nodeA.addNeighbor(nodeB); nodeA.addNeighbor(nodeC);
        nodeB.addNeighbor(nodeA); nodeB.addNeighbor(nodeC);
        nodeC.addNeighbor(nodeA); nodeC.addNeighbor(nodeB);

        List<EdgeNode> edgeNodes = List.of(nodeA, nodeB, nodeC);
        List<EdgeNode> allNodes  = List.of(nodeA, nodeB, nodeC);

        // Now the real iFogSim FogDevices, one per node.
        double[] mipsA = {100, 200, 300, 500};
        double[] mipsB = {500, 750, 900, 1000, 1100, 1200};
        double[] mipsC = {100, 250, 500, 750};

        DFARMFogDevice deviceA = createFogNode("FogNode_A", mipsA);
        DFARMFogDevice deviceB = createFogNode("FogNode_B", mipsB);
        DFARMFogDevice deviceC = createFogNode("FogNode_C", mipsC);

        // The DFARM controller — this plays the role a normal DatacenterBroker
        // would in plain CloudSim.
        //
        // idleReleaseThresholdSec is set to 15.0 (Algorithm 6's grace period
        // before an idle VM gets released). That's roughly double Node C's
        // own boot time (8.0s, the slowest of the three), which felt like a
        // reasonable middle ground: long enough that a VM sitting idle for a
        // moment between two nearby task arrivals doesn't get torn down for
        // no reason, but still short enough to actually matter within this
        // workload's runtime (if it were as long as the whole simulation,
        // idle VMs would just never age out, and the setting would be
        // pointless).
        //
        // deepSearch=true here means Algorithm 7's queue-adjustment logic is
        // switched on — just the simple variant, not the more aggressive one
        // (that one was never implemented). See DFARMScheduler's class
        // comment for the overview, the note on assignAcquire() for exactly
        // where it kicks in, and QueueAdjustment for the actual walk/formula
        // (which was checked in isolation against the paper's own worked
        // example before ever being wired in here). This only became
        // possible once DFARMController stopped firing off a task's
        // CLOUDLET_SUBMIT the moment it was scheduled, and instead started
        // holding it in a real per-VM queue first — see
        // DFARMController.enqueueForSubmission / isVmBusy / dispatchNextQueued.
        //
        // cloudNode is always null here, since (as mentioned up top) there's
        // no cloud tier in this project. DFARM's 4th acquisition source and
        // its cloud-offload path just never get a chance to run — both
        // DFARMScheduler and DFARMController already treat a null cloudNode
        // as "there is no cloud, don't try."
        DFARMController controller = new DFARMController(
            "DFARMController", edgeNodes, null, 0.15, false, true, 15.0);
        int controllerId = controller.getId();

        // Tell the controller which datacenter (DFARMFogDevice) actually
        // belongs to which EdgeNode. Without this, CloudSim's own VM-creation
        // logic can — and does — place a VM on the wrong node's host by
        // mistake. See DFARMController.createVmsInDatacenter() for the details.
        controller.setNodeDatacenterIds(Map.of(
                "A", deviceA.getId(), "B", deviceB.getId(), "C", deviceC.getId()));

        // Build the actual EdgeVms — each one gets registered both with its
        // EdgeNode (the logical side) and later with the controller (the real,
        // simulated side).
        List<EdgeVm> allVms = new ArrayList<>();
        allVms.addAll(createNodeVms(controllerId, nodeA, mipsA, 5.0, 10.0, BW_NODE_A_KBps, BW_INTERNODE_KBps));
        allVms.addAll(createNodeVms(controllerId, nodeB, mipsB, 3.0, 8.0, BW_NODE_B_KBps, BW_INTERNODE_KBps));
        allVms.addAll(createNodeVms(controllerId, nodeC, mipsC, 8.0, 15.0, BW_NODE_C_KBps, BW_INTERNODE_KBps));

        controller.submitGuestList(allVms);

        // Generate the workload. It's seeded, so this trial's workload is
        // reproducible even though it's randomly generated — see
        // createCloudlets(..., seed) below for exactly how.
        Map<String, EdgeNode> nodeMap = Map.of("A", nodeA, "B", nodeB, "C", nodeC);
        List<EdgeCloudlet> cloudlets = createCloudlets(controllerId, nodeMap, seed);
        List<EdgeCloudlet> originalCloudlets = List.copyOf(cloudlets);
        controller.submitCloudletList(cloudlets);

        // This is the fault-injection demo: two failures, both scheduled
        // through the same scheduleFailure() call, which shows NodeFailureEvent
        // (see its own javadoc) is a genuinely reusable mechanism and not
        // something special-cased for one scenario.
        //   - Node C goes down TRANSIENTLY at t=30s, we notice 1s later, and
        //     it comes back 20s after the real failure moment (so at t=50s).
        //     This exercises the health-flag recovery, VM reintegration, and
        //     MTTR tracking.
        //   - Node B goes down PERMANENTLY at t=40s, and we notice 2s later.
        //
        // Why t=40s specifically (it used to be t=80s): with replRatio=0.15
        // and SIMULATION_SEED=42, only the tightest ~15% of deadlines ever
        // get a replica, and it turns out every replica this workload creates
        // shows up early, between t=1s and t=27s. With the failures sitting
        // at t=30s and t=80s, neither one ever actually caught a task that
        // was mid-execution AND had a live replica backing it up — t=30s only
        // ever caught replicas that were still just sitting idle on standby,
        // and t=80s landed on a completely different batch of tasks that
        // weren't replicated at all. So failOver() never actually ran, even
        // though replicas existed the whole time ("Successful failovers"
        // stayed stuck at 0). I confirmed this by running once with
        // INJECT_FAILURES=false and reading the trace: original task #92 (it
        // does get replicated — see its REPLICATION_DECISION event — with
        // replica #-93 sitting as a warm standby on Node A / VM2) runs on
        // Node B / VM6 from t=38.989s to t=44.549s. t=40s (noticed at t=42s)
        // falls right inside that window, with room to spare on both sides —
        // so now it genuinely cancels a task that's actively running and has
        // a real replica ready to take over, instead of hitting an idle
        // standby or an unreplicated task. Node A, where the replica lives,
        // is never scheduled to fail, so the replica stays healthy and able
        // to receive the failover. I also considered task #244 (replica
        // #-245 on Node C) as a target, but ruled it out: that replica gets
        // created at t=27.03, which is before Node C's own failure at t=30 —
        // so Node C's failure would just discard it as an idle standby
        // (loseStandbyReplica) long before task #244 even starts running
        // (t=63-71s). Using #244 would've meant also moving or dropping the
        // Node C failure, which we didn't want to do.
        List<NodeFailureEvent> configuredFailures = List.of(
                new NodeFailureEvent("C", 30.0, NodeFailureEvent.FailureType.TRANSIENT, 20.0, 1.0),
                new NodeFailureEvent("B", 40.0, NodeFailureEvent.FailureType.PERMANENT, -1, 2.0)
        );
        List<NodeFailureEvent> failures = INJECT_FAILURES ? configuredFailures : List.of();
        for (NodeFailureEvent event : failures) {
            controller.scheduleFailure(event);
        }

        // Actually run the simulation now.
        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        printResults(controller);
        verifyReplicaNodeDiversity(controller);
        verifyFailoverBehavior(controller, failures);

        // Now run the four baseline schedulers on the exact same workload and
        // VM pool DFARM just used, so the comparison is apples-to-apples.
        double clock = CloudSim.clock();
        List<EdgeSchedulerBase> baselines = List.of(
            new RSScheduler(),
            new RRScheduler(),
            new MCTScheduler(),
            new RALBAScheduler()
        );

        Map<String, Double> baselineMakespan = new LinkedHashMap<>();
        Map<String, Double> baselineMissRate = new LinkedHashMap<>();
        for (EdgeSchedulerBase sched : baselines) {
            sched.schedule(originalCloudlets, allVms, clock);
            double makespan = sched.computeMakespan();
            double missRate = sched.computeMissRate(originalCloudlets.size());
            baselineMakespan.put(sched.getAlgorithmName(), makespan);
            baselineMissRate.put(sched.getAlgorithmName(), missRate);
            Log.printlnConcat("[Baseline] ", sched.getAlgorithmName(),
                    " - makespan=", String.format("%.2f", makespan),
                    "s  missRate=", String.format("%.1f%%", missRate * 100));
        }

        if (exportDetailed) {
            // Time to write out the DFARM reports. Notice we deliberately use
            // controller.getAllVms() here and not the local allVms list above.
            // They're not quite the same thing: the local allVms is every VM
            // we CONFIGURED when building the topology (which is the right
            // list for the baselines above, since they're purely analytical
            // and never touch CloudSim), while controller.getAllVms() is only
            // the VMs CloudSim actually CONFIRMED it managed to create
            // (populated from getGuestsCreatedList() back in submitCloudlets()).
            // These reports are supposed to describe what really happened in
            // the simulation, not what we intended to happen — see
            // DFARMResultsExporter's own class comment — so they need the
            // confirmed list, even though right now (after fixing the
            // placement bug) the two lists happen to be identical anyway.
            List<EdgeVm> confirmedVms = controller.getAllVms();
            Path runDir = new DFARMResultsExporter(controller, allNodes, confirmedVms).exportAll();

            new AlgorithmComparisonExporter(controller, baselines, confirmedVms, allNodes, runDir).exportComparison();

            // The detailed research-grade trace workbook (Task Execution Log,
            // Task Event Timeline, DFARM Internal Decision Log) gets written
            // last on purpose, so it also picks up the lightweight trace
            // events the baselines just recorded above. It sits alongside the
            // CSV reports rather than replacing them, at least for now.
            Path traceWorkbook = new TraceWorkbookExporter(controller, runDir).export();
            Log.printlnConcat("Execution trace workbook written to: ", traceWorkbook.toAbsolutePath());
            Log.printlnConcat("All results written to: ", runDir.toAbsolutePath());
        }

        // This is DFARM's own real computational cost of searching for a
        // place to put each task — measured with System.nanoTime(), so it's
        // actual wall-clock time the search took, not a simulated CloudSim
        // delay. It covers every successful doSchedule() call the run made:
        // original placements, resubmissions, and replica placements alike
        // (see DFARMScheduler.doSchedule()).
        double[] searchTimes = ExecutionTraceManager.getInstance().getTaskRecords().stream()
                .map(rec -> rec.vmSearchTimeMs)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .toArray();
        double searchMin = Arrays.stream(searchTimes).min().orElse(0.0);
        double searchMax = Arrays.stream(searchTimes).max().orElse(0.0);
        double searchAvg = Arrays.stream(searchTimes).average().orElse(0.0);

        Log.println("\n-- DFARM VM-Search Time (DFARMScheduler's own real search-computation cost) --");
        Log.printlnConcat("Min : ", String.format("%.4f", searchMin), " ms");
        Log.printlnConcat("Max : ", String.format("%.4f", searchMax), " ms");
        Log.printlnConcat("Avg : ", String.format("%.4f", searchAvg), " ms");
    }

    // ── Building the FogDevices and VMs ──────────────────────────────────────

    //Builds one DFARMFogDevice with a single PowerHost, giving it one Pe
    //(processing element) for every VM slot the node needs.
    private static DFARMFogDevice createFogNode(String name, double[] mipsValues) throws Exception {
        // We sort the Pe list DESCENDING by MIPS here, and it matters more
        // than it looks. CloudSim's own admission check for "can this VM even
        // go on this host" (HostEntity.isSuitableForGuest()) only looks at
        // getPeList().getFirst().getMips() — in other words, it just checks
        // the FIRST Pe in the list and assumes every Pe has that same
        // capacity. That's a known gap for hosts with mixed-capacity Pes
        // (there's even a @TODO about it in this fork's own
        // VmScheduler.getPeCapacity(): "It considers that all PEs have the
        // same capacity, what has been shown doesn't be assured"). If we'd
        // left the Pes in mipsValues' natural ascending order, any VM that
        // needed more MIPS than the smallest Pe would get rejected outright
        // by its own node — even if a bigger Pe was sitting right there that
        // could've handled it. We actually saw this happen: most VMs were
        // silently getting stranded. Sorting descending fixes it, because now
        // the biggest Pe is the one the admission check looks at, so any VM
        // that can genuinely fit somewhere on this node gets through.
        double[] sortedMips = mipsValues.clone();
        Arrays.sort(sortedMips);
        List<Pe> peList = new ArrayList<>();
        int peId = 0;
        for (int i = sortedMips.length - 1; i >= 0; i--) {
            peList.add(new Pe(peId++, new PeProvisionerSimple(sortedMips[i])));
        }

        int  totalRam     = mipsValues.length * 1024;   // 1024 MB per VM slot
        long totalBw      = mipsValues.length * 1000L;  // 1000 Mbps per VM slot
        long totalStorage = mipsValues.length * 10000L; // 10000 MB per VM slot

        PowerHost host = new PowerHost(
            FogUtils.generateEntityId(),
            new RamProvisionerSimple(totalRam),
            new BwProvisionerSimple(totalBw),
            totalStorage,
            peList,
            new VmSchedulerTimeShared(peList),
            new FogLinearPowerModel(0, 0) // energy metrics unused by DFARM
        );

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
            "x86", "Linux", "KVM", host, 10.0, 3.0, 0.05, 0.001, 0.0);

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        // Notice schedulingInterval is 0.01, not the old value of 10 — this
        // one's important. FogDevice's base class (PowerDatacenter) always
        // reschedules its next "check for finished cloudlets" event exactly
        // schedulingInterval seconds from now, no matter when a cloudlet is
        // actually due to finish — unlike plain CloudSim's Datacenter class,
        // which schedules that check to land right AT the real completion
        // time. So with schedulingInterval=10, every single cloudlet
        // completion only ever got noticed on a coarse 10-second grid, which
        // threw off makespan and completion-time numbers well beyond what
        // DFARM's own deadline/DPL math would suggest. Setting it to
        // something tiny like 0.01 means completions get detected almost
        // exactly when they really happen.
        return new DFARMFogDevice(
            name, characteristics, new DFARMVmAllocationPolicy(hostList),
            new LinkedList<Storage>(), 0.01, 10000, 10000, 0, 0.0);
    }

    //Builds the VMs for one node and registers each one with it.
    private static List<EdgeVm> createNodeVms(int userId,
                                              EdgeNode node,
                                              double[] mipsValues,
                                              double bootTime,
                                              double acqDelay,
                                              double wirelessBwKBps,
                                              double interNodeBwKBps) {
        List<EdgeVm> vms = new ArrayList<>();

        for (double mips : mipsValues) {
            EdgeVm vm = new EdgeVm(
                nextVmId++,
                userId,
                mips,
                1,
                1024,
                1000L,
                10000L,
                "KVM",
                new CloudletSchedulerSpaceShared(),
                bootTime,
                acqDelay,
                wirelessBwKBps,
                interNodeBwKBps,
                node.getNodeId()
            );

            node.addVm(vm);
            vms.add(vm);
        }

        return vms;
    }

    // ── Workload generator (follows the same distribution as the CloudSim reference) ────

    private static List<EdgeCloudlet> createCloudlets(int userId, Map<String, EdgeNode> nodeMap, long seed) {
        List<EdgeCloudlet> list = new ArrayList<>();
        Random rand = new Random(seed);

        int idCounter = 0; // local to this call, starts at 0 every time — nothing carries over between trials
        int totalTasks = 250;

        for (int i = 0; i < totalTasks; i++) {

            // 1. Spread arrivals evenly across the whole window. This
            // replaced an older 3-phase bursty pattern (150/90/60 tasks
            // crammed into 0-20s then a 40s tail) that dumped 80% of the
            // workload into the first 20 seconds — that front-loading was
            // causing a lot of rejections just from queueing pressure,
            // independent of how good the scheduling itself was.
            double arrivalTime = rand.nextDouble() * ARRIVAL_WINDOW_SEC;

            // 2. Bias which node each task "belongs to," so a good chunk of
            // tasks are forced to consider cross-node offload.
            String nodeBias;
            if (i % 2 == 0) nodeBias = "A";
            else if (i % 4 == 1) nodeBias = "B";
            else nodeBias = "C";

            // 3. Pick a task size/deadline profile.
            long mi;
            double inputKB, outputKB, deadline;

            double type = rand.nextDouble();

            if (type < 0.3) {
                // SMALL task
                mi = 1000;
                inputKB = 80;
                outputKB = 20;
                deadline = 30 + rand.nextDouble() * 10; // 30-40s

            } else if (type < 0.6) {
                // MEDIUM task
                mi = 2000;
                inputKB = 150;
                outputKB = 50;

                if (nodeBias.equals("A")) {
                    deadline = 35 + rand.nextDouble() * 15;   // 35-50s
                } else {
                    deadline = 50 + rand.nextDouble() * 20;
                }

            } else if (type < 0.85) {
                // LARGE task
                mi = 5000;
                inputKB = 300;
                outputKB = 100;

                if (nodeBias.equals("A")) {
                    deadline = 40 + rand.nextDouble() * 20;   // 40-60s
                } else {
                    deadline = 80 + rand.nextDouble() * 20;
                }

            } else {
                // HUGE task
                mi = 8000;
                inputKB = 600;
                outputKB = 200;

                if (nodeBias.equals("A")) {
                    deadline = 50 + rand.nextDouble() * 25;   // 50-75s
                } else {
                    deadline = 100 + rand.nextDouble() * 30;
                }
            }

            EdgeCloudlet cl = new EdgeCloudlet(
                    idCounter,
                    mi,
                    inputKB,
                    outputKB,
                    deadline,
                    arrivalTime,
                    "device_" + nodeBias + "_" + idCounter,
                    5.0 + rand.nextDouble() * 10
            );

            cl.setUserId(userId);
            cl.setOriginNode(nodeMap.get(nodeBias));
            list.add(cl);
            idCounter++;
        }

        Log.println("Created " + list.size() + " EdgeCloudlets (realistic workload).");
        return list;
    }

    // ── Printing results and sanity-checking them ────────────────────────────

    private static void printResults(DFARMController controller) {
        DFARMScheduler dfarm = controller.getDfarm();
        int total    = controller.getTotalOriginalTasks();
        int rejected = (int) dfarm.getRejectedTasks().stream()
                                  .filter(t -> !t.isDuplicate()).count();

        Log.println("\n========== DFARM SUMMARY ==========");
        Log.printlnConcat("Total original tasks   : ", total);
        Log.printlnConcat("Accepted               : ", (total - rejected));
        Log.printlnConcat("Rejected (TRR)         : ", rejected,
                " (", String.format("%.1f", dfarm.computeTRR(total) * 100), "%)");
        Log.printlnConcat("Structural repl. fails : ", dfarm.getStructuralReplicaFailures().size());
        Log.printlnConcat("Makespan                : ",
                String.format("%.2f", controller.computeActualMakespan()), "s");
        Log.printlnConcat("Throughput              : ",
                String.format("%.4f", controller.computeActualThroughput()), " tasks/s");
        Log.printlnConcat("ARUR                    : ",
                String.format("%.4f", controller.computeActualARUR()));
        Log.printlnConcat("Cross-node offloads     : ", dfarm.getCrossNodeCloudletIds().size());
        Log.printlnConcat("Cloud offloads          : ", dfarm.getCloudCloudletIds().size());
        Log.println("====================================");
    }

    //Checks that the node-diversity enhancement is actually holding up:
    //every replica should be on a different physical node than its original.
    //(The only exception is a logged structural failure — that means the
    //replica was never placed anywhere at all, so there's nothing to check.)
    private static void verifyReplicaNodeDiversity(DFARMController controller) {
        DFARMScheduler dfarm = controller.getDfarm();
        Map<EdgeCloudlet, EdgeVm> taskVmMap = dfarm.getTaskVmMap();

        int checked = 0;
        int violations = 0;
        for (EdgeCloudlet task : taskVmMap.keySet()) {
            if (task.isDuplicate()) continue;
            int replicaId = -(Math.abs(task.getCloudletId()) + 1);
            EdgeVm originalVm = taskVmMap.get(task);

            for (EdgeCloudlet candidate : taskVmMap.keySet()) {
                if (candidate.isDuplicate() && candidate.getCloudletId() == replicaId) {
                    EdgeVm replicaVm = taskVmMap.get(candidate);
                    checked++;
                    if (originalVm.getNodeId().equals(replicaVm.getNodeId())) {
                        violations++;
                        Log.printlnConcat("!!! NODE-DIVERSITY VIOLATION: task #", task.getCloudletId(),
                                " and its replica are both on Node ", originalVm.getNodeId());
                    }
                    break;
                }
            }
        }

        Log.printlnConcat("\n[Verification] Replica node-diversity check: ",
                checked, " replica pair(s) checked, ", violations, " violation(s).");
    }

    //A sanity check on the fault-injection demo: every task that was sitting
    //on a node when it failed should have ended up either failed over,
    //resubmitted, or explicitly counted as lost — nothing should just vanish
    //unaccounted for. For a node that failed PERMANENTLY, nothing should ever
    //be left "in progress" there again by the end of the run. For a
    //TRANSIENT failure that's not quite fair to check the same way, since the
    //node comes back and can legitimately pick up new work afterward — so
    //that check only looks at what happened during the failure window, not
    //the final snapshot.
    private static void verifyFailoverBehavior(DFARMController controller, List<NodeFailureEvent> failureEvents) {
        Log.println("\n[Verification] Failover behavior check:");
        Log.printlnConcat("  Node failures injected : ", controller.getNodeFailureLog().size());
        Log.printlnConcat("  Successful failovers   : ", controller.getFailoverCount());
        Log.printlnConcat("  Reactive resubmissions : ", controller.getResubmissionCount());
        Log.printlnConcat("  Tasks lost             : ", controller.getTasksLost().size());
        Log.printlnConcat("  Avg recovery latency   : ", String.format("%.4f", controller.computeAvgRecoveryLatency()), "s");
        double mttr = controller.computeMTTR();
        Log.printlnConcat("  MTTR                   : ", Double.isNaN(mttr) ? "N/A" : String.format("%.4fs", mttr));

        if (!controller.getTasksLost().isEmpty()) {
            String lostIds = controller.getTasksLost().stream()
                    .map(t -> String.valueOf(t.getCloudletId()))
                    .collect(Collectors.joining(", "));
            Log.println("  Lost task IDs: " + lostIds);
        }

        for (NodeFailureEvent event : failureEvents) {
            if (event.getFailureType() != NodeFailureEvent.FailureType.PERMANENT) {
                Log.printlnConcat("  Node ", event.getNodeId(),
                        " (TRANSIENT) — recovery reintegration exercised, not checked for zero residual"
                                + " (legitimate new work can land there again after recovery).");
                continue;
            }
            List<EdgeCloudlet> stillOnFailedNode = controller.getDfarm().getInProgressTasksOnNode(event.getNodeId());
            if (!stillOnFailedNode.isEmpty()) {
                String ids = stillOnFailedNode.stream()
                        .map(t -> (t.isDuplicate() ? "replica#" : "task#") + t.getCloudletId())
                        .collect(Collectors.joining(", "));
                Log.printlnConcat("  !!! WARNING: Node ", event.getNodeId(), " (PERMANENT) still has ",
                        stillOnFailedNode.size(), " task(s) in-progress after the run — should be zero. IDs: ", ids);
            } else {
                Log.printlnConcat("  OK: Node ", event.getNodeId(), " (PERMANENT) has no tasks left stranded.");
            }
        }
    }
}
