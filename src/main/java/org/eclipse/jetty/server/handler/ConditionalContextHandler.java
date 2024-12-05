package org.eclipse.jetty.server.handler;

/**
 * ConditionalContextHandler
 *
 * @author mnova
 */
public class ConditionalContextHandler {

    private String propertyName;
    private ContextHandler contextHandler;

    public String getPropertyName() {
        return propertyName;
    }

    public void setPropertyName(final String propertyName) {
        this.propertyName = propertyName;
    }

    public ContextHandler getContextHandler() {
        return contextHandler;
    }

    public void setContextHandler(final ContextHandler contextHandler) {
        this.contextHandler = contextHandler;
    }
}
