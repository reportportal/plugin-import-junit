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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author <a href="mailto:ivan_budayeu@epam.com">Ivan Budayeu</a>
 */
public class ZipImportStrategy extends AbstractImportStrategy {

  private static final Predicate<ZipEntry> isFile = zipEntry -> !zipEntry.isDirectory();
  private static final Predicate<ZipEntry> isXml =
      zipEntry -> zipEntry.getName().endsWith(XML_EXTENSION);
  private static final Logger log = LoggerFactory.getLogger(ZipImportStrategy.class);

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
    try (ZipArchiveInputStream zipInputStream = new ZipArchiveInputStream(file.getInputStream(),
        StandardCharsets.UTF_8.toString(), true, true)) {
      String launchUuid = startLaunch(getLaunchName(file, ZIP_EXTENSION), projectName, rq);
      savedLaunchUuid = launchUuid;
      List<ParseResults> parseResults = new ArrayList<>();
      ZipEntry zipEntry;

      while ((zipEntry = zipInputStream.getNextEntry()) != null) {
        if (isFile.test(zipEntry) && isXml.test(zipEntry)) {

          ByteArrayInputStream bais = getZipEntryInputStream(zipInputStream, zipEntry.getSize());

          parseResults.add(xunitParseService.call(
              bais,
              launchUuid,
              projectName,
              isSkippedNotIssue(rq.getAttributes())));
        }
      }

      ParseResults results = processResults(parseResults);
      finishLaunch(launchUuid, projectName, results);
      updateStartTime(launchUuid, results.getStartTime());
      return launchUuid;
    } catch (Exception e) {
      log.error("Error during launch import", e);
      updateBrokenLaunch(savedLaunchUuid);
      throw new ReportPortalException(ErrorType.IMPORT_FILE_ERROR, cleanMessage(e));
    }
  }

  private ByteArrayInputStream getZipEntryInputStream(ZipArchiveInputStream zipInputStream,
      long zipEntrySize) throws IOException {
    byte[] bytes = new byte[(int) zipEntrySize];

    zipInputStream.readNBytes(bytes, 0, (int) zipEntrySize);
    return new ByteArrayInputStream(bytes);
  }

}
