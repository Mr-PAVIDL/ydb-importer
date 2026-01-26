package tech.ydb.importer.benchmark;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import org.testcontainers.containers.PostgreSQLContainer;

import tech.ydb.core.Result;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.importer.YdbImporter;
import tech.ydb.importer.config.ImporterConfig;
import tech.ydb.importer.config.SourceConfig;
import tech.ydb.importer.config.SourceType;
import tech.ydb.importer.config.TableOptions;
import tech.ydb.importer.config.TableRef;
import tech.ydb.importer.config.TargetConfig;
import tech.ydb.importer.config.TargetType;
import tech.ydb.importer.config.WorkerConfig;
import tech.ydb.importer.config.YdbAuthMode;
import tech.ydb.importer.integration.LocalYdbTestContainer;
import tech.ydb.table.SessionRetryContext;
import tech.ydb.table.TableClient;
import tech.ydb.table.query.DataQueryResult;
import tech.ydb.table.transaction.TxControl;

public class BenchmarkMain {

    private static final int DEFAULT_ROWS = 100_000;
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final int DEFAULT_POOL_SIZE = 4;
    private static final int DEFAULT_BUFFER_COUNT = 0;
    private static final int DEFAULT_WRITER_POOL_SIZE = 0;
    private static final Duration YDB_STARTUP_TIMEOUT = Duration.ofSeconds(60);
    private static final long MEMORY_SAMPLE_INTERVAL_MS = 500;
    private static final String TABLE_OPTIONS_NAME = "default";
    private static final String RESULTS_DIR = "bench-results";

