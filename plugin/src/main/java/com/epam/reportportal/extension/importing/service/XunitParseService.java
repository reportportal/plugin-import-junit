/*
 * Copyright 2021 EPAM Systems
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

import com.epam.reportportal.rules.exception.ErrorType;
import com.epam.reportportal.rules.exception.ReportPortalException;
import java.io.IOException;
import java.io.InputStream;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

public class XunitParseService {

  private final ApplicationEventPublisher applicationEventPublisher;

  public XunitParseService(ApplicationEventPublisher applicationEventPublisher) {
    this.applicationEventPublisher = applicationEventPublisher;
  }

  public ParseResults call(InputStream inputStream, String launchUuid, String projectName,
      boolean isSkippedNotIssue) {
    XunitImportHandler handler;
    try {
      SAXParserFactory spf = SAXParserFactory.newInstance();
      SAXParser saxParser = spf.newSAXParser();
      XMLReader reader = saxParser.getXMLReader();

      // Xerces 1 - http://xerces.apache.org/xerces-j/features.html#external-general-entities
      // Xerces 2 - http://xerces.apache.org/xerces2-j/features.html#external-general-entities

      // Xerces 2 only - http://xerces.apache.org/xerces-j/features.html#external-general-entities
      spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      // Using the SAXParserFactory's setFeature
      spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
      spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      spf.setXIncludeAware(false);
      // Using the XMLReader's setFeature
      reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
      reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

      handler = new XunitImportHandler(applicationEventPublisher);
      handler.withParameters(launchUuid, projectName, isSkippedNotIssue);

      saxParser.parse(inputStream, handler);
    } catch (SAXException | IOException | ParserConfigurationException e) {
      throw new ReportPortalException(ErrorType.PARSING_XML_ERROR, e.getMessage());
    }
    return new ParseResults(handler.getInitialTime(), handler.getCommonDuration());
  }

}
