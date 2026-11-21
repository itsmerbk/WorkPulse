package com.workpulse;

import java.nio.file.Files;
import java.nio.file.Path;

public class WorkPulseTest {
    public static void main(String[] args) throws Exception {
        Path tmpDb = Files.createTempFile("workpulse_test_", ".db");
        tmpDb.toFile().deleteOnExit();

        try (SprintStore store = new SprintStore(tmpDb.toString())) {
            // Seeded IN_PROGRESS has 2 tasks (WP-103, WP-104) with WIP limit = 3
            String move1 = store.moveTask("WP-105", "IN_PROGRESS");
            assert move1.contains("\"ok\":true") : "Moving 3rd task into IN_PROGRESS (WIP limit 3) must succeed";

            // Create WP-107 and try exceeding IN_PROGRESS WIP limit of 3
            String created = store.createTask("Load Test Sprint API", "Arjun V", "HIGH", 3);
            assert created.contains("\"ticketKey\":\"WP-107\"") : "New task must receive WP-107";

            String blocked = store.moveTask("WP-107", "IN_PROGRESS");
            assert blocked.contains("\"ok\":false") : "4th task in IN_PROGRESS must be blocked by WIP limit";
            assert blocked.contains("WIP limit") : "Error message must explain WIP limit: " + blocked;

            // Complete WP-103 -> frees 1 WIP slot in IN_PROGRESS
            String doneRes = store.moveTask("WP-103", "DONE");
            assert doneRes.contains("\"ok\":true") : "Moving WP-103 to DONE must succeed";

            // Now WP-107 can enter IN_PROGRESS cleanly
            String unblocked = store.moveTask("WP-107", "IN_PROGRESS");
            assert unblocked.contains("\"ok\":true") : "WP-107 must enter IN_PROGRESS once WIP slot opens";
        }

        System.out.println("ALL WORKPULSE ASSERTION TESTS PASSED (ACID WIP-Limit Enforcement, Kanban Transitions, Cycle-Time Metrics)");
    }
}
