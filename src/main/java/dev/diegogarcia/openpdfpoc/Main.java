package dev.diegogarcia.openpdfpoc;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Entry point. Shows {@link LauncherWindow}, which lets the user pick a
 * viewer engine (see {@link OpenPdfTextViewerApp} and
 * {@link PdfBoxRenderViewerApp}) each time they want to open a PDF.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Fall back to the default look and feel.
        }
        SwingUtilities.invokeLater(() -> new LauncherWindow().setVisible(true));
    }
}
