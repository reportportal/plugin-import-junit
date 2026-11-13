/*
 * Copyright 2026 EPAM Systems
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.reportportal.base.infrastructure.events.FinishLaunchRqEvent;
import com.epam.reportportal.base.infrastructure.events.StartLaunchRqEvent;
import com.epam.reportportal.base.infrastructure.events.StartRootItemRqEvent;
import com.epam.reportportal.base.infrastructure.persistence.dao.LaunchRepository;
import com.epam.reportportal.base.infrastructure.persistence.entity.enums.StatusEnum;
import com.epam.reportportal.base.infrastructure.persistence.entity.launch.Launch;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import com.epam.reportportal.extension.importing.model.LaunchImportRQ;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ZipImportStrategyTest {

  private static final String REPORT =
      "<testsuite name=\"LoginTest\" tests=\"1\" timestamp=\"2024-01-01T10:00:00\" time=\"1.5\">"
          + "<testcase name=\"shouldLogin\" classname=\"LoginTest\" time=\"1.5\"/>"
          + "</testsuite>";

  private final List<Object> events = new ArrayList<>();
  private final LaunchRepository launchRepository = mock(LaunchRepository.class);
  private final Launch launch = new Launch();

  @Test
  void shouldImportArchiveWithinLimits() throws IOException {
    when(launchRepository.findByUuid(anyString())).thenReturn(Optional.of(launch));
    ZipImportStrategy strategy = new ZipImportStrategy(events::add, launchRepository);

    String launchUuid = strategy.importLaunch(zip("a/TEST-1.xml", "b/TEST-2.xml"), "project",
        new LaunchImportRQ());

    assertEquals(launchUuid, eventsOf(StartLaunchRqEvent.class).get(0).getStartLaunchRq().getUuid());
    assertEquals(2, eventsOf(StartRootItemRqEvent.class).size());
    assertEquals(1, eventsOf(FinishLaunchRqEvent.class).size());
  }

  @Test
  void shouldRejectArchiveWithTooManyEntriesBeforeCreatingLaunch() throws IOException {
    ZipImportStrategy strategy = new ZipImportStrategy(events::add, launchRepository, 2,
        Long.MAX_VALUE);
    MockMultipartFile file = zip("a/TEST-1.xml", "b/TEST-2.xml", "c/TEST-3.xml");

    ReportPortalException e = assertThrows(ReportPortalException.class,
        () -> strategy.importLaunch(file, "project", null));

    assertTrue(e.getMessage().contains("more than 2 entries"));
    assertTrue(events.isEmpty());
    verifyNoInteractions(launchRepository);
  }

  @Test
  void shouldRejectArchiveExceedingUncompressedSizeLimitAndInterruptLaunch() throws IOException {
    when(launchRepository.findByUuid(anyString())).thenReturn(Optional.of(launch));
    // a single report fits, two of them don't
    long limit = REPORT.getBytes(UTF_8).length * 3L / 2;
    ZipImportStrategy strategy = new ZipImportStrategy(events::add, launchRepository, 100, limit);

    ReportPortalException e = assertThrows(ReportPortalException.class,
        () -> strategy.importLaunch(zip("a/TEST-1.xml", "b/TEST-2.xml"), "project", null));

    assertTrue(e.getMessage().contains("exceeds"), e.getMessage());
    assertEquals(StatusEnum.INTERRUPTED, launch.getStatus());
  }

  private static MockMultipartFile zip(String... entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (String entry : entries) {
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(REPORT.getBytes(UTF_8));
        zip.closeEntry();
      }
    }
    return new MockMultipartFile("file", "results.zip", "application/zip", bytes.toByteArray());
  }

  private <T> List<T> eventsOf(Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).toList();
  }
}
