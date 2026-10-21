package com.tipdm.agentplatform.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 校验所有原生查询的「声明返回类型 vs PG 实际 JDBC 类型」一致。
 *
 * <p><b>为什么单测兜不住这类错</b>：service 层测试把 Repository mock 掉，
 * Spring Data 的返回值转换（Boolean→Integer 那一步）根本不执行；
 * 声明与驱动返回类型不符，只会在真实 JDBC 调用时抛
 * {@code ClassCastException}，表现为看护定时任务每轮报错而非启动失败。
 * 本仓无 {@code @DataJpaTest}/{@code @SpringBootTest} 基座，
 * 故用「读源码声明 + 问数据库真实类型」的方式做静态一致性检查。</p>
 *
 * <p>连不上本地 PG 时整类跳过（{@link Assumptions}），不产生假绿也不阻塞构建。</p>
 */
class NativeQueryReturnTypeTest {

    /** 原生查询里声明为标量的方法：方法名 → 声明的返回类型。 */
    private static final Object[][] SCALAR_QUERIES = {
            {"existsDeletedBySessionId", "boolean"},
    };

    @Test
    void declaredScalarReturnTypesMatchPostgresJdbcTypes() throws Exception {
        Assumptions.assumeTrue(canConnect(), "local PostgreSQL not reachable");

        try (Connection conn = openConnection()) {
            for (Object[] query : SCALAR_QUERIES) {
                String method = (String) query[0];
                String declared = (String) query[1];
                String sql = nativeSqlOf(method);

                String jdbcType = probeColumnType(conn, sql);
                assertEquals(jdbcType, expectedJdbcTypeOf(declared),
                        () -> "方法 " + method + " 声明返回 " + declared
                                + "，但 PG 对其原生查询返回 JDBC 类型 " + jdbcType
                                + "；Spring Data 会在返回值转换处抛 ClassCastException");
            }
        }
    }

    /**
     * {@code SELECT EXISTS} 在 PG 里产出 {@code boolean}，不是 {@code int}。
     *
     * <p>这不是能靠推断定论的事——本测试存在的意义就是把推断变成实测。</p>
     */
    @Test
    void selectExistsYieldsBooleanOnPostgres() throws Exception {
        Assumptions.assumeTrue(canConnect(), "local PostgreSQL not reachable");

        try (Connection conn = openConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TEMP TABLE exists_type_probe (sid text)");
            st.execute("INSERT INTO exists_type_probe VALUES ('a')");
            try {
                assertEquals("bool",
                        probeColumnType(conn, "SELECT EXISTS (SELECT 1 FROM exists_type_probe WHERE sid = 'a')"),
                        "SELECT EXISTS 的 JDBC 类型若不是 bool，existsDeletedBySessionId 的声明需重新评估");
                assertEquals("bool",
                        probeColumnType(conn, "SELECT EXISTS (SELECT 1 FROM exists_type_probe WHERE sid = 'zz')"),
                        "EXISTS 的否定分支也必须是 bool（不能只在命中分支成立）");
            } finally {
                st.execute("DROP TABLE exists_type_probe");
            }
        }
    }

    /** 供后续新增原生标量查询时复用：命中/未命中都要能给出预期。 */
    @Test
    void existsQueryReturnsBothTrueAndFalse() throws Exception {
        Assumptions.assumeTrue(canConnect(), "local PostgreSQL not reachable");

        try (Connection conn = openConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TEMP TABLE exists_value_probe (sid text)");
            st.execute("INSERT INTO exists_value_probe VALUES ('a')");
            try {
                assertTrue(readBoolean(conn, "SELECT EXISTS (SELECT 1 FROM exists_value_probe WHERE sid = 'a')"),
                        "命中时应为 true");
                assertFalse(readBoolean(conn, "SELECT EXISTS (SELECT 1 FROM exists_value_probe WHERE sid = 'zz')"),
                        "未命中时应为 false —— 判据不能把「查不到行」误判成已删除");
            } finally {
                st.execute("DROP TABLE exists_value_probe");
            }
        }
    }

    // ---------- 辅助 ----------

    private static boolean readBoolean(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static String probeColumnType(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.getMetaData().getColumnTypeName(1).toLowerCase();
        }
    }

