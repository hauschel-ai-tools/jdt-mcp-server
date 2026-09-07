package org.naturzukunft.jdt.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashSet;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.Set;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.manipulation.JavaManipulation;
import org.eclipse.jdt.internal.core.manipulation.CodeTemplateContextType;
import org.eclipse.jface.text.templates.Template;
import org.eclipse.jface.text.templates.TemplateContextType;
import org.eclipse.text.templates.ContextTypeRegistry;
import org.eclipse.text.templates.TemplatePersistenceData;
import org.eclipse.text.templates.TemplateReaderWriter;
import org.eclipse.text.templates.TemplateStoreCore;
import org.osgi.framework.Bundle;

/**
 * Bootstraps the JDT code template store for headless mode.
 *
 * Refactorings in org.eclipse.jdt.core.manipulation render method bodies and
 * Javadoc from code templates ("getterbody", "setterbody", "gettercomment", ...).
 * They reach them through {@link JavaManipulation#getCodeTemplateStore()}, a static
 * field that the org.eclipse.jdt.ui JavaPlugin fills on workbench startup. Headless
 * that plugin never starts, the field stays null, and
 * SelfEncapsulateFieldRefactoring.checkFinalConditions() dies with
 * "Cannot invoke TemplateStoreCore.getTemplateData(boolean) because
 * this.fInstanceStore is null" — every jdt_encapsulate_field call failed that way.
 *
 * The default templates themselves are shipped by org.eclipse.jdt.ui as
 * templates/default-codetemplates.xml and contributed through the
 * org.eclipse.ui.editors.templates extension point. The extension registry is
 * available headless, so the store is rebuilt from the same source the workbench
 * uses, without activating any UI bundle: the workbench implementation
 * (ContributionTemplateStore) needs a jface IPreferenceStore and logs through
 * EditorsPlugin, both of which drag in the UI stack this server must stay out of.
 */
public final class HeadlessCodeTemplates {

    /** Preference key the workbench stores customized code templates under. */
    private static final String CODE_TEMPLATES_KEY = "org.eclipse.jdt.ui.text.custom_code_templates";

    private static final String TEMPLATES_EXTENSION_POINT = "org.eclipse.ui.editors.templates";
    private static final String ELEMENT_INCLUDE = "include";
    private static final String ATTRIBUTE_FILE = "file";
    private static final String ATTRIBUTE_TRANSLATIONS = "translations";

    private static final String FALLBACK_PREFERENCE_NODE_ID = "org.eclipse.jdt.ui";

    private static final TemplatePersistenceData[] NO_TEMPLATES = new TemplatePersistenceData[0];

    private HeadlessCodeTemplates() {
        // utility class
    }

    /**
     * Installs a code template store into {@link JavaManipulation} unless one is
     * already present. Idempotent and safe to call from any thread; a failure is
     * logged and left for {@link #checkGetterSetterTemplates()} to report.
     */
    public static synchronized void bootstrap() {
        if (JavaManipulation.getCodeTemplateStore() != null) {
            return;
        }
        try {
            if (JavaManipulation.getPreferenceNodeId() == null) {
                JavaManipulation.setPreferenceNodeId(FALLBACK_PREFERENCE_NODE_ID);
            }

            ContextTypeRegistry registry = JavaManipulation.getCodeTemplateContextRegistry();
            if (registry == null) {
                registry = new ContextTypeRegistry();
                CodeTemplateContextType.registerContextTypes(registry);
                JavaManipulation.setCodeTemplateContextRegistry(registry);
            }

            IEclipsePreferences preferences =
                    InstanceScope.INSTANCE.getNode(JavaManipulation.getPreferenceNodeId());
            ContributedCodeTemplateStore store = new ContributedCodeTemplateStore(registry, preferences);
            store.load();
            JavaManipulation.setCodeTemplateStore(store);

            McpLogger.info("HeadlessCodeTemplates",
                    "Bootstrapped JDT code template store with " + store.getTemplates().length + " template(s)");
        } catch (Throwable t) {
            McpLogger.warn("HeadlessCodeTemplates", "Could not bootstrap JDT code template store: " + t);
        }
    }

