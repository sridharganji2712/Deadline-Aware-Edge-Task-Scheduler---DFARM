package org.cloudbus.cloudsim.examples;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.cloudbus.cloudsim.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates algorithm_comparison.xlsx — a multi-sheet workbook that places
 * DFARM and all four baseline algorithms side-by-side on every key metric.
 *
 * Sheets:
 *   1. Summary              — one row per algorithm, all metrics
 *   2. Makespan Chart Data  — makespan per algorithm (for charting in Excel)
 *   3. TRR / Miss-Rate      — rejection/miss rate per algorithm
 *   4. Throughput           — tasks/s per algorithm
 *   5. ARUR                 — resource utilisation per algorithm
 *   6. Per-VM CT Comparison — each VM's CT across all algorithms
 *   7. Per-Node Load        — how many tasks each node received per algorithm
 *   8. Deadline Analysis    — deadline met vs missed per algorithm
 */
public class AlgorithmComparisonExporter {

    private final EdgeDFARMBroker         broker;
    private final List<EdgeSchedulerBase> baselines;   // RS, RR, MCT, RALBA in order
    private final List<EdgeVm>            allVms;
    private final List<EdgeNode>          edgeNodes;
    private final int                     totalOriginalTasks;
    private final Path                    runDir;

    /** Snapshot of metrics collected from DFARM after the simulation. */
    private record DfarmMetrics(
            double makespan, double trr, double throughput, double arur,
            int totalTasks, int accepted, int rejected, long replicas,
            long crossNodeCount, Map<EdgeVm, Double> vmCtMap) {}

    public AlgorithmComparisonExporter(EdgeDFARMBroker broker,
                                       List<EdgeSchedulerBase> baselines,
                                       List<EdgeVm> allVms,
                                       List<EdgeNode> edgeNodes,
                                       Path runDir) {
        this.broker             = broker;
        this.baselines          = baselines;
        this.allVms             = allVms;
        this.edgeNodes          = edgeNodes;
        this.totalOriginalTasks = broker.getTotalOriginalTasks();
        this.runDir             = runDir;
    }

