/*
 * Copyright 2019 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.reportportal.extension.importing.service;

import static com.epam.reportportal.extension.importing.service.FileExtensionConstant.XML_EXTENSION;
import static com.epam.reportportal.extension.importing.service.FileExtensionConstant.ZIP_EXTENSION;

import com.epam.reportportal.extension.importing.model.LaunchImportRQ;
import com.epam.reportportal.rules.exception.ErrorType;
import com.epam.reportportal.rules.exception.ReportPortalException;
import com.epam.ta.reportportal.dao.LaunchRepository;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.commons.io.FilenameUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author <a href="mailto:ivan_budayeu@epam.com">Ivan Budayeu</a>
 */
public class ZipImportStrategy extends AbstractImportStrategy {

  private static final Predicate<ZipEntry> isFile = zipEntry -> !zipEntry.isDirectory();
  private static final Predicate<ZipEntry> isXml =
      zipEntry -> zipEntry.getName().endsWith(XML_EXTENSION);

  private final XunitParseService xunitParseService;

  public ZipImportStrategy(ApplicationEventPublisher eventPublisher,
      LaunchRepository launchRepository) {
    super(eventPublisher, launchRepository);
    this.xunitParseService = new XunitParseService(eventPublisher);
  }

  @Override
  public String importLaunch(MultipartFile file, String projectName, LaunchImportRQ rq) {
    //copy of the launch's id to use it in catch block if something goes wrong
    String savedLaunchUuid = null;
    File zip = transferToTempFile(file);

    try (ZipFile zipFile = new ZipFile(zip)) {
      String launchUuid = startLaunch(getLaunchName(file, ZIP_EXTENSION), projectName, rq);
      savedLaunchUuid = launchUuid;
      List<ParseResults> parseResults = zipFile.stream().filter(isFile.and(isXml))
          .map(zipEntry ->
              xunitParseService.call(getEntryStream(zipFile, zipEntry), launchUuid,
                  projectName,
                  isSkippedNotIssue(rq.getAttributes()))).collect(Collectors.toList());
      ParseResults results = processResults(parseResults);
      finishLaunch(launchUuid, projectName, results);
      return launchUuid;
    } catch (Exception e) {
      e.printStackTrace();
      updateBrokenLaunch(savedLaunchUuid, projectName);
      throw new ReportPortalException(ErrorType.IMPORT_FILE_ERROR, cleanMessage(e));
    } finally {
      try {
        Files.deleteIfExists(zip.getAbsoluteFile().toPath());
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  private InputStream getEntryStream(ZipFile file, ZipEntry zipEntry) {
    try {
      return file.getInputStream(zipEntry);
    } catch (IOException e) {
      throw new ReportPortalException(ErrorType.IMPORT_FILE_ERROR, e.getMessage());
    }
  }

  private File transferToTempFile(MultipartFile file) {
    try {
      File tmp = File.createTempFile(file.getOriginalFilename(),
          "." + FilenameUtils.getExtension(file.getOriginalFilename())
      );
      file.transferTo(tmp);
      return tmp;
    } catch (IOException e) {
      throw new ReportPortalException("Error during transferring multipart file.", e);
    }
  }
}
