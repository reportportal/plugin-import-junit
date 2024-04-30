package com.epam.reportportal.extension.importing.event.handler;

/**
 * @author Andrei Piankouski
 */
public interface EventHandler<T> {

	void handle(T event);
}