    private static Connection openConnection() throws SQLException {
        String host = System.getenv().getOrDefault("DB_HOST", "localhost");
        String port = System.getenv().getOrDefault("DB_PORT", "5432");
        String db = System.getenv().getOrDefault("DB_NAME", "agent_platform");
        String user = System.getenv().getOrDefault("DB_USER", "agent_platform");
        String pwd = System.getenv().getOrDefault("DB_PASSWORD", "agent_platform");
        return DriverManager.getConnection(
                "jdbc:postgresql://" + host + ":" + port + "/" + db, user, pwd);
    }

    private static boolean canConnect() {
        try (Connection conn = openConnection()) {
            return conn.isValid(3);
        } catch (SQLException e) {
            return false;
        }
    }

    private static String expectedJdbcTypeOf(String declared) {
        return switch (declared) {
            case "boolean" -> "bool";
            case "int", "Integer" -> "int4";
            case "long", "Long" -> "int8";
            case "String" -> "text";
            default -> throw new IllegalArgumentException("未登记的标量返回类型: " + declared);
        };
    }

    /**
     * 从 Repository 源码里抽出该方法的原生 SQL，避免测试与实现漂移。
     *
     * <p>SQL 常写成多个字面量用 {@code +} 拼接（Checkstyle 行长限制所致），
     * 只读第一个字面量会得到截断的 SQL ⇒ 必须收集「{@code +} 连接的字面量序列」。
     * 命名参数无实参可绑，统一替换为占位字面量，只求类型探测能执行。</p>
     */
    private static String nativeSqlOf(String method) throws Exception {
        String source = readRepositorySource();
        int decl = source.indexOf(method + "(");
        Assumptions.assumeTrue(decl > 0, "未在 Repository 源码中找到 " + method + " 的声明，请同步本测试");

        // 从声明位置往前回溯到最近的 @Query（跳过紧邻的 @Modifying/@Transactional 等）
        int queryStart = source.lastIndexOf("@Query", decl);
        Assumptions.assumeTrue(queryStart > 0,
                method + " 上方没有 @Query，它不是原生查询，本测试不适用");

        StringBuilder sql = new StringBuilder();
        int cursor = queryStart;
        // 逐个吃掉 "字面量 + 字面量 + …"，直到遇到非字符串内容（即 nativeQuery = true）
        while (true) {
            int quoteStart = source.indexOf('"', cursor);
            Assumptions.assumeTrue(quoteStart > 0 && quoteStart < decl,
                    "未在 " + method + " 的 @Query 中解析到 SQL 字面量（可能引用常量，本测试暂不覆盖）");
            String literal = readJavaStringLiteral(source, quoteStart);
            sql.append(literal);

            // 字面量之后跳过空白：要么是 '+'（继续拼），要么是 ',' 或 ')'（收尾）
            int i = quoteStart + literal.length() + 2;
            while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
                i++;
            }
            if (i < source.length() && source.charAt(i) == '+') {
                sql.append(' ');
                cursor = i + 1;
                continue;
            }
            break;
        }

        // 命名参数替换为占位字面量：仅做类型探测，不关心具体值
        return sql.toString().replaceAll("\\s+", " ").replaceAll(":[a-zA-Z_][a-zA-Z0-9_]*", "'probe'").trim();
    }

    /** 从起始双引号处读出一个完整的 Java 字符串字面量（含 Java 层的转义还原）。 */
    private static String readJavaStringLiteral(String source, int quoteStart) {
        StringBuilder out = new StringBuilder();
        int i = quoteStart + 1;
        while (i < source.length()) {
            char ch = source.charAt(i);
            if (ch == '\\' && i + 1 < source.length()) {
                out.append(source.charAt(i + 1));
                i += 2;
                continue;
            }
            if (ch == '"') {
                return out.toString();
            }
            out.append(ch);
            i++;
        }
        throw new IllegalStateException("字符串字面量未闭合");
    }

    private static String readRepositorySource() throws Exception {
        Path path = Path.of(
                "src/main/java/com/tipdm/agentplatform/repository/AgentSessionOwnerRepository.java");
        Assumptions.assumeTrue(Files.exists(path),
                "repository source not found at " + path.toAbsolutePath());
        return Files.readString(path);
    }
}