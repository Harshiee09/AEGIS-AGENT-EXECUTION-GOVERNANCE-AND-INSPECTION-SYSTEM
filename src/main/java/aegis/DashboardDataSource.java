package aegis;

import java.sql.SQLException;

/** Contract for loading the data displayed by the Swing dashboard. */
public interface DashboardDataSource {
    DashboardData loadDashboard() throws SQLException;
}
