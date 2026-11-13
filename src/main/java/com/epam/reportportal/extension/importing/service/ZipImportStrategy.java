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
import static org.apache.commons.io.FileUtils.ONE_MB;

import com.epam.reportportal.base.infrastructure.persistence.dao.LaunchRepository;
import com.epam.reportportal.base.infrastructure.rules.exception.ErrorType;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import com.epam.reportportal.extension.importing.model.LaunchImportRQ;
import com.epam.reportportal.extension.importing.utils.UncompressedSizeLimit;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.multipart.MultipartFile;

/**
 * @author <a href="mailto:ivan_budayeu@epam.com">Ivan Budayeu</a>
 */
@Slf4j
public class ZipImportStrategy extends AbstractImportStrategy {

  /**
   * Zip bomb protection: the upload itself is limited to 32 MB, these limits apply to its content.
   */
  static final int MAX_ENTRIES = 10_000;
  static final long MAX_UNCOMPRESSED_BYTES = 512 * ONE_MB;

  private static final Predicate<ZipEntry> isFile = zipEntry -> !zipEntry.isDirectory();
  private static final Predicate<ZipEntry> isXml =
      zipEntry -> zipEntry.getName().endsWith(XML_EXTENSION);

  private final XunitParseService xunitParseService;
  private final int maxEntries;
  private final long maxUncompressedBytes;

  public ZipImportStrategy(ApplicationEventPublisher eventPublisher, LaunchRepository launchRepository) {
    this(eventPublisher, launchRepository, MAX_ENTRIES, MAX_UNCOMPRESSED_BYTES);
  }

  ZipImportStrategy(ApplicationEventPublisher eventPublisher, LaunchRepository launchRepository,
      int maxEntries, long maxUncompressedBytes) {
    super(eventPublisher, launchRepository);
    this.xunitParseService = new XunitParseService(eventPublisher);
    this.maxEntries = maxEntries;
    this.maxUncompressedBytes = maxUncompressedBytes;
  }

  @Override
  public String importLaunch(MultipartFile file, String projectName, LaunchImportRQ rq) {
    String launchUuid = null;
    Instant launchStartTime = getExistingLaunchStartTime(rq);
    File zip = transferToTempFile(file);

    try (ZipFile zipFile = new ZipFile(zip)) {
      // checked before the launch is created, so a rejected archive doesn't leave an empty launch
      if (zipFile.size() > maxEntries) {
        throw new ReportPortalException(ErrorType.IMPORT_FILE_ERROR,
            "Archive contains more than " + maxEntries + " entries");
      }
      // counts bytes actually read, so fake sizes in zip headers can't bypass it
      UncompressedSizeLimit sizeLimit = new UncompressedSizeLimit(maxUncompressedBytes);

      launchUuid = getLaunchUuid(getLaunchName(file, ZIP_EXTENSION), projectName, rq);
      boolean skippedIsNotIssue = isSkippedNotIssue(rq);
      List<ParseResults> parseResults = new ArrayList<>();
      for (ZipEntry zipEntry : Collections.list(zipFile.entries())) {
        if (!isFile.and(isXml).test(zipEntry)) {
          continue;
        }
        try (InputStream entryStream = sizeLimit.wrap(zipFile.getInputStream(zipEntry))) {
          parseResults.add(xunitParseService.call(entryStream, launchUuid, projectName,
              skippedIsNotIssue, launchStartTime));
        }
      }
      ParseResults results = processResults(parseResults);
      completeCreatedLaunch(launchUuid, projectName, results, rq);
      return launchUuid;
    } catch (Exception e) {
      log.error("Failed to import xUnit ZIP file '{}' into launch '{}' for project '{}'",
          file.getOriginalFilename(), launchUuid, projectName, e);
      updateBrokenCreatedLaunch(launchUuid, rq);
      throw new ReportPortalException(ErrorType.IMPORT_FILE_ERROR, cleanMessage(e));
    } finally {
      try {
        Files.deleteIfExists(zip.getAbsoluteFile().toPath());
      } catch (IOException e) {
        log.warn("Failed to delete temporary ZIP file '{}'", zip.getAbsolutePath(), e);
      }
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
