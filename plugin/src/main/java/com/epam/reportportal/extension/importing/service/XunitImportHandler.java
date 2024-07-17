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

import static com.epam.reportportal.extension.importing.service.XunitReportTag.TESTSUITE;
import static com.epam.reportportal.extension.importing.service.XunitReportTag.TESTSUITES;
import static com.epam.reportportal.extension.importing.service.XunitReportTag.fromString;
import static com.epam.reportportal.extension.importing.utils.DateUtils.toMillis;
import static com.epam.ta.reportportal.entity.enums.TestItemIssueGroup.NOT_ISSUE_FLAG;

import com.epam.reportportal.events.FinishItemRqEvent;
import com.epam.reportportal.events.SaveLogRqEvent;
import com.epam.reportportal.events.StartChildItemRqEvent;
import com.epam.reportportal.events.StartRootItemRqEvent;
import com.epam.reportportal.rules.exception.ErrorType;
import com.epam.reportportal.rules.exception.ReportPortalException;
import com.epam.ta.reportportal.entity.enums.LogLevel;
import com.epam.ta.reportportal.entity.enums.StatusEnum;
import com.epam.ta.reportportal.entity.enums.TestItemTypeEnum;
import com.epam.ta.reportportal.ws.reporting.FinishTestItemRQ;
import com.epam.ta.reportportal.ws.reporting.Issue;
import com.epam.ta.reportportal.ws.reporting.ItemAttributesRQ;
import com.epam.ta.reportportal.ws.reporting.SaveLogRQ;
import com.epam.ta.reportportal.ws.reporting.StartTestItemRQ;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

