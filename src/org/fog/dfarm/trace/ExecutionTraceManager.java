package org.fog.dfarm.trace;

import java.util.*;

/**
 * Centralized, timestamped execution trace collector. Every major class in
 * the DFARM module (DFARMController, DFARMScheduler, EdgeSchedulerBase,
 * DFARMFogDevice) writes into this during the simulation; nothing reads
 * from it until after CloudSim.stopSimulation(), when the workbook exporter
 * consumes it to reconstruct the full run.
 *
 * A process-wide singleton, matching this codebase's existing idiom for
 * simulation-lifetime collectors (see org.fog.utils.TimeKeeper) — this
 * project runs one simulation per JVM invocation from a single main(), so
 * there's no multi-run isolation concern; call reset() at the start of a
 * run to guarantee a clean slate regardless.
 *
 * Three independent collections, one per report grain:
 *   - events: raw append-only timestamped log (Sheet 2, Task Event Timeline)
 *   - taskRecords: one mutable DTO per task/replica, filled in incrementally
 *     over its lifecycle (Sheet 1, Complete Task Execution Log)
 *   - decisionLog: one entry per scheduling attempt (Sheet 7, DFARM Internal
 *     Decision Log)
 */
public final class ExecutionTraceManager {

    private static final ExecutionTraceManager INSTANCE = new ExecutionTraceManager();
    public static ExecutionTraceManager getInstance() { return INSTANCE; }

    private final List<TraceEvent> events = new ArrayList<>();
    private final Map<Integer, TaskExecutionRecord> taskRecords = new LinkedHashMap<>();
    private final List<DecisionLogEntry> decisionLog = new ArrayList<>();
    private int batchCounter = 0;

    private ExecutionTraceManager() {}

    public void reset() {
        events.clear();
        taskRecords.clear();
        decisionLog.clear();
        batchCounter = 0;
    }

    public void recordEvent(double timestamp, TraceEventType type, Integer taskId, Integer vmId,
                            String nodeId, String description) {
        events.add(new TraceEvent(timestamp, type, taskId, vmId, nodeId, description));
    }

    public TaskExecutionRecord getOrCreateRecord(int taskId) {
        return taskRecords.computeIfAbsent(taskId, id -> {
            TaskExecutionRecord r = new TaskExecutionRecord();
            r.taskId = id;
            return r;
        });
    }

    public TaskExecutionRecord getRecord(int taskId) {
        return taskRecords.get(taskId);
    }

    public void addDecisionLogEntry(DecisionLogEntry entry) {
        decisionLog.add(entry);
    }

    public int nextBatchNumber() {
        return ++batchCounter;
    }

    public List<TraceEvent> getEvents() { return events; }
    public Collection<TaskExecutionRecord> getTaskRecords() { return taskRecords.values(); }
    public List<DecisionLogEntry> getDecisionLog() { return decisionLog; }
}
