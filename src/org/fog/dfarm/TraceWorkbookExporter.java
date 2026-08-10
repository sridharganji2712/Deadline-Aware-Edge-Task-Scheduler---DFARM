package org.fog.dfarm;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.fog.dfarm.trace.DecisionLogEntry;
import org.fog.dfarm.trace.ExecutionTraceManager;
import org.fog.dfarm.trace.TaskExecutionRecord;
import org.fog.dfarm.trace.TraceEvent;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Research-grade execution-trace workbook — Phase 1 of the redesigned
 * reporting system (see project notes): reconstructs the complete
 * chronological life cycle of every task, replica, and scheduling decision
 * from ExecutionTraceManager, which DFARMController/DFARMScheduler/
 * EdgeSchedulerBase/DFARMFogDevice all write into during the run.
 *
 * This exporter is a pure CONSUMER — it never mutates simulation state, and
 * runs only after CloudSim.stopSimulation(). Sheets:
 *   1. Task Execution Log   — one row per original task AND per replica
 *   2. Task Event Timeline  — one row per timestamped scheduling event
 *   7. DFARM Internal Decision Log — one row per scheduling attempt
 *
 * (Sheet numbers match the full 12-sheet spec; sheets 3-6 and 8-12 are
 * Phase 2 — aggregations over this same trace data, not yet built.)
 *
 * Coexists with the existing CSV exporters (DFARMResultsExporter,
 * AlgorithmComparisonExporter) rather than replacing them in this phase.
 */
public class TraceWorkbookExporter {

    private final DFARMController controller;
    private final DFARMScheduler dfarm;
    private final ExecutionTraceManager trace;
    private final Path runDir;

    public TraceWorkbookExporter(DFARMController controller, Path runDir) {
        this.controller = controller;
        this.dfarm = controller.getDfarm();
        this.trace = ExecutionTraceManager.getInstance();
        this.runDir = runDir;
    }

    public Path export() throws IOException {
        Path outPath = runDir.resolve("dfarm_trace_report.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            writeTaskExecutionLog(wb);
            writeTaskEventTimeline(wb);
            writeDecisionLog(wb);
            try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                wb.write(fos);
            }
        }
        return outPath;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Sheet 1 — Complete Task Execution Log
    // ═════════════════════════════════════════════════════════════════════

    private static final String[] TASK_LOG_HEADERS = {
        // Task information
        "Task ID", "Original Task ID", "Is Replica", "Replica Number", "Source Device",
        "Origin Edge Node", "Arrival Time", "Deadline", "MI", "Input Size (KB)", "Output Size (KB)",
        "DPL Value", "Scheduling Priority", "Priority Rank In Batch", "Batch Number", "Batch Timestamp",
        // Scheduling decision
        "Algorithm", "Assigned Node", "Assigned VM", "Assigned VM MIPS", "Acquisition Source",
        "Released VM", "Fresh VM", "Cross-node", "Cloud", "Reused VM", "Cross-node Offload", "Cloud Offload",
        "Acquisition Delay", "Boot Delay", "Uplink Delay", "Downlink Delay", "Inter-node Delay",
        "Estimated Start Time", "Estimated Finish Time", "Actual Start Time", "Actual Finish Time",
        "CPU Time", "Completion Time (CT)", "VM Queue Position", "Queue Length Before", "Queue Length After",
        "VM CT Before", "VM CT After",
        // Deadline information
        "Deadline Met", "Deadline Miss Amount", "Rejected", "Reject Reason",
        // Replica information
        "Replica Created", "Replica Parent", "Replica VM", "Replica Node", "Replica Reserved Time",
        "Replica Activated", "Replica Activation Time", "Replica Discarded", "Replica Lost", "Replica Failure Reason",
        // Fault information
        "Original Node Failed", "Failure Time", "Detection Delay", "Recovery Time",
        "Resubmitted", "Failover Used", "Failover Node", "Failover VM",
        // Final status
        "Finished Successfully", "Cancelled", "Lost", "Dropped", "Completed By Replica", "Completed By Original",
        // Performance
        "Waiting Time", "Response Time", "Turnaround Time", "Network Delay", "Total Delay",
        "Execution Delay", "Resource Utilization Contribution"
    };

