package org.cloudbus.cloudsim.examples;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.*;

/**
 * EdgeDFARMExample — end-to-end simulation of DFARM on a 3-node edge topology.
 *
 * Topology (from PPT slide 11):
 *   Node A: 4 VMs, max 1000 MIPS, bootTime=5s,  acqDelay=10s, wireless=50 Mbps
 *   Node B: 6 VMs, max 2000 MIPS, bootTime=3s,  acqDelay=8s,  wireless=35 Mbps
 *   Node C: 4 VMs, max  750 MIPS, bootTime=8s,  acqDelay=15s, wireless=10 Mbps
 *
 * Each node is modelled as a separate CloudSim Datacenter.
 * All VMs are created upfront — EdgeNode pool management is a logical
 * scheduling abstraction, not actual VM lifecycle in CloudSim.
 *
 * DFARM parameters (edge-adapted):
 *   replRatio      = 0.10  (10% replication — edge-safe, down from paper's 30%)
 *   allowTaskToRun = false (hard-line: rejected tasks are dropped)
 *   deepSearch     = true  (aggressive queue adjustment for lower TRR)
 */
public class EdgeDFARMExample {

    // ── Wireless bandwidth conversions (Mbps → KBps = Mbps * 1024 / 8) ───────
    private static final double BW_NODE_A_KBps   = 50.0  * 1024 / 8;  // 2560 KBps
    private static final double BW_NODE_B_KBps   = 35.0  * 1024 / 8;  // 6400 KBps
    private static final double BW_NODE_C_KBps   = 10.0  * 1024 / 8;  // 1280 KBps
    private static final double BW_INTERNODE_KBps = 500.0 * 1024 / 8; // 12800 KBps (fibre backhaul)

    public static void main(String[] args) {
        Log.println("Starting EdgeDFARMExample...\n");

        try {
            // Initialise CloudSim 
            CloudSim.init(1, Calendar.getInstance(), false);

   

            //Create EdgeNode objects 
            EdgeNode nodeA = new EdgeNode("A", BW_NODE_A_KBps,   BW_INTERNODE_KBps);
            EdgeNode nodeB = new EdgeNode("B", BW_NODE_B_KBps,   BW_INTERNODE_KBps);
            EdgeNode nodeC = new EdgeNode("C", BW_NODE_C_KBps,   BW_INTERNODE_KBps);

            //Cross-node offload links
            nodeA.addNeighbor(nodeB); nodeA.addNeighbor(nodeC);
            nodeB.addNeighbor(nodeA); nodeB.addNeighbor(nodeC);
            nodeC.addNeighbor(nodeA); nodeC.addNeighbor(nodeB);

            List<EdgeNode> edgeNodes = List.of(nodeA, nodeB, nodeC);

            //Create one CloudSim Datacenter per edge node (required for VM placement)
            createDatacenter("Datacenter_A", new double[]{100, 200, 300, 500});
            createDatacenter("Datacenter_B", new double[]{500, 750, 900, 1000, 1100, 1200});
            createDatacenter("Datacenter_C", new double[]{100, 250, 500, 750});

            //Create EdgeDFARMBroker
            EdgeDFARMBroker broker = new EdgeDFARMBroker(
                "EdgeDFARMBroker",
                edgeNodes,
                0.15,   
                false,  
                true   
            );
            int brokerId = broker.getId();

            //Create EdgeVms and register them with nodes
            List<EdgeVm> allVms = new ArrayList<>();

            // Node A — 4 VMs, max 1000 MIPS, bootTime=5s, acqDelay=10s
            allVms.addAll(createNodeVms(brokerId, nodeA,
                new double[]{100, 200, 300, 500},
                5.0, 10.0, BW_NODE_A_KBps, BW_INTERNODE_KBps));

            // Node B — 6 VMs, max 2000 MIPS, bootTime=3s, acqDelay=8s
            allVms.addAll(createNodeVms(brokerId, nodeB,
                new double[]{500, 750, 900, 1000, 1100, 1200},
                3.0, 8.0, BW_NODE_B_KBps, BW_INTERNODE_KBps));

            // Node C — 4 VMs, max 750 MIPS, bootTime=8s, acqDelay=15s
            allVms.addAll(createNodeVms(brokerId, nodeC,
                new double[]{100, 250, 500, 750},
                8.0, 15.0, BW_NODE_C_KBps, BW_INTERNODE_KBps));

           
            broker.submitGuestList(allVms);

            //Create EdgeCloudlets
            Map<String, EdgeNode> nodeMap = Map.of(
                "A", nodeA, "B", nodeB, "C", nodeC);
            List<EdgeCloudlet> cloudlets = createCloudlets(brokerId, nodeMap);
            
            List<EdgeCloudlet> originalCloudlets = List.copyOf(cloudlets);
            broker.submitCloudletList(cloudlets);

            //Run the simulation
            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            //Print final results 
            printResults(broker);

            //Export DFARM xlsx reports
            Path runDir = new DFARMResultsExporter(broker, edgeNodes, allVms).exportAll();

            //Run baseline algorithms on the same workload 
            double clock = CloudSim.clock();  

            List<EdgeSchedulerBase> baselines = List.of(
                new RSScheduler(),
                new RRScheduler(),
                new MCTScheduler(),
                new RALBAScheduler()
            );

            for (EdgeSchedulerBase sched : baselines) {
                sched.schedule(originalCloudlets, allVms, clock);
                Log.printlnConcat("[Baseline] ", sched.getAlgorithmName(),
                        " — makespan=", String.format("%.2f", sched.computeMakespan()),
                        "s  missRate=",
                        String.format("%.1f%%", sched.computeMissRate(originalCloudlets.size()) * 100));
            }

            // Export cross-algorithm comparison
            new AlgorithmComparisonExporter(broker, baselines, allVms,
                    edgeNodes, runDir).exportComparison();

            Log.printlnConcat("All results written to: ", runDir.toAbsolutePath());
            Log.println("EdgeDFARMExample finished.");

        } catch (Exception e) {
            e.printStackTrace();
            Log.println("error: " + e.getMessage());
        }
    }

   
    //creates VMs for one edge node and registers them
   