    /**
     * Checks whether the getter and setter body templates can be rendered.
     *
     * @return null when the store is usable, otherwise a message naming what is
     *         missing — callers turn this into a tool error instead of letting a
     *         NullPointerException from deep inside JDT reach the client.
     */
    public static String checkGetterSetterTemplates() {
        bootstrap();

        TemplateStoreCore store = JavaManipulation.getCodeTemplateStore();
        if (store == null) {
            return "JDT code template store is not available in headless mode. "
                    + "Getter/setter bodies cannot be rendered; see the server log "
                    + "(HeadlessCodeTemplates) for the bootstrap failure.";
        }
        for (String templateId : new String[] {
                CodeTemplateContextType.GETTERSTUB_ID, CodeTemplateContextType.SETTERSTUB_ID }) {
            if (store.findTemplateById(templateId) == null) {
                return "JDT code template '" + templateId + "' is missing from the headless template store. "
                        + "The org.eclipse.jdt.ui default code templates could not be read from the "
                        + "extension point " + TEMPLATES_EXTENSION_POINT + ".";
            }
        }
        return null;
    }

    /**
     * Template store that takes its defaults straight from the
     * org.eclipse.ui.editors.templates extension point, the way the workbench's
     * ContributionTemplateStore does, but without any UI bundle dependency.
     */
    private static final class ContributedCodeTemplateStore extends TemplateStoreCore {

        ContributedCodeTemplateStore(ContextTypeRegistry registry, IEclipsePreferences preferences) {
            super(registry, preferences, CODE_TEMPLATES_KEY);
        }

        @Override
        protected void loadContributedTemplates() throws IOException {
            IExtensionRegistry extensionRegistry = Platform.getExtensionRegistry();
            if (extensionRegistry == null) {
                return;
            }
            Set<String> knownIds = new HashSet<>();
            for (IConfigurationElement element
                    : extensionRegistry.getConfigurationElementsFor(TEMPLATES_EXTENSION_POINT)) {
                if (!ELEMENT_INCLUDE.equals(element.getName())) {
                    continue;
                }
                for (TemplatePersistenceData data : readIncluded(element)) {
                    if (data.isCustom() || !knownIds.add(data.getId()) || !isRenderable(data)) {
                        continue;
                    }
                    internalAdd(data);
                }
            }
        }

        /**
         * Keeps only templates whose context type this store knows — the same
         * extension point also carries the Java editor and SWT templates, whose
         * context types live in the UI bundles.
         */
        private boolean isRenderable(TemplatePersistenceData data) {
            Template template = data.getTemplate();
            if (template == null) {
                return false;
            }
            TemplateContextType contextType = getRegistry().getContextType(template.getContextTypeId());
            if (contextType == null) {
                return false;
            }
            try {
                contextType.validate(template.getPattern());
                return true;
            } catch (Exception e) {
                McpLogger.warn("HeadlessCodeTemplates",
                        "Skipping invalid code template '" + data.getId() + "': " + e.getMessage());
                return false;
            }
        }

        private TemplatePersistenceData[] readIncluded(IConfigurationElement element) throws IOException {
            Bundle bundle = Platform.getBundle(element.getContributor().getName());
            URL templates = findInBundle(bundle, element.getAttribute(ATTRIBUTE_FILE));
            if (templates == null) {
                return NO_TEMPLATES;
            }
            ResourceBundle translations = readTranslations(bundle, element.getAttribute(ATTRIBUTE_TRANSLATIONS));
            try (InputStream in = templates.openStream()) {
                return new TemplateReaderWriter().read(in, translations);
            }
        }

        private ResourceBundle readTranslations(Bundle bundle, String path) {
            URL url = findInBundle(bundle, path);
            if (url == null) {
                return null;
            }
            try (InputStream in = url.openStream()) {
                return new PropertyResourceBundle(in);
            } catch (IOException e) {
                McpLogger.warn("HeadlessCodeTemplates", "Could not read template translations " + path + ": " + e);
                return null;
            }
        }

        private URL findInBundle(Bundle bundle, String path) {
            if (bundle == null || path == null) {
                return null;
            }
            return FileLocator.find(bundle, IPath.fromOSString(path), null);
        }
    }
}
