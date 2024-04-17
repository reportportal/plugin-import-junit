package com.epam.reportportal.extension.importing.command;

import static com.epam.reportportal.rules.commons.validation.BusinessRule.expect;
import static com.epam.reportportal.rules.exception.ErrorType.INCORRECT_REQUEST;
import static com.epam.ta.reportportal.commons.Predicates.notNull;
import static org.apache.commons.io.FileUtils.ONE_MB;

import com.epam.reportportal.extension.CommonPluginCommand;
import com.epam.reportportal.extension.importing.model.LaunchImportRQ;
import com.epam.reportportal.extension.util.RequestEntityConverter;
import com.epam.reportportal.rules.exception.ErrorType;
import com.epam.reportportal.rules.exception.ReportPortalException;
import com.epam.ta.reportportal.ws.reporting.OperationCompletionRS;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author Pavel Bortnik
 */
public class JUnitImportCommand implements CommonPluginCommand<OperationCompletionRS> {

  private static final String FILE_PARAM = "file";
  private static final String ENTITY_PARAM = "entity";
  private static final String ZIP_EXTENSION = "zip";
  private static final String XML_EXTENSION = "xml";
  private static final long MAX_FILE_SIZE = 32 * ONE_MB;

  private final RequestEntityConverter requestEntityConverter;

  public JUnitImportCommand(RequestEntityConverter requestEntityConverter) {
    this.requestEntityConverter = requestEntityConverter;
  }

  @Override
  public OperationCompletionRS executeCommand(Map<String, Object> params) {
    Optional<LaunchImportRQ> launchImportRQ = Optional.ofNullable(params.get(ENTITY_PARAM))
        .map(it -> requestEntityConverter.getEntity(ENTITY_PARAM, params, LaunchImportRQ.class));
    MultipartFile file = (MultipartFile) Optional.ofNullable(params.get(FILE_PARAM))
        .orElseThrow(() -> new ReportPortalException(
            ErrorType.BAD_REQUEST_ERROR, "File for import wasn't provided"));
    validate(file);
    return new OperationCompletionRS("Import started");
  }

  @Override
  public String getName() {
    return "import";
  }

  private void validate(MultipartFile file) {
    expect(file.getOriginalFilename(), notNull()).verify(INCORRECT_REQUEST,
        "File name should be not empty."
    );
    expect(file.getOriginalFilename(),
        it -> it.endsWith(ZIP_EXTENSION) || it.endsWith(XML_EXTENSION)
    ).verify(INCORRECT_REQUEST,
        "Should be a zip archive or an xml file " + file.getOriginalFilename()
    );
    expect(file.getSize(), size -> size <= MAX_FILE_SIZE).verify(INCORRECT_REQUEST,
        "File size is more than 32 Mb."
    );
  }
}
