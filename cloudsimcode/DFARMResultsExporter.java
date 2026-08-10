package org.cloudbus.cloudsim.examples;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.cloudbus.cloudsim.*;

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
 * Exports DFARM simulation results to xlsx files under a timestamped run folder.
 *
 * Output layout:
 *   C:\MINIPROJECT\DFARM_EDGE_RESULTS\<yyyyMMdd_HHmmss>\
 *     node_A_stats.xlsx      — per-node cloudlet log for Node A
 *     node_B_stats.xlsx      — per-node cloudlet log for Node B
 *     node_C_stats.xlsx      — per-node cloudlet log for Node C
 *     task_journey.xlsx      — full journey of every task
 *     simulation_summary.xlsx — DFARM metrics, VM breakdown, topology
 */
public class DFARMResultsExporter {

    private static final String BASE_DIR = "C:\\MINIPROJECT\\DFARM_EDGE_RESULTS";
    private static final DateTimeFormatter FOLDER_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path runDir;
    private final EdgeDFARMBroker broker;
    private final DFARMScheduler  dfarm;
    private final List<EdgeNode>  edgeNodes;
    private final List<EdgeVm>    allVms;
    private final int             totalOriginalTasks;

    // Derived lookup maps built once and reused across all writers
    private final Map<Integer, EdgeVm>        vmById;           // vmId → EdgeVm
    private final Map<Integer, EdgeCloudlet>  cloudletById;     // cloudletId → EdgeCloudlet
    private final Map<Integer, Cloudlet>      receivedById;     // cloudletId → finished Cloudlet
    private final Set<Integer>                crossNodeIds;     // cloudlet IDs offloaded cross-node
    private final Set<Integer>                reusedVmIds;      // VM IDs re-used from idle pool

    public DFARMResultsExporter(EdgeDFARMBroker broker,
                                List<EdgeNode> edgeNodes,
                                List<EdgeVm> allVms) {
        this.broker             = broker;
        this.dfarm              = broker.getDfarm();
        this.edgeNodes          = edgeNodes;
        this.allVms             = allVms;
        this.totalOriginalTasks = broker.getTotalOriginalTasks();

        // Create timestamped run directory
        String ts = LocalDateTime.now().format(FOLDER_FMT);
        this.runDir = Paths.get(BASE_DIR, ts);
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create results directory: " + runDir, e);
        }

        // Build lookup maps
        this.vmById = new HashMap<>();
        for (EdgeVm vm : allVms) vmById.put(vm.getId(), vm);

        this.cloudletById = new HashMap<>();
        for (Map.Entry<EdgeCloudlet, EdgeVm> e : dfarm.getTaskVmMap().entrySet()) {
            cloudletById.put(e.getKey().getCloudletId(), e.getKey());
        }
        // Include rejected tasks in the map
        for (EdgeCloudlet ec : dfarm.getRejectedTasks()) {
            cloudletById.put(ec.getCloudletId(), ec);
        }

        this.receivedById = new HashMap<>();
        for (Cloudlet c : broker.getCloudletReceivedList()) {
            receivedById.put(c.getCloudletId(), c);
        }

