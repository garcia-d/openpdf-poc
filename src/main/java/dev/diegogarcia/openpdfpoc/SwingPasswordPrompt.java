package dev.diegogarcia.openpdfpoc;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;

/**
 * This app's Swing implementation of {@link PasswordResolver.PasswordPrompt}:
 * shows a modal dialog with a password field, centered on a given parent
 * component, and reports its error state on a second attempt onward.
 *
 * <p>{@link PasswordResolver} itself has no Swing dependency - it only knows
 * about the {@link PasswordResolver.PasswordPrompt} interface, and calls it
 * synchronously on whatever thread {@code resolve} was invoked from. Since
 * {@code resolve} runs on a background thread here (see
 * {@link AbstractPdfViewerApp#openFile}), this class does the EDT hop
 * itself, via {@link SwingUtilities#invokeAndWait}, and blocks until the
 * dialog is dismissed.</p>
 */
final class SwingPasswordPrompt implements PasswordResolver.PasswordPrompt {

    private final Component parent;

    SwingPasswordPrompt(Component parent) {
        this.parent = parent;
    }

    @Override
    public char[] getPassword(String fileName, int attempt, boolean previousAttemptFailed) {
        final char[][] result = new char[1][];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JPasswordField passwordField = new JPasswordField(20);
                JPanel panel = new JPanel(new BorderLayout(0, 6));
                String prompt = "\"" + fileName + "\" is password protected.\nEnter the password to open it:";
                panel.add(new JLabel("<html>" + prompt.replace("\n", "<br>") + "</html>"), BorderLayout.NORTH);
                panel.add(passwordField, BorderLayout.CENTER);
                if (previousAttemptFailed) {
                    JLabel error = new JLabel("Incorrect password. Please try again.");
                    error.setForeground(Color.RED);
                    panel.add(error, BorderLayout.SOUTH);
                }

                int option = JOptionPane.showConfirmDialog(
                        parent, panel, "Password required",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

                result[0] = option == JOptionPane.OK_OPTION ? passwordField.getPassword() : null;
            });
        } catch (Exception e) {
            result[0] = null;
        }
        return result[0];
    }
}
