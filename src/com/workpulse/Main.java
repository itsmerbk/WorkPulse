package com.workpulse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = 8089;
        SprintStore store = new SprintStore("workpulse_sprint.db");
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/", exchange -> {
            String html = Files.readString(Path.of("web/index.html"), StandardCharsets.UTF_8);
            sendResponse(exchange, 200, "text/html; charset=UTF-8", html);
        });

        server.createContext("/api/state", exchange -> {
            try {
                sendResponse(exchange, 200, "application/json", store.getDashboardStateJson());
            } catch (Exception e) {
                sendResponse(exchange, 500, "application/json", "{\"ok\":false,\"error\":\"" + JsonUtil.escape(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/tasks", exchange -> {
            try {
                Map<String, String> body = parseBody(exchange);
                String title = body.getOrDefault("title", "");
                String assignee = body.getOrDefault("assignee", "Team Member");
                String priority = body.getOrDefault("priority", "MEDIUM");
                int points = Integer.parseInt(body.getOrDefault("storyPoints", "3"));
                String res = store.createTask(title, assignee, priority, points);
                sendResponse(exchange, res.contains("\"ok\":true") ? 200 : 400, "application/json", res);
            } catch (Exception e) {
                sendResponse(exchange, 400, "application/json", "{\"ok\":false,\"error\":\"" + JsonUtil.escape(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/move", exchange -> {
            try {
                Map<String, String> body = parseBody(exchange);
                String key = body.getOrDefault("ticketKey", "");
                String target = body.getOrDefault("targetColumn", "");
                String res = store.moveTask(key, target);
                sendResponse(exchange, res.contains("\"ok\":true") ? 200 : 409, "application/json", res);
            } catch (Exception e) {
                sendResponse(exchange, 400, "application/json", "{\"ok\":false,\"error\":\"" + JsonUtil.escape(e.getMessage()) + "\"}");
            }
        });

        server.createContext("/api/wip", exchange -> {
            try {
                Map<String, String> body = parseBody(exchange);
                String col = body.getOrDefault("colCode", "");
                int limit = Integer.parseInt(body.getOrDefault("wipLimit", "3"));
                String res = store.updateWipLimit(col, limit);
                sendResponse(exchange, res.contains("\"ok\":true") ? 200 : 400, "application/json", res);
            } catch (Exception e) {
                sendResponse(exchange, 400, "application/json", "{\"ok\":false,\"error\":\"" + JsonUtil.escape(e.getMessage()) + "\"}");
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            try {
                store.close();
            } catch (Exception ignored) {
            }
        }));

        server.start();
        System.out.println("WorkPulse Agile Kanban Sprint Board & WIP Enforcer running at http://127.0.0.1:" + port);
    }

    private static Map<String, String> parseBody(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return JsonUtil.parseObject(raw);
    }

    private static void sendResponse(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
