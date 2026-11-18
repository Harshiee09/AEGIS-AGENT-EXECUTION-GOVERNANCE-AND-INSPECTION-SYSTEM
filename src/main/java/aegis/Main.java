package aegis;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { /* Default Swing style is still usable. */ }
            AegisDashboard dashboard = new AegisDashboard(new ActionRequestRepository());
            dashboard.setVisible(true);
            dashboard.refresh();
        });
    }
}