    private static List<EdgeVm> createNodeVms(int brokerId,
                                              EdgeNode node,
                                              double[] mipsValues,
                                              double bootTime,
                                              double acqDelay,
                                              double wirelessBwKBps,
                                              double interNodeBwKBps) {
        List<EdgeVm> vms = new ArrayList<>();

        for (double mips : mipsValues) {
            EdgeVm vm = new EdgeVm(
                Vm.highestId.incrementAndGet(), 
                brokerId,
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

 

  private static List<EdgeCloudlet> createCloudlets(int brokerId, Map<String, EdgeNode> nodeMap) {
    List<EdgeCloudlet> list = new ArrayList<>();
    Random rand = new Random(42);

    int idCounter = 0;
    int totalTasks = 300;

    for (int i = 0; i < totalTasks; i++) {

        //1. BURST + RANDOM ARRIVAL 
        double arrivalTime;
        if (i < 200) {
            arrivalTime = rand.nextDouble() * 10;          // early (0–10 s)
        } else if (i < 360) {
            arrivalTime = 10 + rand.nextDouble() * 10;    // heavy burst (10–20 s)
        } else {
            arrivalTime = 20 + rand.nextDouble() * 40;    // tail (20–60 s)
        }

        //2. NODE BIAS (FORCE CROSS-NODE)
        String nodeBias;
        if (i % 2 == 0) nodeBias = "A";
        else if (i % 4 == 1) nodeBias = "B";
        else nodeBias = "C";

        //3. TASK TYPE DISTRIBUTION
        long mi;
        double inputKB, outputKB, deadline;

        double type = rand.nextDouble();

        if (type < 0.3) {
            // SMALL
            mi = 1000;
            inputKB = 80;
            outputKB = 20;

            if (nodeBias.equals("A")) {
         deadline = 30 + rand.nextDouble() * 10;   // 30–40
            } else {
                deadline = 30 + rand.nextDouble() * 10;
            }

        } else if (type < 0.6) {
            // MEDIUM
            mi = 2000;
            inputKB = 150;
            outputKB = 50;

            if (nodeBias.equals("A")) {
      deadline = 35 + rand.nextDouble() * 15;   // 35–50
            } else {
                deadline = 50 + rand.nextDouble() * 20;
            }

        } else if (type < 0.85) {
            // LARGE
            mi = 5000;
            inputKB = 300;
            outputKB = 100;

            if (nodeBias.equals("A")) {
             deadline = 40 + rand.nextDouble() * 20;   // 40–60
            } else {
                deadline = 80 + rand.nextDouble() * 20;
            }

        } else {
            // HUGE
            mi = 8000;
            inputKB = 600;
            outputKB = 200;

            if (nodeBias.equals("A")) {
             deadline = 50 + rand.nextDouble() * 25;   // 50–75
            } else {
                deadline = 100 + rand.nextDouble() * 30;
            }
        }

        // 4. CREATE CLOUDLET
        EdgeCloudlet cl = new EdgeCloudlet(
                Cloudlet.highestId.incrementAndGet(),
                mi,
                inputKB,
                outputKB,
                deadline,
                arrivalTime,
                "device_" + nodeBias + "_" + idCounter,
                5.0 + rand.nextDouble() * 10
        );

        cl.setUserId(brokerId);
        cl.setOriginNode(nodeMap.get(nodeBias));
        list.add(cl);
        idCounter++;
    }

    Log.println("Created " + list.size() + " EdgeCloudlets (realistic workload).");
    return list;
}

    
    // printing results

    private static void printResults(EdgeDFARMBroker broker) {
        List<Cloudlet> received = broker.getCloudletReceivedList();
        DecimalFormat df = new DecimalFormat("###.##");

        Log.println("\n========== CLOUDLET EXECUTION RESULTS ==========");
        Log.println("ID\t\tSTATUS\t\tVM\tNode\tStart\t\tFinish\t\tCPU Time");

        for (Cloudlet c : received) {
            if (c instanceof EdgeCloudlet ec) {
                String tag = ec.isDuplicate() ? "[R]" : "   ";
                Log.print(tag + " #" + c.getCloudletId() + "\t");

                if (c.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
                    int vmId = c.getGuestId();
                    // find which node this VM belongs to
                    String nodeId = "?";
                    for (Cloudlet sub : broker.getCloudletSubmittedList()) {
                        if (sub.getCloudletId() == c.getCloudletId()) {
                            nodeId = "N/A";
                            break;
                        }
                    }
                    Log.println("SUCCESS\t\t#" + vmId
                            + "\t" + nodeId
                            + "\t" + df.format(c.getExecStartTime())
                            + "\t\t" + df.format(c.getExecFinishTime())
                            + "\t\t" + df.format(c.getActualCPUTime()));
                } else {
                    Log.println(c.getStatus());
                }
            }
        }

        // DFARM metrics are printed in broker.shutdownEntity() automatically.
        // Reprint summary here for clarity.
        DFARMScheduler dfarm = broker.getDfarm();
        int total    = broker.getTotalOriginalTasks();
        int rejected = (int) dfarm.getRejectedTasks().stream()
                                  .filter(t -> !t.isDuplicate()).count();

        Log.println("\n========== DFARM SUMMARY ==========");
        Log.printlnConcat("Total original tasks : ", total);
        Log.printlnConcat("Accepted             : ", (total - rejected));
        Log.printlnConcat("Rejected (TRR)       : ", rejected,
                " (", String.format("%.1f", dfarm.computeTRR(total) * 100), "%)");
        Log.printlnConcat("Makespan             : ",
                String.format("%.2f", dfarm.computeMakespan()), "s");
        Log.printlnConcat("Throughput           : ",
                String.format("%.4f", dfarm.computeThroughput(total)), " tasks/s");
        Log.printlnConcat("ARUR                 : ",
                String.format("%.4f", dfarm.computeARUR()));
        Log.println("====================================");
    }

    //create one CloudSim Datacenter with one host whose PEs match the given MIPS values
    private static Datacenter createDatacenter(String name, double[] mipsValues) {
        List<Pe> peList = new ArrayList<>();
        for (double mips : mipsValues) {
            peList.add(new Pe(new PeProvisionerSimple(mips)));
        }

        int  totalRam     = mipsValues.length * 1024;   // 1024 MB per VM slot
        long totalBw      = mipsValues.length * 1000L;  // 1000 Mbps per VM slot
        long totalStorage = mipsValues.length * 10000L; // 10000 MB per VM slot

        List<Host> hostList = new ArrayList<>();
        hostList.add(new Host(
            new RamProvisionerSimple(totalRam),
            new BwProvisionerSimple(totalBw),
            totalStorage,
            peList,
            new VmSchedulerTimeShared(peList)
        ));

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
            "x86", "Linux", "KVM", hostList, 10.0, 0.0, 0.0, 0.0, 0.0);

        try {
            return new Datacenter(name, characteristics,
                new VmAllocationPolicySimple(hostList), new LinkedList<>(), 0);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create datacenter: " + name, e);
        }
    }
}
