package com.epam.reportportal.extension.importing.command;

import com.epam.reportportal.extension.CommonPluginCommand;
import java.util.List;
import java.util.Map;

public class FileFormatsCommand implements CommonPluginCommand<List<String>> {

  @Override
  public List<String> executeCommand(Map<String, Object> params) {
    return List.of("xml", "zip");
  }

  @Override
  public String getName() {
    return "formats";
  }
}
