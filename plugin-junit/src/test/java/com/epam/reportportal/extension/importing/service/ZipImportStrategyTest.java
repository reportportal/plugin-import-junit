package com.epam.reportportal.extension.importing.service;

import static com.epam.reportportal.extension.utils.FileUtils.getMultipartFile;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.epam.reportportal.extension.importing.model.LaunchImportRQ;
import com.epam.ta.reportportal.dao.LaunchRepository;
import com.epam.ta.reportportal.entity.launch.Launch;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.multipart.commons.CommonsMultipartFile;

@ExtendWith(MockitoExtension.class)
class ZipImportStrategyTest {

  @Mock
  private ApplicationEventPublisher applicationEventPublisher;

  @Mock
  private LaunchRepository launchRepository;

  @InjectMocks
  ZipImportStrategy zipImportStrategy;

  @Test
  void importLaunch() throws IOException {
    CommonsMultipartFile multipartFile = getMultipartFile("test_reports.zip");

    LaunchImportRQ rq = new LaunchImportRQ();
    rq.setStartTime(Instant.now());

    when(launchRepository.findByUuid(anyString())).thenReturn(Optional.of(new Launch()));

    zipImportStrategy.importLaunch(multipartFile, "test-1", rq);
  }
}
