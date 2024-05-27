package com.epam.reportportal.extension.importing;

import static com.epam.reportportal.extension.importing.command.XUnitImportCommand.MAX_FILE_SIZE;

import com.epam.reportportal.extension.CommonPluginCommand;
import com.epam.reportportal.extension.IntegrationGroupEnum;
import com.epam.reportportal.extension.PluginCommand;
import com.epam.reportportal.extension.ReportPortalExtensionPoint;
import com.epam.reportportal.extension.common.IntegrationTypeProperties;
import com.epam.reportportal.extension.event.PluginEvent;
import com.epam.reportportal.extension.importing.command.XUnitImportCommand;
import com.epam.reportportal.extension.importing.event.plugin.PluginEventHandlerFactory;
import com.epam.reportportal.extension.importing.event.plugin.PluginEventListener;
import com.epam.reportportal.extension.importing.utils.MemoizingSupplier;
import com.epam.reportportal.extension.util.RequestEntityConverter;
import com.epam.ta.reportportal.dao.IntegrationRepository;
import com.epam.ta.reportportal.dao.IntegrationTypeRepository;
import com.epam.ta.reportportal.dao.LaunchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.PostConstruct;
import org.pf4j.Extension;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;

/**
 * @author Andrei Piankouski
 */
@Extension
public class ImportXUnitPluginExtension implements ReportPortalExtensionPoint, DisposableBean {

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
  private LaunchRepository launchRepository;

  @Autowired
  private ApplicationEventPublisher eventPublisher;

  @Autowired
  private ApplicationContext applicationContext;

  public ImportXUnitPluginExtension(Map<String, Object> initParams) {
    resourcesDir = IntegrationTypeProperties.RESOURCES_DIRECTORY.getValue(initParams)
        .map(String::valueOf).orElse("");

    pluginLoadedListener = new MemoizingSupplier<>(() -> new PluginEventListener(PLUGIN_ID,
        new PluginEventHandlerFactory(resourcesDir, integrationTypeRepository,
            integrationRepository)
    ));

    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    requestEntityConverter = new RequestEntityConverter(objectMapper);
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
    params.put("maxFileSize", MAX_FILE_SIZE);
    params.put("acceptFileMimeTypes",
        List.of("application/zip", "application/x-zip-compressed", "application/zip-compressed",
            "application/xml", "text/xml"));
    params.put("metadata", Map.of("isIntegrationAllowed", "false"));
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

  @Override
  public IntegrationGroupEnum getIntegrationGroup() {
    return IntegrationGroupEnum.IMPORT;
  }

  private Map<String, PluginCommand> getCommands() {
    return new HashMap<>();
  }

  private Map<String, CommonPluginCommand<?>> getCommonCommands() {
    HashMap<String, CommonPluginCommand<?>> pluginCommands = new HashMap<>();
    var xunitImportCommand = new XUnitImportCommand(requestEntityConverter,
        eventPublisher, launchRepository);
    pluginCommands.put(xunitImportCommand.getName(), xunitImportCommand);
    return pluginCommands;
  }
}
