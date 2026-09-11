package dev.diegogarcia.openpdfpoc;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.GridLayout;

/**
 * First window shown by the application: lets the user pick which viewer
 * engine to open a PDF with. Each button opens its engine's window
 * independently and leaves this launcher open, so the user can come back and
 * pick again (including the same engine, for another file) after closing it.
 *
 * @see OpenPdfTextViewerApp
 * @see PdfBoxRenderViewerApp
 */
public class LauncherWindow extends JFrame {

    public LauncherWindow() {
        super("OpenPDF Viewer (POC)");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        JButton openPdfButton = new JButton("Pure OpenPDF (text)");
        openPdfButton.addActionListener(e -> new OpenPdfTextViewerApp().setVisible(true));

        JButton pdfBoxButton = new JButton("PDFBox (render)");
        pdfBoxButton.addActionListener(e -> new PdfBoxRenderViewerApp().setVisible(true));

        JPanel buttons = new JPanel(new GridLayout(1, 2, 12, 0));
        buttons.add(openPdfButton);
        buttons.add(pdfBoxButton);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        content.add(new JLabel("Choose a viewer engine to open a PDF with:"), BorderLayout.NORTH);
        content.add(buttons, BorderLayout.CENTER);

        setContentPane(content);
        setResizable(false);
        pack();
        setSize(Math.max(getWidth(), 420), getHeight());
        setLocationRelativeTo(null);
    }
}
