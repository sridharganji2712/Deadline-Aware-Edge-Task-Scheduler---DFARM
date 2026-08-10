# DFARM Edge Scheduling — iFogSim2 Implementation

## Introduction

This project implements **DFARM** (Deadline and Fault-Aware Resource Manager), a scheduling algorithm originally designed for cloud environments ([Awan, Aleem, Hussain & Prodan, *Cluster Computing*, 2024](#references)), adapted for **Edge Computing**. The implementation was originally built on CloudSim and has since been implemented in iFogSim suiting the edge computing scenarios, with several scheduling and fault-tolerance mechanisms added beyond the base cloud-paper design. This README documents the current, verified state of that port.

### What DFARM Does

DFARM schedules incoming tasks (cloudlets) across a multi-node edge topology while satisfying **deadline compliance** — every task carries an absolute deadline. DFARM prioritises tasks with tighter deadlines using the **DPL (Deadline-Per-Length)** metric and attempts to assign each task to a VM whose predicted completion time fits within the deadline.

### Edge Adaptations

The original cloud paper uses a flat pool of homogeneous VMs. This implementation makes the following edge-specific changes:

| | Cloud (paper) | Edge (this implementation) |
|---|---|---|
| **Topology** | Single datacenter | 3 nodes (A, B, C) with wireless + backhaul links |
| **CT formula** | `boot + acq + MI/MIPS` | `uplink + boot + acq + MI/MIPS + downlink` |
| **Deadline-Per-Length formula** | `(deadline − arrivalTime) / MI` | `(deadline − currentTime − uplink) / MI` (network-aware) |
| **Replication ratio** | 30% | **Tightest 15% of the DPL distribution** — a task is flagged for a standby replica only if its DPL falls at or below the 15th percentile (i.e. the most deadline-pressured 15% of tasks), not a fixed fraction of all tasks |
| **VM acquisition** | 50-VM flat pool | 4–6 VMs per node, acquired via a 3-source cascade (see below) |

> **Note on the replication ratio:** an earlier draft of this document stated a fixed 10% ratio. The verified, currently-configured value — confirmed directly from `REPLICATION_DECISION` trace log entries across multiple runs — is **15%**, applied as a DPL-percentile cutoff, not a flat share of the workload.

### Topology

Verified against the exported `Topology` sheet (`simulation_summary.xlsx`) and per-VM execution records:

```
Node A — 4 VMs, MIPS ladder {100, 200, 300, 500},         boot 5s,  acqDelay 10s
Node B — 6 VMs, MIPS ladder {500, 750, 900, 1000, 1100, 1200}, boot 3s,  acqDelay 8s
Node C — 4 VMs, MIPS ladder {100, 250, 500, 750},          boot 8s,  acqDelay 15s
```

All three nodes are fully connected (each is a neighbour of the other two), enabling cross-node task offload when local VMs are saturated.

> **Verification note:** Node B's full 6-VM MIPS ladder is directly confirmed by observed VM assignments in exported run data. Node A's and Node C's lowest-MIPS VMs (100 MIPS on A; 100 and 250 MIPS on C) did not receive any task in the runs used for verification, so those three specific values are asserted from configuration rather than independently observed in execution output — worth a quick cross-check against the `EdgeNode`/`EdgeVm` setup code in `EdgeDFARMExample` if this document is used as a citable reference.

### Workload

Cloudlets are generated across four size tiers. Verified against `task_journey.xlsx` (unique task-length values observed in a 250-task run):

| Category | Million Instructions | Observed count (250-task run) |
|---|---|---|
| Small | 1000 MI | 66 |
| Medium | 2000 MI | 82 |
| Large | 5000 MI | 65 |
| Huge | 8000 MI | 41 |

**Arrival pattern:** tasks arrive via a **uniform random draw across a fixed arrival window** (`ARRIVAL_WINDOW_SEC = 60s`). An earlier version used a 3-phase front-loaded burst pattern (early wave 0–10s, heavy burst 10–20s, tail 20–60s); this was replaced after a dead-branch bug was found in the burst-phase logic, and the current implementation draws arrival times uniformly across the full 60-second window instead.

**Task count:** this varies by run purpose — a 300-task run is used as the authoritative headline baseline (no fault injection); 250-task runs are used for fault-injection and replica-behaviour analysis (see below). If you're citing task-count-dependent figures anywhere in this repo, state which run they came from.

### VM Acquisition Cascade

When direct assignment and queue adjustment both fail, DFARM evaluates all acquisition sources simultaneously:

1. **Released-idle VM** on origin node — no acquisition delay, just boot time
2. **Fresh VM** from origin node's cold pool — full boot + acquisition delay
3. **Cross-node VM** from any neighbour — adds inter-node transfer delay

The candidate with the lowest predicted CT that still meets the deadline is chosen.

### Baseline Comparisons

Four baseline algorithms run analytically on the same workload after the DFARM simulation, using the identical edge CT model, so results are directly comparable:

- **RS** — Random Selection
- **RR** — Round Robin
- **MCT** — Minimum Completion Time
- **RALBA** — Resource-Aware Load Balancing Algorithm

---

## Enhancements Over the Base Implementation

Four mechanisms were added on top of the base cloud-paper design during the edge/iFogSim2 port. Each is verified against real exported run data, not just design intent.

### 1. Pre-Commit Phase for Queue Adjustment

In the base implementation, once `assignAcquire()` commits a task to a VM, it is submitted to the simulation immediately (`commit()` appends it to the VM's queue and the broker dispatches it right away) — there is no gap in which a not-yet-running task can be reordered relative to others already queued on the same VM.

The enhanced version introduces a **per-VM holding queue** that defers actual dispatch (`cloudletSubmit()`) until the VM is free. This opens a **pre-commit window**: a task sits in the holding queue, committed but not yet irrevocably submitted, and during that window `tryQueueAdjustment()` (Algorithm 7, extended) can walk the queue **tail-backward** and reprioritise a newly-arrived, tighter-deadline task ahead of already-queued ones — as long as doing so doesn't cause anyone else to miss their own deadline.

**The untouchable boundary:** the walk stops before index 0 and before any *dispatched* task or *active standby-replica placeholder* — those are already executing (or reserved) and cannot be reordered around. This was tightened after two related bugs were found and fixed:
- Adjustment logic initially tested feasibility against queue index 0 even when that task was already dispatched and executing in real CloudSim — physically meaningless, since a completion time already in progress can't be reordered.
- A deeper case: a warm-standby replica placeholder can sit at index 0 without itself being dispatched, leaving the *real* executing task one slot in — still exposed. Fixed by walking forward past every replica-flagged entry to find the true boundary, not just index 0.

Once a task actually clears the holding queue and is dispatched, it becomes **post-commit** — immutable, no longer reachable by the reordering algorithm.

### 2. Node Diversity (Replica Placement)

Every standby replica is placed on a VM belonging to a **different physical node** than its original — a hard exclusion filter applied before the replica's own VM search runs, not a separate placement algorithm. Concretely: once the original's node is fixed, the replica's candidate VM pool is narrowed to exclude every VM on that node *before* the normal cost-based VM search (same logic as regular assignment) runs over the remaining pool.

**Verified: 11 of 11 replica pairs (100%) landed on a different physical node from their original**, across a full fault-injection run:

| Original Task | Original Node | Replica | Replica Node |
|---|---|---|---|
| #4, #44 | B | −5, −45 | C |
| #26, #78, #80, #92, #144 | B | −27, −79, −81, −93, −145 | A |
| #206, #214, #232, #244 | B | −207, −215, −233, −245 | C |

*(Note: the replica cloudlet ID convention is `replicaID = −(originalID + 1)`, confirmed from `REPLICA_CREATED` trace log entries — not the naive `−originalID` that an ID-matching script might assume.)*

**Known limitation, also verified:** node diversity only guarantees a replica isn't on the *same* node as its original — it does nothing to protect the replica from an *independent* failure of whatever node it does land on. With only 3 nodes, diversifying away from the original's node still leaves a real chance of landing on the one other node that happens to fail. This was observed directly (see next section).

### 3. Node Failure Simulation

A fault-injection framework supports both **transient** (with a configurable recovery time) and **permanent** node failures, injected at configurable simulated timestamps, with a fixed detection delay (~1–2s observed) modelling the lag between a node going down and the scheduler noticing.

**Fate of all 11 replicas in one verified fault-injection run** (Node C transient failure, Node B permanent failure):

| Outcome | Count | Cause |
|---|---|---|
| Activated (successful failover) | 3 | Original's node failed while the replica's node was still healthy and the task was in-flight |
| Discarded (never needed) | 3 | Original completed cleanly before any failure occurred |
| Lost (killed before use) | 5 | The replica's *own* node failed independently while it was still an idle standby, before its original ever needed it |

→ **Replica utilization: 3/11 = 27.27%.** Every replica that got the *chance* to help succeeded (3 for 3); the low headline number reflects how many never got the chance, not a failure of the failover logic itself.

**Full walkthrough of one successful failover** (task #92, retimed run with Node B failing mid-execution):

| Time | Event |
|---|---|
| t=9.36s | Task #92 arrives; DPL flags it in the tightest 15% — replicated |
| t=10.03s | Original committed to Node B; replica created as warm standby on Node A |
| t=38.99s | Original begins executing on Node B |
| t=40.0s | Node B fails (PERMANENT) |
| t=42.03s | Failure detected → original abandoned → replica activated |
| t=56.78s | Replica begins executing on Node A (after waiting its own turn in that VM's holding queue) |
| t=73.48s | Replica finishes; result delivered to device |

**Important caveat found during verification:** activation is not instantaneous execution — the replica still has to wait its turn in its own VM's holding queue (a ~14.75s gap was observed between activation and actual dispatch for task #92). Comparing against other algorithms' logged finish-time estimates for the same task (which were already flagged as deadline-missed at earlier times than DFARM's actual replica finish), it's likely this particular recovery **completed the task but missed its original deadline**. DFARM's replication guarantees eventual task completion (fault tolerance) — it does **not** guarantee that completion happens within the original deadline. These are two separate properties, and this run demonstrates the gap between them concretely.

### 4. Time Complexity Analysis

The scheduler's VM-search path is instrumented with real wall-clock timing (`System.nanoTime()`), not just asymptotic analysis.

**Theoretical complexity**, per task, following the three-stage cascade: Direct Assign `O(V_node)` → Queue Adjustment `O(Q)` → Dynamic VM Provisioning `O(N·V)` — an overall worst case of **O(V_total + Q) per task**: linear in total VM count and local queue depth, never combinatorial.

**Measured, by task-size tier** (from `VM_Search_Analysis`, 250-task fault-injection run, 103 successfully-searched tasks):

| Tier | Count | Avg Deadline | Avg / Min / Max Search Time (ms) | Deadline consumed (avg case) | Deadline consumed (worst case) |
|---|---|---|---|---|---|
| Small | 12 | 34.76s | 0.991 / 0.043 / 3.368 | 0.0029% | 0.0097% |
| Medium | 27 | 54.54s | 0.329 / 0.021 / 4.893 | 0.0006% | 0.0090% |
| Large | 64 | 83.67s | 0.634 / 0.013 / 30.445 | 0.0008% | 0.0364% |
| **Overall** | **103** | **70.33s** | **0.596 / 0.013 / 30.445** | **0.0008%** | **0.0433%** |

Even the worst observed case (30.44ms, on the cascade-fallback path) consumes under 0.05% of a task's deadline budget — scheduling overhead is negligible next to real execution and network delay costs.

---

## Output

Results are exported to `C:\MINIPROJECT\DFARM_EDGE_RESULTS\` *(confirm current path — this may have moved as part of the iFogSim2 port)*, as a set of XLSX reports:

- `simulation_summary.xlsx` — Overall_Statistics, Fault_Tolerance, Topology, Rejected_Tasks, Structural_Replica_Failures, VM_Search_Analysis
- `algorithm_comparison.xlsx` — per-algorithm Summary, Makespan, TRR/MissRate, Throughput, ARUR, Deadline_Analysis
- `dfarm_trace_report.xlsx` — Task Execution Log, Task Event Timeline, DFARM Decision Log
- `task_journey.xlsx` — full per-task lifecycle log
- `node_A_stats.xlsx`, `node_B_stats.xlsx`, `node_C_stats.xlsx` — per-node cloudlet placement (original vs. replica)

---

## File Index

> **Note:** the paths below reflect the original CloudSim-module layout. Confirm these against the current repo structure before relying on them — the iFogSim2 port may have relocated some classes, and the holding-queue / pre-commit mechanism (Section 1 above) implies at least one additional class (a controller-level component managing `enqueueForSubmission()` / `isVmBusy()` / `dispatchNextQueued()`) that isn't represented in this index and should be added once the exact class name is confirmed.

| Class | Path |
|---|---|
| `EdgeDFARMExample.java` | `modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/` |
| `DFARMScheduler.java` | `modules/cloudsim/src/main/java/org/cloudbus/cloudsim/` |
| `EdgeDFARMBroker.java` | `modules/cloudsim/src/main/java/org/cloudbus/cloudsim/` |
| `EdgeNode.java` | `modules/cloudsim/src/main/java/org/cloudbus/cloudsim/` |
| `EdgeVm.java` | `modules/cloudsim/src/main/java/org/cloudbus/cloudsim/` |
| `EdgeCloudlet.java` | `modules/cloudsim/src/main/java/org/cloudbus/cloudsim/` |
| `EdgeSchedulerBase.java` | `modules/cloudsim/src/main/java/org/cloudbus/cloudsim/` |
| `RSScheduler.java` | `modules/cloudsim/src/main/java/org/cloudbus/cloudsim/` |
| `RRScheduler.java` | `modules/cloudsim/src/main/java/org/cloudbus/cloudsim/` |
| `MCTScheduler.java` | `modules/cloudsim/src/main/java/org/cloudbus/cloudsim/` |
| `RALBAScheduler.java` | `modules/cloudsim/src/main/java/org/cloudbus/cloudsim/` |
| `DFARMResultsExporter.java` | `modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/` |
| `AlgorithmComparisonExporter.java` | `modules/cloudsim-examples/src/main/java/org/cloudbus/cloudsim/examples/` |

---

## Class & Method Reference

### `EdgeDFARMExample.java`

**Overview:** The simulation entry point. Builds the 3-node edge topology, creates all VMs and cloudlets, runs the simulation, prints results, runs the four baseline schedulers analytically on the same workload, and triggers XLSX export.

| Method | Responsibility |
|---|---|
| `main()` | Orchestrates the full simulation: initialises the simulator, creates nodes and broker, submits VMs and cloudlets, starts and stops simulation, prints results, exports all XLSX reports, runs baselines. |
| `createNodeVms()` | Creates one `EdgeVm` per MIPS value in the array, registers each VM with the given edge node's cold pool, and returns the list. Used once per node during setup. |
| `createCloudlets()` | Generates the configured number of `EdgeCloudlet` tasks with arrival times drawn uniformly across a fixed arrival window, task sizes (small/medium/large/huge), and deadlines scaled to node bias and task size. Sets origin node for uplink delay computation. |
| `printResults(EdgeDFARMBroker broker)` | Prints the cloudlet execution table (ID, status, VM, node, start/finish/CPU times) and the DFARM summary block (total tasks, accepted, rejected, TRR, makespan, throughput, ARUR). |

### `DFARMScheduler.java`

**Overview:** The core DFARM scheduling engine. A pure logic container, not a simulation entity. Implements the paper's algorithms (adapted for edge) using arrival-driven scheduling.

**Key data structures:**
- `availableVms` — VMs currently in the active pool (acquired from node pools)
- `vmCtMap` — predicted completion time per VM (boot+acq overhead + queued task costs)
- `vmQueueMap` — ordered task queue per VM
- `taskVmMap` — task-to-VM assignment (includes replicas)
- `dplHistory` — sorted DPL values for fault-tolerance threshold decisions

| Method | Responsibility |
|---|---|
| `initScheduler(List<EdgeVm> vms, double currentTime)` | Algorithm 3. Resets all internal state and starts with an empty active VM pool so the acquisition cascade is triggered on demand. |
| `scheduleOneTask(EdgeCloudlet task, double currentTime)` | Algorithm 1. Decides fault-tolerance (replicate or not), inserts DPL into history, calls `doSchedule` for the task, and if replication was decided, schedules a replica on a second VM — excluding the original's node (see Node Diversity). Returns a list: empty = rejected, one item = assigned, two items = assigned with replica. |
| `notifyTaskCompleted(EdgeCloudlet task)` | Algorithm 6. Removes a finished task from its VM's queue. If the queue empties, moves the VM to the released-idle pool. |
| `assignAcquire(...)` | Algorithm 4. Phase 1: iterates active VMs sorted by CT, returns the first whose estimated finish meets the deadline. Phase 2: tries queue adjustment via `adjustPossible`. Phase 3: calls `pickBestAcquisition` to acquire a new VM. |
| `pickBestAcquisition(...)` | Unified acquisition. Builds candidates from all three sources (released-idle, fresh, cross-node), classifies each as feasible or infeasible against the deadline, and selects the best feasible candidate. |
| `buildAcqCandidatesWithLogging(EdgeCloudlet task)` | Builds the full candidate list with per-source diagnostic log lines. |
| `buildAcqCandidates(EdgeCloudlet task)` | Silent version of the above. |
| `activateCandidate(...)` | Promotes the winning VM from its node pool to active, seeds its `vmCtMap` entry with boot+acq overhead, and adds it to `availableVms`. |
| `adjustPossible(EdgeCloudlet newTask, EdgeVm vm)` | Algorithm 7. Traverses the VM's queue in reverse, checking whether swapping `newTask` earlier would let both tasks meet their deadlines — bounded by the untouchable-boundary rule (see Pre-Commit Phase). Returns the candidate cloudlet to swap before, or null. |
| `adjustToVm(EdgeCloudlet newTask, EdgeVm vm, EdgeCloudlet candidate)` | Inserts `newTask` before `candidate` in the VM's ordered queue. |
| `determineFaultTolerance(EdgeCloudlet task, List<Double> history, double ratio, double currentTime)` | Algorithm 8. Computes the network-aware DPL for the incoming task and compares it against the `ratio`-th percentile of the DPL history (currently 15%). Returns true (replicate) if the task's DPL is at or below that threshold. |
| `doSchedule(EdgeCloudlet task, double currentTime)` | Calls `assignAcquire` and immediately calls `commit` if a VM was found. |
| `commit(EdgeCloudlet task, EdgeVm vm)` | Records task→VM in `taskVmMap`, adds the task's cost to `vmCtMap`, and appends the task to the VM's queue in `vmQueueMap`. |
| `releaseIdleVm(EdgeVm vm)` | Moves a VM from `availableVms` to `releasedIdleVms`. |
| `taskCost(EdgeVm vm, EdgeCloudlet task)` | Computes `MI/MIPS + downlinkDelay`. |
| `taskCostWithUplink(EdgeCloudlet task, EdgeVm vm)` | Returns `taskCost + uplinkDelay`. |
| `networkAwareDPL(EdgeCloudlet task, double currentTime)` | Computes `(deadline − currentTime − uplinkDelay) / MI`. |
| `sortedVmCtEntries()` | Returns active VMs sorted by ascending CT. |
| `insertSortedDPL(EdgeCloudlet task, double currentTime)` | Inserts the task's DPL into `dplHistory` via binary search. |
| `makeCopy(EdgeCloudlet original)` | Creates a replica cloudlet with ID `−(|originalID|+1)`, same parameters as the original, `isDuplicate = true`, placed on a VM excluded from the original's node. |
| `computeTRR(int totalOriginalTasks)` | Task Rejection Rate: rejected originals / total originals. |
| `computeMakespan()` | Maximum CT across all VMs. |
| `computeThroughput(int totalOriginalTasks)` | Accepted originals / makespan. |
| `computeARUR()` | Average Resource Utilisation Ratio: avg(VM CT) / makespan. |

### `EdgeDFARMBroker.java`

**Overview:** Wires `DFARMScheduler` into the simulation event loop using arrival-driven scheduling. Manages the pre-commit holding queue and models the full end-to-end delay: uplink → boot/acq → execution → downlink.

| Method | Responsibility |
|---|---|
| `submitCloudlets()` | Initialises the DFARM scheduler with the active VM list, schedules one arrival event per task. |
| `processEvent(SimEvent ev)` | Event dispatcher — routes arrival and downlink-complete events to their handlers. |
| `handleTaskArrival(SimEvent ev)` | Calls `dfarm.scheduleOneTask()` at the simulation clock, enqueues scheduled cloudlets for deferred (pre-commit) dispatch rather than submitting immediately. |
| `submitWithDelay(EdgeCloudlet cloudlet, EdgeVm vm, int datacenterId)` | Computes the pre-execution delay and dispatches the cloudlet once its VM is free. |
| `processCloudletReturn(SimEvent ev)` | Called when CPU execution finishes; schedules a downlink-complete event. |
| `handleDownlinkComplete(SimEvent ev)` | Notifies DFARM the task finished (triggering idle-release if applicable), logs predicted-vs-actual CT delta. |
| `checkShutdown()` | Triggers shutdown only when all submissions and pending arrivals are exhausted. |
| `logActualVsPredicted(EdgeCloudlet cloudlet)` | Logs the delta between DFARM's predicted CT and actual simulation time — used to validate CT model accuracy. |
| `shutdownEntity()` | Invokes parent shutdown then `printDFARMMetrics()`. |
| `printDFARMMetrics()` | Prints the DFARM performance summary. |

### `EdgeNode.java`

**Overview:** Represents a physical edge node. Maintains three VM lifecycle pools (`availableVmPool`, `activeVms`, `releasedIdleVms`) and provides bandwidth-based delay calculations.

| Method | Responsibility |
|---|---|
| `addVm(EdgeVm vm)` | Registers a VM into the node's cold pool. |
| `addNeighbor(EdgeNode node)` | Adds a neighbour node for cross-node offload. |
| `findReleasedVm()` | Returns the highest-MIPS VM in `releasedIdleVms`, or null. |
| `findFreshVm()` | Returns the highest-MIPS VM in `availableVmPool`, or null. |
| `computeUplinkDelay(double inputSizeKB)` | Returns `inputSizeKB / wirelessBandwidthKBps`. |
| `activateReleasedVm(EdgeVm vm)` / `activateFreshVm(EdgeVm vm)` | Moves a VM into `activeVms` from the respective pool. |
| `releaseIdleVms(...)` | Algorithm 6 (node-side). Moves active VMs with empty queues to `releasedIdleVms`. |
| `findNeighborWithFreeVm()` | Returns the first neighbour node with a released-idle or fresh VM available. |
| `computeInterNodeDelay(double inputSizeKB)` | Returns `inputSizeKB / interNodeBandwidthKBps`. |

### `EdgeVm.java`

**Overview:** Edge-specific VM carrying wireless bandwidth, backhaul bandwidth, and cold-start timing parameters.

| Method | Responsibility |
|---|---|
| `computeUplinkDelay(double inputSizeKB)` | Device → node transmission time. |
| `computeDownlinkDelay(double outputSizeKB)` | Node → device result transmission time. |
| `computeInterNodeDelay(double inputSizeKB)` | Backhaul transfer time for cross-node offload. |
| `computeEdgeCT(EdgeCloudlet cloudlet, double currentVmCt)` | Full CT for a fresh VM: `currentVmCt + uplink + boot + acq + MI/MIPS + downlink`. |
| `computeEdgeCTReused(EdgeCloudlet cloudlet, double currentVmCt)` | CT for a released-idle VM (no acquisition delay). |

### `EdgeCloudlet.java`

**Overview:** Task representation carrying deadline, arrival time, input/output sizes, source device, duplicate flag, and origin node.

| Method | Responsibility |
|---|---|
| `EdgeCloudlet(...)` (full constructor) | Sets all edge fields, marks `isDuplicate = false`. |
| `EdgeCloudlet(...)` (auto-ID constructor) | Calls the full constructor with an auto-incremented ID. |
| `computeDPL(double currentTime)` | Returns `(deadline − currentTime) / cloudletLength`. Lower DPL = tighter deadline relative to computation size = higher replication priority. |
| Getters/Setters | `getDeadline`, `getArrivalTime`, `getInputSizeKB`, `getOutputSizeKB`, `getDeviceLatencyMs`, `getSourceDeviceId`, `isDuplicate`, `getOriginNode`, and their setters. |

### `EdgeSchedulerBase.java`

**Overview:** Abstract base for all four baseline schedulers. Provides shared state and the identical edge CT model used by DFARM, for fair comparison. Baselines run analytically — no simulation events.

| Method | Responsibility |
|---|---|
| `schedule(...)` | Abstract — subclasses implement their assignment policy. |
| `getAlgorithmName()` | Abstract — returns the algorithm's short identifier. |
| `initVmCtMap(List<EdgeVm> vms)` | Seeds `vmCtMap` with each VM's cold-start overhead. |
| `taskCost(EdgeVm vm, EdgeCloudlet task)` | Returns `uplink + MI/MIPS + downlink`. |
| `assign(EdgeCloudlet task, EdgeVm vm)` | Records the assignment and flags deadline misses. |
| `computeMakespan()` | Maximum CT across all assigned VMs. |
| `computeMissRate(int totalOriginalTasks)` | Deadline-missed originals / total originals. |
| `computeThroughput(int totalOriginalTasks)` | Accepted originals / makespan. |
| `computeARUR()` | Average VM CT / makespan. |

### `RSScheduler.java` — Random Selection

Assigns each task to a randomly chosen VM (fixed seed 42, reproducible). No deadline awareness; all tasks always assigned. `getAlgorithmName()` → `"RS"`.

### `RRScheduler.java` — Round Robin

Assigns tasks to VMs in strict cyclic order. No deadline awareness; all tasks always assigned. `getAlgorithmName()` → `"RR"`.

### `MCTScheduler.java` — Minimum Completion Time

For each task, assigns to the VM minimising `vmCt[vm] + taskCost(vm, task)`. Greedy; no deadline awareness; all tasks always assigned. `getAlgorithmName()` → `"MCT"`.

### `RALBAScheduler.java` — Resource-Aware Load Balancing

Two-phase: **Fill phase** computes a per-VM time budget and assigns tasks (sorted descending by MI) to the VM with the most remaining capacity that fits; **Spill phase** handles overflow tasks via MCT. `getAlgorithmName()` → `"RALBA"`.

---

## References

1. Ahmad Awan, Muhammad Aleem, Atif Hussain, Radu Prodan. "DFARM: a deadline-aware fault-tolerant scheduler for cloud computing." *Cluster Computing*, vol. 27, pp. 9323–9344, 2024.
2. Rodrigo N. Calheiros, Rajiv Ranjan, Anton Beloglazov, César A. F. De Rose, Rajkumar Buyya. "CloudSim: A Toolkit for Modeling and Simulation of Cloud Computing Environments and Evaluation of Resource Provisioning Algorithms." *Software: Practice and Experience*, vol. 41, no. 1, pp. 23–50, 2011.
3. Atif Hussain, Muhammad Aleem, Arshad Khan, Muhammad Iqbal, Ansar Islam. "RALBA: a computation-aware load balancing scheduler for cloud computing." *Cluster Computing*, vol. 21, pp. 1667–1680, 2018.
4. Tracy D. Braun, Howard Jay Siegel, Noah Beck, Ladislau L. Bölöni, Muthucumaru Maheswaran, Albert I. Reuther, James P. Robertson, Mitchell D. Theys, Bin Yao, Debra Hensgen, Richard F. Freund. "A Comparison of Eleven Static Heuristics for Mapping a Class of Independent Tasks onto Heterogeneous Distributed Computing Systems." *Journal of Parallel and Distributed Computing*, vol. 61, no. 6, pp. 810–837, 2001.
5. Akash Garg, Ritu Garg. "Fault Tolerance Mechanisms in Cloud Computing: A Survey." *International Journal of Computer Applications*, vol. 143, no. 11, pp. 14–18, 2016.