public class XunitImportHandler extends DefaultHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(XunitImportHandler.class);

  private final ApplicationEventPublisher eventPublisher;

  private static final int MAX_ENTITY_NAME_LENGTH = 256;

  public XunitImportHandler(ApplicationEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
  }

  private Instant initialTime = Instant.now();

  //initial info
  private String projectName;
  private String launchUuid;
  private boolean isSkippedNotIssue = false;

  //need to know item's id to attach System.out/System.err logs
  private String currentItemUuid;

  private Instant startSuiteTime;

  private long commonDuration;
  private long currentDuration;

  private long currentSuiteDuration;

  //items structure ids
  private Deque<String> itemUuids;
  private Set<ItemAttributesRQ> itemAttributes;
  private StatusEnum status;
  private StringBuilder message;
  private Instant startItemTime;
  private boolean rootVerified;

  @Override
  public void startDocument() {
    itemUuids = new ArrayDeque<>();
    message = new StringBuilder();
    startSuiteTime = Instant.now();
  }

  @Override
  public void endDocument() {
  }

  @Override
  public void startElement(String uri, String localName, String qName, Attributes attributes) {
    verifyRootElement(qName);
    switch (XunitReportTag.fromString(qName)) {
      case TESTSUITE:
        if (itemUuids.isEmpty()) {
          startRootItem(attributes.getValue(XunitReportTag.ATTR_NAME.getValue()),
              attributes.getValue(XunitReportTag.START_TIME.getValue()),
              attributes.getValue(XunitReportTag.TIMESTAMP.getValue()),
              attributes.getValue(XunitReportTag.ATTR_TIME.getValue())
          );
        } else {
          startTestItem(attributes.getValue(XunitReportTag.ATTR_NAME.getValue()));
        }
        break;
      case TESTCASE:
        startStepItem(attributes.getValue(XunitReportTag.ATTR_NAME.getValue()),
            attributes.getValue(XunitReportTag.START_TIME.getValue()),
            attributes.getValue(XunitReportTag.TIMESTAMP.getValue()),
            attributes.getValue(XunitReportTag.ATTR_TIME.getValue())
        );
        break;
      case ERROR:
      case FAILURE:
        message = new StringBuilder();
        status = StatusEnum.FAILED;
        break;
      case SKIPPED:
        message = new StringBuilder();
        status = StatusEnum.SKIPPED;
        break;
      case SYSTEM_OUT:
      case SYSTEM_ERR:
      case WARNING:
        message = new StringBuilder();
        break;
      case PROPERTIES:
        itemAttributes = new HashSet<>();
        break;
      case PROPERTY:
        handleProperty(attributes);
      case UNKNOWN:
      default:
        LOGGER.warn("Unknown tag: {}", qName);
        break;
    }
  }

  @Override
  public void endElement(String uri, String localName, String qName) {
    switch (XunitReportTag.fromString(qName)) {
      case TESTSUITE:
        finishRootItem();
        break;
      case TESTCASE:
        finishTestItem();
        break;
      case SKIPPED:
      case ERROR:
      case FAILURE:
      case SYSTEM_ERR:
        attachLog(LogLevel.ERROR);
        break;
      case SYSTEM_OUT:
        attachLog(LogLevel.INFO);
        break;
      case WARNING:
        attachLog(LogLevel.WARN);
        break;
      case UNKNOWN:
      default:
        LOGGER.warn("Unknown tag: {}", qName);
        break;
    }
  }

  @Override
  public void characters(char[] ch, int start, int length) {
    String msg = new String(ch, start, length);
    if (!msg.isEmpty()) {
      message.append(msg);
    }
  }

  private void startRootItem(String name, String startTime, String timestamp, String duration) {
    if (null != timestamp) {
      startItemTime = parseTimeStamp(timestamp);
      if (startSuiteTime.isAfter(startItemTime)) {
        startSuiteTime = startItemTime;
      }
    } else if (null != startTime) {
      startItemTime = parseTimeStamp(startTime);
      if (startSuiteTime.isAfter(startItemTime)) {
        startSuiteTime = startItemTime;
      }
    } else {
      startItemTime = Instant.now();
      startSuiteTime = Instant.now();
    }
    currentSuiteDuration = toMillis(duration);
    StartTestItemRQ rq = buildStartTestRq(name);

    eventPublisher.publishEvent(
        new StartRootItemRqEvent(this, projectName, rq));
    itemUuids.push(rq.getUuid());

    if (initialTime.isAfter(startItemTime)) {
      initialTime = startItemTime;
    }
  }


  private void handleProperty(Attributes attributes) {
    if ("attribute".equalsIgnoreCase(attributes.getValue("name"))) {
      var value = attributes.getValue("value").split(":");
      if (value.length > 1) {
        itemAttributes.add(new ItemAttributesRQ(value[0], value[1]));
      } else {
        itemAttributes.add(new ItemAttributesRQ(value[0]));
      }
    }
  }

  private void verifyRootElement(String qName) {
    if (!rootVerified) {
      XunitReportTag rootTag = fromString(qName);
      if (!Lists.newArrayList(TESTSUITES, TESTSUITE).contains(rootTag)) {
        throw new ReportPortalException(ErrorType.IMPORT_FILE_ERROR,
            "Root node in junit xml file must be 'testsuites' or 'testsuite'");
      }
      rootVerified = true;
    }
  }

  private Instant parseTimeStamp(String timestamp) {
    // try to parse datetime as Long, otherwise parse as timestamp
    try {
      return Instant.ofEpochMilli(Long.parseLong(timestamp));
    } catch (NumberFormatException ignored) {
      DateTimeFormatter formatter =
          new DateTimeFormatterBuilder().appendOptional(DateTimeFormatter.RFC_1123_DATE_TIME)
              .appendOptional(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
              .appendOptional(DateTimeFormatter.ISO_LOCAL_DATE_TIME).optionalStart()
              .appendOffsetId().appendZoneId().optionalEnd().optionalStart().appendLiteral(' ')
              .parseCaseSensitive().appendZoneId().optionalEnd().toFormatter();

      TemporalAccessor temporalAccessor = formatter.parse(timestamp);
      if (isParsedTimeStampHasOffset(temporalAccessor)) {
        return ZonedDateTime.from(temporalAccessor).withZoneSameInstant(ZoneOffset.UTC)
            .toInstant();
      } else {
        return LocalDateTime.from(temporalAccessor).toInstant(ZoneOffset.UTC);
      }
    }

  }

  private void startTestItem(String name) {
    StartTestItemRQ rq = buildStartTestRq(name);
    eventPublisher.publishEvent(new StartChildItemRqEvent(this, projectName, itemUuids.peek(), rq));
    itemUuids.push(rq.getUuid());
  }

  private void startStepItem(String name, String startTime, String timestamp, String duration) {
    StartTestItemRQ rq = new StartTestItemRQ();
    rq.setLaunchUuid(launchUuid);
    rq.setType(TestItemTypeEnum.STEP.name());
    rq.setName(StringUtils.abbreviate(name, MAX_ENTITY_NAME_LENGTH));
    rq.setUuid(UUID.randomUUID().toString());

    if (null != timestamp) {
      startItemTime = parseTimeStamp(timestamp);
    } else if (null != startTime) {
      startItemTime = parseTimeStamp(startTime);
    } else {
      startItemTime = startSuiteTime;
    }

    rq.setStartTime(startItemTime);

    eventPublisher.publishEvent(
        new StartChildItemRqEvent(this, projectName, itemUuids.peek(), rq));

    String id = rq.getUuid();
    currentDuration = toMillis(duration);
    currentItemUuid = id;
    itemUuids.push(id);
  }

  private void finishRootItem() {
    FinishTestItemRQ rq = new FinishTestItemRQ();
    markAsNotIssue(rq);
    rq.setEndTime(startSuiteTime.plus(currentSuiteDuration, ChronoUnit.MILLIS));
    rq.setAttributes(itemAttributes);
    eventPublisher.publishEvent(
        new FinishItemRqEvent(this, projectName, itemUuids.poll(), rq));
    status = null;
    itemAttributes = new HashSet<>();
  }

  private void finishTestItem() {
    FinishTestItemRQ rq = new FinishTestItemRQ();
    markAsNotIssue(rq);
    Instant endTime = startItemTime.plus(currentDuration, ChronoUnit.MILLIS);
    commonDuration += currentDuration;
    rq.setEndTime(endTime);
    rq.setStatus(Optional.ofNullable(status).orElse(StatusEnum.PASSED).name());
    rq.setAttributes(itemAttributes);
    currentItemUuid = itemUuids.poll();
    eventPublisher.publishEvent(
        new FinishItemRqEvent(this, projectName, currentItemUuid, rq));
    status = null;
    itemAttributes = new HashSet<>();
  }

  private void markAsNotIssue(FinishTestItemRQ rq) {
    if (StatusEnum.SKIPPED.equals(status) && isSkippedNotIssue) {
      Issue issue = new Issue();
      issue.setIssueType(NOT_ISSUE_FLAG.getValue());
      rq.setIssue(issue);
    }
  }

  private void attachLog(LogLevel logLevel) {
    if (null != message && message.length() != 0) {
      SaveLogRQ saveLogRQ = new SaveLogRQ();
      saveLogRQ.setLevel(logLevel.name());
      saveLogRQ.setLogTime(startItemTime);
      saveLogRQ.setMessage(message.toString().trim());
      saveLogRQ.setItemUuid(currentItemUuid);
      eventPublisher.publishEvent(
          new SaveLogRqEvent(this, projectName, saveLogRQ, null));
    }
  }

  private StartTestItemRQ buildStartTestRq(String name) {
    StartTestItemRQ rq = new StartTestItemRQ();
    rq.setUuid(UUID.randomUUID().toString());
    rq.setLaunchUuid(launchUuid);
    rq.setStartTime(startItemTime);
    rq.setType(TestItemTypeEnum.TEST.name());
    rq.setName(Strings.isNullOrEmpty(name) ? "no_name" : name);
    return rq;
  }

  public void withParameters(String launchId, String projectName, boolean isSkippedNotIssue) {
    this.projectName = projectName;
    this.launchUuid = launchId;
    this.isSkippedNotIssue = isSkippedNotIssue;
  }

  public Instant getStartSuiteTime() {
    return startSuiteTime;
  }

  public Instant getInitialTime() {
    return initialTime;
  }

  public long getCommonDuration() {
    return commonDuration;
  }

  private boolean isParsedTimeStampHasOffset(TemporalAccessor temporalAccessor) {
    return temporalAccessor.query(TemporalQueries.offset()) != null;
  }

}
