package com.epam.reportportal.extension.importing.event;

import com.epam.reportportal.extension.importing.event.handler.EventHandler;

/**
 * @author Andrei Piankouski
 */
public interface EventHandlerFactory<T> {

	EventHandler<T> getEventHandler(String key);
}
