package dev.diegogarcia.openpdfpoc;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.concurrent.ExecutionException;

/**
 * Shared Swing chrome (open button, page navigation, status bar, password
 * handling wiring) for the two viewer engines this POC ships:
 * {@link OpenPdfTextViewerApp} and {@link PdfBoxRenderViewerApp}.
 *
 * <p>Subclasses only need to say how to open a document and how to draw one
 * page of it; this class takes care of the file chooser (remembering the
 * last directory used), running that open on a background thread, and the
 * previous/next page bookkeeping.</p>
 */
public abstract class AbstractPdfViewerApp extends JFrame {

    private final String baseTitle;
    private final JLabel fileLabel = new JLabel("No file open");
    private final JLabel statusLabel = new JLabel("Ready");
    private final JButton previousButton = new JButton("< Previous");
    private final JButton nextButton = new JButton("Next >");
    private final JLabel pageLabel = new JLabel("-");

    /** Directory the file chooser last navigated to; null opens to its own default. */
    private File lastDirectory;
    private int currentPage;
    private int totalPages;

    protected AbstractPdfViewerApp(String title) {
        super(title);
        this.baseTitle = title;
        // Not EXIT_ON_CLOSE: this window is opened from the launcher window
        // (see Main/LauncherWindow), which must stay open - and the JVM
        // running - after this one is closed, so the user can pick an
        // engine again.
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    }

    /**
     * Lays out and packs the window. Subclasses must call this once, as the
     * last statement of their own constructor, after their own fields are
     * initialized - this method calls the abstract {@code build*} methods
     * below, which are overridden by the subclass and may rely on those
     * fields (e.g. a content-panel field created with an inline
     * initializer). Calling it any earlier - e.g. from this constructor,
     * before the subclass's field initializers have run - would trip the
     * classic "overridable method call from a constructor" pitfall and hit
     * those fields while they're still null.
     */
    protected final void initializeUi() {
        setLayout(new BorderLayout());
        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildContentPanel(), BorderLayout.CENTER);
        add(buildNavigationPanel(), BorderLayout.SOUTH);
        setNavigationEnabled(false);
        setPreferredSize(new Dimension(800, 600));
        pack();
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeDocument();
            }
        });
    }

    /** Builds the component that shows page content; placed in {@code BorderLayout.CENTER}. */
    protected abstract JComponent buildContentPanel();

    /**
     * Opens the given file for this engine, prompting for a password via
     * {@link PasswordResolver} if needed, and returns a summary of the
     * opened document. Runs on a background thread (not the EDT); throw
     * {@link PasswordResolver.PasswordEntryCancelledException} (or let it
     * propagate) if the user cancels the password prompt.
     */
    protected abstract DocumentSummary openDocument(File file) throws Exception;

    /** Renders the given 1-based page number into the content component. Runs on the EDT. */
    protected abstract void renderPage(int pageNumber);

    /** Releases whatever document handle is currently open, if any. Safe to call when none is open. */
    protected abstract void closeDocument();

    /** Metadata about a just-opened document, gathered while validating its password. */
    protected static final class DocumentSummary {
        final int pageCount;
        final boolean encrypted;
        final String title;

        DocumentSummary(int pageCount, boolean encrypted, String title) {
            this.pageCount = pageCount;
            this.encrypted = encrypted;
            this.title = title;
        }
    }

    private JPanel buildTopPanel() {
        JButton openButton = new JButton("Open PDF...");
        openButton.addActionListener(e -> chooseAndOpenFile());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
        left.add(openButton);
        left.add(fileLabel);

        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        top.add(left, BorderLayout.WEST);
        return top;
    }

    private JPanel buildNavigationPanel() {
        previousButton.addActionListener(e -> showPage(currentPage - 1));
        nextButton.addActionListener(e -> showPage(currentPage + 1));

        JPanel nav = new JPanel(new FlowLayout(FlowLayout.CENTER));
        nav.add(previousButton);
        nav.add(pageLabel);
        nav.add(nextButton);

        JPanel south = new JPanel(new BorderLayout());
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        south.add(nav, BorderLayout.CENTER);
        south.add(statusLabel, BorderLayout.SOUTH);
        return south;
    }

    private void chooseAndOpenFile() {
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setFileFilter(new FileNameExtensionFilter("PDF files (*.pdf)", "pdf"));
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            lastDirectory = chooser.getCurrentDirectory();
            openFile(selected);
        }
    }

    private void openFile(File file) {
        setUiBusy(true, "Opening " + file.getName() + "...");
        closeDocument();

        new SwingWorker<DocumentSummary, Void>() {
            @Override
            protected DocumentSummary doInBackground() throws Exception {
                return openDocument(file);
            }

            @Override
            protected void done() {
                setUiBusy(false, null);
                try {
                    loadDocument(file, get());
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof PasswordResolver.PasswordEntryCancelledException) {
                        statusLabel.setText("Cancelled.");
                    } else {
                        showError("Could not open \"" + file.getName() + "\": " + describe(cause));
                        statusLabel.setText("Failed to open file.");
                    }
                } catch (Exception ex) {
                    showError("Unexpected error: " + describe(ex));
                }
            }
        }.execute();
    }

    /** {@code Throwable#getMessage()}, falling back to the exception's class name when it's null. */
    private static String describe(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    private void loadDocument(File file, DocumentSummary summary) {
        currentPage = 0;
        totalPages = summary.pageCount;

        setTitle(baseTitle + " - " + file.getName());
        fileLabel.setText(file.getName());
        setNavigationEnabled(totalPages > 0);

        StringBuilder status = new StringBuilder();
        status.append(totalPages).append(totalPages == 1 ? " page" : " pages");
        status.append(summary.encrypted ? " - encrypted" : " - not encrypted");
        if (summary.title != null && !summary.title.trim().isEmpty()) {
            status.append(" - \"").append(summary.title).append('"');
        }
        statusLabel.setText(status.toString());

        showPage(1);
    }

    private void showPage(int pageNumber) {
        if (totalPages == 0 || pageNumber < 1 || pageNumber > totalPages) {
            return;
        }
        currentPage = pageNumber;
        pageLabel.setText("Page " + currentPage + " of " + totalPages);
        previousButton.setEnabled(currentPage > 1);
        nextButton.setEnabled(currentPage < totalPages);
        renderPage(currentPage);
    }

    private void setNavigationEnabled(boolean enabled) {
        previousButton.setEnabled(false);
        nextButton.setEnabled(false);
        if (!enabled) {
            pageLabel.setText("-");
        }
    }

    private void setUiBusy(boolean busy, String message) {
        if (message != null) {
            statusLabel.setText(message);
        }
        setCursor(Cursor.getPredefinedCursor(busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }
}
