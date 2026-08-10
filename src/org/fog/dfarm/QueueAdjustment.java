package org.fog.dfarm;

/**
 * Paper's Algorithm 7, SIMPLE variant only — queue-adjustment for a per-VM
 * holding queue. Pure, standalone, no dependency on live scheduler state
 * (DFARMScheduler wires this in separately) so it can be unit-tested against
 * the paper's own worked example in isolation before ever touching real
 * simulation state.
 *
 * Preconditions the caller must have already established before calling this:
 *   - straight tail-append of Tx onto this VM's queue would violate Tx's own
 *     deadline (that's the trigger condition — if append succeeds, just
 *     append; don't call this at all).
 *
 * Given the VM's current holding queue in execution order [q_1, ..., q_n]
 * (q_1 next to run, q_n the tail), walks k = n, n-1, ..., 1, testing
 * insertion of Tx immediately before q_k:
 *   - q_k's revised CT = q_k's CURRENT CT + Tx's own ET               (Eq. 5)
 *   - Tx's revised CT at this slot = (CT of whatever precedes this slot —
 *     q_{k-1}'s CT, or the VM's base CT if k == 1) + Tx's own ET      (Eq. 6)
 *   - both q_k's revised CT <= q_k's deadline AND Tx's revised CT <= Tx's
 *     deadline must hold for this position to be accepted.
 * The walk keeps the LAST accepted position and stops at the first failure
 * (not the first success) — a later position closer to the tail is
 * preferred over an earlier one, since it disturbs less of the queue.
 *
 * Deliberately does NOT re-verify every task further down the queue that
 * also shifts later as a side effect of the insertion (q_{k+1}...q_n) —
 * that is not an oversight to "fix"; it's exactly what the paper's simple
 * variant specifies. The aggressive variant (which would re-verify the
 * whole downstream chain) is out of scope for this task.
 */
public final class QueueAdjustment {

    private QueueAdjustment() {}

    /** Sentinel returned when no valid adjustment position exists at all
     *  (even the tail-most test, k = n, fails). */
    public static final int NO_VALID_POSITION = -1;

    /**
     * @param currentCts  currentCts[i] = q_{i+1}'s current completion time,
     *                    i.e. currentCts[0] is q_1's CT, ..., currentCts[n-1]
     *                    is q_n's CT — in execution order, head to tail.
     * @param deadlines   deadlines[i] = q_{i+1}'s own deadline, same order.
     * @param vmBaseCt    the VM's base completion time — what q_1 would have
     *                    started from had nothing else been queued (used as
     *                    "predecessor CT" when testing insertion at the very
     *                    head, k == 1).
     * @param txDeadline  Tx's own deadline.
     * @param txEt        Tx's own execution time (MI / MIPS).
     * @return the 0-indexed array position to insert Tx at (0 = new head,
     *         currentCts.length = new tail — though a caller only ever
     *         reaches this method when tail-append already failed, so the
     *         tail position itself is never a valid return value here),
     *         or {@link #NO_VALID_POSITION} if no position works.
     */
    public static int findInsertionPosition(double[] currentCts, double[] deadlines,
                                            double vmBaseCt, double txDeadline, double txEt) {
        if (currentCts.length != deadlines.length) {
            throw new IllegalArgumentException("currentCts and deadlines must be the same length");
        }
        int n = currentCts.length;
        int lastAccepted = NO_VALID_POSITION;

        for (int p = n - 1; p >= 0; p--) {
            double predecessorCt = (p == 0) ? vmBaseCt : currentCts[p - 1];
            double txRevisedCt = predecessorCt + txEt;      // Eq. 6
            double qkRevisedCt = currentCts[p] + txEt;       // Eq. 5

            boolean feasible = qkRevisedCt <= deadlines[p] && txRevisedCt <= txDeadline;
            if (feasible) {
                lastAccepted = p;
            } else {
                break;
            }
        }
        return lastAccepted;
    }
}
