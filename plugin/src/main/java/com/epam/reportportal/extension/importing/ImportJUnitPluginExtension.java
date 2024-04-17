package com.epam.reportportal.extension.importing;

import com.epam.reportportal.extension.CommonPluginCommand;
import com.epam.reportportal.extension.PluginCommand;
import com.epam.reportportal.extension.ReportPortalExtensionPoint;
import com.epam.reportportal.extension.common.IntegrationTypeProperties;
import com.epam.reportportal.extension.event.PluginEvent;
import com.epam.reportportal.extension.importing.command.JUnitImportCommand;
import com.epam.reportportal.extension.importing.event.plugin.PluginEventHandlerFactory;
import com.epam.reportportal.extension.importing.event.plugin.PluginEventListener;
import com.epam.reportportal.extension.importing.utils.MemoizingSupplier;
import com.epam.reportportal.extension.util.RequestEntityConverter;
import com.epam.ta.reportportal.dao.IntegrationRepository;
import com.epam.ta.reportportal.dao.IntegrationTypeRepository;
import com.epam.ta.reportportal.dao.LogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.PostConstruct;
import org.pf4j.Extension;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;

/**
 * @author Andrei Piankouski
 */
@Extension
public class ImportJUnitPluginExtension implements ReportPortalExtensionPoint, DisposableBean {

  private static final String PLUGIN_ID = "JUnit";
  public static final String BINARY_DATA_PROPERTIES_FILE_ID = "binary-data.properties";

  private final Supplier<Map<String, PluginCommand>> pluginCommandMapping = new MemoizingSupplier<>(
      this::getCommands);

  private final Supplier<Map<String, CommonPluginCommand<?>>> commonPluginCommandMapping = new MemoizingSupplier<>(
      this::getCommonCommands);

  private final String resourcesDir;

  private final Supplier<ApplicationListener<PluginEvent>> pluginLoadedListener;

  private final RequestEntityConverter requestEntityConverter;

  @Autowired
  private IntegrationTypeRepository integrationTypeRepository;

  @Autowired
  private IntegrationRepository integrationRepository;

  @Autowired
  private LogRepository logRepository;

  @Autowired
  private ApplicationContext applicationContext;

  public ImportJUnitPluginExtension(Map<String, Object> initParams) {
    resourcesDir = IntegrationTypeProperties.RESOURCES_DIRECTORY.getValue(initParams)
        .map(String::valueOf).orElse("");

    pluginLoadedListener = new MemoizingSupplier<>(() -> new PluginEventListener(PLUGIN_ID,
        new PluginEventHandlerFactory(resourcesDir, integrationTypeRepository,
            integrationRepository)
    ));

    requestEntityConverter = new RequestEntityConverter(new ObjectMapper());
  }

  @PostConstruct
  public void createIntegration() {
    initListeners();
  }

  private void initListeners() {
    ApplicationEventMulticaster applicationEventMulticaster = applicationContext.getBean(
        AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
        ApplicationEventMulticaster.class
    );
    applicationEventMulticaster.addApplicationListener(pluginLoadedListener.get());
  }

  @Override
  public void destroy() {
    removeListeners();
  }

  private void removeListeners() {
    ApplicationEventMulticaster applicationEventMulticaster = applicationContext.getBean(
        AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
        ApplicationEventMulticaster.class
    );
    applicationEventMulticaster.removeApplicationListener(pluginLoadedListener.get());
  }

  @Override
  public Map<String, ?> getPluginParams() {
    Map<String, Object> params = new HashMap<>();
    params.put(ALLOWED_COMMANDS, new ArrayList<>(pluginCommandMapping.get().keySet()));
    params.put(COMMON_COMMANDS, new ArrayList<>(commonPluginCommandMapping.get().keySet()));
    return params;
  }

  @Override
  public CommonPluginCommand getCommonCommand(String commandName) {
    return commonPluginCommandMapping.get().get(commandName);
  }

  @Override
  public PluginCommand getIntegrationCommand(String commandName) {
    return pluginCommandMapping.get().get(commandName);
  }

  private Map<String, PluginCommand> getCommands() {
    return new HashMap<>();
  }

  private Map<String, CommonPluginCommand<?>> getCommonCommands() {
    HashMap<String, CommonPluginCommand<?>> pluginCommands = new HashMap<>();
    JUnitImportCommand junitImportCommand = new JUnitImportCommand(requestEntityConverter);
    pluginCommands.put(junitImportCommand.getName(), junitImportCommand);
    return pluginCommands;
  }
}