    public void exportComparison() throws IOException {
        DFARMScheduler dfarm = broker.getDfarm();
        int rejected  = (int) dfarm.getRejectedTasks().stream()
                .filter(t -> !t.isDuplicate()).count();
        int accepted  = totalOriginalTasks - rejected;
        long replicas = dfarm.getTaskVmMap().keySet().stream()
                .filter(EdgeCloudlet::isDuplicate).count();

        DfarmMetrics dfarmM = new DfarmMetrics(
                dfarm.computeMakespan(),
                dfarm.computeTRR(totalOriginalTasks),
                dfarm.computeThroughput(totalOriginalTasks),
                dfarm.computeARUR(),
                totalOriginalTasks,
                accepted,
                rejected,
                replicas,
                dfarm.getCrossNodeCloudletIds().size(),
                dfarm.getVmCtMap()
        );

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            writeSummarySheet(wb, dfarmM);
            writeMetricChart(wb, dfarmM, "Makespan_s",
                    "Makespan Comparison (seconds — lower is better)",
                    a -> dfarmM.makespan(),
                    b -> b.computeMakespan());
            writeMetricChart(wb, dfarmM, "TRR_MissRate",
                    "TRR / Miss-Rate Comparison (lower is better)",
                    a -> dfarmM.trr(),
                    b -> b.computeMissRate(totalOriginalTasks));
            writeMetricChart(wb, dfarmM, "Throughput_tasks_s",
                    "Throughput Comparison (tasks/s — higher is better)",
                    a -> dfarmM.throughput(),
                    b -> b.computeThroughput(totalOriginalTasks));
            writeMetricChart(wb, dfarmM, "ARUR",
                    "ARUR Comparison (closer to 1.0 is better)",
                    a -> dfarmM.arur(),
                    b -> b.computeARUR());
            writeVmCtSheet(wb, dfarmM);
            writePerNodeLoadSheet(wb, dfarmM);
            writeDeadlineSheet(wb, dfarmM);

            Path outPath = runDir.resolve("algorithm_comparison.xlsx");
            try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                wb.write(fos);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Sheet 1 — Summary (one row per algorithm)
    // ═════════════════════════════════════════════════════════════════════════

    private void writeSummarySheet(XSSFWorkbook wb, DfarmMetrics dfarmM) {
        Sheet sheet = wb.createSheet("Summary");

        String[] headers = {
            "Algorithm", "Total Tasks", "Accepted / Met", "Rejected / Missed",
            "Miss/Reject Rate (%)", "Makespan (s)", "Throughput (tasks/s)",
            "ARUR", "Replicas Created", "Cross-Node Offloads",
            "Avg Task Cost (s)", "Best VM MIPS Used"
        };

        Row title = sheet.createRow(0);
        mergeTitle(wb, sheet, title, 0, headers.length - 1,
                "DFARM vs Baseline Algorithms — Simulation Comparison");

        Row hdr = sheet.createRow(1);
        CellStyle hdrStyle = headerStyle(wb);
        for (int c = 0; c < headers.length; c++) {
            Cell cell = hdr.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(hdrStyle);
        }

        int rowNum = 2;

        // DFARM row (highlighted — it's the proposed algorithm)
        CellStyle dfarmStyle = algoRowStyle(wb, IndexedColors.LIGHT_CORNFLOWER_BLUE);
        rowNum = writeAlgoRow(wb, sheet, rowNum, "DFARM", dfarmStyle,
                totalOriginalTasks,
                dfarmM.accepted(),
                dfarmM.rejected(),
                dfarmM.trr() * 100,
                dfarmM.makespan(),
                dfarmM.throughput(),
                dfarmM.arur(),
                (int) dfarmM.replicas(),
                (int) dfarmM.crossNodeCount(),
                avgTaskCostDfarm(),
                bestVmMips());

        // Baseline rows
        CellStyle[] baselineStyles = {
            algoRowStyle(wb, IndexedColors.LIGHT_YELLOW),
            algoRowStyle(wb, IndexedColors.LEMON_CHIFFON),
            algoRowStyle(wb, IndexedColors.LIGHT_GREEN),
            algoRowStyle(wb, IndexedColors.TAN),
        };

        for (int i = 0; i < baselines.size(); i++) {
            EdgeSchedulerBase b = baselines.get(i);
            int missed   = (int) b.getDeadlineMissedTasks().stream()
                    .filter(t -> !t.isDuplicate()).count();
            int bAccepted = totalOriginalTasks - missed;
            rowNum = writeAlgoRow(wb, sheet, rowNum, b.getAlgorithmName(),
                    baselineStyles[i % baselineStyles.length],
                    totalOriginalTasks,
                    bAccepted,
                    missed,
                    b.computeMissRate(totalOriginalTasks) * 100,
                    b.computeMakespan(),
                    b.computeThroughput(totalOriginalTasks),
                    b.computeARUR(),
                    0, 0,   // no replicas or cross-node in baselines
                    avgTaskCostBaseline(b),
                    bestVmMips());
        }

        // Delta rows — DFARM improvement over each baseline
        sheet.createRow(rowNum++); // blank separator
        CellStyle deltaStyle = algoRowStyle(wb, IndexedColors.GREY_25_PERCENT);
        CellStyle boldItalic = wb.createCellStyle();
        Font fi = wb.createFont(); fi.setBold(true); fi.setItalic(true);
        boldItalic.setFont(fi);

        Row deltaHdr = sheet.createRow(rowNum++);
        Cell dh = deltaHdr.createCell(0);
        dh.setCellValue("DFARM Improvement vs Baselines (negative = DFARM is better for that metric)");
        dh.setCellStyle(boldItalic);

        for (EdgeSchedulerBase b : baselines) {
            double missImprove     = dfarmM.trr()        - b.computeMissRate(totalOriginalTasks);
            double makespanImprove = dfarmM.makespan()   - b.computeMakespan();
            double thrputImprove   = dfarmM.throughput() - b.computeThroughput(totalOriginalTasks);
            double arurImprove     = dfarmM.arur()       - b.computeARUR();

            Row row = sheet.createRow(rowNum++);
            for (int c = 0; c < headers.length; c++) row.createCell(c).setCellStyle(deltaStyle);

            setCell(row, 0,  "DFARM − " + b.getAlgorithmName());
            setCell(row, 1,  "—");
            setCell(row, 2,  "—");
            setCell(row, 3,  "—");
            setCell(row, 4,  String.format("%.2f%%", missImprove * 100));
            setCell(row, 5,  String.format("%.4f s", makespanImprove));
            setCell(row, 6,  String.format("%.6f t/s", thrputImprove));
            setCell(row, 7,  String.format("%.4f", arurImprove));
            setCell(row, 8,  "—");
            setCell(row, 9,  "—");
            setCell(row, 10, "—");
            setCell(row, 11, "—");
        }

        autoSizeColumns(sheet, headers.length);
    }

    private int writeAlgoRow(XSSFWorkbook wb, Sheet sheet, int rowNum,
                             String algoName, CellStyle style,
                             int total, int accepted, int rejected, double missRatePct,
                             double makespan, double throughput, double arur,
                             int replicas, int crossNode,
                             double avgCost, double bestMips) {
        Row row = sheet.createRow(rowNum);
        for (int c = 0; c <= 11; c++) row.createCell(c).setCellStyle(style);

        setCell(row, 0,  algoName);
        setCell(row, 1,  (double) total);
        setCell(row, 2,  (double) accepted);
        setCell(row, 3,  (double) rejected);
        setCell(row, 4,  String.format("%.2f%%", missRatePct));
        setCell(row, 5,  makespan);
        setCell(row, 6,  throughput);
        setCell(row, 7,  arur);
        setCell(row, 8,  replicas > 0 ? (double) replicas : 0.0);
        setCell(row, 9,  crossNode > 0 ? (double) crossNode : 0.0);
        setCell(row, 10, avgCost);
        setCell(row, 11, bestMips);
        return rowNum + 1;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Sheet 2-5 — Single metric chart-ready sheets
    // ═════════════════════════════════════════════════════════════════════════

    @FunctionalInterface
    private interface DfarmGetter { double get(DfarmMetrics m); }
    @FunctionalInterface
    private interface BaselineGetter { double get(EdgeSchedulerBase b); }

    private void writeMetricChart(XSSFWorkbook wb, DfarmMetrics dfarmM,
                                  String sheetName, String title,
                                  DfarmGetter dfarmGetter,
                                  BaselineGetter baselineGetter) {
        Sheet sheet = wb.createSheet(sheetName);

        Row titleRow = sheet.createRow(0);
        mergeTitle(wb, sheet, titleRow, 0, 1, title);

        Row hdr = sheet.createRow(1);
        CellStyle hdrStyle = headerStyle(wb);
        Cell h0 = hdr.createCell(0); h0.setCellValue("Algorithm"); h0.setCellStyle(hdrStyle);
        Cell h1 = hdr.createCell(1); h1.setCellValue("Value");     h1.setCellStyle(hdrStyle);

        CellStyle dfarmStyle = algoRowStyle(wb, IndexedColors.LIGHT_CORNFLOWER_BLUE);
        CellStyle baseStyle  = algoRowStyle(wb, IndexedColors.LIGHT_YELLOW);

        int r = 2;
        Row dfarmRow = sheet.createRow(r++);
        dfarmRow.createCell(0).setCellStyle(dfarmStyle);
        dfarmRow.createCell(1).setCellStyle(dfarmStyle);
        setCell(dfarmRow, 0, "DFARM");
        setCell(dfarmRow, 1, dfarmGetter.get(dfarmM));

        for (EdgeSchedulerBase b : baselines) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellStyle(baseStyle);
            row.createCell(1).setCellStyle(baseStyle);
            setCell(row, 0, b.getAlgorithmName());
            setCell(row, 1, baselineGetter.get(b));
        }

        sheet.setColumnWidth(0, 20 * 256);
        sheet.setColumnWidth(1, 25 * 256);

        // Note row
        sheet.createRow(r + 1).createCell(0).setCellValue(
                "Tip: Select A2:B" + (r) + " and insert a Clustered Bar/Column chart in Excel.");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Sheet 6 — Per-VM CT across all algorithms
    // ═════════════════════════════════════════════════════════════════════════

    private void writeVmCtSheet(XSSFWorkbook wb, DfarmMetrics dfarmM) {
        Sheet sheet = wb.createSheet("Per_VM_CT");

        // Build sorted VM list
        List<EdgeVm> sortedVms = allVms.stream()
                .sorted(Comparator.comparing(EdgeVm::getNodeId).thenComparingInt(EdgeVm::getId))
                .collect(Collectors.toList());

        // Headers: VM ID | Node | MIPS | DFARM CT | RS CT | RR CT | MCT CT | RALBA CT
        List<String> colHeaders = new ArrayList<>(List.of("VM ID", "Node", "MIPS",
                "DFARM CT (s)"));
        for (EdgeSchedulerBase b : baselines) colHeaders.add(b.getAlgorithmName() + " CT (s)");

        Row titleRow = sheet.createRow(0);
        mergeTitle(wb, sheet, titleRow, 0, colHeaders.size() - 1,
                "Per-VM Completion Time — All Algorithms");

        Row hdr = sheet.createRow(1);
        CellStyle hdrStyle = headerStyle(wb);
        for (int c = 0; c < colHeaders.size(); c++) {
            Cell cell = hdr.createCell(c);
            cell.setCellValue(colHeaders.get(c));
            cell.setCellStyle(hdrStyle);
        }

        int rowNum = 2;
        for (EdgeVm vm : sortedVms) {
            Row row = sheet.createRow(rowNum++);
            setCell(row, 0, vm.getId());
            setCell(row, 1, vm.getNodeId());
            setCell(row, 2, vm.getMips());
            setCell(row, 3, dfarmM.vmCtMap().getOrDefault(vm, 0.0));
            for (int i = 0; i < baselines.size(); i++) {
                double ct = baselines.get(i).getVmCtMap().getOrDefault(vm, 0.0);
                setCell(row, 4 + i, ct);
            }
        }

        // Makespan row
        int sumRow = rowNum + 1;
        Row ms = sheet.createRow(sumRow);
        CellStyle bold = boldStyle(wb);
        Cell mslbl = ms.createCell(0); mslbl.setCellValue("MAKESPAN"); mslbl.setCellStyle(bold);
        setCell(ms, 3, dfarmM.makespan());
        for (int i = 0; i < baselines.size(); i++) {
            setCell(ms, 4 + i, baselines.get(i).computeMakespan());
        }

        autoSizeColumns(sheet, colHeaders.size());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Sheet 7 — Per-node task load
    // ═════════════════════════════════════════════════════════════════════════

    private void writePerNodeLoadSheet(XSSFWorkbook wb, DfarmMetrics dfarmM) {
        Sheet sheet = wb.createSheet("Per_Node_Load");

        List<String> nodeIds = edgeNodes.stream().map(EdgeNode::getNodeId).collect(Collectors.toList());
        List<String> colHeaders = new ArrayList<>(List.of("Node"));
        colHeaders.add("DFARM #tasks");
        for (EdgeSchedulerBase b : baselines) colHeaders.add(b.getAlgorithmName() + " #tasks");

        Row titleRow = sheet.createRow(0);
        mergeTitle(wb, sheet, titleRow, 0, colHeaders.size() - 1,
                "Per-Node Task Load — All Algorithms");

        Row hdr = sheet.createRow(1);
        CellStyle hdrStyle = headerStyle(wb);
        for (int c = 0; c < colHeaders.size(); c++) {
            Cell cell = hdr.createCell(c);
            cell.setCellValue(colHeaders.get(c));
            cell.setCellStyle(hdrStyle);
        }

        int rowNum = 2;
        for (String nodeId : nodeIds) {
            Row row = sheet.createRow(rowNum++);
            setCell(row, 0, "Node " + nodeId);

            // DFARM count: tasks assigned to VMs on this node
            long dfarmCount = broker.getDfarm().getTaskVmMap().entrySet().stream()
                    .filter(e -> !e.getKey().isDuplicate())
                    .filter(e -> e.getValue().getNodeId().equals(nodeId))
                    .count();
            setCell(row, 1, (double) dfarmCount);

            for (int i = 0; i < baselines.size(); i++) {
                long cnt = baselines.get(i).getTaskVmMap().entrySet().stream()
                        .filter(e -> e.getValue().getNodeId().equals(nodeId))
                        .count();
                setCell(row, 2 + i, (double) cnt);
            }
        }

        autoSizeColumns(sheet, colHeaders.size());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Sheet 8 — Deadline analysis (met vs missed)
    // ═════════════════════════════════════════════════════════════════════════

    private void writeDeadlineSheet(XSSFWorkbook wb, DfarmMetrics dfarmM) {
        Sheet sheet = wb.createSheet("Deadline_Analysis");

        String[] headers = {
            "Algorithm", "Total Tasks", "Deadline Met", "Deadline Missed / Rejected",
            "Met (%)", "Missed / Reject Rate (%)",
            "Small (≤1000 MI) Met", "Small Missed",
            "Medium (2000 MI) Met", "Medium Missed",
            "Large (≥5000 MI) Met", "Large Missed"
        };

        Row titleRow = sheet.createRow(0);
        mergeTitle(wb, sheet, titleRow, 0, headers.length - 1,
                "Deadline Analysis — Met vs Missed per Task Category");

        Row hdr = sheet.createRow(1);
        CellStyle hdrStyle = headerStyle(wb);
        for (int c = 0; c < headers.length; c++) {
            Cell cell = hdr.createCell(c);
            cell.setCellValue(headers[c]);
            cell.setCellStyle(hdrStyle);
        }

        int rowNum = 2;

        // DFARM
        CellStyle dfarmStyle = algoRowStyle(wb, IndexedColors.LIGHT_CORNFLOWER_BLUE);
        rowNum = writeDeadlineRow(wb, sheet, rowNum, "DFARM", dfarmStyle,
                computeDfarmDeadlineStats(dfarmM));

        // Baselines
        CellStyle baseStyle = algoRowStyle(wb, IndexedColors.LIGHT_YELLOW);
        for (EdgeSchedulerBase b : baselines) {
            rowNum = writeDeadlineRow(wb, sheet, rowNum, b.getAlgorithmName(), baseStyle,
                    computeBaselineDeadlineStats(b));
        }

        autoSizeColumns(sheet, headers.length);
    }

    /** [total, met, missed, metPct, missedPct, smallMet, smallMissed, medMet, medMissed, largeMet, largeMissed] */
    private double[] computeDfarmDeadlineStats(DfarmMetrics dfarmM) {
        int total   = totalOriginalTasks;
        int missed  = dfarmM.rejected();
        int met     = total - missed;

        // Category breakdown (original tasks only in taskVmMap)
        long[] sm = categoryDeadlineStats(
                broker.getDfarm().getTaskVmMap().entrySet().stream()
                        .filter(e -> !e.getKey().isDuplicate())
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
                broker.getCloudletReceivedList());

        return new double[]{total, met, missed,
                100.0 * met / total, 100.0 * missed / total,
                sm[0], sm[1], sm[2], sm[3], sm[4], sm[5]};
    }

    private double[] computeBaselineDeadlineStats(EdgeSchedulerBase b) {
        int total  = b.getTaskVmMap().size();
        int missed = (int) b.getDeadlineMissedTasks().stream()
                .filter(t -> !t.isDuplicate()).count();
        int met    = total - missed;

        // Category breakdown via estimated timestamps
        long smallMet = 0, smallMissed = 0, medMet = 0, medMissed = 0,
             largeMet = 0, largeMissed = 0;
        for (Map.Entry<EdgeCloudlet, double[]> e : b.getTaskTimestamps().entrySet()) {
            EdgeCloudlet ec = e.getKey();
            double finish   = e.getValue()[1];
            boolean metD    = finish <= ec.getDeadline();
            long mi = ec.getCloudletLength();
            if      (mi <= 1000) { if (metD) smallMet++; else smallMissed++; }
            else if (mi == 2000) { if (metD) medMet++;   else medMissed++; }
            else                 { if (metD) largeMet++;  else largeMissed++; }
        }

        return new double[]{total, met, missed,
                total > 0 ? 100.0 * met / total : 0,
                total > 0 ? 100.0 * missed / total : 0,
                smallMet, smallMissed, medMet, medMissed, largeMet, largeMissed};
    }

    /** Category deadline stats for DFARM using actual CloudSim times. */
    private long[] categoryDeadlineStats(Map<EdgeCloudlet, EdgeVm> taskMap,
                                         List<Cloudlet> received) {
        Map<Integer, Cloudlet> byId = new HashMap<>();
        for (Cloudlet c : received) byId.put(c.getCloudletId(), c);

        long sm = 0, smM = 0, mm = 0, mmM = 0, lm = 0, lmM = 0;
        for (EdgeCloudlet ec : taskMap.keySet()) {
            Cloudlet c = byId.get(ec.getCloudletId());
            boolean met = c != null && c.getExecFinishTime() <= ec.getDeadline();
            long mi = ec.getCloudletLength();
            if      (mi <= 1000) { if (met) sm++;  else smM++; }
            else if (mi == 2000) { if (met) mm++;  else mmM++; }
            else                 { if (met) lm++;  else lmM++; }
        }
        return new long[]{sm, smM, mm, mmM, lm, lmM};
    }

    private int writeDeadlineRow(XSSFWorkbook wb, Sheet sheet, int rowNum,
                                 String name, CellStyle style, double[] stats) {
        Row row = sheet.createRow(rowNum);
        for (int c = 0; c < 12; c++) row.createCell(c).setCellStyle(style);
        setCell(row, 0,  name);
        setCell(row, 1,  stats[0]);
        setCell(row, 2,  stats[1]);
        setCell(row, 3,  stats[2]);
        setCell(row, 4,  String.format("%.1f%%", stats[3]));
        setCell(row, 5,  String.format("%.1f%%", stats[4]));
        setCell(row, 6,  stats[5]);
        setCell(row, 7,  stats[6]);
        setCell(row, 8,  stats[7]);
        setCell(row, 9,  stats[8]);
        setCell(row, 10, stats[9]);
        setCell(row, 11, stats[10]);
        return rowNum + 1;
    }

    // ── Helper metrics ────────────────────────────────────────────────────────

    private double avgTaskCostDfarm() {
        Map<EdgeCloudlet, EdgeVm> tvm = broker.getDfarm().getTaskVmMap();
        if (tvm.isEmpty()) return 0;
        return tvm.entrySet().stream()
                .filter(e -> !e.getKey().isDuplicate())
                .mapToDouble(e -> {
                    EdgeCloudlet ec = e.getKey();
                    EdgeVm vm = e.getValue();
                    return vm.computeUplinkDelay(ec.getInputSizeKB())
                         + (double) ec.getCloudletLength() / vm.getMips()
                         + vm.computeDownlinkDelay(ec.getOutputSizeKB());
                })
                .average().orElse(0.0);
    }

    private double avgTaskCostBaseline(EdgeSchedulerBase b) {
        return b.getTaskTimestamps().values().stream()
                .mapToDouble(ts -> ts[1] - ts[0])
                .average().orElse(0.0);
    }

    private double bestVmMips() {
        return allVms.stream().mapToDouble(EdgeVm::getMips).max().orElse(0.0);
    }

    // ── POI helpers ───────────────────────────────────────────────────────────

    private static void setCell(Row row, int col, Object value) {
        Cell cell = row.getCell(col);
        if (cell == null) cell = row.createCell(col);
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) cell.setCellValue("—");
            else cell.setCellValue(d);
        } else if (value instanceof String s) {
            cell.setCellValue(s.isEmpty() ? "—" : s);
        } else if (value != null) {
            cell.setCellValue(value.toString());
        } else {
            cell.setCellValue("—");
        }
    }

    private static void mergeTitle(XSSFWorkbook wb, Sheet sheet, Row row,
                                   int firstCol, int lastCol, String text) {
        Cell cell = row.createCell(firstCol);
        cell.setCellValue(text);
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 13);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        cell.setCellStyle(style);
        sheet.addMergedRegion(new CellRangeAddress(
                row.getRowNum(), row.getRowNum(), firstCol, lastCol));
    }

    private static CellStyle headerStyle(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setBorderBottom(BorderStyle.THIN);
        return s;
    }

    private static CellStyle boldStyle(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont(); f.setBold(true); s.setFont(f);
        return s;
    }

    private static CellStyle algoRowStyle(XSSFWorkbook wb, IndexedColors color) {
        CellStyle s = wb.createCellStyle();
        s.setFillForegroundColor(color.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        return s;
    }

    private static void autoSizeColumns(Sheet sheet, int count) {
        for (int c = 0; c < count; c++) {
            sheet.autoSizeColumn(c);
            if (sheet.getColumnWidth(c) > 60 * 256) sheet.setColumnWidth(c, 60 * 256);
        }
    }
}
