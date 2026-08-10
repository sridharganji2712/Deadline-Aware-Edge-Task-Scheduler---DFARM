package org.cloudbus.cloudsim;

import org.cloudbus.cloudsim.UtilizationModelFull;

/**
 * Extends Cloudlet with edge-specific fields needed by DFARM:
 *  - deadline          : absolute deadline (simulation time units)
 *  - arrivalTime       : when the task metadata reached the scheduler
 *  - inputSizeKB       : payload size — drives uplink delay
 *  - outputSizeKB      : result size  — drives downlink delay
 *  - deviceLatencyMs   : known RTT to nearest edge node (informational)
 *  - sourceDeviceId    : which device submitted this task
 *  - isDuplicate       : true if this is a fault-tolerance replica
 */
public class EdgeCloudlet extends Cloudlet {

    private double   deadline;
    private double   arrivalTime;
    private double   inputSizeKB;
    private double   outputSizeKB;
    private double   deviceLatencyMs;
    private String   sourceDeviceId;
    private boolean  isDuplicate;
    /** Edge node whose wireless link received this task. Drives uplink/interNode costs. */
    private EdgeNode originNode;

    /**
     * Full constructor.
     *
     * @param cloudletId     unique ID
     * @param cloudletLength computation size in MI
     * @param inputSizeKB    payload size in KB  (drives uplink delay)
     * @param outputSizeKB   result size in KB   (drives downlink delay)
     * @param deadline       absolute deadline in simulation time
     * @param arrivalTime    time metadata reached the scheduler
     * @param sourceDeviceId ID of the originating device
     * @param deviceLatencyMs known RTT to nearest node in ms
     */
    public EdgeCloudlet(
            int cloudletId,
            long cloudletLength,
            double inputSizeKB,
            double outputSizeKB,
            double deadline,
            double arrivalTime,
            String sourceDeviceId,
            double deviceLatencyMs) {

        super(
            cloudletId,
            cloudletLength,
            1,                              // pesNumber — 1 PE per task
            (long)(inputSizeKB * 1024),     // cloudletFileSize in bytes
            (long)(outputSizeKB * 1024),    // cloudletOutputSize in bytes
            new UtilizationModelFull(),
            new UtilizationModelFull(),
            new UtilizationModelFull()
        );

        this.inputSizeKB    = inputSizeKB;
        this.outputSizeKB   = outputSizeKB;
        this.deadline       = deadline;
        this.arrivalTime    = arrivalTime;
        this.sourceDeviceId = sourceDeviceId;
        this.deviceLatencyMs = deviceLatencyMs;
        this.isDuplicate    = false;
    }

    /**
     * Auto-ID constructor (uses CloudSim's AtomicInteger auto-ID).
     */
    public EdgeCloudlet(
            long cloudletLength,
            double inputSizeKB,
            double outputSizeKB,
            double deadline,
            double arrivalTime,
            String sourceDeviceId,
            double deviceLatencyMs) {

        this(
            highestId.incrementAndGet(),
            cloudletLength,
            inputSizeKB,
            outputSizeKB,
            deadline,
            arrivalTime,
            sourceDeviceId,
            deviceLatencyMs
        );
    }

    // DPL formula: (deadline - currentTime) / MI — lower = tighter deadline = higher priority
    public double computeDPL(double currentTime) {
        return (deadline - currentTime) / (double) getCloudletLength();
    }

    // getters & setters

    public double getDeadline()         { return deadline; }
    public void   setDeadline(double d) { this.deadline = d; }

    public double getArrivalTime()            { return arrivalTime; }
    public void   setArrivalTime(double t)    { this.arrivalTime = t; }

    public double getInputSizeKB()            { return inputSizeKB; }
    public void   setInputSizeKB(double s)    { this.inputSizeKB = s; }

    public double getOutputSizeKB()           { return outputSizeKB; }
    public void   setOutputSizeKB(double s)   { this.outputSizeKB = s; }

    public double getDeviceLatencyMs()           { return deviceLatencyMs; }
    public void   setDeviceLatencyMs(double ms)  { this.deviceLatencyMs = ms; }

    public String getSourceDeviceId()              { return sourceDeviceId; }
    public void   setSourceDeviceId(String id)     { this.sourceDeviceId = id; }

    public boolean isDuplicate()              { return isDuplicate; }
    public void    setDuplicate(boolean b)    { this.isDuplicate = b; }

    public EdgeNode getOriginNode()           { return originNode; }
    public void     setOriginNode(EdgeNode n) { this.originNode = n; }
}
