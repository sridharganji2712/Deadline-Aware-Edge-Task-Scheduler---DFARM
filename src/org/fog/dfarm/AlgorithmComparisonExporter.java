package org.fog.dfarm;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates algorithm_comparison.xlsx, placing DFARM and the 4 baseline
 * algorithms side-by-side on every key metric, matching the sheet structure
 * of the original CloudSim reference implementation's exporter (see
 * /template/algorithm_comparison.xlsx). DFARM is reported as a single row
 * using its real, CloudSim-observed metrics (DFARMController.computeActual*())
 * — no separate "predicted" row — since baselines never run inside the
 * CloudSim event loop (see EdgeSchedulerBase) and only ever have one
 * (analytical) number to offer, DFARM's own analytical bookkeeping is no
 * longer surfaced here either, for a like-for-like single value per algorithm.
 *
 * Sheets: Summary, Makespan_s, TRR_MissRate, Throughput_tasks_s, ARUR,
 * Per_VM_CT, Per_Node_Load, Deadline_Analysis.
 */
public class AlgorithmComparisonExporter {

    private final DFARMController         controller;
    private final List<EdgeSchedulerBase> baselines;   // RS, RR, MCT, RALBA in order
    private final List<EdgeVm>            allVms;      // edge-tier VMs only (baselines don't see cloud)
    private final List<EdgeNode>          reportNodes; // edge nodes + cloud node
    private final int                     totalOriginalTasks;
    private final Path                    runDir;

    private record DfarmMetrics(
            double makespan, double trr, double throughput, double arur,
            int totalTasks, int accepted, int rejected, long replicas,
            long crossNodeCount, long cloudCount, Map<EdgeVm, Double> vmCtMap) {}

    public AlgorithmComparisonExporter(DFARMController controller,
                                       List<EdgeSchedulerBase> baselines,
                                       List<EdgeVm> allVms,
                                       List<EdgeNode> reportNodes,
                                       Path runDir) {
        this.controller         = controller;
        this.baselines          = baselines;
        this.allVms             = allVms;
        this.reportNodes        = reportNodes;
        this.totalOriginalTasks = controller.getTotalOriginalTasks();
        this.runDir             = runDir;
    }

