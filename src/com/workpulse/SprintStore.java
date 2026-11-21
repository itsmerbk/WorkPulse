package com.workpulse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SprintStore implements AutoCloseable {
    private final Connection conn;

    public SprintStore(String dbPath) throws SQLException {
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initSchema();
        seedInitialData();
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("PRAGMA busy_timeout=5000;");
            st.execute("""
                CREATE TABLE IF NOT EXISTS board_columns (
                    col_code TEXT PRIMARY KEY,
                    col_name TEXT NOT NULL,
                    wip_limit INTEGER NOT NULL DEFAULT 0,
                    display_order INTEGER NOT NULL
                )
            """);
            st.execute("""
                CREATE TABLE IF NOT EXISTS sprint_tasks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ticket_key TEXT NOT NULL UNIQUE,
                    title TEXT NOT NULL,
                    assignee TEXT NOT NULL,
                    priority TEXT NOT NULL,
                    story_points INTEGER NOT NULL DEFAULT 3,
                    status TEXT NOT NULL DEFAULT 'BACKLOG',
                    created_at_ms INTEGER NOT NULL,
                    started_at_ms INTEGER NOT NULL DEFAULT 0,
                    completed_at_ms INTEGER NOT NULL DEFAULT 0
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_tasks_status ON sprint_tasks(status, id ASC);");
        }
    }

    private void seedInitialData() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM board_columns")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }

        Object[][] cols = {
            {"BACKLOG", "Backlog", 0, 1},
            {"TODO", "Ready for Sprint", 0, 2},
            {"IN_PROGRESS", "In Progress", 3, 3},
            {"IN_REVIEW", "Code Review", 2, 4},
            {"DONE", "Done", 0, 5}
        };
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO board_columns (col_code, col_name, wip_limit, display_order) VALUES (?, ?, ?, ?)")) {
            for (Object[] c : cols) {
                ps.setString(1, (String) c[0]);
                ps.setString(2, (String) c[1]);
                ps.setInt(3, (Integer) c[2]);
                ps.setInt(4, (Integer) c[3]);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        long now = System.currentTimeMillis();
        Object[][] tasks = {
            {"WP-101", "Implement Virtual Thread HTTP Router", "Bharath K", "HIGH", 5, "DONE", now - 7200_000L, now - 5400_000L, now - 1800_000L},
            {"WP-102", "Add SQLite WAL Connection Pool", "Vikram R", "CRITICAL", 8, "IN_REVIEW", now - 5000_000L, now - 3200_000L, 0L},
            {"WP-103", "Build Drag-and-Drop Kanban UI", "Ananya S", "HIGH", 5, "IN_PROGRESS", now - 4200_000L, now - 1900_000L, 0L},
            {"WP-104", "Enforce Atomic Column WIP Limits", "Karthik M", "CRITICAL", 5, "IN_PROGRESS", now - 3600_000L, now - 1200_000L, 0L},
            {"WP-105", "Write GitHub Actions CI Pipeline", "Divya N", "MEDIUM", 3, "TODO", now - 2800_000L, 0L, 0L},
            {"WP-106", "Add Cycle-Time Percentile Export", "Meera P", "LOW", 3, "BACKLOG", now - 2000_000L, 0L, 0L}
        };
        try (PreparedStatement ps = conn.prepareStatement(
                """
                INSERT INTO sprint_tasks (ticket_key, title, assignee, priority, story_points, status, created_at_ms, started_at_ms, completed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (Object[] t : tasks) {
                ps.setString(1, (String) t[0]);
                ps.setString(2, (String) t[1]);
                ps.setString(3, (String) t[2]);
                ps.setString(4, (String) t[3]);
                ps.setInt(5, (Integer) t[4]);
                ps.setString(6, (String) t[5]);
                ps.setLong(7, (Long) t[6]);
                ps.setLong(8, (Long) t[7]);
                ps.setLong(9, (Long) t[8]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public synchronized String createTask(String title, String assignee, String rawPriority, int storyPoints) throws SQLException {
        if (title == null || title.isBlank()) {
            return "{\"ok\":false,\"error\":\"Task title is required\"}";
        }
        String priority = rawPriority == null ? "MEDIUM" : rawPriority.trim().toUpperCase();
        if (!priority.equals("CRITICAL") && !priority.equals("HIGH") && !priority.equals("MEDIUM") && !priority.equals("LOW")) {
            priority = "MEDIUM";
        }
        int points = Math.max(1, Math.min(21, storyPoints));
        String owner = (assignee == null || assignee.isBlank()) ? "Unassigned" : assignee.trim();

        conn.setAutoCommit(false);
        try {
            long id;
            String tempKey = "TMP-" + System.nanoTime();
            try (PreparedStatement ins = conn.prepareStatement(
                    """
                    INSERT INTO sprint_tasks (ticket_key, title, assignee, priority, story_points, status, created_at_ms)
                    VALUES (?, ?, ?, ?, ?, 'BACKLOG', ?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                ins.setString(1, tempKey);
                ins.setString(2, title.trim());
                ins.setString(3, owner);
                ins.setString(4, priority);
                ins.setInt(5, points);
                ins.setLong(6, System.currentTimeMillis());
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    id = keys.next() ? keys.getLong(1) : 1L;
                }
            }

            String ticketKey = String.format(Locale.US, "WP-%d", 100 + id);
            try (PreparedStatement upd = conn.prepareStatement("UPDATE sprint_tasks SET ticket_key = ? WHERE id = ?")) {
                upd.setString(1, ticketKey);
                upd.setLong(2, id);
                upd.executeUpdate();
            }

            conn.commit();
            return String.format(Locale.US,
                "{\"ok\":true,\"ticketKey\":\"%s\",\"title\":\"%s\"}",
                ticketKey, JsonUtil.escape(title.trim())
            );
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public synchronized String moveTask(String rawTicketKey, String rawTargetCol) throws SQLException {
        String key = rawTicketKey == null ? "" : rawTicketKey.trim().toUpperCase();
        String target = rawTargetCol == null ? "" : rawTargetCol.trim().toUpperCase();

        conn.setAutoCommit(false);
        try {
            String currentStatus;
            long startedAt;
            long completedAt;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, started_at_ms, completed_at_ms FROM sprint_tasks WHERE ticket_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return "{\"ok\":false,\"error\":\"Ticket " + JsonUtil.escape(key) + " not found\"}";
                    }
                    currentStatus = rs.getString("status");
                    startedAt = rs.getLong("started_at_ms");
                    completedAt = rs.getLong("completed_at_ms");
                }
            }

            if (currentStatus.equals(target)) {
                conn.rollback();
                return "{\"ok\":true,\"ticketKey\":\"" + JsonUtil.escape(key) + "\",\"status\":\"" + target + "\"}";
            }

            String colName;
            int wipLimit;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT col_name, wip_limit FROM board_columns WHERE col_code = ?")) {
                ps.setString(1, target);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return "{\"ok\":false,\"error\":\"Invalid target column " + JsonUtil.escape(target) + "\"}";
                    }
                    colName = rs.getString("col_name");
                    wipLimit = rs.getInt("wip_limit");
                }
            }

            // Enforce atomic Work-In-Progress (WIP) limit on constrained columns
            if (wipLimit > 0) {
                try (PreparedStatement cnt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM sprint_tasks WHERE status = ? AND ticket_key <> ?")) {
                    cnt.setString(1, target);
                    cnt.setString(2, key);
                    try (ResultSet rs = cnt.executeQuery()) {
                        int activeInCol = rs.next() ? rs.getInt(1) : 0;
                        if (activeInCol >= wipLimit) {
                            conn.rollback();
                            return String.format(Locale.US,
                                "{\"ok\":false,\"error\":\"WIP limit (%d) reached for %s! Finish or move an active task before pulling %s.\"}",
                                wipLimit, JsonUtil.escape(colName), JsonUtil.escape(key)
                            );
                        }
                    }
                }
            }

            long now = System.currentTimeMillis();
            if (("IN_PROGRESS".equals(target) || "IN_REVIEW".equals(target) || "DONE".equals(target)) && startedAt == 0L) {
                startedAt = now;
            }
            if ("DONE".equals(target)) {
                completedAt = now;
            } else {
                completedAt = 0L;
            }

            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE sprint_tasks SET status = ?, started_at_ms = ?, completed_at_ms = ? WHERE ticket_key = ?")) {
                upd.setString(1, target);
                upd.setLong(2, startedAt);
                upd.setLong(3, completedAt);
                upd.setString(4, key);
                upd.executeUpdate();
            }

            conn.commit();
            return String.format(Locale.US,
                "{\"ok\":true,\"ticketKey\":\"%s\",\"status\":\"%s\"}",
                JsonUtil.escape(key), JsonUtil.escape(target)
            );
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public synchronized String updateWipLimit(String rawColCode, int newLimit) throws SQLException {
        String col = rawColCode == null ? "" : rawColCode.trim().toUpperCase();
        int limit = Math.max(0, Math.min(20, newLimit));
        try (PreparedStatement ps = conn.prepareStatement("UPDATE board_columns SET wip_limit = ? WHERE col_code = ?")) {
            ps.setInt(1, limit);
            ps.setString(2, col);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                return "{\"ok\":false,\"error\":\"Column not found\"}";
            }
            return "{\"ok\":true,\"colCode\":\"" + JsonUtil.escape(col) + "\",\"wipLimit\":" + limit + "}";
        }
    }

    public synchronized String getDashboardStateJson() throws SQLException {
        List<String> colJsons = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT col_code, col_name, wip_limit, display_order FROM board_columns ORDER BY display_order ASC")) {
            while (rs.next()) {
                colJsons.add(String.format(Locale.US,
                    "{\"colCode\":\"%s\",\"colName\":\"%s\",\"wipLimit\":%d,\"order\":%d}",
                    JsonUtil.escape(rs.getString("col_code")),
                    JsonUtil.escape(rs.getString("col_name")),
                    rs.getInt("wip_limit"),
                    rs.getInt("display_order")
                ));
            }
        }

        int totalPoints = 0, donePoints = 0, activeWip = 0, criticalOpen = 0, completedTasks = 0;
        long totalCycleMs = 0L;
        long now = System.currentTimeMillis();
        List<String> taskJsons = new ArrayList<>();

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM sprint_tasks ORDER BY id ASC")) {
            while (rs.next()) {
                String status = rs.getString("status");
                String priority = rs.getString("priority");
                int pts = rs.getInt("story_points");
                long startedMs = rs.getLong("started_at_ms");
                long completedMs = rs.getLong("completed_at_ms");

                totalPoints += pts;
                if ("DONE".equals(status)) {
                    donePoints += pts;
                    completedTasks++;
                    if (startedMs > 0 && completedMs >= startedMs) {
                        totalCycleMs += (completedMs - startedMs);
                    }
                } else {
                    if ("CRITICAL".equals(priority)) criticalOpen++;
                    if ("IN_PROGRESS".equals(status) || "IN_REVIEW".equals(status)) activeWip++;
                }

                double cycleMins = 0.0;
                if (startedMs > 0) {
                    long end = "DONE".equals(status) && completedMs > 0 ? completedMs : now;
                    cycleMins = Math.max(0.1, (end - startedMs) / 60000.0);
                }

                taskJsons.add(String.format(Locale.US,
                    "{\"id\":%d,\"ticketKey\":\"%s\",\"title\":\"%s\",\"assignee\":\"%s\",\"priority\":\"%s\",\"storyPoints\":%d,\"status\":\"%s\",\"cycleMins\":%.1f}",
                    rs.getLong("id"),
                    JsonUtil.escape(rs.getString("ticket_key")),
                    JsonUtil.escape(rs.getString("title")),
                    JsonUtil.escape(rs.getString("assignee")),
                    JsonUtil.escape(priority),
                    pts,
                    JsonUtil.escape(status),
                    cycleMins
                ));
            }
        }

        double velocityPct = totalPoints > 0 ? Math.round(((double) donePoints / totalPoints) * 1000.0) / 10.0 : 0.0;
        double avgCycleMins = completedTasks > 0 ? Math.round(((double) totalCycleMs / completedTasks / 60000.0) * 10.0) / 10.0 : 0.0;

        return String.format(Locale.US,
            "{\"metrics\":{\"totalPoints\":%d,\"donePoints\":%d,\"velocityPct\":%.1f,\"activeWip\":%d,\"criticalOpen\":%d,\"avgCycleMins\":%.1f},\"columns\":[%s],\"tasks\":[%s]}",
            totalPoints, donePoints, velocityPct, activeWip, criticalOpen, avgCycleMins,
            String.join(",", colJsons),
            String.join(",", taskJsons)
        );
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }
}
