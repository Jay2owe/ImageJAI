package imagejai.ui;

import imagejai.engine.OutboundEvent;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Tiny Data Governance lamp that flashes whenever bytes leave the JVM.
 */
public final class EgressIndicator extends JComponent implements OutboundEvent.Listener {
    static final Color IDLE = new Color(0x40, 0x40, 0x40);
    static final Color ACTIVE = new Color(0xE3, 0x1B, 0x23);

    private Color current = IDLE;
    private Timer decay;
    private final AutoCloseable subscription;

    public EgressIndicator() {
        setPreferredSize(new Dimension(8, 8));
        setMinimumSize(new Dimension(8, 8));
        setMaximumSize(new Dimension(8, 8));
        setToolTipText("<html>Egress indicator. Lights when ImageJAI sends bytes to the agent process."
                + "<br>(In On-premises mode the bytes go to a local process, not over the network.)</html>");
        subscription = OutboundEvent.subscribe(this);
    }

    @Override
    public void outboundEvent(OutboundEvent event) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                flash();
            }
        });
    }

    public void dispose() {
        try {
            subscription.close();
        } catch (Exception ignore) {
        }
        if (decay != null) {
            decay.stop();
        }
    }

    void flash() {
        current = ACTIVE;
        repaint();
        if (decay == null) {
            decay = new Timer(300, new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    current = IDLE;
                    repaint();
                }
            });
            decay.setRepeats(false);
        }
        decay.restart();
    }

    boolean isActiveForTest() {
        return ACTIVE.equals(current);
    }

    Color currentColorForTest() {
        return current;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(current);
            g2.fillOval(1, 1, 6, 6);
        } finally {
            g2.dispose();
        }
    }
}