    public Path exportComparison() throws IOException {
        DFARMScheduler dfarm = controller.getDfarm();
        int rejected  = (int) dfarm.getRejectedTasks().stream().filter(t -> !t.isDuplicate()).count();
        int accepted  = totalOriginalTasks - rejected;
        long replicas = dfarm.getTaskVmMap().keySet().stream().filter(EdgeCloudlet::isDuplicate).count();

        DfarmMetrics dfarmM = new DfarmMetrics(
                controller.computeActualMakespan(),
                dfarm.computeTRR(totalOriginalTasks),
                controller.computeActualThroughput(),
                controller.computeActualARUR(),
                totalOriginalTasks, accepted, rejected, replicas,
                dfarm.getCrossNodeCloudletIds().size(),
                dfarm.getCloudCloudletIds().size(),
                controller.computeActualVmCtMap()
        );

        Path outPath = runDir.resolve("algorithm_comparison.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            writeSummary(wb, dfarmM);
            writeSingleMetricSheet(wb, "Makespan_s", "Makespan (s)", dfarmM.makespan(),
                    EdgeSchedulerBase::computeMakespan);
            writeSingleMetricSheet(wb, "TRR_MissRate", "Miss/Reject Rate", dfarmM.trr(),
                    b -> b.computeMissRate(totalOriginalTasks));
            writeSingleMetricSheet(wb, "Throughput_tasks_s", "Throughput (tasks/s)", dfarmM.throughput(),
                    b -> b.computeThroughput(totalOriginalTasks));
            writeSingleMetricSheet(wb, "ARUR", "ARUR", dfarmM.arur(), EdgeSchedulerBase::computeARUR);
            writeVmCt(wb, dfarmM);
            writePerNodeLoad(wb, dfarmM);
            writeDeadlineAnalysis(wb, dfarmM);
            try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                wb.write(fos);
            }
        }
        return outPath;
    }

    // ── Summary ──────────────────────────────────────────────────────────────

    private void writeSummary(XSSFWorkbook wb, DfarmMetrics dfarmM) {
        Sheet sheet = wb.createSheet("Summary");
        int r = writeTitle(wb, sheet, 0, "DFARM vs Baseline Algorithms — Simulation Comparison");

        String[] headers = {
            "Algorithm", "Total Tasks", "Accepted / Met", "Rejected / Missed",
            "Miss/Reject Rate (%)", "Makespan (s)", "Throughput (tasks/s)",
            "ARUR", "Replicas Created", "Cross-Node Offloads", "Cloud Offloads",
            "Avg Task Cost (s)", "Best VM MIPS Used"
        };
        writeHeaderRow(wb, sheet, r, headers);
        r++;

        r = writeSummaryRow(wb, sheet, r, "DFARM", totalOriginalTasks, dfarmM.accepted(), dfarmM.rejected(),
                dfarmM.trr() * 100, dfarmM.makespan(), dfarmM.throughput(), dfarmM.arur(),
                (int) dfarmM.replicas(), (int) dfarmM.crossNodeCount(), (int) dfarmM.cloudCount(),
                avgTaskCostDfarm(), bestVmMips());

        for (EdgeSchedulerBase b : baselines) {
            int missed   = (int) b.getDeadlineMissedTasks().stream().filter(t -> !t.isDuplicate()).count();
            int bAccepted = totalOriginalTasks - missed;
            r = writeSummaryRow(wb, sheet, r, b.getAlgorithmName(), totalOriginalTasks, bAccepted, missed,
                    b.computeMissRate(totalOriginalTasks) * 100, b.computeMakespan(),
                    b.computeThroughput(totalOriginalTasks), b.computeARUR(),
                    0, 0, 0, avgTaskCostBaseline(b), bestVmMips());
        }

        r++;
        r = writeSectionTitle(wb, sheet, r, "DFARM Improvement vs Baselines (negative = DFARM is better for that metric)");
        String[] deltaHeaders = {"Comparison", "Total Tasks", "Accepted / Met", "Rejected / Missed",
                "Miss/Reject Rate Delta (pp)", "Makespan Delta (s)", "Throughput Delta (t/s)", "ARUR Delta",
                "Replicas Created", "Cross-Node Offloads", "Cloud Offloads", "Avg Task Cost (s)", "Best VM MIPS Used"};
        writeHeaderRow(wb, sheet, r, deltaHeaders);
        r++;
        for (EdgeSchedulerBase b : baselines) {
            r = writeDeltaRow(wb, sheet, r, "DFARM − " + b.getAlgorithmName(),
                    dfarmM.trr(), dfarmM.makespan(), dfarmM.throughput(), dfarmM.arur(), b);
        }

        autoSizeColumns(sheet, headers.length);
    }

    private int writeSummaryRow(XSSFWorkbook wb, Sheet sheet, int rowIdx, String name, int total, int accepted,
                                int rejected, double missRatePct, double makespan, double throughput, double arur,
                                int replicas, int crossNode, int cloud, double avgCost, double bestMips) {
        Row row = sheet.createRow(rowIdx);
        int c = 0;
        setCell(row, c++, name);
        setCell(row, c++, total);
        setCell(row, c++, accepted);
        setCell(row, c++, rejected);
        setCell(row, c++, missRatePct);
        setCell(row, c++, makespan);
        setCell(row, c++, throughput);
        setCell(row, c++, arur);
        setCell(row, c++, replicas);
        setCell(row, c++, crossNode);
        setCell(row, c++, cloud);
        setCell(row, c++, avgCost);
        setCell(row, c, bestMips);
        return rowIdx + 1;
    }

    private int writeDeltaRow(XSSFWorkbook wb, Sheet sheet, int rowIdx, String label, double trr, double makespan,
                              double throughput, double arur, EdgeSchedulerBase b) {
        double missImprove     = trr        - b.computeMissRate(totalOriginalTasks);
        double makespanImprove = makespan   - b.computeMakespan();
        double thrputImprove   = throughput - b.computeThroughput(totalOriginalTasks);
        double arurImprove     = arur       - b.computeARUR();

        Row row = sheet.createRow(rowIdx);
        int c = 0;
        setCell(row, c++, label);
        setCell(row, c++, "—");
        setCell(row, c++, "—");
        setCell(row, c++, "—");
        setCell(row, c++, missImprove * 100);
        setCell(row, c++, makespanImprove);
        setCell(row, c++, thrputImprove);
        setCell(row, c++, arurImprove);
        setCell(row, c++, "—");
        setCell(row, c++, "—");
        setCell(row, c++, "—");
        setCell(row, c++, "—");
        setCell(row, c, "—");
        return rowIdx + 1;
    }

    // ── Single-metric comparison sheets (Makespan_s, TRR_MissRate, etc.) ──────

    private void writeSingleMetricSheet(XSSFWorkbook wb, String sheetName, String title, double dfarmValue,
                                        java.util.function.ToDoubleFunction<EdgeSchedulerBase> baselineFn) {
        Sheet sheet = wb.createSheet(sheetName);
        int r = writeTitle(wb, sheet, 0, title);
        writeHeaderRow(wb, sheet, r, new String[]{"Algorithm", "Value"});
        r++;
        r = writeRow(wb, sheet, r, "DFARM", dfarmValue);
        for (EdgeSchedulerBase b : baselines) {
            r = writeRow(wb, sheet, r, b.getAlgorithmName(), baselineFn.applyAsDouble(b));
        }
        autoSizeColumns(sheet, 2);
    }

    // ── Per-VM CT across all algorithms ─────────────────────────────────────

    private void writeVmCt(XSSFWorkbook wb, DfarmMetrics dfarmM) {
        Sheet sheet = wb.createSheet("Per_VM_CT");
        int r = writeTitle(wb, sheet, 0, "Per-VM Completion Time — All Algorithms");

        List<EdgeVm> sortedVms = allVms.stream()
                .sorted(Comparator.comparing(EdgeVm::getNodeId).thenComparingInt(EdgeVm::getId))
                .collect(Collectors.toList());

        List<String> headers = new ArrayList<>(List.of("VM ID", "Node", "MIPS", "DFARM CT (s)"));
        for (EdgeSchedulerBase b : baselines) headers.add(b.getAlgorithmName() + " CT (s)");
        writeHeaderRow(wb, sheet, r, headers.toArray(new String[0]));
        r++;

        for (EdgeVm vm : sortedVms) {
            Row row = sheet.createRow(r++);
            int c = 0;
            setCell(row, c++, vm.getId());
            setCell(row, c++, vm.getNodeId());
            setCell(row, c++, vm.getMips());
            setCell(row, c++, dfarmM.vmCtMap().getOrDefault(vm, 0.0));
            for (EdgeSchedulerBase b : baselines) {
                setCell(row, c++, b.getVmCtMap().getOrDefault(vm, 0.0));
            }
        }

        Row makespanRow = sheet.createRow(r);
        int c = 0;
        setCell(makespanRow, c++, "MAKESPAN");
        setCell(makespanRow, c++, "");
        setCell(makespanRow, c++, "");
        setCell(makespanRow, c++, dfarmM.makespan());
        for (EdgeSchedulerBase b : baselines) {
            setCell(makespanRow, c++, b.computeMakespan());
        }

        autoSizeColumns(sheet, headers.size());
    }

    // ── Per-node task load ────────────────────────────────────────────────────

    private void writePerNodeLoad(XSSFWorkbook wb, DfarmMetrics dfarmM) {
        Sheet sheet = wb.createSheet("Per_Node_Load");

        List<String> headers = new ArrayList<>(List.of("Node", "DFARM #tasks"));
        for (EdgeSchedulerBase b : baselines) headers.add(b.getAlgorithmName() + " #tasks");
        writeHeaderRow(wb, sheet, 0, headers.toArray(new String[0]));

        int r = 1;
        for (EdgeNode node : reportNodes) {
            String nodeId = node.getNodeId();
            Row row = sheet.createRow(r++);
            int c = 0;
            setCell(row, c++, "Node " + nodeId);

            long dfarmCount = controller.getDfarm().getTaskVmMap().entrySet().stream()
                    .filter(e -> !e.getKey().isDuplicate())
                    .filter(e -> e.getValue().getNodeId().equals(nodeId))
                    .count();
            setCell(row, c++, dfarmCount);

            // Baselines don't see the cloud tier — 0 tasks there by construction.
            for (EdgeSchedulerBase b : baselines) {
                long cnt = b.getTaskVmMap().entrySet().stream()
                        .filter(e -> e.getValue().getNodeId().equals(nodeId))
                        .count();
                setCell(row, c++, cnt);
            }
        }

        autoSizeColumns(sheet, headers.size());
        sheet.createFreezePane(0, 1);
    }

    // ── Deadline analysis (met vs missed, by task size category) ────────────

    private void writeDeadlineAnalysis(XSSFWorkbook wb, DfarmMetrics dfarmM) {
        Sheet sheet = wb.createSheet("Deadline_Analysis");
        String[] headers = {
            "Algorithm", "Total Tasks", "Deadline Met", "Deadline Missed/Rejected",
            "Met (%)", "Missed/Reject Rate (%)",
            "Small(≤1000 MI) Met", "Small Missed",
            "Medium(2000 MI) Met", "Medium Missed",
            "Large(≥5000 MI) Met", "Large Missed"
        };
        writeHeaderRow(wb, sheet, 0, headers);

        int r = 1;
        r = writeDeadlineRow(wb, sheet, r, "DFARM", computeDfarmDeadlineStats(dfarmM));
        for (EdgeSchedulerBase b : baselines) {
            r = writeDeadlineRow(wb, sheet, r, b.getAlgorithmName(), computeBaselineDeadlineStats(b));
        }

        autoSizeColumns(sheet, headers.length);
        sheet.createFreezePane(0, 1);
    }

    private int writeDeadlineRow(XSSFWorkbook wb, Sheet sheet, int rowIdx, String name, double[] stats) {
        Row row = sheet.createRow(rowIdx);
        int c = 0;
        setCell(row, c++, name);
        setCell(row, c++, (int) stats[0]);
        setCell(row, c++, (int) stats[1]);
        setCell(row, c++, (int) stats[2]);
        setCell(row, c++, stats[3]);
        setCell(row, c++, stats[4]);
        setCell(row, c++, (int) stats[5]);
        setCell(row, c++, (int) stats[6]);
        setCell(row, c++, (int) stats[7]);
        setCell(row, c++, (int) stats[8]);
        setCell(row, c++, (int) stats[9]);
        setCell(row, c, (int) stats[10]);
        return rowIdx + 1;
    }

    /** [total, met, missed, metPct, missedPct, smallMet, smallMissed, medMet, medMissed, largeMet, largeMissed] */
    private double[] computeDfarmDeadlineStats(DfarmMetrics dfarmM) {
        int total   = totalOriginalTasks;
        int missed  = dfarmM.rejected();
        int met     = total - missed;

        long[] sm = categoryDeadlineStats(
                controller.getDfarm().getTaskVmMap().entrySet().stream()
                        .filter(e -> !e.getKey().isDuplicate())
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));

        return new double[]{total, met, missed,
                100.0 * met / total, 100.0 * missed / total,
                sm[0], sm[1], sm[2], sm[3], sm[4], sm[5]};
    }

    private double[] computeBaselineDeadlineStats(EdgeSchedulerBase b) {
        int total  = b.getTaskVmMap().size();
        int missed = (int) b.getDeadlineMissedTasks().stream().filter(t -> !t.isDuplicate()).count();
        int met    = total - missed;

        long smallMet = 0, smallMissed = 0, medMet = 0, medMissed = 0, largeMet = 0, largeMissed = 0;
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

    /** Category deadline stats for DFARM using actual simulated finish times. */
    private long[] categoryDeadlineStats(Map<EdgeCloudlet, EdgeVm> taskMap) {
        Map<Integer, org.cloudbus.cloudsim.Cloudlet> byId = new HashMap<>();
        for (org.cloudbus.cloudsim.Cloudlet c : controller.getCloudletReceivedList()) byId.put(c.getCloudletId(), c);

        long sm = 0, smM = 0, mm = 0, mmM = 0, lm = 0, lmM = 0;
        for (EdgeCloudlet ec : taskMap.keySet()) {
            org.cloudbus.cloudsim.Cloudlet c = byId.get(ec.getCloudletId());
            boolean met = c != null && c.getExecFinishTime() <= ec.getDeadline();
            long mi = ec.getCloudletLength();
            if      (mi <= 1000) { if (met) sm++;  else smM++; }
            else if (mi == 2000) { if (met) mm++;  else mmM++; }
            else                 { if (met) lm++;  else lmM++; }
        }
        return new long[]{sm, smM, mm, mmM, lm, lmM};
    }

    // ── Helper metrics ────────────────────────────────────────────────────────

    private double avgTaskCostDfarm() {
        Map<EdgeCloudlet, EdgeVm> tvm = controller.getDfarm().getTaskVmMap();
        if (tvm.isEmpty()) return 0;
        return tvm.entrySet().stream()
                .filter(e -> !e.getKey().isDuplicate())
                .mapToDouble(e -> {
                    EdgeCloudlet ec = e.getKey();
                    EdgeVm vm = e.getValue();
                    return vm.computeUplinkDelay(ec.getInputSizeKB(), ec.getDeviceLatencyMs())
                         + (double) ec.getCloudletLength() / vm.getMips()
                         + vm.computeDownlinkDelay(ec.getOutputSizeKB(), ec.getDeviceLatencyMs());
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

    // ═════════════════════════════════════════════════════════════════════
    // POI helpers
    // ═════════════════════════════════════════════════════════════════════

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

    private int writeRow(XSSFWorkbook wb, Sheet sheet, int rowIdx, String label, Object value) {
        Row row = sheet.createRow(rowIdx);
        setCell(row, 0, label);
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
}