        this.crossNodeIds = dfarm.getCrossNodeCloudletIds();
        this.reusedVmIds  = dfarm.getReusedVmIds();
    }

    /** Export all four report files. Returns the run directory path. */
    public Path exportAll() throws IOException {
        exportPerNodeStats();
        exportTaskJourney();
        exportSimulationSummary();
        System.out.println("[DFARMResultsExporter] Results written to: " + runDir.toAbsolutePath());
        return runDir;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // File 1 — Per-node stats (one file per node)
    // ═════════════════════════════════════════════════════════════════════════

    private void exportPerNodeStats() throws IOException {
        // Build node → assigned cloudlets map from taskVmMap
        Map<String, List<EdgeCloudlet>> nodeCloudlets = new LinkedHashMap<>();
        for (EdgeNode node : edgeNodes) {
            nodeCloudlets.put(node.getNodeId(), new ArrayList<>());
        }
        for (Map.Entry<EdgeCloudlet, EdgeVm> e : dfarm.getTaskVmMap().entrySet()) {
            String nodeId = e.getValue().getNodeId();
            nodeCloudlets.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(e.getKey());
        }

        for (EdgeNode node : edgeNodes) {
            String nodeId = node.getNodeId();
            List<EdgeCloudlet> tasks = nodeCloudlets.getOrDefault(nodeId, List.of());

            try (XSSFWorkbook wb = new XSSFWorkbook()) {
                Sheet sheet = wb.createSheet("Node_" + nodeId + "_Cloudlets");

                // Title row
                Row title = sheet.createRow(0);
                mergeTitleCell(wb, sheet, title, 0, 13,
                        "Edge Node " + nodeId + " — Cloudlet Execution Log");

                // Header row
                String[] headers = {
                    "Cloudlet ID", "Type", "Source Device",
                    "VM ID", "VM MIPS", "VM Node",
                    "Arrival Time (s)", "Exec Start (s)", "Exec Finish (s)",
                    "CPU Time (s)", "Status", "Deadline (s)", "Deadline Met?",
                    "Cross-Node Offload"
                };
                Row hdr = sheet.createRow(1);
                CellStyle hdrStyle = headerStyle(wb);
                for (int c = 0; c < headers.length; c++) {
                    Cell cell = hdr.createCell(c);
                    cell.setCellValue(headers[c]);
                    cell.setCellStyle(hdrStyle);
                }

                // Data rows — sort by cloudlet ID
                tasks.sort(Comparator.comparingInt(EdgeCloudlet::getCloudletId));
                int rowNum = 2;
                CellStyle okStyle  = colorStyle(wb, IndexedColors.LIGHT_GREEN);
                CellStyle missStyle = colorStyle(wb, IndexedColors.ROSE);
                CellStyle crossStyle = colorStyle(wb, IndexedColors.LIGHT_YELLOW);

                for (EdgeCloudlet ec : tasks) {
                    EdgeVm vm = dfarm.getTaskVmMap().get(ec);
                    Cloudlet finished = receivedById.get(ec.getCloudletId());
                    boolean isCrossNode = crossNodeIds.contains(ec.getCloudletId());

                    double execStart  = finished != null ? finished.getExecStartTime()  : Double.NaN;
                    double execFinish = finished != null ? finished.getExecFinishTime() : Double.NaN;
                    double cpuTime    = finished != null ? finished.getActualCPUTime()  : Double.NaN;
                    String status     = finished != null
                            ? finished.getStatus().toString() : "NOT_RETURNED";
                    boolean metDeadline = !Double.isNaN(execFinish) && execFinish <= ec.getDeadline();

                    Row row = sheet.createRow(rowNum++);
                    CellStyle rowStyle = isCrossNode ? crossStyle
                            : (metDeadline ? okStyle : missStyle);

                    setCell(row, 0, ec.getCloudletId());
                    setCell(row, 1, ec.isDuplicate() ? "Replica" : "Original");
                    setCell(row, 2, ec.getSourceDeviceId());
                    setCell(row, 3, vm != null ? vm.getId() : -1);
                    setCell(row, 4, vm != null ? vm.getMips() : 0.0);
                    setCell(row, 5, vm != null ? vm.getNodeId() : "?");
                    setCell(row, 6, ec.getArrivalTime());
                    setCell(row, 7, execStart);
                    setCell(row, 8, execFinish);
                    setCell(row, 9, cpuTime);
                    setCell(row, 10, status);
                    setCell(row, 11, ec.getDeadline());
                    setCell(row, 12, metDeadline ? "YES" : "NO");
                    setCell(row, 13, isCrossNode ? "YES — cross-node" : "No");

                    // Apply row colour to all cells
                    for (int c = 0; c <= 13; c++) {
                        Cell cell = row.getCell(c);
                        if (cell == null) cell = row.createCell(c);
                        cell.setCellStyle(rowStyle);
                        // Re-apply value after style (style must be set before value for colour)
                    }
                    // Re-write values since setCellStyle can clear content
                    setCell(row, 0, ec.getCloudletId());
                    setCell(row, 1, ec.isDuplicate() ? "Replica" : "Original");
                    setCell(row, 2, ec.getSourceDeviceId());
                    setCell(row, 3, vm != null ? vm.getId() : -1);
                    setCell(row, 4, vm != null ? vm.getMips() : 0.0);
                    setCell(row, 5, vm != null ? vm.getNodeId() : "?");
                    setCell(row, 6, ec.getArrivalTime());
                    setCell(row, 7, execStart);
                    setCell(row, 8, execFinish);
                    setCell(row, 9, cpuTime);
                    setCell(row, 10, status);
                    setCell(row, 11, ec.getDeadline());
                    setCell(row, 12, metDeadline ? "YES" : "NO");
                    setCell(row, 13, isCrossNode ? "YES — cross-node" : "No");
                }

                // Summary mini-block
                int sumRow = rowNum + 1;
                Row s1 = sheet.createRow(sumRow);
                setCell(s1, 0, "Total assigned to Node " + nodeId);
                setCell(s1, 1, (double) tasks.size());

                Row s2 = sheet.createRow(sumRow + 1);
                long met = tasks.stream().filter(ec -> {
                    Cloudlet f = receivedById.get(ec.getCloudletId());
                    return f != null && f.getExecFinishTime() <= ec.getDeadline();
                }).count();
                setCell(s2, 0, "Deadline met");
                setCell(s2, 1, (double) met);

                Row s3 = sheet.createRow(sumRow + 2);
                long crossCnt = tasks.stream()
                        .filter(ec -> crossNodeIds.contains(ec.getCloudletId())).count();
                setCell(s3, 0, "Cross-node offloaded");
                setCell(s3, 1, (double) crossCnt);

                autoSizeColumns(sheet, headers.length);

                Path outPath = runDir.resolve("node_" + nodeId + "_stats.xlsx");
                try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                    wb.write(fos);
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // File 2 — Task journey (all tasks, one row per task)
    // ═════════════════════════════════════════════════════════════════════════

    private void exportTaskJourney() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Task_Journey");

            String[] headers = {
                "Cloudlet ID", "Type", "Source Device",
                "MI", "Input KB", "Output KB", "Deadline (s)", "Arrival Time (s)",
                "Assigned Node", "VM ID", "VM MIPS", "VM Boot (s)", "VM AcqDelay (s)",
                "Uplink Delay (s)", "Compute Time (s)", "Downlink Delay (s)",
                "DFARM CT Estimate (s)",
                "Exec Start (s)", "Exec Finish (s)", "Actual CPU (s)",
                "CloudSim Status", "Deadline Met?",
                "Migration / Offload", "VM Reused (no AcqDelay)?",
                "Transition Log"
            };

            Row title = sheet.createRow(0);
            mergeTitleCell(wb, sheet, title, 0, headers.length - 1,
                    "DFARM Edge — Full Task Journey Log");

            Row hdr = sheet.createRow(1);
            CellStyle hdrStyle = headerStyle(wb);
            for (int c = 0; c < headers.length; c++) {
                Cell cell = hdr.createCell(c);
                cell.setCellValue(headers[c]);
                cell.setCellStyle(hdrStyle);
            }

            // Collect all tasks: assigned + rejected (originals only, then replicas)
            List<EdgeCloudlet> allTasks = new ArrayList<>();
            // Assigned originals
            for (Map.Entry<EdgeCloudlet, EdgeVm> e : dfarm.getTaskVmMap().entrySet()) {
                allTasks.add(e.getKey());
            }
            // Rejected (not in taskVmMap)
            for (EdgeCloudlet ec : dfarm.getRejectedTasks()) {
                if (!dfarm.getTaskVmMap().containsKey(ec)) {
                    allTasks.add(ec);
                }
            }
            allTasks.sort(Comparator.comparingInt(EdgeCloudlet::getCloudletId));

            int rowNum = 2;
            CellStyle okStyle    = colorStyle(wb, IndexedColors.LIGHT_GREEN);
            CellStyle missStyle  = colorStyle(wb, IndexedColors.ROSE);
            CellStyle rejectStyle = colorStyle(wb, IndexedColors.GREY_25_PERCENT);
            CellStyle crossStyle = colorStyle(wb, IndexedColors.LIGHT_YELLOW);

            for (EdgeCloudlet ec : allTasks) {
                EdgeVm vm = dfarm.getTaskVmMap().get(ec);
                Cloudlet finished = receivedById.get(ec.getCloudletId());
                boolean isCrossNode = crossNodeIds.contains(ec.getCloudletId());
                boolean isReused    = vm != null && reusedVmIds.contains(vm.getId());
                boolean isRejected  = vm == null;

                double uplinkDelay  = vm != null ? vm.computeUplinkDelay(ec.getInputSizeKB())   : Double.NaN;
                double computeTime  = vm != null ? ec.getCloudletLength() / vm.getMips()         : Double.NaN;
                double downlinkDelay = vm != null ? vm.computeDownlinkDelay(ec.getOutputSizeKB()) : Double.NaN;
                double dfarmCt      = vm != null ? dfarm.getVmCtMap().getOrDefault(vm, Double.NaN) : Double.NaN;

                double execStart  = finished != null ? finished.getExecStartTime()  : Double.NaN;
                double execFinish = finished != null ? finished.getExecFinishTime() : Double.NaN;
                double cpuTime    = finished != null ? finished.getActualCPUTime()  : Double.NaN;
                String status     = isRejected ? "REJECTED"
                        : (finished != null ? finished.getStatus().toString() : "NOT_RETURNED");
                boolean metDeadline = !Double.isNaN(execFinish) && execFinish <= ec.getDeadline();

                String migration = isRejected ? "N/A (rejected)"
                        : isCrossNode ? "Cross-node offload" : "Same-node";
                String reusedStr = isReused ? "YES (boot only, no AcqDelay)" : "No";

                // Build transition log string
                String transitionLog = buildTransitionLog(ec, vm, execStart, execFinish, isCrossNode, isReused);

                CellStyle rowStyle;
                if (isRejected)      rowStyle = rejectStyle;
                else if (isCrossNode) rowStyle = crossStyle;
                else if (metDeadline) rowStyle = okStyle;
                else                  rowStyle = missStyle;

                Row row = sheet.createRow(rowNum++);
                // Apply style first, then set values
                for (int c = 0; c < headers.length; c++) {
                    row.createCell(c).setCellStyle(rowStyle);
                }

                setCell(row, 0,  ec.getCloudletId());
                setCell(row, 1,  ec.isDuplicate() ? "Replica" : "Original");
                setCell(row, 2,  ec.getSourceDeviceId());
                setCell(row, 3,  (double) ec.getCloudletLength());
                setCell(row, 4,  ec.getInputSizeKB());
                setCell(row, 5,  ec.getOutputSizeKB());
                setCell(row, 6,  ec.getDeadline());
                setCell(row, 7,  ec.getArrivalTime());
                setCell(row, 8,  vm != null ? vm.getNodeId() : "—");
                setCell(row, 9,  vm != null ? vm.getId() : -1);
                setCell(row, 10, vm != null ? vm.getMips() : 0.0);
                setCell(row, 11, vm != null ? vm.getBootTime() : 0.0);
                setCell(row, 12, vm != null ? vm.getAcquisitionDelay() : 0.0);
                setCell(row, 13, uplinkDelay);
                setCell(row, 14, computeTime);
                setCell(row, 15, downlinkDelay);
                setCell(row, 16, dfarmCt);
                setCell(row, 17, execStart);
                setCell(row, 18, execFinish);
                setCell(row, 19, cpuTime);
                setCell(row, 20, status);
                setCell(row, 21, isRejected ? "N/A" : (metDeadline ? "YES" : "NO — MISSED"));
                setCell(row, 22, migration);
                setCell(row, 23, reusedStr);
                setCell(row, 24, transitionLog);
            }

            autoSizeColumns(sheet, headers.length);

            Path outPath = runDir.resolve("task_journey.xlsx");
            try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                wb.write(fos);
            }
        }
    }

    /**
     * Human-readable step-by-step description of a task's journey through DFARM.
     */
    private String buildTransitionLog(EdgeCloudlet ec, EdgeVm vm,
                                      double execStart, double execFinish,
                                      boolean isCrossNode, boolean isReused) {
        StringBuilder sb = new StringBuilder();
        sb.append("1) Arrived at broker (t=").append(fmt(ec.getArrivalTime())).append("s)");

        if (vm == null) {
            sb.append(" → 2) DFARM rejected (no VM could meet deadline ").append(ec.getDeadline()).append("s)");
            return sb.toString();
        }

        sb.append(" → 2) DFARM scheduled to VM #").append(vm.getId())
          .append(" [Node ").append(vm.getNodeId()).append(", ").append(vm.getMips()).append(" MIPS]");

        if (isCrossNode) {
            sb.append(" via cross-node offload (Phase 3 acquireVm)");
        } else if (isReused) {
            sb.append(" (VM re-used from idle pool — no AcqDelay)");
        } else {
            sb.append(" (direct assign / queue adjust)");
        }

        double uplink = vm.computeUplinkDelay(ec.getInputSizeKB());
        sb.append(" → 3) Uplink transfer ").append(fmt(uplink)).append("s");

        if (!Double.isNaN(execStart)) {
            sb.append(" → 4) Execution started (t=").append(fmt(execStart)).append("s)");
        }
        if (!Double.isNaN(execFinish)) {
            sb.append(" → 5) Completed (t=").append(fmt(execFinish)).append("s)");
            boolean met = execFinish <= ec.getDeadline();
            sb.append(" — deadline ").append(met ? "MET" : "MISSED")
              .append(" [limit: ").append(ec.getDeadline()).append("s]");
        } else {
            sb.append(" → 5) Result not returned to broker");
        }
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // File 3 — Simulation summary (multi-sheet)
    // ═════════════════════════════════════════════════════════════════════════

    private void exportSimulationSummary() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            writeOverallStatsSheet(wb);
            writeVmBreakdownSheet(wb);
            writeTopologySheet(wb);
            writeRejectedTasksSheet(wb);

            Path outPath = runDir.resolve("simulation_summary.xlsx");
            try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                wb.write(fos);
            }
        }
    }

    private void writeOverallStatsSheet(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("Overall_Statistics");
        CellStyle labelStyle = boldStyle(wb);
        CellStyle hdrStyle   = headerStyle(wb);

        int r = 0;

        // Section: DFARM Parameters
        Row secHdr = sheet.createRow(r++);
        Cell h1 = secHdr.createCell(0);
        h1.setCellValue("DFARM Configuration Parameters");
        h1.setCellStyle(hdrStyle);

        int rejected = (int) dfarm.getRejectedTasks().stream()
                .filter(t -> !t.isDuplicate()).count();
        int accepted = totalOriginalTasks - rejected;
        long replicas = dfarm.getTaskVmMap().keySet().stream()
                .filter(EdgeCloudlet::isDuplicate).count();

        Object[][] params = {
            {"replRatio (replication threshold)",   "10%"},
            {"allowTaskToRun (soft-line)",           "false (hard-line — rejected tasks dropped)"},
            {"deepSearch (aggressive adjustment)",   "true"},
            {"Edge nodes",                           edgeNodes.size()},
            {"Total VMs",                            allVms.size()},
        };
        for (Object[] row : params) {
            Row dataRow = sheet.createRow(r++);
            Cell lbl = dataRow.createCell(0); lbl.setCellValue((String) row[0]); lbl.setCellStyle(labelStyle);
            setCell(dataRow, 1, row[1]);
        }

        r++; // blank row

        // Section: Task Statistics
        Row sec2 = sheet.createRow(r++);
        Cell h2 = sec2.createCell(0);
        h2.setCellValue("Task Statistics");
        h2.setCellStyle(hdrStyle);

        Object[][] taskStats = {
            {"Total original tasks submitted",        (double) totalOriginalTasks},
            {"Replica tasks created (fault tolerance)", (double) replicas},
            {"Total tasks sent to simulation",         (double)(totalOriginalTasks + replicas)},
            {"Accepted (original, met deadline)",       (double) accepted},
            {"Rejected (original, missed deadline)",    (double) rejected},
            {"Cross-node offloaded tasks",              (double) crossNodeIds.size()},
        };
        for (Object[] row : taskStats) {
            Row dataRow = sheet.createRow(r++);
            Cell lbl = dataRow.createCell(0); lbl.setCellValue((String) row[0]); lbl.setCellStyle(labelStyle);
            setCell(dataRow, 1, row[1]);
        }

        r++;

        // Section: DFARM Performance Metrics
        Row sec3 = sheet.createRow(r++);
        Cell h3 = sec3.createCell(0);
        h3.setCellValue("DFARM Performance Metrics");
        h3.setCellStyle(hdrStyle);

        double makespan   = dfarm.computeMakespan();
        double trr        = dfarm.computeTRR(totalOriginalTasks);
        double throughput = dfarm.computeThroughput(totalOriginalTasks);
        double arur       = dfarm.computeARUR();

        // Count deadline hits from received cloudlets
        long deadlineMet = broker.getCloudletReceivedList().stream()
                .filter(c -> c instanceof EdgeCloudlet)
                .filter(c -> !((EdgeCloudlet) c).isDuplicate())
                .filter(c -> c.getStatus() == Cloudlet.CloudletStatus.SUCCESS)
                .filter(c -> {
                    EdgeCloudlet ec = (EdgeCloudlet) c;
                    return c.getExecFinishTime() <= ec.getDeadline();
                })
                .count();

        Object[][] metrics = {
            {"TRR — Task Rejection Rate",            String.format("%.4f (%.1f%%)", trr, trr * 100)},
            {"Makespan (max VM CT across all VMs)",  String.format("%.4f s", makespan)},
            {"Throughput (accepted / makespan)",      String.format("%.6f tasks/s", throughput)},
            {"ARUR — Avg Resource Utilisation Rate", String.format("%.4f", arur)},
            {"Deadline met (returned originals)",     String.format("%d / %d", deadlineMet, accepted)},
        };
        for (Object[] row : metrics) {
            Row dataRow = sheet.createRow(r++);
            Cell lbl = dataRow.createCell(0); lbl.setCellValue((String) row[0]); lbl.setCellStyle(labelStyle);
            setCell(dataRow, 1, row[1]);
        }

        sheet.setColumnWidth(0, 50 * 256);
        sheet.setColumnWidth(1, 35 * 256);
    }

    private void writeVmBreakdownSheet(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("VM_CT_Breakdown");

        String[] headers = {
            "VM ID", "Node", "MIPS", "Boot Time (s)", "Acq Delay (s)",
            "Reused (no AcqDelay)?", "Tasks Assigned", "DFARM CT (s)",
            "Wireless BW (KBps)", "Inter-Node BW (KBps)"
        };
        Row hdr = sheet.createRow(0);
        CellStyle hdrStyle = headerStyle(wb);
        for (int c = 0; c < headers.length; c++) {
            Cell cell = hdr.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(hdrStyle);
        }

        // Count tasks per VM
        Map<EdgeVm, Integer> taskCount = new HashMap<>();
        for (EdgeVm vm : dfarm.getVmCtMap().keySet()) taskCount.put(vm, 0);
        for (Map.Entry<EdgeCloudlet, EdgeVm> e : dfarm.getTaskVmMap().entrySet()) {
            taskCount.merge(e.getValue(), 1, Integer::sum);
        }

        List<EdgeVm> sortedVms = dfarm.getVmCtMap().keySet().stream()
                .sorted(Comparator.comparing(EdgeVm::getNodeId)
                        .thenComparingInt(EdgeVm::getId))
                .collect(Collectors.toList());

        int rowNum = 1;
        CellStyle reusedStyle = colorStyle(wb, IndexedColors.LIGHT_YELLOW);

        for (EdgeVm vm : sortedVms) {
            boolean isReused = reusedVmIds.contains(vm.getId());
            double ct = dfarm.getVmCtMap().get(vm);
            int tasks = taskCount.getOrDefault(vm, 0);

            Row row = sheet.createRow(rowNum++);
            if (isReused) {
                for (int c = 0; c < headers.length; c++) row.createCell(c).setCellStyle(reusedStyle);
            }

            setCell(row, 0, vm.getId());
            setCell(row, 1, vm.getNodeId());
            setCell(row, 2, vm.getMips());
            setCell(row, 3, vm.getBootTime());
            setCell(row, 4, vm.getAcquisitionDelay());
            setCell(row, 5, isReused ? "YES" : "No");
            setCell(row, 6, (double) tasks);
            setCell(row, 7, ct);
            setCell(row, 8, vm.getWirelessBandwidthKBps());
            setCell(row, 9, vm.getInterNodeBandwidthKBps());
        }

        autoSizeColumns(sheet, headers.length);
    }

    private void writeTopologySheet(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("Topology");

        CellStyle hdrStyle   = headerStyle(wb);
        CellStyle labelStyle = boldStyle(wb);

        int r = 0;
        Row hdr = sheet.createRow(r++);
        Cell h = hdr.createCell(0);
        h.setCellValue("Edge Node Topology");
        h.setCellStyle(hdrStyle);

        String[] colHdrs = {"Node ID", "VM Count", "Max MIPS", "Boot Time (s)",
                            "Acq Delay (s)", "Wireless BW (Mbps)", "Inter-Node BW (Mbps)",
                            "Neighbours"};
        Row chRow = sheet.createRow(r++);
        for (int c = 0; c < colHdrs.length; c++) {
            Cell cell = chRow.createCell(c);
            cell.setCellValue(colHdrs[c]);
            cell.setCellStyle(labelStyle);
        }

        for (EdgeNode node : edgeNodes) {
            List<EdgeVm> nodeVms = allVms.stream()
                    .filter(v -> v.getNodeId().equals(node.getNodeId()))
                    .collect(Collectors.toList());

            double maxMips  = nodeVms.stream().mapToDouble(EdgeVm::getMips).max().orElse(0);
            double bootTime = nodeVms.isEmpty() ? 0 : nodeVms.get(0).getBootTime();
            double acqDelay = nodeVms.isEmpty() ? 0 : nodeVms.get(0).getAcquisitionDelay();
            double wirelessMbps   = node.getWirelessBandwidthKBps()  * 8 / 1024;
            double interNodeMbps  = node.getInterNodeBandwidthKBps() * 8 / 1024;
            String neighbours = node.getNeighborNodes().stream()
                    .map(EdgeNode::getNodeId)
                    .collect(Collectors.joining(", "));

            Row row = sheet.createRow(r++);
            setCell(row, 0, node.getNodeId());
            setCell(row, 1, (double) nodeVms.size());
            setCell(row, 2, maxMips);
            setCell(row, 3, bootTime);
            setCell(row, 4, acqDelay);
            setCell(row, 5, wirelessMbps);
            setCell(row, 6, interNodeMbps);
            setCell(row, 7, neighbours);
        }

        autoSizeColumns(sheet, colHdrs.length);
    }

    private void writeRejectedTasksSheet(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("Rejected_Tasks");

        String[] headers = {"Cloudlet ID", "Type", "Source Device",
                "MI", "Input KB", "Output KB", "Deadline (s)", "Arrival Time (s)",
                "Reason"};
        Row hdr = sheet.createRow(0);
        CellStyle hdrStyle   = headerStyle(wb);
        CellStyle rejectStyle = colorStyle(wb, IndexedColors.ROSE);
        for (int c = 0; c < headers.length; c++) {
            Cell cell = hdr.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(hdrStyle);
        }

        List<EdgeCloudlet> rejected = dfarm.getRejectedTasks().stream()
                .filter(t -> !t.isDuplicate())
                .sorted(Comparator.comparingInt(EdgeCloudlet::getCloudletId))
                .collect(Collectors.toList());

        int rowNum = 1;
        for (EdgeCloudlet ec : rejected) {
            Row row = sheet.createRow(rowNum++);
            for (int c = 0; c < headers.length; c++) row.createCell(c).setCellStyle(rejectStyle);

            setCell(row, 0, ec.getCloudletId());
            setCell(row, 1, ec.isDuplicate() ? "Replica" : "Original");
            setCell(row, 2, ec.getSourceDeviceId());
            setCell(row, 3, (double) ec.getCloudletLength());
            setCell(row, 4, ec.getInputSizeKB());
            setCell(row, 5, ec.getOutputSizeKB());
            setCell(row, 6, ec.getDeadline());
            setCell(row, 7, ec.getArrivalTime());
            setCell(row, 8, "No VM could schedule task within deadline " + ec.getDeadline() + "s");
        }

        autoSizeColumns(sheet, headers.length);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // POI helpers
    // ═════════════════════════════════════════════════════════════════════════

    private void setCell(Row row, int col, Object value) {
        Cell cell = row.getCell(col);
        if (cell == null) cell = row.createCell(col);
        if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (value instanceof String s) {
            if (s.equals("NaN") || s.isEmpty()) cell.setCellValue("—");
            else cell.setCellValue(s);
        } else if (value != null) {
            cell.setCellValue(value.toString());
        } else {
            cell.setCellValue("—");
        }
        // Handle NaN doubles
        if (value instanceof Double d && Double.isNaN(d)) {
            cell.setCellValue("—");
        }
    }

    private void mergeTitleCell(XSSFWorkbook wb, Sheet sheet, Row row,
                                int firstCol, int lastCol, String text) {
        Cell cell = row.createCell(firstCol);
        cell.setCellValue(text);
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 13);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font white = wb.createFont();
        white.setBold(true);
        white.setFontHeightInPoints((short) 13);
        white.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(white);
        cell.setCellStyle(style);
        sheet.addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), firstCol, lastCol));
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

    private CellStyle boldStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private CellStyle colorStyle(XSSFWorkbook wb, IndexedColors color) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(color.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private void autoSizeColumns(Sheet sheet, int count) {
        for (int c = 0; c < count; c++) {
            sheet.autoSizeColumn(c);
            // Cap at 60 chars wide
            int width = sheet.getColumnWidth(c);
            if (width > 60 * 256) sheet.setColumnWidth(c, 60 * 256);
        }
    }

    private String fmt(double v) {
        if (Double.isNaN(v)) return "—";
        return String.format("%.4f", v);
    }
}