    private void writeTaskExecutionLog(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("Task Execution Log");
        writeHeaderRow(wb, sheet, TASK_LOG_HEADERS);

        Map<Integer, EdgeVm> vmById = new HashMap<>();
        for (EdgeVm vm : dfarm.getVmCtMap().keySet()) vmById.put(vm.getId(), vm);

        List<TaskExecutionRecord> records = new ArrayList<>(trace.getTaskRecords());
        records.sort(Comparator.comparingInt(r -> r.taskId));

        int r = 1;
        for (TaskExecutionRecord rec : records) {
            Row row = sheet.createRow(r++);
            int c = 0;

            setCell(row, c++, rec.taskId);
            setCell(row, c++, rec.originalTaskId);
            setCell(row, c++, rec.isReplica);
            setCell(row, c++, rec.replicaNumber);
            setCell(row, c++, rec.sourceDevice);
            setCell(row, c++, rec.originNode);
            setCell(row, c++, rec.arrivalTime);
            setCell(row, c++, rec.deadline);
            setCell(row, c++, rec.mi);
            setCell(row, c++, rec.inputSizeKB);
            setCell(row, c++, rec.outputSizeKB);
            setCell(row, c++, rec.dplValue);
            setCell(row, c++, rec.schedulingPriority);
            setCell(row, c++, rec.priorityRank);
            setCell(row, c++, rec.batchNumber);
            setCell(row, c++, rec.batchTimestamp);

            setCell(row, c++, rec.algorithm);
            setCell(row, c++, rec.assignedNode);
            setCell(row, c++, rec.assignedVmId);
            setCell(row, c++, rec.assignedVmMips);
            setCell(row, c++, rec.acquisitionSource);
            setCell(row, c++, rec.releasedVm);
            setCell(row, c++, rec.freshVm);
            setCell(row, c++, rec.crossNode);
            setCell(row, c++, rec.cloud);
            setCell(row, c++, rec.reusedVm);
            setCell(row, c++, rec.crossNodeOffload);
            setCell(row, c++, rec.cloudOffload);
            setCell(row, c++, rec.acquisitionDelay);
            setCell(row, c++, rec.bootDelay);
            setCell(row, c++, rec.uplinkDelay);
            setCell(row, c++, rec.downlinkDelay);
            setCell(row, c++, rec.interNodeDelay);
            setCell(row, c++, rec.estimatedStartTime);
            setCell(row, c++, rec.estimatedFinishTime);
            setCell(row, c++, rec.actualStartTime);
            setCell(row, c++, rec.actualFinishTime);
            setCell(row, c++, rec.cpuTime);
            setCell(row, c++, rec.completionTime);
            setCell(row, c++, rec.vmQueuePosition);
            setCell(row, c++, rec.queueLengthBefore);
            setCell(row, c++, rec.queueLengthAfter);
            setCell(row, c++, rec.vmCtBefore);
            setCell(row, c++, rec.vmCtAfter);

            setCell(row, c++, rec.deadlineMet);
            setCell(row, c++, rec.deadlineMissAmount);
            setCell(row, c++, rec.rejected);
            setCell(row, c++, rec.rejectReason);

            setCell(row, c++, rec.replicaCreated);
            setCell(row, c++, rec.replicaParentId);
            setCell(row, c++, rec.replicaVmId);
            setCell(row, c++, rec.replicaNode);
            setCell(row, c++, rec.replicaReservedTime);
            setCell(row, c++, rec.replicaActivated);
            setCell(row, c++, rec.replicaActivationTime);
            setCell(row, c++, rec.replicaDiscarded);
            setCell(row, c++, rec.replicaLost);
            setCell(row, c++, rec.replicaFailureReason);

            setCell(row, c++, rec.originalNodeFailed);
            setCell(row, c++, rec.failureTime);
            setCell(row, c++, rec.detectionDelay);
            setCell(row, c++, rec.recoveryTime);
            setCell(row, c++, rec.resubmitted);
            setCell(row, c++, rec.failoverUsed);
            setCell(row, c++, rec.failoverNode);
            setCell(row, c++, rec.failoverVmId);

            setCell(row, c++, rec.finishedSuccessfully);
            setCell(row, c++, rec.cancelled);
            setCell(row, c++, rec.lost);
            setCell(row, c++, rec.dropped);
            setCell(row, c++, rec.completedByReplica);
            setCell(row, c++, rec.completedByOriginal);

            setCell(row, c++, rec.waitingTime);
            setCell(row, c++, rec.responseTime);
            setCell(row, c++, rec.turnaroundTime);
            setCell(row, c++, rec.networkDelay);
            setCell(row, c++, rec.totalDelay);
            setCell(row, c++, rec.executionDelay);

            Double utilization = null;
            if (rec.cpuTime != null && rec.assignedVmId != null) {
                EdgeVm vm = vmById.get(rec.assignedVmId);
                if (vm != null) {
                    Double finalCt = dfarm.getVmCtMap().get(vm);
                    if (finalCt != null && finalCt > 0) utilization = rec.cpuTime / finalCt;
                }
            }
            setCell(row, c, utilization);

            // color the row by outcome, for quick visual scanning
            CellStyle rowStyle = rec.lost || rec.dropped ? lostStyle(wb)
                    : (rec.deadlineMet != null && !rec.deadlineMet) ? missedStyle(wb)
                    : rec.isReplica ? replicaStyle(wb)
                    : null;
            if (rowStyle != null) {
                for (int i = 0; i < TASK_LOG_HEADERS.length; i++) {
                    Cell cell = row.getCell(i);
                    if (cell != null) cell.setCellStyle(rowStyle);
                }
            }
        }

        autoSizeColumns(sheet, TASK_LOG_HEADERS.length);
        sheet.createFreezePane(0, 1);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Sheet 2 — Task Event Timeline
    // ═════════════════════════════════════════════════════════════════════

    private void writeTaskEventTimeline(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("Task Event Timeline");
        String[] headers = {"Timestamp", "Event", "Task", "VM", "Node", "Description"};
        writeHeaderRow(wb, sheet, headers);

        List<TraceEvent> events = new ArrayList<>(trace.getEvents());
        events.sort(Comparator.comparingDouble(e -> e.timestamp));

        int r = 1;
        for (TraceEvent e : events) {
            Row row = sheet.createRow(r++);
            setCell(row, 0, e.timestamp);
            setCell(row, 1, e.type.name());
            setCell(row, 2, e.taskId);
            setCell(row, 3, e.vmId);
            setCell(row, 4, e.nodeId);
            setCell(row, 5, e.description);
        }

        autoSizeColumns(sheet, headers.length);
        sheet.createFreezePane(0, 1);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Sheet 7 — DFARM Internal Decision Log
    // ═════════════════════════════════════════════════════════════════════

    private void writeDecisionLog(XSSFWorkbook wb) {
        Sheet sheet = wb.createSheet("DFARM Decision Log");
        String[] headers = {
            "Timestamp", "Task", "Is Replica", "Priority (DPL)", "Replication Threshold", "DPL History Size",
            "Candidate Summary", "Chosen Candidate CT", "Chosen Candidate Finish", "Chosen Feasible",
            "Chosen VM", "Chosen Node", "Reason Selected", "Rejected Candidate Reason",
            "Deep Search Used", "Queue Adjustment Used", "Commit Time", "Replica Placement Reason"
        };
        writeHeaderRow(wb, sheet, headers);

        List<DecisionLogEntry> log = new ArrayList<>(trace.getDecisionLog());
        log.sort(Comparator.comparingDouble(e -> e.timestamp));

        int r = 1;
        for (DecisionLogEntry e : log) {
            Row row = sheet.createRow(r++);
            int c = 0;
            setCell(row, c++, e.timestamp);
            setCell(row, c++, e.taskId);
            setCell(row, c++, e.isReplica);
            setCell(row, c++, e.priority);
            setCell(row, c++, e.replicationThreshold);
            setCell(row, c++, e.dplHistorySize);
            setCell(row, c++, e.candidateSummary);
            setCell(row, c++, e.chosenCandidateCt);
            setCell(row, c++, e.chosenCandidateFinish);
            setCell(row, c++, e.chosenFeasible);
            setCell(row, c++, e.chosenVmId);
            setCell(row, c++, e.chosenNode);
            setCell(row, c++, e.reasonSelected);
            setCell(row, c++, e.rejectedCandidateReason);
            setCell(row, c++, e.deepSearchUsed);
            setCell(row, c++, e.queueAdjustmentUsed);
            setCell(row, c++, e.commitTime);
            setCell(row, c, e.replicaPlacementReason);
        }

        autoSizeColumns(sheet, headers.length);
        sheet.createFreezePane(0, 1);
    }

    // ═════════════════════════════════════════════════════════════════════
    // POI helpers
    // ═════════════════════════════════════════════════════════════════════

    private void writeHeaderRow(XSSFWorkbook wb, Sheet sheet, String[] headers) {
        Row hdr = sheet.createRow(0);
        CellStyle style = headerStyle(wb);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = hdr.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
    }

    private void setCell(Row row, int col, Object value) {
        Cell cell = row.createCell(col);
        if (value == null) return; // leave blank rather than writing a sentinel string
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

    private CellStyle missedStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle lostStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle replicaStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private void autoSizeColumns(Sheet sheet, int count) {
        for (int c = 0; c < count; c++) {
            sheet.autoSizeColumn(c);
            if (sheet.getColumnWidth(c) > 60 * 256) sheet.setColumnWidth(c, 60 * 256);
        }
    }
}
