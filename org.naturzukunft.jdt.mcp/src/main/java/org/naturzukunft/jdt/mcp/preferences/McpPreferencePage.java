package org.naturzukunft.jdt.mcp.preferences;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.naturzukunft.jdt.mcp.Activator;
import org.naturzukunft.jdt.mcp.McpServerManager;

/**
 * Preference page for configuring the JDT MCP Server.
 */
public class McpPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    private Label statusLabel;
    private BooleanFieldEditor enabledEditor;
    private IntegerFieldEditor portStartEditor;
    private IntegerFieldEditor portEndEditor;

    public McpPreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Configure the Eclipse JDT MCP Server for AI coding assistants.");
    }

    @Override
    public void init(IWorkbench workbench) {
        // Nothing to initialize
    }

    @Override
    protected void createFieldEditors() {
        Composite parent = getFieldEditorParent();

        // Server enabled toggle
        enabledEditor = new BooleanFieldEditor(
                McpPreferenceConstants.PREF_SERVER_ENABLED,
                "Enable MCP Server",
                parent);
        addField(enabledEditor);

        // Port range start
        portStartEditor = new IntegerFieldEditor(
                McpPreferenceConstants.PREF_PORT_START,
                "Port Range Start:",
                parent);
        portStartEditor.setValidRange(1024, 65535);
        addField(portStartEditor);

        // Port range end
        portEndEditor = new IntegerFieldEditor(
                McpPreferenceConstants.PREF_PORT_END,
                "Port Range End:",
                parent);
        portEndEditor.setValidRange(1024, 65535);
        addField(portEndEditor);

        // Separator
        Label separator = new Label(parent, SWT.HORIZONTAL | SWT.SEPARATOR);
        GridData gridData = new GridData(GridData.FILL_HORIZONTAL);
        gridData.horizontalSpan = 2;
        separator.setLayoutData(gridData);

        // Status display
        Label statusTitle = new Label(parent, SWT.NONE);
        statusTitle.setText("Server Status:");

        statusLabel = new Label(parent, SWT.NONE);
        updateStatusLabel();
    }

    private void updateStatusLabel() {
        if (statusLabel == null || statusLabel.isDisposed()) {
            return;
        }

        McpServerManager manager = Activator.getDefault().getMcpServerManager();
        if (manager != null && manager.isRunning()) {
            statusLabel.setText("Running on port " + manager.getPort() +
                    " - http://localhost:" + manager.getPort() + "/mcp/sse");
        } else {
            statusLabel.setText("Not running");
        }
        statusLabel.getParent().layout();
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        super.propertyChange(event);
        // Could update UI based on changes
    }

    @Override
    public boolean performOk() {
        boolean result = super.performOk();

        if (result) {
            // Apply changes to the running server
            boolean enabled = getPreferenceStore().getBoolean(McpPreferenceConstants.PREF_SERVER_ENABLED);

            if (enabled) {
                Activator.getDefault().restartMcpServer();
            } else {
                Activator.getDefault().stopMcpServer();
            }

            updateStatusLabel();
        }

        return result;
    }

    @Override
    protected void performDefaults() {
        super.performDefaults();
        updateStatusLabel();
    }
}
