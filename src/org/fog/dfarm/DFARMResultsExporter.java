package org.fog.dfarm;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.cloudbus.cloudsim.Cloudlet;
import org.fog.dfarm.trace.ExecutionTraceManager;
import org.fog.dfarm.trace.TaskExecutionRecord;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Exports DFARM simulation results as .xlsx workbooks under a timestamped run
 * folder, matching the sheet/column structure of the original CloudSim
 * reference implementation's exporters (see /template/*.xlsx) — with the
 * predicted-vs-actual duplication removed (single "actual", CloudSim-observed
 * result set only, via DFARMController.computeActual*()) and DFARM's own
 * extensions (cloud tier, fault tolerance / failover) layered in as
 * additional, clearly-labelled sections/sheets rather than disrupting the
 * base template layout.
 *
 * Output layout (relative to the working directory, portable across OSes):
 *   ./DFARM_EDGE_RESULTS/<yyyyMMdd_HHmmss>_<totalOriginalTasks>tasks/
 *     simulation_summary.xlsx  (Overall_Statistics, VM_CT_Breakdown, Topology,
 *                                Rejected_Tasks, Structural_Replica_Failures,
 *                                Fault_Tolerance)
 *     task_journey.xlsx        (Task_Journey)
 *     node_A_stats.xlsx, node_B_stats.xlsx, node_C_stats.xlsx
 *                              (Node_<id>_Cloudlets — one file per node in
 *                              reportNodes; no cloud tier in this project, so
 *                              no node_CLOUD_stats.xlsx is ever produced)
 */
public class DFARMResultsExporter {

    private static final Path BASE_DIR =
            Paths.get(System.getProperty("user.dir"), "DFARM_EDGE_RESULTS");
    private static final DateTimeFormatter FOLDER_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path runDir;
    private final DFARMController controller;
    private final DFARMScheduler  dfarm;
    private final List<EdgeNode>  reportNodes;      // edge nodes + cloud node
    private final List<EdgeVm>    allVms;
    private final int             totalOriginalTasks;

    private final Map<Integer, Cloudlet>      receivedById;     // cloudletId → finished Cloudlet
    private final Set<Integer>                crossNodeIds;     // cloudlet IDs offloaded cross-node
    private final Set<Integer>                cloudIds;         // cloudlet IDs offloaded to cloud
    private final Set<Integer>                reusedVmIds;      // VM IDs re-used from idle pool
    private final Map<EdgeVm, Double>         actualVmCt;       // VM → actual (observed) CT

    public DFARMResultsExporter(DFARMController controller,
                                List<EdgeNode> reportNodes,
                                List<EdgeVm> allVms) throws IOException {
        this.controller         = controller;
        this.dfarm              = controller.getDfarm();
        this.reportNodes        = reportNodes;
        this.allVms             = allVms;
        this.totalOriginalTasks = controller.getTotalOriginalTasks();

        String ts = LocalDateTime.now().format(FOLDER_FMT) + "_" + totalOriginalTasks + "tasks";
        this.runDir = BASE_DIR.resolve(ts);
        Files.createDirectories(runDir);

        this.receivedById = new HashMap<>();
        for (Cloudlet c : controller.getCloudletReceivedList()) {
            receivedById.put(c.getCloudletId(), c);
        }

        this.crossNodeIds = dfarm.getCrossNodeCloudletIds();
        this.cloudIds     = dfarm.getCloudCloudletIds();
        this.reusedVmIds  = dfarm.getReusedVmIds();
        this.actualVmCt   = controller.computeActualVmCtMap();
    }

    /** Export all reports. Returns the run directory path. */
    public Path exportAll() throws IOException {
        exportSimulationSummary();
        exportTaskJourney();
        exportPerNodeStats();
        System.out.println("[DFARMResultsExporter] Results written to: " + runDir.toAbsolutePath());
        return runDir;
    }

    // ═════════════════════════════════════════════════════════════════════
    // simulation_summary.xlsx
    // ═════════════════════════════════════════════════════════════════════