    public static void main(String[] args) throws Exception {
        int rows = DEFAULT_ROWS;
        int batchSize = DEFAULT_BATCH_SIZE;
        int poolSize = DEFAULT_POOL_SIZE;
        int bufferCount = DEFAULT_BUFFER_COUNT;
        int writerPoolSize = DEFAULT_WRITER_POOL_SIZE;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--rows":
                    rows = Integer.parseInt(args[++i]);
                    break;
                case "--batch-size":
                    batchSize = Integer.parseInt(args[++i]);
                    break;
                case "--pool-size":
                    poolSize = Integer.parseInt(args[++i]);
                    break;
                case "--buffer-count":
                    bufferCount = Integer.parseInt(args[++i]);
                    break;
                case "--writer-pool-size":
                    writerPoolSize = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(1);
            }
        }

        String commitId = loadCommitId();
        printHeader(rows, batchSize, poolSize, commitId);
        run(rows, batchSize, poolSize, bufferCount, writerPoolSize, commitId);
    }

    @SuppressWarnings("resource")
    private static void run(int rows, int batchSize, int poolSize,
            int bufferCount, int writerPoolSize, String commitId) throws Exception {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5");
        LocalYdbTestContainer ydb = new LocalYdbTestContainer();

        try {
            postgres.start();
            ydb.start();
            waitForYdbReady(ydb.getConnectionString());

            long genStart = System.currentTimeMillis();
            try (Connection con = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                DataGenerator.generate(con, rows);
            }
            long genElapsed = System.currentTimeMillis() - genStart;
            System.out.printf("%n=== Data Generation ===%n");
            System.out.printf("Generated %,d rows in %.1fs%n", rows, genElapsed / 1000.0);

            ImporterConfig config = buildConfig(postgres, ydb, batchSize, poolSize,
                    bufferCount, writerPoolSize);
            MemoryTracker memTracker = new MemoryTracker(MEMORY_SAMPLE_INTERVAL_MS);

            System.out.printf("%n=== Import ===%n");
            memTracker.start();
            long importStart = System.currentTimeMillis();

            new YdbImporter(config).run();

            long importElapsed = System.currentTimeMillis() - importStart;
            memTracker.stop();

            double rowsPerSec = rows / (importElapsed / 1000.0);
            long peakMb = memTracker.getPeakUsedBytes() / (1024 * 1024);

            System.out.printf("Imported %,d rows in %.1fs (%,.0f rows/s)%n",
                    rows, importElapsed / 1000.0, rowsPerSec);
            System.out.printf("Peak heap usage: %d MB%n", peakMb);

            System.out.printf("%n=== Verification ===%n");
            long actualCount = countYdbRows(ydb.getConnectionString());
            if (actualCount == rows) {
                System.out.printf("Row count: %,d OK%n", actualCount);
            } else {
                System.out.printf("Row count MISMATCH: expected %,d, got %,d%n", rows, actualCount);
            }

            String resultsPath = nextResultsPath(commitId);
            writeResults(resultsPath, commitId, rows, batchSize, poolSize,
                    genElapsed, importElapsed, rowsPerSec,
                    peakMb, actualCount);

        } finally {
            ydb.stop();
            postgres.stop();
        }
    }

    private static void printHeader(int rows, int batchSize, int poolSize, String commitId) {
        System.out.printf("=== Benchmark Configuration ===%n");
        System.out.printf("Commit:      %s%n", commitId);
        System.out.printf("Rows:        %,d%n", rows);
        System.out.printf("Batch size:  %,d%n", batchSize);
        System.out.printf("Pool size:   %d%n", poolSize);
    }

    private static String loadCommitId() {
        try (InputStream is = BenchmarkMain.class.getResourceAsStream("/git.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String abbrev = props.getProperty("git.commit.id.abbrev", "unknown");
                String dirty = props.getProperty("git.dirty", "false");
                return "true".equals(dirty) ? abbrev + "-dirty" : abbrev;
            }
        } catch (IOException ignore) {
            // fall through
        }
        return "unknown";
    }

    private static String nextResultsPath(String commitId) {
        File dir = new File(RESULTS_DIR);
        dir.mkdirs();

        for (int n = 1; ; n++) {
            File f = new File(dir, commitId + "-" + n + ".json");
            if (!f.exists()) {
                return f.getPath();
            }
        }
    }

    private static ImporterConfig buildConfig(
            PostgreSQLContainer<?> postgres,
            LocalYdbTestContainer ydb,
            int batchSize,
            int poolSize,
            int bufferCount,
            int writerPoolSize) {

        ImporterConfig config = new ImporterConfig();

        WorkerConfig workers = new WorkerConfig();
        workers.setReaderPoolSize(poolSize);
        if (bufferCount > 0) {
            workers.setBufferCount(bufferCount);
        }
        if (writerPoolSize > 0) {
            workers.setWriterPoolSize(writerPoolSize);
        }
        config.setWorkers(workers);

        SourceConfig src = new SourceConfig();
        src.setType(SourceType.POSTGRESQL);
        src.setClassName(postgres.getDriverClassName());
        src.setJdbcUrl(postgres.getJdbcUrl());
        src.setUserName(postgres.getUsername());
        src.setPassword(postgres.getPassword());
        config.setSource(src);

        TargetConfig tgt = new TargetConfig();
        tgt.setType(TargetType.YDB);
        tgt.setAuthMode(YdbAuthMode.NONE);
        tgt.setConnectionString(ydb.getConnectionString());
        tgt.setReplaceExisting(true);
        tgt.setLoadData(true);
        tgt.setMaxBatchRows(batchSize);
        config.setTarget(tgt);

        TableOptions options = new TableOptions(TABLE_OPTIONS_NAME, "bench_pg.${schema}.${table}");
        config.getOptionsMap().put(options.getName(), options);

        TableRef ref = new TableRef();
        ref.setOptions(options);
        ref.setSchema(DataGenerator.SCHEMA);
        ref.setTable(DataGenerator.TABLE);
        config.getTableRefs().add(ref);

        return config;
    }

    private static long countYdbRows(String connectionString) {
        try (GrpcTransport transport = GrpcTransport.forConnectionString(connectionString).build();
                TableClient client = TableClient.newClient(transport).build()) {

            SessionRetryContext retryCtx = SessionRetryContext.create(client).build();
            String db = transport.getDatabase();
            String tablePath = db + "/bench_pg." + DataGenerator.SCHEMA + "." + DataGenerator.TABLE;
            String query = "SELECT COUNT(*) AS cnt FROM `" + tablePath + "`;";

            Result<DataQueryResult> result = retryCtx
                    .supplyResult(session -> session.executeDataQuery(query,
                            TxControl.serializableRw()))
                    .join();

            DataQueryResult dqr = result.getValue();
            tech.ydb.table.result.ResultSetReader rs = dqr.getResultSet(0);
            if (rs.next()) {
                return rs.getColumn(0).getUint64();
            }
            return -1;
        }
    }

    private static void waitForYdbReady(String connectionString) throws InterruptedException {
        long deadline = System.nanoTime() + YDB_STARTUP_TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {
            try (GrpcTransport transport = GrpcTransport.forConnectionString(connectionString).build();
                    TableClient client = TableClient.newClient(transport).build()) {

                SessionRetryContext retryCtx = SessionRetryContext.create(client).build();
                String db = transport.getDatabase();

                if (!retryCtx.supplyStatus(session -> session.executeDataQuery(
                        "SELECT 1;", TxControl.onlineRo()).thenApply(Result::getStatus))
                        .join().isSuccess()) {
                    Thread.sleep(500);
                    continue;
                }

                // Storage pools may not be ready even after query engine is up
                String probeTable = db + "/__bench_ready_check";
                if (!retryCtx.supplyStatus(session -> session.executeSchemeQuery(
                        "CREATE TABLE `" + probeTable + "` (id Int32, PRIMARY KEY(id));"))
                        .join().isSuccess()) {
                    Thread.sleep(500);
                    continue;
                }
                retryCtx.supplyStatus(session -> session.executeSchemeQuery(
                        "DROP TABLE `" + probeTable + "`;")).join();
                return;
            } catch (Exception ignore) {
                // YDB not ready yet
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("YDB did not become ready within " + YDB_STARTUP_TIMEOUT);
    }

    private static final String JSON_TEMPLATE = "{\n"
            + "  \"timestamp\": \"%s\",\n"
            + "  \"commit\": \"%s\",\n"
            + "  \"config\": {\n"
            + "    \"rows\": %d,\n"
            + "    \"batchSize\": %d,\n"
            + "    \"poolSize\": %d\n"
            + "  },\n"
            + "  \"results\": {\n"
            + "    \"dataGenerationMs\": %d,\n"
            + "    \"importMs\": %d,\n"
            + "    \"rowsPerSecond\": %.1f,\n"
            + "    \"peakHeapMb\": %d\n"
            + "  },\n"
            + "  \"verification\": {\n"
            + "    \"expectedRows\": %d,\n"
            + "    \"actualRows\": %d,\n"
            + "    \"passed\": %b\n"
            + "  }\n"
            + "}\n";

    private static void writeResults(String path, String commitId, int rows, int batchSize,
            int poolSize, long genElapsedMs, long importElapsedMs, double rowsPerSec,
            long peakHeapMb, long actualRowCount) {

        String json = String.format(JSON_TEMPLATE,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                commitId, rows, batchSize, poolSize,
                genElapsedMs, importElapsedMs, rowsPerSec, peakHeapMb,
                rows, actualRowCount, actualRowCount == rows);

        try (Writer writer = new FileWriter(path)) {
            writer.write(json);
            System.out.printf("%nResults written to %s%n", path);
        } catch (IOException e) {
            System.err.println("Warning: failed to write results file: " + e.getMessage());
        }
    }
}
