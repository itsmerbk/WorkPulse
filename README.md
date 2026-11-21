# WorkPulse

**Real-Time Agile Kanban Sprint Board, WIP-Limit Enforcer & Cycle-Time Analytics (Java 21 + Vanilla JavaScript)**

WorkPulse is a real-time sprint execution and Lean flow-control platform built on a **Java 21 Virtual Threads** HTTP server, **SQLite WAL** transactional persistence, and an interactive **JavaScript** HTML5 Drag-and-Drop Kanban board. It enforces strict Work-In-Progress (WIP) column caps at the database transaction layer and computes live sprint velocity and task cycle times.

## Quickstart

~~~bash
git clone https://github.com/itsmerbk/WorkPulse.git
cd WorkPulse

mkdir -p out
javac -cp "lib/*" -d out src/com/workpulse/*.java

# 1. Run ACID WIP-limit enforcement & cycle-time assertions
java -ea --enable-native-access=ALL-UNNAMED -cp "out:lib/*" com.workpulse.WorkPulseTest

# 2. Start the live Kanban Sprint Board (http://127.0.0.1:8089)
java --enable-native-access=ALL-UNNAMED -cp "out:lib/*" com.workpulse.Main
~~~
