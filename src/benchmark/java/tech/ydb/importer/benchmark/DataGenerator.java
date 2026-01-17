package tech.ydb.importer.benchmark;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

final class DataGenerator {

    static final String SCHEMA = "public";
    static final String TABLE = "bench_data";

    private static final int INSERT_BATCH_SIZE = 2000;
    private static final int PROGRESS_INTERVAL = 100_000;
    private static final LocalDateTime BASE_TS = LocalDateTime.of(2020, 1, 1, 0, 0, 0);

    private DataGenerator() {
    }

    static void generate(Connection con, int totalRows) throws SQLException {
        createTable(con);
        insertRows(con, totalRows);
    }

    private static void createTable(Connection con) throws SQLException {
        con.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "id INTEGER PRIMARY KEY, "
                        + "text_col VARCHAR(100) NOT NULL, "
                        + "int_col BIGINT NOT NULL, "
                        + "double_col DOUBLE PRECISION NOT NULL, "
                        + "ts_col TIMESTAMP NOT NULL"
                        + ")"
        );
    }

    private static void insertRows(Connection con, int totalRows) throws SQLException {
        con.setAutoCommit(false);
        String sql = "INSERT INTO " + TABLE
                + " (id, text_col, int_col, double_col, ts_col) VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int batchCount = 0;
            for (int i = 1; i <= totalRows; i++) {
                ps.setInt(1, i);
                ps.setString(2, "row_" + i);
                ps.setLong(3, (long) i * 17);
                ps.setDouble(4, i * 0.31);
                ps.setTimestamp(5, Timestamp.valueOf(BASE_TS.plusSeconds(i)));
                ps.addBatch();
                batchCount++;

                if (batchCount >= INSERT_BATCH_SIZE) {
                    ps.executeBatch();
                    con.commit();
                    batchCount = 0;
                }
                if (i % PROGRESS_INTERVAL == 0) {
                    System.out.printf("  Generated %,d / %,d rows%n", i, totalRows);
                }
            }
            if (batchCount > 0) {
                ps.executeBatch();
                con.commit();
            }
        }
        con.setAutoCommit(true);
    }
}
