package org.fog.dfarm;

/**
 * Standalone verification of {@link QueueAdjustment} against the paper's own
 * worked example, run BEFORE this logic is ever wired into DFARMScheduler —
 * console-based (matching this project's existing verify*() pattern in
 * DFARMEdgeExample, e.g. verifyReplicaNodeDiversity/verifyFailoverBehavior)
 * rather than JUnit, since no test-runner infrastructure exists in this
 * ad-hoc-javac-compiled project.
 *
 * Run directly: java org.fog.dfarm.QueueAdjustmentTest
 */
public class QueueAdjustmentTest {

    private static int failures = 0;

    public static void main(String[] args) {
        testPaperWorkedExample();
        testNoValidPosition();
        testAllTheWayToHead();

        if (failures == 0) {
            System.out.println("\nALL TESTS PASSED");
        } else {
            System.out.println("\n" + failures + " TEST(S) FAILED");
            System.exit(1);
        }
    }

    // Queue [T3, T2, T1, T0] (T3 next to run, T0 tail). vmBaseCt=0, all four
    // tasks have ET=2, giving currentCts = [2, 4, 6, 8] under sequential
    // execution. Tx has ET=3.
    //
    // Walking from the tail (T0) backward:
    //   k=T0 (p=3): predecessor=T1's CT=6, txRevised=9 <= txDeadline(9) OK;
    //               qkRevised=8+3=11 <= T0.deadline(12) OK          -> PASS
    //   k=T1 (p=2): predecessor=T2's CT=4, txRevised=7 <= 9 OK;
    //               qkRevised=6+3=9 <= T1.deadline(10) OK           -> PASS
    //   k=T2 (p=1): predecessor=T3's CT=2, txRevised=5 <= 9 OK;
    //               qkRevised=4+3=7 <= T2.deadline(6) FAILS         -> STOP
    // Last accepted was p=2 (the T1 test) -> insert at index 2:
    // [T3, T2, Tx, T1, T0] — exactly the paper's own worked example.
    private static void testPaperWorkedExample() {
        double[] currentCts = {2, 4, 6, 8};              // T3, T2, T1, T0
        double[] deadlines  = {100, 6, 10, 12};           // T3's is irrelevant (never tested)
        double vmBaseCt = 0;
        double txDeadline = 9;
        double txEt = 3;

        // sanity: confirm the trigger precondition — straight tail-append
        // (after T0) really does fail, matching "this algorithm only runs
        // when append already fails."
        double appendCt = currentCts[3] + txEt;
        check("paper example: tail-append must fail (trigger precondition)",
                appendCt > txDeadline, true);

        int pos = QueueAdjustment.findInsertionPosition(currentCts, deadlines, vmBaseCt, txDeadline, txEt);
        check("paper worked example: Tx inserted at index 2 ([T3,T2,Tx,T1,T0])", pos, 2);
    }

    // Same queue, but Tx's own deadline is tight enough that even the very
    // first test (k=n, against T0) fails -> no valid position anywhere.
    private static void testNoValidPosition() {
        double[] currentCts = {2, 4, 6, 8};
        double[] deadlines  = {100, 100, 100, 12}; // loosen T1/T2 so ONLY Tx's own deadline is the blocker
        double vmBaseCt = 0;
        double txDeadline = 8;  // p=3 test: txRevised = currentCts[2](6)+3=9 > 8 -> fails immediately
        double txEt = 3;

        int pos = QueueAdjustment.findInsertionPosition(currentCts, deadlines, vmBaseCt, txDeadline, txEt);
        check("no valid position anywhere -> NO_VALID_POSITION", pos, QueueAdjustment.NO_VALID_POSITION);
    }

    // Same queue, but every deadline is generous enough that the walk
    // succeeds all the way back to the very head (k=1, predecessor = vmBaseCt).
    private static void testAllTheWayToHead() {
        double[] currentCts = {2, 4, 6, 8};
        double[] deadlines  = {20, 20, 20, 20};
        double vmBaseCt = 0;
        double txDeadline = 8;  // tight enough that straight tail-append still fails...
        double txEt = 1;        // ...but small ET means every insertion test still passes

        // sanity: tail-append must still fail (trigger precondition)
        double appendCt = currentCts[3] + txEt; // 8+1=9
        check("all-the-way-to-head: tail-append must fail (trigger precondition)",
                appendCt > txDeadline, true);

        int pos = QueueAdjustment.findInsertionPosition(currentCts, deadlines, vmBaseCt, txDeadline, txEt);
        check("all-the-way-to-head: Tx inserted at index 0 (new head)", pos, 0);
    }

    private static void check(String label, int actual, int expected) {
        boolean pass = actual == expected;
        report(label, pass, String.valueOf(expected), String.valueOf(actual));
    }

    private static void check(String label, boolean actual, boolean expected) {
        boolean pass = actual == expected;
        report(label, pass, String.valueOf(expected), String.valueOf(actual));
    }

    private static void report(String label, boolean pass, String expected, String actual) {
        if (pass) {
            System.out.println("PASS: " + label);
        } else {
            System.out.println("FAIL: " + label + " (expected " + expected + ", got " + actual + ")");
            failures++;
        }
    }
}
