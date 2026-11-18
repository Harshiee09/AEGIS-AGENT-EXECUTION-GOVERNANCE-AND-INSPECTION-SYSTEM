package aegis;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Opens a fresh JDBC connection for each repository operation. */
public final class DBConnection {
    private DBConnection() { }

    public static Connection open() throws SQLException {
        String url = setting("AEGIS_DB_URL", "jdbc:mysql://localhost:3306/aegis");
        String user = setting("AEGIS_DB_USER", "root");
        String password = System.getenv().getOrDefault("AEGIS_DB_PASSWORD", "");
        return DriverManager.getConnection(url, user, password);
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
