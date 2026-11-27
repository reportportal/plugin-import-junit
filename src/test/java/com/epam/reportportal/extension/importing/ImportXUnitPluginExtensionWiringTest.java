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

package com.epam.reportportal.extension.importing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.epam.reportportal.base.infrastructure.persistence.dao.IntegrationRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.IntegrationTypeRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.LaunchRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.ProjectRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.ProjectUserRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.organization.OrganizationRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.organization.OrganizationUserRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the extension the way the plugin manager does on service-api with Spring Boot 4: the host
 * context exposes only a Jackson 3 {@link JsonMapper} bean named {@code objectMapper} and no Jackson
 * 2 {@code com.fasterxml.jackson.databind.ObjectMapper}.
 */
class ImportXUnitPluginExtensionWiringTest {

  @Test
  void shouldWireAgainstHostContextWithOnlyJackson3Mapper() {
    try (AnnotationConfigApplicationContext host = hostContext()) {
      assertFalse(hasJackson2ObjectMapperBean(host),
          "host context must not expose a Jackson 2 ObjectMapper");

      ImportXUnitPluginExtension extension = new ImportXUnitPluginExtension(Map.of());
      AutowireCapableBeanFactory factory = host.getAutowireCapableBeanFactory();
      factory.autowireBean(extension);
      factory.initializeBean(extension, "junitExtension");

      assertNotNull(extension.getCommonExtensionCommands().get("import"));
      assertTrue(extension.getPluginParams().containsKey("maxFileSize"));
      extension.destroy();
    }
  }

  private static AnnotationConfigApplicationContext hostContext() {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.registerBean("objectMapper", JsonMapper.class, () -> JsonMapper.builder().build());
    context.registerBean(IntegrationTypeRepository.class, () -> mock(IntegrationTypeRepository.class));
    context.registerBean(IntegrationRepository.class, () -> mock(IntegrationRepository.class));
    context.registerBean(LaunchRepository.class, () -> mock(LaunchRepository.class));
    context.registerBean(ProjectRepository.class, () -> mock(ProjectRepository.class));
    context.registerBean(OrganizationUserRepository.class,
        () -> mock(OrganizationUserRepository.class));
    context.registerBean(OrganizationRepository.class, () -> mock(OrganizationRepository.class));
    context.registerBean(ProjectUserRepository.class, () -> mock(ProjectUserRepository.class));
    context.refresh();
    return context;
  }

  private static boolean hasJackson2ObjectMapperBean(AnnotationConfigApplicationContext context) {
    try {
      Class<?> jackson2 = Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
      return context.getBeanNamesForType(jackson2).length > 0;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }
}
