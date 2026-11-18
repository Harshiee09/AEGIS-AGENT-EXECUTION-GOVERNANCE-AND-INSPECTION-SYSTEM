package aegis;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

public final class AegisDashboard extends JFrame {
    private static final Color NAVY = new Color(20, 33, 57);
    private static final Color BLUE = new Color(45, 99, 221);
    private static final Color BACKGROUND = new Color(245, 247, 251);
    private static final Color MUTED = new Color(95, 108, 129);
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss");

    private final DashboardDataSource repository;
    private final JLabel activeAgents = valueLabel();
    private final JLabel totalRequests = valueLabel();
    private final JLabel pendingRequests = valueLabel();
    private final JLabel status = new JLabel("Ready");
    private final JButton refresh = new JButton("Refresh");
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"ID", "Agent", "Action", "Resource", "Risk", "Decision", "Execution", "Created"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };

    public AegisDashboard(DashboardDataSource repository) {
        super("AEGIS | Live Dashboard");
        this.repository = repository;
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 600));
        setSize(1120, 720);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BACKGROUND);
        setLayout(new BorderLayout());
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        refresh.addActionListener(event -> runTask(false));
        Timer liveRefresh = new Timer(3000, event -> {
            if (refresh.isEnabled()) runTask(true);
        });
        liveRefresh.start();
    }

    public void refresh() { runTask(false); }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(NAVY);
        panel.setBorder(BorderFactory.createEmptyBorder(22, 28, 22, 28));
        JLabel title = new JLabel("AEGIS");
        title.setFont(new Font("Segoe UI", Font.BOLD, 28));
        title.setForeground(Color.WHITE);
        JLabel subtitle = new JLabel("Agent action request monitor  |  Live MySQL view");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        subtitle.setForeground(new Color(190, 203, 224));
        JPanel text = new JPanel(new GridLayout(2, 1, 0, 2));
        text.setOpaque(false);
        text.add(title);
        text.add(subtitle);
        panel.add(text, BorderLayout.WEST);
        JLabel phase = new JLabel("GUI  •  MySQL  •  JDBC");
        phase.setFont(new Font("Segoe UI", Font.BOLD, 12));
        phase.setForeground(new Color(190, 203, 224));
        panel.add(phase, BorderLayout.EAST);
        return panel;
    }

    private JPanel content() {
        JPanel panel = new JPanel(new BorderLayout(0, 20));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(24, 28, 18, 28));

        JPanel cards = new JPanel(new GridLayout(1, 3, 16, 0));
        cards.setOpaque(false);
        cards.add(card("ACTIVE AGENTS", activeAgents, "Currently marked active in MySQL"));
        cards.add(card("ACTION REQUESTS", totalRequests, "Requests for active agents in MySQL"));
        cards.add(card("PENDING DECISIONS", pendingRequests, "Awaiting admin approval"));
        panel.add(cards, BorderLayout.NORTH);

        JPanel tablePanel = new JPanel(new BorderLayout(0, 14));
        tablePanel.setBackground(Color.WHITE);
        tablePanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(225, 230, 238)),
                BorderFactory.createEmptyBorder(18, 18, 18, 18)));
        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.setOpaque(false);
        JLabel heading = new JLabel("Recent action requests");
        heading.setFont(new Font("Segoe UI", Font.BOLD, 18));
        heading.setForeground(NAVY);
        toolbar.add(heading, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        styleButton(refresh, new Color(232, 238, 250), NAVY);
        buttons.add(refresh);
        toolbar.add(buttons, BorderLayout.EAST);
        tablePanel.add(toolbar, BorderLayout.NORTH);

        JTable table = new JTable(model);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        table.setRowHeight(36);
        table.setShowGrid(false);
        table.setSelectionBackground(new Color(225, 235, 255));
        table.setSelectionForeground(NAVY);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        JTableHeader tableHeader = table.getTableHeader();
        tableHeader.setFont(new Font("Segoe UI", Font.BOLD, 12));
        tableHeader.setBackground(new Color(238, 242, 248));
        tableHeader.setForeground(NAVY);
        tableHeader.setPreferredSize(new Dimension(0, 38));
        table.getColumnModel().getColumn(0).setPreferredWidth(55);
        table.getColumnModel().getColumn(3).setPreferredWidth(180);
        table.getColumnModel().getColumn(7).setPreferredWidth(170);
        table.getColumnModel().getColumn(4).setCellRenderer(new BadgeRenderer());
        table.getColumnModel().getColumn(5).setCellRenderer(new BadgeRenderer());
        table.getColumnModel().getColumn(6).setCellRenderer(new BadgeRenderer());
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(230, 234, 240)));
        tablePanel.add(scroll, BorderLayout.CENTER);
        JLabel note = new JLabel("Live requests from AEGIS agents are stored in MySQL and refreshed automatically.");
        note.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        note.setForeground(MUTED);
        tablePanel.add(note, BorderLayout.SOUTH);
        panel.add(tablePanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel footer() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BACKGROUND);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 28, 18, 28));
        status.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        status.setForeground(MUTED);
        panel.add(status, BorderLayout.WEST);
        return panel;
    }

    private static JPanel card(String title, JLabel value, String description) {
        JPanel card = new JPanel(new GridLayout(3, 1, 0, 4));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(225, 230, 238)),
                BorderFactory.createEmptyBorder(14, 18, 14, 18)));
        JLabel label = new JLabel(title);
        label.setFont(new Font("Segoe UI", Font.BOLD, 12));
        label.setForeground(MUTED);
        JLabel hint = new JLabel(description);
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        hint.setForeground(MUTED);
        card.add(label);
        card.add(value);
        card.add(hint);
        return card;
    }

    private static JLabel valueLabel() {
        JLabel label = new JLabel("—");
        label.setFont(new Font("Segoe UI", Font.BOLD, 27));
        label.setForeground(NAVY);
        return label;
    }

    private static void styleButton(JButton button, Color background, Color foreground) {
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setBackground(background);
        button.setForeground(foreground);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(11, 16, 11, 16));
        button.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
    }

    private void runTask(boolean silent) {
        refresh.setEnabled(false);
        status.setText("Loading live requests from MySQL...");
        new SwingWorker<DashboardData, Void>() {
            @Override protected DashboardData doInBackground() throws Exception {
                return repository.loadDashboard();
            }

            @Override protected void done() {
                refresh.setEnabled(true);
                try {
                    showData(get());
                    status.setText("Connected to MySQL. Live data refreshed.");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    if (silent) status.setText("Live refresh interrupted."); else showError(error);
                } catch (ExecutionException error) {
                    if (silent) status.setText("Live refresh failed; check MySQL connection.");
                    else showError(error.getCause() == null ? error : error.getCause());
                }
            }
        }.execute();
    }

    private void showData(DashboardData data) {
        activeAgents.setText(Integer.toString(data.activeAgents()));
        totalRequests.setText(Integer.toString(data.totalRequests()));
        pendingRequests.setText(Integer.toString(data.pendingRequests()));
        model.setRowCount(0);
        List<ActionRequest> requests = data.requests();
        for (ActionRequest request : requests) {
            model.addRow(new Object[]{request.id(), request.agentName(), request.action(),
                    request.resource(), request.riskLevel(), request.decisionStatus(), request.executionStatus(),
                    request.createdAt().format(DATE_FORMAT)});
        }
    }

    private void showError(Throwable error) {
        status.setText("Database operation failed. Check MySQL setup and credentials.");
        String detail = error.getMessage();
        if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
        JOptionPane.showMessageDialog(this,
                "Could not reach AEGIS MySQL data.\n\n" + detail
                        + "\n\nSee README.md for setup steps.",
                "AEGIS database error", JOptionPane.ERROR_MESSAGE);
    }

    private static final class BadgeRenderer extends DefaultTableCellRenderer {
        BadgeRenderer() { setHorizontalAlignment(SwingConstants.CENTER); }

        @Override public java.awt.Component getTableCellRendererComponent(JTable table,
                Object value, boolean selected, boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            String text = String.valueOf(value);
            setFont(getFont().deriveFont(Font.BOLD));
            if (!selected) {
                setBackground(Color.WHITE);
                setForeground(switch (text) {
                    case "HIGH" -> new Color(190, 42, 52);
                    case "MEDIUM" -> new Color(173, 111, 0);
                    case "LOW" -> new Color(24, 126, 83);
                    case "PENDING" -> BLUE;
                    default -> NAVY;
                });
            }
            return this;
        }
    }
}