    private void exportSimulationSummary() throws IOException {
        Path outPath = runDir.resolve("simulation_summary.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            writeOverallStatistics(wb);
            writeVmCtBreakdown(wb);
            writeTopology(wb);
            writeRejectedTasks(wb);
            writeStructuralReplicaFailures(wb);
            writeFaultTolerance(wb);
            writeVmSearchAnalysis(wb);
            try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                wb.write(fos);
            }
        }
    }

    private void writeOverallStatistics(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("Overall_Statistics");
        int r = 0;

        int rejected = (int) dfarm.getRejectedTasks().stream().filter(t -> !t.isDuplicate()).count();
        int accepted = totalOriginalTasks - rejected;
        long replicas = dfarm.getTaskVmMap().keySet().stream().filter(EdgeCloudlet::isDuplicate).count();

        long deadlineMet = controller.getCloudletReceivedList().stream()
                .filter(c -> c instanceof EdgeCloudlet)
                .filter(c -> !((EdgeCloudlet) c).isDuplicate())
                .filter(c -> c.getStatus() == Cloudlet.CloudletStatus.SUCCESS)
                .filter(c -> {
                    EdgeCloudlet ec = (EdgeCloudlet) c;
                    return c.getExecFinishTime() <= ec.getDeadline();
                })
                .count();

        r = writeSectionTitle(wb, sheet, r, "DFARM Configuration Parameters");
        r = writeRow(wb, sheet, r, "replRatio (replication threshold)",
                String.format("%.0f%%", dfarm.getReplRatio() * 100));
        r = writeRow(wb, sheet, r, "allowTaskToRun (soft-line)",
                dfarm.isAllowTaskToRun()
                        ? "true (soft-line — force onto fastest VM)"
                        : "false (hard-line — rejected tasks dropped)");
        r = writeRow(wb, sheet, r, "deepSearch (Algorithm 7 queue-adjustment, simple variant)", String.valueOf(dfarm.isDeepSearch()));
        // reportNodes previously always included exactly one cloud node at the
        // end (hence "- 1" to count only edge nodes) — this project has no
        // cloud tier anymore, so reportNodes now IS the edge node list.
        r = writeRow(wb, sheet, r, "Edge nodes", reportNodes.size());
        r = writeRow(wb, sheet, r, "Total VMs", allVms.size());
        r++;

        r = writeSectionTitle(wb, sheet, r, "Task Statistics");
        r = writeRow(wb, sheet, r, "Total original tasks submitted", totalOriginalTasks);
        r = writeRow(wb, sheet, r, "Replica tasks created (fault tolerance)", replicas);
        r = writeRow(wb, sheet, r, "Total tasks sent to simulation", totalOriginalTasks + replicas);
        r = writeRow(wb, sheet, r, "Accepted (original, met deadline)", accepted);
        r = writeRow(wb, sheet, r, "Rejected (original, missed deadline)", rejected);
        r = writeRow(wb, sheet, r, "Cross-node offloaded tasks", crossNodeIds.size());
        r = writeRow(wb, sheet, r, "Cloud offloaded tasks", cloudIds.size());
        r = writeRow(wb, sheet, r, "Structural replica failures (no other node existed)",
                dfarm.getStructuralReplicaFailures().size());
        r++;

        r = writeSectionTitle(wb, sheet, r, "DFARM Performance Metrics");
        r = writeRow(wb, sheet, r, "TRR — Task Rejection Rate",
                String.format("%.4f (%.2f%%)", dfarm.computeTRR(totalOriginalTasks), dfarm.computeTRR(totalOriginalTasks) * 100));
        r = writeRow(wb, sheet, r, "Makespan (max VM CT across all VMs)",
                String.format("%.4f s", controller.computeActualMakespan()));
        r = writeRow(wb, sheet, r, "Throughput (accepted / makespan)",
                String.format("%.6f tasks/s", controller.computeActualThroughput()));
        r = writeRow(wb, sheet, r, "ARUR — Avg Resource Utilisation Rate",
                String.format("%.4f", controller.computeActualARUR()));
        r = writeRow(wb, sheet, r, "Deadline met (returned originals)", deadlineMet + " / " + accepted);
        // DFARMScheduler's own real search-computation cost (System.nanoTime()
        // wall-clock, not a simulated CloudSim delay) across every successful
        // doSchedule() call — see TaskExecutionRecord.vmSearchTimeMs.
        double[] searchStats = vmSearchTimeStats(ExecutionTraceManager.getInstance().getTaskRecords());
        r = writeRow(wb, sheet, r, "Avg VM-Search Time (ms)", String.format("%.4f", searchStats[0]));
        r = writeRow(wb, sheet, r, "Min VM-Search Time (ms)", String.format("%.4f", searchStats[1]));
        r = writeRow(wb, sheet, r, "Max VM-Search Time (ms)", String.format("%.4f", searchStats[2]));
        r++;

        r = writeSectionTitle(wb, sheet, r, "Fault Tolerance Metrics");
        r = writeRow(wb, sheet, r, "Node failures injected", controller.getNodeFailureLog().size());
        r = writeRow(wb, sheet, r, "Successful failovers", controller.getFailoverCount());
        r = writeRow(wb, sheet, r, "Reactive resubmissions", controller.getResubmissionCount());
        r = writeRow(wb, sheet, r, "Tasks lost", controller.getTasksLost().size());
        r = writeRow(wb, sheet, r, "Standby replicas activated", dfarm.getActivatedReplicas().size());
        r = writeRow(wb, sheet, r, "Standby replicas discarded (never needed)", dfarm.getDiscardedReplicas().size());
        r = writeRow(wb, sheet, r, "Standby replicas lost (own node failed first)", dfarm.getLostStandbyReplicas().size());
        r = writeRow(wb, sheet, r, "Replica utilization (%)", String.format("%.2f", dfarm.computeReplicaUtilization() * 100));
        r = writeRow(wb, sheet, r, "Extra resource cost of replication (VM-seconds reserved)",
                String.format("%.4f", dfarm.computeTotalReplicaReservedCost()));
        r = writeRow(wb, sheet, r, "Avg recovery latency (s)", String.format("%.4f", controller.computeAvgRecoveryLatency()));
        r = writeRow(wb, sheet, r, "Avg replica activation time (s)", String.format("%.4f", controller.computeAvgReplicaActivationTime()));
        r = writeRow(wb, sheet, r, "Availability of ever-failed nodes (%)", String.format("%.4f", controller.computeAvailability() * 100));
        double mttr = controller.computeMTTR();
        r = writeRow(wb, sheet, r, "MTTR (s)", Double.isNaN(mttr) ? "N/A (no transient recoveries)" : String.format("%.4f", mttr));

        sheet.setColumnWidth(0, 50 * 256);
        sheet.setColumnWidth(1, 40 * 256);
    }

    private void writeVmCtBreakdown(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("VM_CT_Breakdown");
        String[] headers = {
            "VM ID", "Node", "MIPS", "Boot Time (s)", "Acq Delay (s)",
            "Reused (no AcqDelay)?", "Tasks Assigned", "DFARM CT (s)",
            "Wireless BW (KBps)", "Inter-Node BW (KBps)"
        };
        writeHeaderRow(wb, sheet, 0, headers);

        Map<EdgeVm, Integer> taskCount = new HashMap<>();
        for (Map.Entry<EdgeCloudlet, EdgeVm> e : dfarm.getTaskVmMap().entrySet()) {
            taskCount.merge(e.getValue(), 1, Integer::sum);
        }

        List<EdgeVm> sortedVms = allVms.stream()
                .sorted(Comparator.comparing(EdgeVm::getNodeId).thenComparingInt(EdgeVm::getId))
                .collect(Collectors.toList());

        int r = 1;
        for (EdgeVm vm : sortedVms) {
            Row row = sheet.createRow(r++);
            int c = 0;
            setCell(row, c++, vm.getId());
            setCell(row, c++, vm.getNodeId());
            setCell(row, c++, vm.getMips());
            setCell(row, c++, vm.getBootTime());
            setCell(row, c++, vm.getAcquisitionDelay());
            setCell(row, c++, reusedVmIds.contains(vm.getId()) ? "YES" : "No");
            setCell(row, c++, taskCount.getOrDefault(vm, 0));
            setCell(row, c++, actualVmCt.getOrDefault(vm, 0.0));
            setCell(row, c++, vm.getWirelessBandwidthKBps());
            setCell(row, c, vm.getInterNodeBandwidthKBps());
        }

        autoSizeColumns(sheet, headers.length);
        sheet.createFreezePane(0, 1);
    }

    private void writeTopology(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("Topology");
        int r = writeTitle(wb, sheet, 0, "Edge Node Topology");
        String[] headers = {
            "Node ID", "VM Count", "Max MIPS", "Boot Time (s)",
            "Acq Delay (s)", "Wireless BW (Mbps)", "Inter-Node BW (Mbps)",
            "Cloud BW (Mbps)", "Neighbours"
        };
        writeHeaderRow(wb, sheet, r, headers);
        r++;

        for (EdgeNode node : reportNodes) {
            List<EdgeVm> nodeVms = allVms.stream()
                    .filter(v -> v.getNodeId().equals(node.getNodeId()))
                    .collect(Collectors.toList());

            double maxMips  = nodeVms.stream().mapToDouble(EdgeVm::getMips).max().orElse(0);
            double bootTime = nodeVms.isEmpty() ? 0 : nodeVms.get(0).getBootTime();
            double acqDelay = nodeVms.isEmpty() ? 0 : nodeVms.get(0).getAcquisitionDelay();
            double wirelessMbps  = node.getWirelessBandwidthKBps()  * 8 / 1024;
            double interNodeMbps = node.getInterNodeBandwidthKBps() * 8 / 1024;
            double cloudMbps     = node.getCloudBandwidthKBps()     * 8 / 1024;
            String neighbours = node.getNeighborNodes().stream()
                    .map(EdgeNode::getNodeId)
                    .collect(Collectors.joining(", "));

            Row row = sheet.createRow(r++);
            int c = 0;
            setCell(row, c++, node.getNodeId());
            setCell(row, c++, nodeVms.size());
            setCell(row, c++, maxMips);
            setCell(row, c++, bootTime);
            setCell(row, c++, acqDelay);
            setCell(row, c++, wirelessMbps);
            setCell(row, c++, interNodeMbps);
            setCell(row, c++, cloudMbps);
            setCell(row, c, neighbours);
        }

        autoSizeColumns(sheet, headers.length);
    }

    private void writeRejectedTasks(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("Rejected_Tasks");
        String[] headers = {"Cloudlet ID", "Type", "Source Device",
                "MI", "Input KB", "Output KB", "Deadline (s)", "Arrival Time (s)", "Reason"};
        writeHeaderRow(wb, sheet, 0, headers);

        List<EdgeCloudlet> rejected = dfarm.getRejectedTasks().stream()
                .filter(t -> !t.isDuplicate())
                .sorted(Comparator.comparingInt(EdgeCloudlet::getCloudletId))
                .collect(Collectors.toList());

        int r = 1;
        for (EdgeCloudlet ec : rejected) {
            Row row = sheet.createRow(r++);
            int c = 0;
            setCell(row, c++, ec.getCloudletId());
            setCell(row, c++, ec.isDuplicate() ? "Replica" : "Original");
            setCell(row, c++, ec.getSourceDeviceId());
            setCell(row, c++, ec.getCloudletLength());
            setCell(row, c++, ec.getInputSizeKB());
            setCell(row, c++, ec.getOutputSizeKB());
            setCell(row, c++, ec.getDeadline());
            setCell(row, c++, ec.getArrivalTime());
            setCell(row, c, "No VM could schedule task within deadline " + ec.getDeadline() + "s");
        }

        autoSizeColumns(sheet, headers.length);
        sheet.createFreezePane(0, 1);
    }

    private void writeStructuralReplicaFailures(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("Structural_Replica_Failures");
        String[] headers = {"Replica Cloudlet ID", "Original Cloudlet ID",
                "MI", "Deadline (s)", "Arrival Time (s)", "Reason"};
        writeHeaderRow(wb, sheet, 0, headers);

        List<EdgeCloudlet> failures = dfarm.getStructuralReplicaFailures().stream()
                .sorted(Comparator.comparingInt(EdgeCloudlet::getCloudletId))
                .collect(Collectors.toList());

        int r = 1;
        for (EdgeCloudlet ec : failures) {
            int originalId = -ec.getCloudletId() - 1;
            Row row = sheet.createRow(r++);
            int c = 0;
            setCell(row, c++, ec.getCloudletId());
            setCell(row, c++, originalId);
            setCell(row, c++, ec.getCloudletLength());
            setCell(row, c++, ec.getDeadline());
            setCell(row, c++, ec.getArrivalTime());
            setCell(row, c, "No VM exists on any node other than the original's — topology has no diverse alternative");
        }

        autoSizeColumns(sheet, headers.length);
        sheet.createFreezePane(0, 1);
    }

    private void writeFaultTolerance(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("Fault_Tolerance");
        int r = writeTitle(wb, sheet, 0, "Node Failure Injection — Detail");

        r = writeSectionTitle(wb, sheet, r, "Injected Events");
        String[] eventHeaders = {"Node", "Time (s)", "Type", "Recovery Time (s)"};
        writeHeaderRow(wb, sheet, r, eventHeaders);
        r++;
        for (NodeFailureEvent event : controller.getNodeFailureLog()) {
            Row row = sheet.createRow(r++);
            int c = 0;
            setCell(row, c++, event.getNodeId());
            setCell(row, c++, event.getTime());
            setCell(row, c++, event.getFailureType().toString());
            setCell(row, c, event.getFailureType() == NodeFailureEvent.FailureType.TRANSIENT
                    ? event.getRecoveryTime() : null);
        }

        if (!controller.getTasksLost().isEmpty()) {
            r++;
            r = writeSectionTitle(wb, sheet, r, "Lost Tasks");
            String[] lostHeaders = {"Cloudlet ID", "MI", "Deadline (s)", "Arrival Time (s)"};
            writeHeaderRow(wb, sheet, r, lostHeaders);
            r++;
            for (EdgeCloudlet t : controller.getTasksLost()) {
                Row row = sheet.createRow(r++);
                int c = 0;
                setCell(row, c++, t.getCloudletId());
                setCell(row, c++, t.getCloudletLength());
                setCell(row, c++, t.getDeadline());
                setCell(row, c, t.getArrivalTime());
            }
        }

        autoSizeColumns(sheet, 4);
    }

    private void writeVmSearchAnalysis(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("VM_Search_Analysis");
        int r = writeTitle(wb, sheet, 0, "DFARM VM-Search Time Analysis (by task size tier)");

        Row noteRow = sheet.createRow(r++);
        noteRow.createCell(0).setCellValue(
                "VM-Search Time = DFARMScheduler's own real wall-clock search cost (System.nanoTime()), "
              + "distinct from simulated CloudSim delays. Only successful searches counted. For tasks "
              + "needing the acquisition cascade (non-direct-assign), this includes internal trace-logging "
              + "(Log.printlnConcat) I/O overhead — see TaskExecutionRecord.vmSearchTimeMs.");
        r++;

        String[] headers = {
            "Category", "Task Count", "Avg Deadline (s)", "Avg Output Comm Cost (s)",
            "Avg VM-Search Time (ms)", "Min VM-Search Time (ms)", "Max VM-Search Time (ms)"
        };
        writeHeaderRow(wb, sheet, r, headers);
        r++;

        List<TaskExecutionRecord> searched = ExecutionTraceManager.getInstance().getTaskRecords().stream()
                .filter(rec -> rec.vmSearchTimeMs != null)
                .collect(Collectors.toList());

        // Same 3-tier MI boundaries already used by AlgorithmComparisonExporter's
        // Deadline_Analysis sheet (mi<=1000 small / mi==2000 medium / else large).
        Map<String, List<TaskExecutionRecord>> byTier = new LinkedHashMap<>();
        byTier.put("Small (≤1000 MI)", new ArrayList<>());
        byTier.put("Medium (2000 MI)", new ArrayList<>());
        byTier.put("Large (≥5000 MI)", new ArrayList<>());
        for (TaskExecutionRecord rec : searched) {
            String tier = rec.mi <= 1000 ? "Small (≤1000 MI)"
                        : rec.mi == 2000 ? "Medium (2000 MI)"
                        : "Large (≥5000 MI)";
            byTier.get(tier).add(rec);
        }

        for (Map.Entry<String, List<TaskExecutionRecord>> e : byTier.entrySet()) {
            r = writeVmSearchTierRow(wb, sheet, r, e.getKey(), e.getValue());
        }
        r = writeVmSearchTierRow(wb, sheet, r, "Overall", searched);

        autoSizeColumns(sheet, headers.length);
    }

    private int writeVmSearchTierRow(XSSFWorkbook wb, Sheet sheet, int rowIdx, String category,
                                     List<TaskExecutionRecord> recs) {
        Row row = sheet.createRow(rowIdx);
        int c = 0;
        setCell(row, c++, category);
        setCell(row, c++, recs.size());
        setCell(row, c++, recs.stream().mapToDouble(rec -> rec.deadline).average().orElse(0.0));
        // Avg Output Comm Cost — read from the existing, already deviceLatencyMs-aware
        // downlinkDelay field DFARMScheduler.commit() populates; never recomputed here.
        setCell(row, c++, recs.stream()
                .filter(rec -> rec.downlinkDelay != null)
                .mapToDouble(rec -> rec.downlinkDelay)
                .average().orElse(0.0));
        double[] stats = vmSearchTimeStats(recs);
        setCell(row, c++, stats[0]);
        setCell(row, c++, stats[1]);
        setCell(row, c, stats[2]);
        return rowIdx + 1;
    }

    /** [avg, min, max] of vmSearchTimeMs across the given records (0s if empty or none set). */
    private double[] vmSearchTimeStats(Collection<TaskExecutionRecord> recs) {
        DoubleSummaryStatistics stats = recs.stream()
                .map(rec -> rec.vmSearchTimeMs)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();
        if (stats.getCount() == 0) return new double[]{0.0, 0.0, 0.0};
        return new double[]{stats.getAverage(), stats.getMin(), stats.getMax()};
    }

    // ═════════════════════════════════════════════════════════════════════
    // task_journey.xlsx
    // ═════════════════════════════════════════════════════════════════════

    private void exportTaskJourney() throws IOException {
        Path outPath = runDir.resolve("task_journey.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Task_Journey");
            int r = writeTitle(wb, sheet, 0, "DFARM Edge — Full Task Journey Log");

            String[] headers = {
                "Cloudlet ID", "Type", "Source Device",
                "MI", "Input KB", "Output KB", "Deadline (s)", "Arrival Time (s)",
                "Assigned Node", "VM ID", "VM MIPS", "VM Boot (s)", "VM AcqDelay (s)",
                "Uplink Delay (s)", "Compute Time (s)", "Downlink Delay (s)",
                "DFARM CT Estimate (s)",
                "Exec Start (s)", "Exec Finish (s)", "Actual CPU (s)",
                "CloudSim Status", "Deadline Met?",
                "Migration / Offload", "VM Reused (no AcqDelay)?",
                "VM-Search Time (ms)", "Failed Search Time (ms)",
                "Transition Log"
            };
            writeHeaderRow(wb, sheet, r, headers);
            r++;

            List<EdgeCloudlet> allTasks = new ArrayList<>(dfarm.getTaskVmMap().keySet());
            for (EdgeCloudlet ec : dfarm.getRejectedTasks()) {
                if (!dfarm.getTaskVmMap().containsKey(ec)) allTasks.add(ec);
            }
            allTasks.sort(Comparator.comparingInt(EdgeCloudlet::getCloudletId));

            for (EdgeCloudlet ec : allTasks) {
                EdgeVm vm = dfarm.getTaskVmMap().get(ec);
                Cloudlet finished = receivedById.get(ec.getCloudletId());
                boolean isCrossNode = crossNodeIds.contains(ec.getCloudletId());
                boolean isCloud     = cloudIds.contains(ec.getCloudletId());
                boolean isReused    = vm != null && reusedVmIds.contains(vm.getId());
                boolean isRejected  = vm == null;

                double uplinkDelay   = vm != null ? vm.computeUplinkDelay(ec.getInputSizeKB(), ec.getDeviceLatencyMs())   : Double.NaN;
                double computeTime   = vm != null ? ec.getCloudletLength() / vm.getMips()         : Double.NaN;
                double downlinkDelay = vm != null ? vm.computeDownlinkDelay(ec.getOutputSizeKB(), ec.getDeviceLatencyMs()) : Double.NaN;
                double dfarmCt       = vm != null ? dfarm.getVmCtMap().getOrDefault(vm, Double.NaN) : Double.NaN;

                double execStart  = finished != null ? finished.getExecStartTime()  : Double.NaN;
                double execFinish = finished != null ? finished.getExecFinishTime() : Double.NaN;
                double cpuTime    = finished != null ? finished.getActualCPUTime()  : Double.NaN;
                String status     = isRejected ? "REJECTED"
                        : (finished != null ? finished.getStatus().toString() : "NOT_RETURNED");
                boolean metDeadline = !Double.isNaN(execFinish) && execFinish <= ec.getDeadline();

                String migration = isRejected ? "N/A (rejected)"
                        : isCloud ? "Cloud offload"
                        : isCrossNode ? "Cross-node offload" : "Same-node";
                String reusedStr = isReused ? "YES (boot only, no AcqDelay)" : "No";

                String transitionLog = buildTransitionLog(ec, vm, execStart, execFinish, isCrossNode, isCloud, isReused);

                TaskExecutionRecord searchRecord = ExecutionTraceManager.getInstance().getRecord(ec.getCloudletId());
                Double vmSearchTimeMs     = searchRecord != null ? searchRecord.vmSearchTimeMs     : null;
                Double failedSearchTimeMs = searchRecord != null ? searchRecord.failedSearchTimeMs : null;

                Row row = sheet.createRow(r++);
                int c = 0;
                setCell(row, c++, ec.getCloudletId());
                setCell(row, c++, ec.isDuplicate() ? "Replica" : "Original");
                setCell(row, c++, ec.getSourceDeviceId());
                setCell(row, c++, ec.getCloudletLength());
                setCell(row, c++, ec.getInputSizeKB());
                setCell(row, c++, ec.getOutputSizeKB());
                setCell(row, c++, ec.getDeadline());
                setCell(row, c++, ec.getArrivalTime());
                setCell(row, c++, vm != null ? vm.getNodeId() : "-");
                setCell(row, c++, vm != null ? vm.getId() : -1);
                setCell(row, c++, vm != null ? vm.getMips() : 0.0);
                setCell(row, c++, vm != null ? vm.getBootTime() : 0.0);
                setCell(row, c++, vm != null ? vm.getAcquisitionDelay() : 0.0);
                setCell(row, c++, uplinkDelay);
                setCell(row, c++, computeTime);
                setCell(row, c++, downlinkDelay);
                setCell(row, c++, dfarmCt);
                setCell(row, c++, execStart);
                setCell(row, c++, execFinish);
                setCell(row, c++, cpuTime);
                setCell(row, c++, status);
                setCell(row, c++, isRejected ? "N/A" : (metDeadline ? "YES" : "NO - MISSED"));
                setCell(row, c++, migration);
                setCell(row, c++, reusedStr);
                setCell(row, c++, vmSearchTimeMs);
                setCell(row, c++, failedSearchTimeMs);
                setCell(row, c, transitionLog);
            }

            autoSizeColumns(sheet, headers.length);
            sheet.createFreezePane(0, 2);

            try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                wb.write(fos);
            }
        }
    }

    private String buildTransitionLog(EdgeCloudlet ec, EdgeVm vm,
                                      double execStart, double execFinish,
                                      boolean isCrossNode, boolean isCloud, boolean isReused) {
        StringBuilder sb = new StringBuilder();
        sb.append("1) Arrived at controller (t=").append(fmt(ec.getArrivalTime())).append("s)");

        if (vm == null) {
            sb.append(" -> 2) DFARM rejected (no VM could meet deadline ").append(ec.getDeadline()).append("s)");
            return sb.toString();
        }

        sb.append(" -> 2) DFARM scheduled to VM #").append(vm.getId())
          .append(" [Node ").append(vm.getNodeId()).append(", ").append(vm.getMips()).append(" MIPS]");

        if (isCloud) {
            sb.append(" via cloud-tier fallback (Source 4 acquireVm)");
        } else if (isCrossNode) {
            sb.append(" via cross-node offload (Source 3 acquireVm)");
        } else if (isReused) {
            sb.append(" (VM re-used from idle pool - no AcqDelay)");
        } else {
            sb.append(" (direct assign / queue adjust)");
        }

        double uplink = vm.computeUplinkDelay(ec.getInputSizeKB(), ec.getDeviceLatencyMs());
        sb.append(" -> 3) Uplink transfer ").append(fmt(uplink)).append("s");

        if (!Double.isNaN(execStart)) {
            sb.append(" -> 4) Execution started (t=").append(fmt(execStart)).append("s)");
        }
        if (!Double.isNaN(execFinish)) {
            sb.append(" -> 5) Completed (t=").append(fmt(execFinish)).append("s)");
            boolean met = execFinish <= ec.getDeadline();
            sb.append(" - deadline ").append(met ? "MET" : "MISSED")
              .append(" [limit: ").append(ec.getDeadline()).append("s]");
        } else {
            sb.append(" -> 5) Result not returned to controller");
        }
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════════════
    // node_<id>_stats.xlsx (one workbook per reported node, incl. cloud)
    // ═════════════════════════════════════════════════════════════════════

    private void exportPerNodeStats() throws IOException {
        Map<String, List<EdgeCloudlet>> nodeCloudlets = new LinkedHashMap<>();
        for (EdgeNode node : reportNodes) nodeCloudlets.put(node.getNodeId(), new ArrayList<>());
        for (Map.Entry<EdgeCloudlet, EdgeVm> e : dfarm.getTaskVmMap().entrySet()) {
            String nodeId = e.getValue().getNodeId();
            nodeCloudlets.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(e.getKey());
        }

        String[] headers = {
            "Cloudlet ID", "Type", "Source Device",
            "VM ID", "VM MIPS", "VM Node",
            "Arrival Time (s)", "Exec Start (s)", "Exec Finish (s)",
            "CPU Time (s)", "Status", "Deadline (s)", "Deadline Met?",
            "Cross-Node Offload", "Cloud Offload"
        };

        for (EdgeNode node : reportNodes) {
            String nodeId = node.getNodeId();
            List<EdgeCloudlet> tasks = nodeCloudlets.getOrDefault(nodeId, List.of());
            tasks.sort(Comparator.comparingInt(EdgeCloudlet::getCloudletId));

            Path outPath = runDir.resolve("node_" + nodeId + "_stats.xlsx");
            try (XSSFWorkbook wb = new XSSFWorkbook()) {
                Sheet sheet = wb.createSheet("Node_" + nodeId + "_Cloudlets");
                int r = writeTitle(wb, sheet, 0, "Edge Node " + nodeId + " — Cloudlet Execution Log");
                writeHeaderRow(wb, sheet, r, headers);
                r++;

                for (EdgeCloudlet ec : tasks) {
                    EdgeVm vm = dfarm.getTaskVmMap().get(ec);
                    Cloudlet finished = receivedById.get(ec.getCloudletId());
                    boolean isCrossNode = crossNodeIds.contains(ec.getCloudletId());
                    boolean isCloud     = cloudIds.contains(ec.getCloudletId());

                    double execStart  = finished != null ? finished.getExecStartTime()  : Double.NaN;
                    double execFinish = finished != null ? finished.getExecFinishTime() : Double.NaN;
                    double cpuTime    = finished != null ? finished.getActualCPUTime()  : Double.NaN;
                    String status     = finished != null ? finished.getStatus().toString() : "NOT_RETURNED";
                    boolean metDeadline = !Double.isNaN(execFinish) && execFinish <= ec.getDeadline();

                    Row row = sheet.createRow(r++);
                    int c = 0;
                    setCell(row, c++, ec.getCloudletId());
                    setCell(row, c++, ec.isDuplicate() ? "Replica" : "Original");
                    setCell(row, c++, ec.getSourceDeviceId());
                    setCell(row, c++, vm != null ? vm.getId() : -1);
                    setCell(row, c++, vm != null ? vm.getMips() : 0.0);
                    setCell(row, c++, vm != null ? vm.getNodeId() : "?");
                    setCell(row, c++, ec.getArrivalTime());
                    setCell(row, c++, execStart);
                    setCell(row, c++, execFinish);
                    setCell(row, c++, cpuTime);
                    setCell(row, c++, status);
                    setCell(row, c++, ec.getDeadline());
                    setCell(row, c++, metDeadline ? "YES" : "NO");
                    setCell(row, c++, isCrossNode ? "YES" : "No");
                    setCell(row, c, isCloud ? "YES" : "No");
                }

                long met = tasks.stream().filter(ec -> {
                    Cloudlet f = receivedById.get(ec.getCloudletId());
                    return f != null && f.getExecFinishTime() <= ec.getDeadline();
                }).count();
                long crossCnt = tasks.stream().filter(ec -> crossNodeIds.contains(ec.getCloudletId())).count();
                long cloudCnt = tasks.stream().filter(ec -> cloudIds.contains(ec.getCloudletId())).count();

                r++;
                r = writeRow(wb, sheet, r, "Total assigned to Node " + nodeId, tasks.size());
                r = writeRow(wb, sheet, r, "Deadline met", met);
                r = writeRow(wb, sheet, r, "Cross-node offloaded", crossCnt);
                writeRow(wb, sheet, r, "Cloud offloaded", cloudCnt);

                autoSizeColumns(sheet, headers.length);
                sheet.createFreezePane(0, 2);

                try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                    wb.write(fos);
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // POI helpers
    // ═════════════════════════════════════════════════════════════════════

    /** Bold banner row spanning columns 0-1, merged. Returns the next free row index. */
    private int writeTitle(XSSFWorkbook wb, Sheet sheet, int rowIdx, String title) {
        Row row = sheet.createRow(rowIdx);
        Cell cell = row.createCell(0);
        cell.setCellValue(title);
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 13);
        style.setFont(font);
        cell.setCellStyle(style);
        sheet.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, 1));
        return rowIdx + 1;
    }

    /** Bold section-header row (single "[Section]" cell in column 0). Returns the next free row index. */
    private int writeSectionTitle(XSSFWorkbook wb, Sheet sheet, int rowIdx, String title) {
        Row row = sheet.createRow(rowIdx);
        Cell cell = row.createCell(0);
        cell.setCellValue("[" + title + "]");
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        cell.setCellStyle(style);
        return rowIdx + 1;
    }

    /** Metric/Value row. Returns the next free row index. */
    private int writeRow(XSSFWorkbook wb, Sheet sheet, int rowIdx, String metric, Object value) {
        Row row = sheet.createRow(rowIdx);
        setCell(row, 0, metric);
        setCell(row, 1, value);
        return rowIdx + 1;
    }

    private void writeHeaderRow(XSSFWorkbook wb, Sheet sheet, int rowIdx, String[] headers) {
        Row hdr = sheet.createRow(rowIdx);
        CellStyle style = headerStyle(wb);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = hdr.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
    }

    private void setCell(Row row, int col, Object value) {
        Cell cell = row.createCell(col);
        if (value == null) return;
        if (value instanceof Double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) return;
            cell.setCellValue(d);
        } else if (value instanceof Integer i) {
            cell.setCellValue(i);
        } else if (value instanceof Long l) {
            cell.setCellValue(l);
        } else if (value instanceof Boolean b) {
            cell.setCellValue(b);
        } else {
            cell.setCellValue(value.toString());
        }
    }

    private CellStyle headerStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private void autoSizeColumns(Sheet sheet, int count) {
        for (int c = 0; c < count; c++) {
            sheet.autoSizeColumn(c);
            if (sheet.getColumnWidth(c) > 60 * 256) sheet.setColumnWidth(c, 60 * 256);
        }
    }

    private String fmt(double v) {
        if (Double.isNaN(v)) return "-";
        return String.format("%.4f", v);
    }
}
